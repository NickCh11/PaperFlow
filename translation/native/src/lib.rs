//! PaperFlow adapter for the pinned offline-translator engine. GPL-3.0-or-later.
//! No document or camera data leaves this library or the device.
mod android_font_provider;
#[cfg(target_os = "android")]
mod live_pipeline_jni;

use std::sync::{Arc, OnceLock};
use jni::{JNIEnv, objects::{JByteArray, JClass, JString}, sys::{jint, jlong, jstring}};
use serde_json::{Value, json};
use translator::{TranslatorSession, FsPackInstallChecker, Feature, TaskInstall, BackgroundMode};
use translator::live_tracker_pipeline::LiveTrackerPipeline;
use translator_ocr::engine::OcrEngine;

struct Runtime { session: Arc<TranslatorSession>, ocr: OcrEngine }
static RUNTIME: OnceLock<Runtime> = OnceLock::new();
fn runtime() -> Result<&'static Runtime, String> {
    RUNTIME.get().ok_or_else(|| "Language runtime is not initialized".into())
}
fn str_arg(env: &mut JNIEnv, text: &JString) -> Result<String, String> {
    env.get_string(text).map(Into::into).map_err(|e| e.to_string())
}
fn reply(env: &mut JNIEnv, result: Result<Value, String>) -> jstring {
    match result {
        Ok(value) => env.new_string(value.to_string()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
        Err(error) => { let _ = env.throw_new("java/io/IOException", error); std::ptr::null_mut() }
    }
}
fn delete_json(plan: &translator::DeletePlan) -> Value {
    json!({"files": plan.file_paths, "directories": plan.directory_paths})
}
fn plan_json(plan: translator::DownloadPlan) -> Value {
    let tasks: Vec<_> = plan.tasks.into_iter().map(|t| {
        let install = match t.install {
            TaskInstall::Raw => json!({"type":"raw"}),
            TaskInstall::Gunzip => json!({"type":"gzip"}),
            TaskInstall::Zip{extract_to,delete_after_extract,marker} => json!({
                "type":"zip", "extractTo":extract_to,"deleteAfterExtract":delete_after_extract,
                "marker":marker.map(|m| json!({"path":m.path,"version":m.version}))
            })
        };
        json!({"pack":t.pack_id,"path":t.install_path,"url":t.url,"size":t.size_bytes,"install":install})
    }).collect();
    json!({"size":plan.total_size,"tasks":tasks,"deleteAfter":delete_json(&plan.delete_after)})
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_paperflow_translation_RuntimeNative_open(mut env: JNIEnv, _: JClass, catalog: JString, base: JString) -> jstring {
    let result = (|| {
        let catalog = str_arg(&mut env,&catalog)?;
        let base = str_arg(&mut env,&base)?;
        if RUNTIME.get().is_none() {
            #[cfg(target_os = "android")]
            android_logger::init_once(android_logger::Config::default().with_max_level(log::LevelFilter::Info).with_tag("PaperFlowRuntime"));
            let checker = FsPackInstallChecker::new(&base);
            let session = TranslatorSession::open(&catalog,None,base,&checker).map_err(|e|format!("{e:?}"))?;
            let _ = RUNTIME.set(Runtime {session:Arc::new(session),ocr:OcrEngine::new()});
        }
        Ok(json!({"ready":true}))
    })();
    reply(&mut env,result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_paperflow_translation_RuntimeNative_call(mut env: JNIEnv, _: JClass, request: JString) -> jstring {
    let result = (|| {
        let req:Value=serde_json::from_str(&str_arg(&mut env,&request)?).map_err(|e|e.to_string())?;
        let r=runtime()?; let session=&r.session;
        let arg=|key:&str|req[key].as_str().unwrap_or("");
        match arg("operation") {
            "languages" => Ok(json!(session.language_overview().into_iter().map(|row|json!({
                "code":row.language.code,"name":row.language.display_name,"size":row.core_size_bytes,
                "installed":row.core_installed,"ocr":row.availability.ocr_files,
                "fromEnglish":row.availability.has_from_english,"toEnglish":row.availability.has_to_english
            })).collect::<Vec<_>>())),
            "refresh" => {session.refresh_snapshot();Ok(json!({}))},
            "plan" => Ok(plan_json(session.plan_download(arg("language"),Feature::Core,None).ok_or("Language pack is unavailable")?)),
            "deletePlan" => Ok(delete_json(&session.prepare_delete(arg("language"),Feature::Core))),
            "translate" => session.translate_text(arg("source"),arg("target"),arg("text")).map(|text|json!({"text":text})).map_err(|e|e.to_string()),
            "detect" => Ok(json!({"code":translator::language_detect::detect_language_robust_code(
                arg("text"),None,&session.available_languages()).map(|c|c.as_str().to_string())})),
            _ => Err("Unknown language operation".into())
        }
    })();
    reply(&mut env,result)
}

fn word_json(word: &translator::ocr::PositionedWord, height: f32, baseline: u32) -> Value {
    let rect=word.bounds.to_aabb();
    json!({"text":word.text,"left":rect.left,"top":rect.top,"right":rect.right,"bottom":rect.bottom,
        "lineHeight":height.round() as u32,"baseline":baseline,"line":word.line_index})
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_paperflow_translation_RuntimeNative_image(mut env: JNIEnv, _: JClass, pixels:JByteArray, width:jint, height:jint, request:JString, output:JByteArray) -> jstring {
    let result=(|| {
        let r=runtime()?;
        let req:Value=serde_json::from_str(&str_arg(&mut env,&request)?).map_err(|e|e.to_string())?;
        if width<=0 || height<=0 || width>20000 || height>20000 {return Err("Invalid image dimensions".into())}
        let bytes=env.convert_byte_array(&pixels).map_err(|e|e.to_string())?;
        if bytes.len()!=width as usize*height as usize*4 {return Err("Invalid RGBA image".into())}
        let source=req["source"].as_str().unwrap_or("auto");
        let target=req["target"].as_str().unwrap_or("");
        let min_conf=req["confidence"].as_f64().unwrap_or(50.0) as f32;
        let still=r.session.build_still_image(&bytes,width as u32,height as u32,1500).map_err(|e|e.to_string())?;
        if !target.is_empty() {
            let selection=if source=="auto" {translator::OcrSourceSelection::auto()} else {translator::OcrSourceSelection::specific(source)};
            let prepared=r.session.translate_from_still(&still,&bytes,width as u32,height as u32,selection,target,min_conf as u32,None,BackgroundMode::AutoDetect,None).map_err(|e|e.to_string())?;
            let rendered=translator::image_render::render_overlay(&prepared,&android_font_provider::AndroidFontProvider,
                &translator::image_render::RenderOptions{language:target.into(),min_font_size_px:8.0}).map_err(|e|e.to_string())?;
            if env.get_array_length(&output).map_err(|e|e.to_string())? as usize !=rendered.rgba_bytes.len() {return Err("Invalid output buffer".into())}
            // JNI byte arrays use signed bytes but preserve the RGBA bit patterns.
            let signed:Vec<i8>=rendered.rgba_bytes.iter().map(|b|*b as i8).collect();
            env.set_byte_array_region(&output,0,&signed).map_err(|e|e.to_string())?;
            return Ok(json!({"text":prepared.extracted_text,"translation":prepared.translated_text,
                "words":prepared.source_words.iter().map(|w|word_json(w,w.bounds.height,w.bounds.to_aabb().bottom)).collect::<Vec<_>>()}));
        }
        // OCR alone uses exactly the same Paddle detector/recognizers, without translating.
        let snap=r.session.snapshot();
        let engine=r.ocr.engine(&snap).map_err(|e|e.to_string())?;
        let boxes=translator::ocr_runtime::detect_boxes_from_still(&engine,&still,width as u32,height as u32).map_err(|e|e.to_string())?;
        let scripts=if source=="auto" {
            let predictions=engine.classify_text_boxes_image(&still.rgb,&boxes,Some(translator::coords::Quadrant::R0)).map_err(|e|e.to_string())?;
            translator::ocr_runtime::route_ppocr_predictions(&engine,&predictions,&boxes).map_err(|e|e.to_string())?
        } else {vec![translator::ocr_runtime::recognizer_script_for_language(&snap,&translator::LanguageCode::from(source)).map_err(|e|e.to_string())?;boxes.len()]};
        let strips=engine.dewarp_strips(&still.rgb,&boxes,Some(translator::coords::Quadrant::R0));
        let lines=engine.recognize_from_strips(&strips,&boxes,&scripts,translator::ppocr::PpocrProfile::Still,width as u32,height as u32,0.0).map_err(|e|e.to_string())?;
        let mut words=Vec::new();
        for (i,line) in lines.iter().enumerate() {
            if line.text.trim().is_empty() || line.confidence*100.0<min_conf {continue}
            let firings:Vec<_>=line.visual_firings.iter().map(|f|(char::from_u32(f.ch).unwrap_or('\u{fffd}'),f.at)).collect();
            let script=scripts.get(i).copied();
            let rtl=script.is_some_and(|s|s.is_rtl());
            let text=if rtl {translator::ppocr::reverse_visual_to_logical(&line.text)} else {line.text.clone()};
            let mut line_words=translator::text_metrics::firing_word_boxes(&text,&firings,script==Some(translator::PpocrScript::Cj),&line.oriented_box,i as u32);
            for word in &mut line_words {
                if rtl {word.text=translator::ppocr::reverse_visual_to_logical(&word.text)}
                words.push(word_json(word,line.oriented_box.height,line.rect.bottom));
            }
        }
        Ok(json!({"words":words,"text":lines.iter().filter(|l|l.confidence*100.0>=min_conf).map(|l|l.text.as_str()).collect::<Vec<_>>().join("\n")}))
    })();
    reply(&mut env,result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_paperflow_translation_RuntimeNative_createLive(mut env:JNIEnv,_:JClass,source:JString,target:JString)->jlong {
    let result=(|| {
        let r=runtime()?;
        let source=str_arg(&mut env,&source)?;let target=str_arg(&mut env,&target)?;
        let pipeline=LiveTrackerPipeline::new(r.session.clone(),Arc::new(android_font_provider::AndroidFontProvider));
        pipeline.set_languages(&source,&target,source=="auto");
        Ok::<_,String>(Arc::into_raw(pipeline) as jlong)
    })();
    match result {Ok(ptr)=>ptr,Err(e)=>{let _=env.throw_new("java/io/IOException",e);0}}
}

/// Called only after the GL view has joined its render thread.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_paperflow_translation_RuntimeNative_destroyLive(_:JNIEnv,_:JClass,ptr:jlong) {
    if ptr!=0 {let pipeline=unsafe {Arc::from_raw(ptr as *const LiveTrackerPipeline)};pipeline.reset();}
}

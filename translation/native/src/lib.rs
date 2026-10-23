//! PaperFlow adapter for the pinned offline-translator engine. GPL-3.0-or-later.
//! No document or camera data leaves this library or the device.
mod android_font_provider;
#[cfg(target_os = "android")]
mod live_pipeline_jni;

use std::sync::{Arc, OnceLock};
use jni::{JNIEnv, objects::{JByteBuffer, JClass, JString}, sys::{jint, jlong, jstring}};
use serde_json::{Value, json};
use translator::{TranslatorSession, FsPackInstallChecker, Feature, TaskInstall, BackgroundMode};
use translator::live_tracker_pipeline::LiveTrackerPipeline;
use translator_ocr::engine::OcrEngine;

struct Runtime { session: Arc<TranslatorSession>, ocr: OcrEngine, bundled: String }
static RUNTIME: OnceLock<Runtime> = OnceLock::new();

// ABI 2 passes direct ByteBuffers instead of Java byte arrays. Keep an explicit
// handshake so stale workflow artifacts cannot be loaded with the wrong JNI ABI.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_paperflow_translation_RuntimeNative_apiVersion(_:JNIEnv,_:JClass)->jint {2}
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
            let disk=std::fs::read_to_string(std::path::Path::new(&base).join("index_v6.json")).ok();
            let session = TranslatorSession::open(&catalog,disk.as_deref(),base,&checker).map_err(|e|format!("{e:?}"))?;
            let _ = RUNTIME.set(Runtime {session:Arc::new(session),ocr:OcrEngine::new(),bundled:catalog});
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
            "catalog" => {
                translator::parse_and_validate_catalog(arg("catalog"))?;
                session.replace_catalog(&r.bundled,Some(arg("catalog")),session.snapshot().base_dir.clone()).map_err(|e|e.to_string())?;
                Ok(json!({}))
            },
            "plan" => {
                if session.snapshot().catalog.language_by_code(&translator::LanguageCode::from(arg("language"))).is_none(){return Err("Language pack is unavailable".into())}
                Ok(plan_json(session.plan_download(arg("language"),Feature::Core,None).ok_or("Language pack is unavailable")?))
            },
            "deletePlan" => Ok(delete_json(&session.prepare_delete(arg("language"),Feature::Core))),
            "upgradePlan" => {
                let snap=session.snapshot();let codes=[translator::LanguageCode::from(arg("language"))];
                Ok(plan_json(if arg("kind")=="ocr" {translator::plan_ocr_engine_upgrades(&snap,&codes,"ppocr")}else{translator::plan_translation_upgrades(&snap,&codes)}))
            },
            "zipPlan" => {
                let marker=req["marker"].as_object().map(|m|translator::InstallMarker{
                    path:m.get("path").and_then(Value::as_str).unwrap_or("").into(),
                    version:m.get("version").and_then(Value::as_i64).unwrap_or(0) as i32});
                let entries:Vec<String>=req["entries"].as_array().ok_or("Missing archive entries")?.iter().filter_map(Value::as_str).map(str::to_string).collect();
                let plan=translator::plan_zip_extraction(arg("extractTo"),marker.as_ref(),&entries)?;
                Ok(json!({"replacePaths":plan.replace_paths,"entries":plan.entries.into_iter().map(|e|json!({"name":e.entry_name,"path":e.install_path,"directory":e.is_directory})).collect::<Vec<_>>()}))
            },
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

// From translator-ocr/ppocr.rs (MIT). The upstream helper is crate-private.
fn reverse_visual_to_logical(text: &str) -> String {
    let mut units=Vec::new();let mut run=String::new();
    for c in text.chars() {
        if c.is_ascii_alphanumeric() || matches!(c,' '|':'|'*'|'.'|'/'|'%'|'+'|'-') {run.push(c);continue}
        if !run.is_empty(){units.push(std::mem::take(&mut run));}units.push(c.to_string());
    }
    if !run.is_empty(){units.push(run);}units.into_iter().rev().collect()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_paperflow_translation_RuntimeNative_image(mut env: JNIEnv, _: JClass, pixels:JByteBuffer, width:jint, height:jint, request:JString, output:JByteBuffer) -> jstring {
    let result=(|| {
        let r=runtime()?;
        let req:Value=serde_json::from_str(&str_arg(&mut env,&request)?).map_err(|e|e.to_string())?;
        if width<=0 || height<=0 || width>20000 || height>20000 {return Err("Invalid image dimensions".into())}
        let capacity=env.get_direct_buffer_capacity(&pixels).map_err(|e|e.to_string())?;
        if capacity!=width as usize*height as usize*4 {return Err("Invalid RGBA image".into())}
        let address=env.get_direct_buffer_address(&pixels).map_err(|e|e.to_string())?;
        // The JNI argument retains the direct buffer for the entire call. This avoids
        // pinning the Java heap or making a second full-resolution input copy.
        let bytes=unsafe {std::slice::from_raw_parts(address,capacity)};
        let source=req["source"].as_str().unwrap_or("auto");
        let target=req["target"].as_str().unwrap_or("");
        let min_conf=req["confidence"].as_f64().unwrap_or(50.0) as f32;
        let still=r.session.build_still_image(&bytes,width as u32,height as u32,1500).map_err(|e|e.to_string())?;
        if !target.is_empty() {
            let selection=if source=="auto" {translator::OcrSourceSelection::auto()} else {translator::OcrSourceSelection::specific(source)};
            let mut prepared=r.session.translate_from_still(&still,&bytes,width as u32,height as u32,selection,target,min_conf as u32,None,BackgroundMode::AutoDetect,None).map_err(|e|e.to_string())?;
            if let Some(edits)=req["sourceEdits"].as_array(){
                if edits.len()!=prepared.blocks.len(){return Err("The photo layout changed. Translate it again before editing.".into())}
                for (block,text) in prepared.blocks.iter_mut().zip(edits){block.source_text=text.as_str().ok_or("Invalid source edit")?.into();block.source_style_spans.clear();}
                prepared.extracted_text=prepared.blocks.iter().map(|b|b.source_text.as_str()).collect::<Vec<_>>().join("\n");
                if !req["translationEdits"].is_array(){prepared=r.session.retranslate_prepared_overlay(prepared,source,target).map_err(|e|e.to_string())?;}
            }
            if let Some(edits)=req["translationEdits"].as_array(){
                if edits.len()!=prepared.blocks.len(){return Err("The photo layout changed. Translate it again before editing.".into())}
                for (block,text) in prepared.blocks.iter_mut().zip(edits){block.translated_text=text.as_str().ok_or("Invalid translation edit")?.into();block.style_spans.clear();}
                prepared.translated_text=prepared.blocks.iter().map(|b|b.translated_text.as_str()).collect::<Vec<_>>().join("\n");
            }
            let rendered=translator::image_render::render_overlay(&prepared,&android_font_provider::AndroidFontProvider,
                &translator::image_render::RenderOptions{language:target.into(),min_font_size_px:8.0}).map_err(|e|e.to_string())?;
            if env.get_direct_buffer_capacity(&output).map_err(|e|e.to_string())? !=rendered.rgba_bytes.len() {return Err("Invalid output buffer".into())}
            let destination=env.get_direct_buffer_address(&output).map_err(|e|e.to_string())?;
            unsafe {std::ptr::copy_nonoverlapping(rendered.rgba_bytes.as_ptr(),destination,rendered.rgba_bytes.len());}
            return Ok(json!({"text":prepared.extracted_text,"translation":prepared.translated_text,
                "words":prepared.source_words.iter().map(|w|word_json(w,w.bounds.height,w.bounds.to_aabb().bottom)).collect::<Vec<_>>(),
                "translatedWords":rendered.translated_words.iter().map(|w|word_json(w,w.bounds.height,w.bounds.to_aabb().bottom)).collect::<Vec<_>>(),
                "blocks":prepared.blocks.iter().map(|b|json!({"source":b.source_text,"translation":b.translated_text,
                    "left":b.bounding_box.left,"top":b.bounding_box.top,"right":b.bounding_box.right,"bottom":b.bounding_box.bottom})).collect::<Vec<_>>()}));
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
        let mut all_words=Vec::new();let mut rtl_lines=std::collections::HashSet::new();
        for (i,line) in lines.iter().enumerate() {
            if line.text.trim().is_empty() || line.confidence*100.0<min_conf {continue}
            let firings:Vec<_>=line.visual_firings.iter().map(|f|(char::from_u32(f.ch).unwrap_or('\u{fffd}'),f.at)).collect();
            let script=scripts.get(i).copied();
            let rtl=script.is_some_and(|s|s.is_rtl());
            if rtl {rtl_lines.insert(i as u32);}
            let text=if rtl {reverse_visual_to_logical(&line.text)} else {line.text.clone()};
            let mut line_words=translator::text_metrics::firing_word_boxes(&text,&firings,script==Some(translator::PpocrScript::Cj),&line.oriented_box,i as u32);
            // Multi-chunk recognition may omit CTC firings. Retain the recognized line
            // as one searchable span instead of silently losing its text in PDFs.
            if line_words.is_empty(){
                all_words.push(translator::ocr::PositionedWord{text:line.text.clone(),bounds:line.oriented_box,line_index:i as u32});
                continue;
            }
            for word in &mut line_words {
                if rtl {word.text=reverse_visual_to_logical(&word.text)}
            }
            all_words.extend(line_words);
        }
        let words:Vec<_>=translator::ocr::order_words_visually(all_words,&rtl_lines).iter().map(|word|{
            let line=&lines[word.line_index as usize];word_json(word,line.oriented_box.height,line.rect.bottom)
        }).collect();
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
        pipeline.set_languages(if source=="auto" {"en"} else {&source},&target,source=="auto");
        Ok::<_,String>(Arc::into_raw(pipeline) as jlong)
    })();
    match result {Ok(ptr)=>ptr,Err(e)=>{let _=env.throw_new("java/io/IOException",e);0}}
}

/// Called only after the GL view has joined its render thread.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_paperflow_translation_RuntimeNative_destroyLive(_:JNIEnv,_:JClass,ptr:jlong) {
    if ptr!=0 {let pipeline=unsafe {Arc::from_raw(ptr as *const LiveTrackerPipeline)};pipeline.reset();}
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_paperflow_translation_RuntimeNative_controlLive(_:JNIEnv,_:JClass,ptr:jlong,enabled:jni::sys::jboolean,af_scanning:jni::sys::jboolean,reset:jni::sys::jboolean) {
    if ptr==0{return}
    let pipeline=unsafe {&*(ptr as *const LiveTrackerPipeline)};
    if reset!=0{pipeline.reset();}
    pipeline.set_target_mode(if enabled!=0&&af_scanning==0{translator::live_tracker_pipeline::TargetMode::Active}else{translator::live_tracker_pipeline::TargetMode::Suppressed});
}

#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_com_paperflow_translation_RuntimeNative_changeLiveLanguages(mut env:JNIEnv,_:JClass,ptr:jlong,source:JString,target:JString) {
    if ptr==0{return}
    let result=(|| {
        let source=str_arg(&mut env,&source)?;let target=str_arg(&mut env,&target)?;
        let pipeline=unsafe {&*(ptr as *const LiveTrackerPipeline)};
        pipeline.set_languages(if source=="auto"{"en"}else{&source},&target,source=="auto");pipeline.reset();Ok::<_,String>(())
    })();
    if let Err(error)=result{let _=env.throw_new("java/io/IOException",error);}
}

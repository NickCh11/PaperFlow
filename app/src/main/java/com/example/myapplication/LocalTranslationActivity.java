package com.example.myapplication;

import android.app.Application;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.activity.result.*;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.*;
import androidx.core.content.FileProvider;
import androidx.lifecycle.*;
import com.paperflow.scanengine.*;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.json.*;
import java.io.*;
import java.text.Normalizer;
import java.util.*;
import java.util.concurrent.*;

/** Photo -> automatic local OCR/language detection -> translation -> preview and export. */
public class LocalTranslationActivity extends BackHandlingActivity {
    private ScrollView placeholderScroll;
    private final int BRAND = AppTheme.accent(),INK = AppTheme.ink(),MUTED = AppTheme.muted(),BG = AppTheme.background();
    private TranslationSession session;
    private ActivityResultLauncher<String[]> picker;
    private ActivityResultLauncher<Intent> camera,savePicker;
    private LinearLayout footer;
    private TextView status,detected,target,translatedTab,originalTab;
    private ProgressBar progress;
    private PreviewView preview;
    private LinearLayout placeholder;
    private boolean original,destroyed;

    public static class TranslationSession extends AndroidViewModel {
        final ExecutorService worker=Executors.newSingleThreadExecutor();
        final MutableLiveData<Integer> changes=new MutableLiveData<>(0);
        final Handler main=new Handler(Looper.getMainLooper());
        Bitmap source;
        TranslationRenderer.Result result;
        ArrayList<TranslationRenderer.Block> blocks=new ArrayList<>();
        final ArrayList<String> capturedPages=new ArrayList<>();int capturedIndex;
        String captureMode="Photo";
        String target="en",sourceLanguage="",sourceOverride="auto",message=UiText.get(R.string.ui_photo_translation_offline_help);
        boolean explicitTarget,error,readableLayout;
        volatile boolean busy,disposed,cancelled;boolean restoring;Uri pendingDestination;
        final Set<File> snapshots=java.util.concurrent.ConcurrentHashMap.newKeySet();volatile File snapshot;File lastExport,exportEvent;String exportAction,exportMime;

        public TranslationSession(Application app){super(app);
            android.content.SharedPreferences prefs=app.getSharedPreferences("photo-translation",0);
            explicitTarget=true;target=prefs.getString("target","en");
        }
        void publish(){changes.setValue(changes.getValue()==null?1:changes.getValue()+1);}
        void status(String value){main.post(()->{if(!disposed&&!cancelled){message=value;publish();}});}
        void begin(String value){cancelled=false;busy=true;error=false;message=value;publish();}
        void checkCancelled()throws InterruptedIOException{if(disposed||cancelled||Thread.currentThread().isInterrupted())throw new InterruptedIOException(UiText.get(R.string.ui_translation_cancelled));}
        void fail(Throwable error){main.post(()->{if(disposed)return;busy=false;this.error=true;String text=error.getMessage();message=text==null ? UiText.get(R.string.ui_could_not_translate_this_photo_try_again_or_choose_a_clearer_photo) : text;publish();});}
        JSONObject language(String kind,String code)throws Exception{
            JSONArray list=LanguageModels.catalog(getApplication(),kind);
            for(int i=0;i<list.length();i++)if(code.equals(list.getJSONObject(i).getString("code")))return list.getJSONObject(i);
            throw new IOException(UiText.get(R.string.ui_this_language_is_not_available));
        }
        void ensureLanguage(String kind,String code)throws Exception{
            checkCancelled();JSONObject entry=language(kind,code);
            if(LanguageModels.installed(getApplication(),kind,entry))return;
            String title=LanguageModels.title(entry);
            status(UiText.format(R.string.ui_first_time_download_value_your_photo_stays_on_this_device, title));
            try{LanguageModels.download(getApplication(),kind,entry,()->cancelled||disposed,(done,total)->status(UiText.format(R.string.ui_downloading_value_value, title, done*100/Math.max(1,total))));}
            catch(InterruptedIOException cancelled){throw cancelled;}
            catch(Exception problem){throw new IOException(UiText.format(R.string.ui_could_not_download_value_connect_to_the_internet_and_tap_try_again, title),problem);}
            checkCancelled();
        }
        void load(Uri uri){
            if(busy)return;begin("Opening photo…");
            worker.execute(()->{Bitmap image=null;try{
                BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
                try(InputStream in=getApplication().getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,options);}
                options.inJustDecodeBounds=false;options.inSampleSize=1;
                while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>2600)options.inSampleSize*=2;
                try(InputStream in=getApplication().getContentResolver().openInputStream(uri)){image=BitmapFactory.decodeStream(in,null,options);}
                if(image==null)throw new IOException(UiText.get(R.string.ui_could_not_open_this_photo_choose_another_image));
                try(InputStream in=getApplication().getContentResolver().openInputStream(uri)){image=ImageOrientation.upright(image,new androidx.exifinterface.media.ExifInterface(in));}
                checkCancelled();
                try{float[] corners=CaptureModes.detectsPaper(captureMode)&&!"Book".equals(captureMode)?PageBoundaryDetector.detect(image):null;
                    if(corners!=null){Bitmap cropped=PageGeometry.crop(image,corners);if(cropped!=image)image.recycle();image=cropped;}
                }catch(RuntimeException cropUnavailable){/* OCR can still read the full photo. */}
                checkCancelled();Bitmap ready=image;image=null;
                main.post(()->{
                    if(disposed||cancelled){ready.recycle();if(!disposed)fail(new InterruptedIOException(UiText.get(R.string.ui_translation_cancelled)));return;}
                    if(source!=null)source.recycle();if(result!=null)result.bitmap.recycle();
                    source=ready;result=null;snapshot=null;lastExport=null;blocks.clear();sourceLanguage="";readableLayout=false;busy=false;
                    translate(true);
                });
            }catch(Exception|OutOfMemoryError problem){if(image!=null&&!image.isRecycled())image.recycle();fail(problem);}});
        }
        void chooseTarget(String code){
            if(busy)return;target=code;explicitTarget=true;getApplication().getSharedPreferences("photo-translation",0).edit().putString("target",code).apply();
            snapshot=null;lastExport=null;if(result!=null){result.bitmap.recycle();result=null;}
            if(source!=null)translate(blocks.isEmpty());else publish();
        }
        void chooseSource(String code){
            if(busy)return;sourceOverride=code;sourceLanguage="";blocks.clear();
            snapshot=null;lastExport=null;
            if(result!=null){result.bitmap.recycle();result=null;}
            if(source!=null)translate(true);else publish();
        }
        static final class Recognition {
            final ArrayList<TranslationRenderer.Block> blocks;final String language;
            Recognition(ArrayList<TranslationRenderer.Block> blocks,String language){this.blocks=blocks;this.language=language;}
        }
        String sourceText(List<TranslationRenderer.Block> ready){StringBuilder out=new StringBuilder();for(TranslationRenderer.Block block:ready)out.append(block.source).append(' ');return out.toString();}
        ArrayList<TranslationRenderer.Block> readBlocks(String ocr){
            ArrayList<TranslationRenderer.Block> ready=TranslationRenderer.blocks(LocalOcr.recognize(getApplication(),source,ocr,0f),ocr.equals("he")||ocr.equals("fa")||ocr.equals("ar"));
            for(TranslationRenderer.Block block:ready)block.source=Normalizer.normalize(block.source,Normalizer.Form.NFKC);
            return ready;
        }
        Recognition readPhoto(String override)throws Exception{
            if(!override.equals("auto")){
                TranslationCatalog.Language language=TranslationCatalog.language(getApplication(),override);ensureLanguage("ocr",language.ocr);
                status(UiText.format(R.string.ui_reading_value_text, language.name));return new Recognition(readBlocks(language.ocr),override);
            }
            com.paperflow.translation.OfflineRuntime runtime=com.paperflow.translation.OfflineRuntime.get(getApplication());
            JSONArray installed=runtime.languages();boolean hasOcr=false;
            for(int i=0;i<installed.length();i++)if(installed.getJSONObject(i).optBoolean("ocr")){hasOcr=true;break;}
            if(!hasOcr){ensureLanguage("ocr","en");ensureLanguage("ocr","el");}
            status(UiText.get(R.string.ui_reading_text_and_detecting_its_language));
            ArrayList<TranslationRenderer.Block> probe=readBlocks("auto");
            if(probe.isEmpty())throw new IOException(UiText.get(R.string.ui_no_readable_text_found_choose_the_photo_language_above_or_try_a_clearer_pho));
            String detected=PhotoLanguageDetector.detect(getApplication(),sourceText(probe));
            if(detected.isEmpty())throw new IOException(UiText.get(R.string.ui_source_language_ambiguous));
            TranslationCatalog.Language language=TranslationCatalog.language(getApplication(),detected);
            ensureLanguage("ocr",language.ocr);return new Recognition(probe,detected);
        }

        void translate(boolean recognize){
            if(busy||source==null)return;begin("Preparing automatic translation…");
            final String requestedTarget=target,requestedSource=sourceOverride;final boolean automaticTarget=!explicitTarget;
            worker.execute(()->{try{
                ArrayList<TranslationRenderer.Block> ready;String detected;
                if(recognize||blocks.isEmpty()){
                    Recognition recognition=readPhoto(requestedSource);ready=recognition.blocks;detected=recognition.language;
                }else{
                    ready=new ArrayList<>(blocks);detected=requestedSource.equals("auto")?PhotoLanguageDetector.detect(getApplication(),sourceText(ready)):requestedSource;
                }
                checkCancelled();if(ready.isEmpty())throw new IOException(UiText.get(R.string.ui_no_readable_text_found_choose_the_photo_language_above_or_try_a_clearer_pho));
                if(detected.isEmpty())throw new IOException(UiText.get(R.string.ui_source_language_ambiguous));
                String chosen=automaticTarget?(detected.equals("el")?"en":"el"):requestedTarget;
                main.post(()->{if(!disposed&&!cancelled){sourceLanguage=detected;target=chosen;publish();}});
                for(String direction:TranslationCatalog.route(detected,chosen))ensureLanguage("translation",direction);
                checkCancelled();status(UiText.get(R.string.ui_preparing_your_translation));
                JSONObject edits=null;
                if(!recognize){JSONArray sources=new JSONArray();for(TranslationRenderer.Block block:ready)sources.put(block.source);edits=new JSONObject().put("sourceEdits",sources);}
                renderNative(detected,chosen,edits);
            }catch(Exception|LinkageError|OutOfMemoryError problem){fail(problem);}});
        }
        void renderNative(String detected,String chosen,JSONObject edits)throws Exception{
            com.paperflow.translation.OfflineRuntime.ImageResult nativeResult=com.paperflow.translation.OfflineRuntime.get(getApplication()).image(source,detected,chosen,50f,edits);
            try{
                checkCancelled();ArrayList<com.paperflow.imageprocessing.OcrTextBox> outputWords=words(nativeResult.metadata.getJSONArray("translatedWords"));
                ArrayList<com.paperflow.imageprocessing.OcrTextBox> sourceWords=words(nativeResult.metadata.getJSONArray("words"));
                ArrayList<TranslationRenderer.Block> nativeBlocks=new ArrayList<>();JSONArray blockRows=nativeResult.metadata.getJSONArray("blocks");
                for(int i=0;i<blockRows.length();i++){
                    JSONObject row=blockRows.getJSONObject(i);Rect rect=new Rect(row.getInt("left"),row.getInt("top"),row.getInt("right"),row.getInt("bottom"));
                    com.paperflow.imageprocessing.OcrTextBox box=new com.paperflow.imageprocessing.OcrTextBox(row.getString("source"),new com.paperflow.imageprocessing.ImageRect(rect.left,rect.top,rect.right,rect.bottom),rect.height(),rect.bottom);
                    TranslationRenderer.Block block=new TranslationRenderer.Block(box);block.words.clear();
                    for(com.paperflow.imageprocessing.OcrTextBox word:sourceWords)if(Rect.intersects(rect,new Rect(word.getBox().getLeft(),word.getBox().getTop(),word.getBox().getRight(),word.getBox().getBottom())))block.words.add(word);
                    block.translation=row.getString("translation");nativeBlocks.add(block);
                }
                publishResult(new TranslationRenderer.Result(nativeResult.bitmap,outputWords),nativeBlocks,detected,chosen,false);
            }catch(Exception|OutOfMemoryError error){if(!nativeResult.bitmap.isRecycled())nativeResult.bitmap.recycle();throw error;}
        }

        static ArrayList<com.paperflow.imageprocessing.OcrTextBox> words(JSONArray rows)throws Exception{
            ArrayList<com.paperflow.imageprocessing.OcrTextBox> words=new ArrayList<>();
            for(int i=0;i<rows.length();i++){JSONObject w=rows.getJSONObject(i);words.add(new com.paperflow.imageprocessing.OcrTextBox(w.getString("text"),new com.paperflow.imageprocessing.ImageRect(w.getInt("left"),w.getInt("top"),w.getInt("right"),w.getInt("bottom")),w.getInt("lineHeight"),w.getInt("baseline")));}return words;
        }
        void publishResult(TranslationRenderer.Result output,ArrayList<TranslationRenderer.Block> ready,String detected,String chosen,boolean usedFallback)throws Exception{
            boolean sameLanguage=detected.equals(chosen);
            File savedSnapshot;try{savedSnapshot=TranslationSnapshot.write(getApplication(),source,output,ready,detected,chosen,sourceOverride,explicitTarget,usedFallback);snapshots.add(savedSnapshot);}catch(Exception e){output.bitmap.recycle();throw e;}
            main.post(()->{
                if(disposed||cancelled){output.bitmap.recycle();if(!disposed)fail(new InterruptedIOException(UiText.get(R.string.ui_translation_cancelled)));return;}
                if(result!=null)result.bitmap.recycle();result=output;snapshot=savedSnapshot;lastExport=null;blocks=ready;sourceLanguage=detected;target=chosen;getApplication().getSharedPreferences("photo-translation",0).edit().putString("recent-source",detected).apply();readableLayout=usedFallback;busy=false;error=false;
                message=sameLanguage ? UiText.format(R.string.ui_this_text_is_already_in_value_choose_another_target_to_translate_it, languageName(chosen)) : usedFallback ? UiText.get(R.string.ui_ready_text_layout_keeps_the_full_translation_readable) : UiText.get(R.string.ui_ready_translated_on_this_device);publish();
            });
        }
        void renderEdits(){if(busy||source==null)return;begin("Updating translation…");worker.execute(()->{try{JSONArray sources=new JSONArray(),translations=new JSONArray();for(TranslationRenderer.Block block:blocks){sources.put(block.source);translations.put(block.translation);}
            renderNative(sourceLanguage,target,new JSONObject().put("sourceEdits",sources).put("translationEdits",translations));}catch(Exception|OutOfMemoryError problem){fail(problem);}});}
        String translatedText(){StringBuilder out=new StringBuilder();for(TranslationRenderer.Block block:blocks){if(out.length()>0)out.append("\n\n");out.append(block.translation);}return out.toString();}
        void export(boolean pdf,String action){
            if(busy||result==null)return;begin("Preparing file…");
            worker.execute(()->{File file=null;try{
                File dir=action.equals("library")?new File(getApplication().getFilesDir(),"documents"):new File(getApplication().getCacheDir(),"translation-exports");
                if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException(UiText.get(R.string.ui_storage_unavailable));
                file=new File(dir,"translated-"+UUID.randomUUID()+(pdf?".pdf":".png"));checkCancelled();
                if(pdf)try(PDDocument document=new PDDocument()){
                    Bitmap image=result.bitmap;float w=595,h=w*image.getHeight()/image.getWidth();PDPage page=new PDPage(new PDRectangle(w,h));document.addPage(page);
                    try(PDPageContentStream stream=new PDPageContentStream(document,page)){stream.drawImage(JPEGFactory.createFromImage(document,image,.94f),0,0,w,h);}
                    new OcrDocument(document,getApplication().getAssets()).addPage(page,result.words,new PageDimensions(image.getWidth(),image.getHeight(),w,h));document.save(file);
                }else try(FileOutputStream out=new FileOutputStream(file)){if(!result.bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException(UiText.get(R.string.ui_could_not_save_image));}
                checkCancelled();File saved=file;
                main.post(()->{if(disposed||cancelled){saved.delete();if(!disposed)fail(new InterruptedIOException(UiText.get(R.string.ui_export_cancelled)));return;}lastExport=saved;exportEvent=saved;exportMime=pdf?"application/pdf":"image/png";exportAction=action;busy=false;message=UiText.get(R.string.ui_ready_to_save);publish();});
            }catch(Exception|OutOfMemoryError problem){if(file!=null)file.delete();fail(problem);}});
        }
        void copyOutput(Uri uri){
            if(restoring){pendingDestination=uri;return;}if(busy)return;if(lastExport==null){fail(new IOException(UiText.get(R.string.ui_could_not_save_image)));return;}final File file=lastExport;begin("Saving to device…");
            worker.execute(()->{try(InputStream in=new FileInputStream(file);OutputStream out=getApplication().getContentResolver().openOutputStream(uri,"w")){
                if(out==null)throw new IOException(UiText.get(R.string.ui_could_not_open_the_selected_location));byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
                main.post(()->{if(disposed)return;busy=false;error=false;message=UiText.get(R.string.ui_saved_to_device);publish();});
            }catch(Exception problem){fail(problem);}});
        }
        void cancel(){cancelled=true;message=UiText.get(R.string.ui_stopping_after_the_current_step);publish();}
        @Override protected void onCleared(){disposed=true;cancelled=true;worker.execute(()->{for(File dir:snapshots)TranslationSnapshot.remove(getApplication(),dir);snapshots.clear();if(source!=null&&!source.isRecycled())source.recycle();if(result!=null&&!result.bitmap.isRecycled())result.bitmap.recycle();});worker.shutdown();}
    }

    static String languageName(String code){return Locale.forLanguageTag(code.replace('_','-')).getDisplayName(UiText.context().getResources().getConfiguration().getLocales().get(0));}
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(this);
        session=new ViewModelProvider(this).get(TranslationSession.class);if(state!=null){if(session.capturedPages.isEmpty()){ArrayList<String> captured=state.getStringArrayList("captured_pages");if(captured!=null)for(String path:captured)if(new File(path).isFile())session.capturedPages.add(path);session.capturedIndex=state.getInt("captured_index");session.captureMode=state.getString("translation_capture_mode","Photo");}original=state.getBoolean("show_original");String saved=state.getString("translation_session"),export=state.getString("pending_export");if(session.source==null&&!session.busy&&saved!=null){session.busy=true;session.restoring=true;session.worker.execute(()->{try{TranslationSnapshot.restore(session,saved);}catch(Exception|OutOfMemoryError e){session.main.post(()->{session.restoring=false;if(session.pendingDestination!=null&&session.lastExport!=null){Uri destination=session.pendingDestination;session.pendingDestination=null;session.busy=false;session.copyOutput(destination);}else session.fail(e);});}});}if(session.lastExport==null&&export!=null)try{File file=new File(export).getCanonicalFile();if(file.isFile()&&(file.getPath().startsWith(getFilesDir().getCanonicalPath()+File.separator)||file.getPath().startsWith(getCacheDir().getCanonicalPath()+File.separator)))session.lastExport=file;}catch(IOException ignored){}}
        picker=registerForActivityResult(new ActivityResultContracts.OpenDocument(),uri->{if(uri!=null){original=false;session.captureMode="Photo";session.capturedPages.clear();session.load(uri);}});
        camera=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{
            if(result.getResultCode()!=RESULT_OK||result.getData()==null)return;
            ArrayList<String> pages=result.getData().getStringArrayListExtra("pages");
            if(pages!=null&&!pages.isEmpty()){
                original=false;session.captureMode=result.getData().getStringExtra("capture_mode");
                session.sourceOverride=result.getData().getStringExtra("translation_source");if(session.sourceOverride==null)session.sourceOverride="auto";
                String selectedTarget=result.getData().getStringExtra("translation_target");if(selectedTarget!=null){session.target=selectedTarget;session.explicitTarget=true;}
                session.capturedPages.clear();session.capturedPages.addAll(pages);session.capturedIndex=0;
                session.load(Uri.fromFile(new File(pages.get(0))));
            }
            else if(result.getData().getBooleanExtra("import_pdf",false))picker.launch(new String[]{"image/*"});
        });
        savePicker=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{if(result.getResultCode()==RESULT_OK&&result.getData()!=null&&result.getData().getData()!=null)session.copyOutput(result.getData().getData());});
        buildUi();session.changes.observe(this,revision->updateUi());
    }
    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(android.widget.LinearLayout.VERTICAL);root.setBackgroundColor(BG);
        LinearLayout toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setBackgroundColor(AppTheme.surface());
        ImageView back=new ImageView(this);back.setImageDrawable(new AppIcon("Back",INK));back.setPadding(dp(15),dp(15),dp(15),dp(15));back.setContentDescription(UiText.get(R.string.ui_back));toolbar.addView(back,new LinearLayout.LayoutParams(dp(52),dp(56)));back.setOnClickListener(v->back());
        TextView title=text(UiText.get(R.string.ui_photo_translation),19,INK);title.setTypeface(null,android.graphics.Typeface.BOLD);title.setSingleLine();title.setEllipsize(android.text.TextUtils.TruncateAt.END);toolbar.addView(title,new LinearLayout.LayoutParams(0,-1,1));
        ImageView more=new ImageView(this);more.setImageDrawable(new AppIcon("More",INK));more.setPadding(dp(14),dp(14),dp(14),dp(14));more.setContentDescription(UiText.get(R.string.ui_more_options));toolbar.addView(more,new LinearLayout.LayoutParams(dp(48),dp(56)));more.setOnClickListener(this::showMore);root.addView(toolbar);
        LinearLayout languages=new LinearLayout(this);languages.setGravity(Gravity.CENTER_VERTICAL);languages.setPadding(dp(18),dp(10),dp(18),dp(10));
        detected=text(UiText.get(R.string.ui_auto_detect),14,MUTED);detected.setContentDescription(UiText.get(R.string.ui_photo_language));detected.setOnClickListener(v->chooseLanguage(true));detected.setMinimumHeight(dp(48));detected.setPadding(0,dp(8),dp(8),dp(8));languages.addView(detected,new LinearLayout.LayoutParams(0,-2,1));
        target=button(UiText.get(R.string.ui_to_english),false);languages.addView(target,new LinearLayout.LayoutParams(0,-2,1));target.setOnClickListener(v->chooseLanguage(false));root.addView(languages);
        LinearLayout tabs=new LinearLayout(this);tabs.setPadding(dp(18),0,dp(18),dp(8));
        translatedTab=button(UiText.get(R.string.ui_translation),false);originalTab=button(UiText.get(R.string.ui_original),false);tabs.addView(translatedTab,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams tabLp=new LinearLayout.LayoutParams(0,-2,1);tabLp.leftMargin=dp(8);tabs.addView(originalTab,tabLp);root.addView(tabs);
        translatedTab.setOnClickListener(v->{original=false;updateUi();});originalTab.setOnClickListener(v->{original=true;updateUi();});
        FrameLayout frame=new FrameLayout(this);frame.setBackground(round(AppTheme.surface(),10));LinearLayout.LayoutParams frameLp=new LinearLayout.LayoutParams(-1,0,1);frameLp.setMargins(dp(18),0,dp(18),0);root.addView(frame,frameLp);
        preview=new PreviewView();frame.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        placeholder=new LinearLayout(this);placeholder.setOrientation(android.widget.LinearLayout.VERTICAL);placeholder.setGravity(Gravity.CENTER);placeholder.setPadding(dp(24),dp(20),dp(24),dp(20));
        ImageView icon=new ImageView(this);icon.setImageDrawable(new AppIcon("Photo Translation",BRAND));placeholder.addView(icon,new LinearLayout.LayoutParams(dp(70),dp(70)));
        TextView heading=text(UiText.get(R.string.ui_translate_a_photo),21,INK);heading.setTypeface(null,android.graphics.Typeface.BOLD);heading.setGravity(Gravity.CENTER);placeholder.addView(heading,space(-1,-2,20));
        TextView help=text(UiText.get(R.string.ui_take_a_photo_or_choose_an_image_text_and_language_are_detected_automaticall),14,MUTED);help.setGravity(Gravity.CENTER);placeholder.addView(help,space(-1,-2,12));placeholderScroll=new ScrollView(this);placeholderScroll.setFillViewport(true);placeholderScroll.addView(placeholder);frame.addView(placeholderScroll,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout statusRow=new LinearLayout(this);statusRow.setGravity(Gravity.CENTER_VERTICAL);statusRow.setPadding(dp(18),dp(10),dp(18),dp(10));
        progress=new ProgressBar(this);statusRow.addView(progress,new LinearLayout.LayoutParams(dp(24),dp(24)));status=text("",13,MUTED);status.setPadding(dp(10),0,0,0);statusRow.addView(status,new LinearLayout.LayoutParams(0,-2,1));root.addView(statusRow);
        footer=new LinearLayout(this);footer.setOrientation(android.widget.LinearLayout.VERTICAL);footer.setBackgroundColor(AppTheme.surface());footer.setPadding(dp(18),dp(10),dp(18),dp(12));root.addView(footer);
        UiInsets.apply(root);setContentView(root);
    }
    private void updateUi(){
        if(destroyed)return;
        status.setText(session.message);status.setTextColor(session.error?0xFFB33A35:MUTED);progress.setVisibility(session.busy?View.VISIBLE:View.GONE);
        detected.setEnabled(!session.busy);detected.setText(session.sourceLanguage.isEmpty() ? (session.sourceOverride.equals("auto") ? UiText.get(R.string.ui_auto_detect) : UiText.format(R.string.ui_value_7cd939c2, languageName(session.sourceOverride))) : UiText.format(R.string.ui_value_7cd939c2, (session.sourceOverride.equals("auto")?"Auto: ":"")+languageName(session.sourceLanguage)));target.setText(!session.explicitTarget&&session.sourceLanguage.isEmpty() ? UiText.get(R.string.ui_auto_translate) : UiText.format(R.string.ui_to_value, languageName(session.target)));target.setEnabled(!session.busy);
        boolean ready=session.result!=null;translatedTab.setEnabled(ready);originalTab.setEnabled(session.source!=null);
        translatedTab.setTextColor(!original&&ready?BRAND:MUTED);originalTab.setTextColor(original?BRAND:MUTED);
        translatedTab.setBackground(round(!original&&ready?AppTheme.soft():AppTheme.surface(),6));originalTab.setBackground(round(original?AppTheme.soft():AppTheme.surface(),6));
        preview.setBitmap(original||!ready?session.source:session.result.bitmap);placeholderScroll.setVisibility(session.source==null?View.VISIBLE:View.GONE);
        footer.removeAllViews();
        if(session.busy){TextView cancel=button(UiText.get(R.string.ui_cancel),false);footer.addView(cancel,new LinearLayout.LayoutParams(-1,dp(48)));cancel.setOnClickListener(v->session.cancel());}
        else if(session.error&&session.source!=null){TextView retry=button(UiText.get(R.string.ui_try_again),true);footer.addView(retry,new LinearLayout.LayoutParams(-1,dp(48)));retry.setOnClickListener(v->{original=false;session.translate(session.blocks.isEmpty());});}
        else if(ready){
            LinearLayout actions=new LinearLayout(this);TextView change=button(UiText.get(R.string.ui_new_photo),false),copy=button(UiText.get(R.string.ui_copy_text),false);actions.addView(change,new LinearLayout.LayoutParams(0,-2,1));actions.addView(copy,new LinearLayout.LayoutParams(0,-2,1));footer.addView(actions);change.setOnClickListener(v->choosePhoto());copy.setOnClickListener(v->showText());
            TextView save=button(UiText.get(R.string.ui_save_to_device),true);footer.addView(save,space(-1,dp(48),8));save.setOnClickListener(v->chooseExport("device"));
        }else{
            LinearLayout inputs=new LinearLayout(this);TextView take=button(UiText.get(R.string.ui_camera),true),importPhoto=button(UiText.get(R.string.ui_import_photo),false);inputs.addView(take,new LinearLayout.LayoutParams(0,-2,1));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1);lp.leftMargin=dp(10);inputs.addView(importPhoto,lp);footer.addView(inputs);take.setOnClickListener(v->openCamera());importPhoto.setOnClickListener(v->picker.launch(new String[]{"image/*"}));
        }
        if(session.capturedPages.size()>1&&!session.busy){TextView pages=button(UiText.format(R.string.ui_page_value,(session.capturedIndex+1)+" / "+session.capturedPages.size()),false);footer.addView(pages,new LinearLayout.LayoutParams(-1,dp(48)));pages.setOnClickListener(v->chooseCapturedPage());}
        if(session.exportEvent!=null){
            File file=session.exportEvent;session.exportEvent=null;
            if("library".equals(session.exportAction)){
                startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("document_action","Register").putExtra("path",file.getAbsolutePath()).putExtra("name","Translated document").putExtra("pages",1));finish();
            }else if("device".equals(session.exportAction)){
                Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(session.exportMime).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"Translated document"+(session.exportMime.equals("application/pdf")?".pdf":".png"));savePicker.launch(intent);
            }else{
                Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",file);startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType(session.exportMime).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),UiText.get(R.string.ui_share_translation)));
            }
        }
    }
    private void chooseCapturedPage(){
        if(session.busy)return;String[] labels=new String[session.capturedPages.size()];
        for(int i=0;i<labels.length;i++)labels[i]=UiText.format(R.string.ui_page_value,i+1);
        new AlertDialog.Builder(this).setTitle(R.string.ui_pages).setSingleChoiceItems(labels,session.capturedIndex,(dialog,index)->{
            session.capturedIndex=index;original=false;dialog.dismiss();session.load(Uri.fromFile(new File(session.capturedPages.get(index))));
        }).show();
    }
    private void chooseLanguage(boolean source){
        if(session.busy)return;
        try{
            ArrayList<String> labels=new ArrayList<>();Map<String,String> codes=new HashMap<>();
            if(source){labels.add(UiText.get(R.string.ui_auto_detect_85cd1ac0));codes.put(UiText.get(R.string.ui_auto_detect_85cd1ac0),"auto");}
            for(TranslationCatalog.Language language:TranslationCatalog.languages(this)){labels.add(language.label());codes.put(language.label(),language.code);}
            LinearLayout fields=new LinearLayout(this);fields.setOrientation(android.widget.LinearLayout.VERTICAL);fields.setPadding(dp(20),dp(8),dp(20),0);
            EditText search=new EditText(this);search.setSingleLine();search.setHint(UiText.get(R.string.ui_search_languages));fields.addView(search);
            ListView list=new ListView(this);ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,labels);list.setAdapter(adapter);fields.addView(list,new LinearLayout.LayoutParams(-1,dp(320)));
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle(source ? UiText.get(R.string.ui_photo_language) : UiText.get(R.string.ui_translate_to)).setView(fields).setNegativeButton(UiText.get(R.string.ui_cancel),null).create();
            search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){adapter.getFilter().filter(s);}public void afterTextChanged(android.text.Editable e){}});
            list.setOnItemClickListener((parent,view,position,id)->{String code=codes.get(adapter.getItem(position));if(code==null)return;dialog.dismiss();original=false;if(source)session.chooseSource(code);else session.chooseTarget(code);});dialog.show();
        }catch(Exception problem){Toast.makeText(this,UiText.get(R.string.ui_could_not_load_translation_languages),Toast.LENGTH_LONG).show();}
    }
    private void choosePhoto(){new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_new_photo)).setItems(new String[]{UiText.get(R.string.ui_camera), UiText.get(R.string.ui_import_photo)},(d,w)->{if(w==0)openCamera();else picker.launch(new String[]{"image/*"});}).show();}
    private void openCamera(){if(!session.busy)camera.launch(new Intent(this,CameraCaptureActivity.class).putExtra("capture_mode",session.captureMode).putExtra("live_translation",true).putExtra("translation_source",session.sourceOverride).putExtra("translation_target",session.target));}
    private void chooseExport(String action){if(session.busy||session.result==null)return;new AlertDialog.Builder(this).setTitle(action.equals("device") ? UiText.get(R.string.ui_save_to_device) : UiText.get(R.string.ui_share_translation)).setItems(new String[]{UiText.get(R.string.ui_pdf_selectable_text), UiText.get(R.string.ui_png_image)},(d,w)->session.export(w==0,action)).setNegativeButton(UiText.get(R.string.ui_cancel),null).show();}
    private void showText(){
        TextView text=text(session.translatedText(),16,INK);text.setTextIsSelectable(true);text.setPadding(dp(20),dp(16),dp(20),dp(16));ScrollView scroll=new ScrollView(this);scroll.addView(text);
        new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_translation)).setView(scroll).setNegativeButton(UiText.get(R.string.ui_close),null).setPositiveButton(UiText.get(R.string.ui_copy_text),(d,w)->{android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);if(clipboard!=null)clipboard.setPrimaryClip(ClipData.newPlainText("Translation",session.translatedText()));}).show();
    }
    private void showMore(View anchor){
        PopupMenu menu=new PopupMenu(this,anchor);UiText.add(menu.getMenu(), "New photo");
        if(!session.blocks.isEmpty()){UiText.add(menu.getMenu(), "Edit recognized text");UiText.add(menu.getMenu(), "Edit translation");}
        if(session.result!=null){UiText.add(menu.getMenu(), "Save to documents");UiText.add(menu.getMenu(), "Share");}
        UiText.add(menu.getMenu(), "About offline translation");
        menu.setOnMenuItemClickListener(item->{if(session.busy)return true;switch(UiText.canonical(item)){
            case "New photo":choosePhoto();break;
            case "Edit recognized text":editText(true);break;
            case "Edit translation":editText(false);break;
            case "Save to documents":session.export(true,"library");break;
            case "Share":chooseExport("share");break;
            default:new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_offline_translation)).setMessage(R.string.ui_offline_translation_help).setPositiveButton(UiText.get(R.string.ui_ok),null).show();
        }return true;});menu.show();
    }
    private void editText(boolean recognized){
        ScrollView scroll=new ScrollView(this);LinearLayout fields=new LinearLayout(this);fields.setOrientation(android.widget.LinearLayout.VERTICAL);fields.setPadding(dp(20),dp(8),dp(20),dp(8));scroll.addView(fields);ArrayList<EditText> edits=new ArrayList<>();
        for(TranslationRenderer.Block block:session.blocks){TextView source=text(block.source,13,MUTED);fields.addView(source);EditText edit=new EditText(this);edit.setText(recognized ? block.source : block.translation);edit.setMinLines(2);fields.addView(edit);edits.add(edit);}
        new AlertDialog.Builder(this).setTitle(recognized ? UiText.get(R.string.ui_correct_recognized_text) : UiText.get(R.string.ui_edit_translation)).setView(scroll).setNegativeButton(UiText.get(R.string.ui_cancel),null).setPositiveButton(UiText.get(R.string.ui_apply),(d,w)->{for(int i=0;i<edits.size();i++){if(recognized)session.blocks.get(i).source=edits.get(i).getText().toString();else session.blocks.get(i).translation=edits.get(i).getText().toString();}original=false;if(recognized)session.translate(false);else session.renderEdits();}).show();
    }
    private void back(){if(session.busy)new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_stop_translation)).setNegativeButton(UiText.get(R.string.ui_keep_translating),null).setPositiveButton(UiText.get(R.string.ui_stop),(d,w)->{session.cancel();finish();}).show();else finish();}
    @Override public void handleBackPressed(){back();}
    @Override protected void onSaveInstanceState(Bundle state){state.putBoolean("show_original",original);state.putStringArrayList("captured_pages",session.capturedPages);state.putInt("captured_index",session.capturedIndex);state.putString("translation_capture_mode",session.captureMode);if(session.snapshot!=null)state.putString("translation_session",session.snapshot.getAbsolutePath());if(session.lastExport!=null)state.putString("pending_export",session.lastExport.getAbsolutePath());super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){destroyed=true;super.onDestroy();}
    private TextView text(String value,int size,int color){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setGravity(Gravity.CENTER_VERTICAL);return view;}
    private TextView button(String value,boolean filled){TextView view=text(value,15,filled ? AppTheme.onAccent() :BRAND);view.setGravity(Gravity.CENTER);view.setTypeface(null,android.graphics.Typeface.BOLD);view.setPadding(dp(10),dp(8),dp(10),dp(8));view.setBackground(round(filled ? BRAND : AppTheme.surface(),6));AppTheme.polishAction(view);view.setContentDescription(value);return view;}
    private GradientDrawable round(int color,int radius){GradientDrawable background=new GradientDrawable();background.setColor(color);background.setCornerRadius(dp(radius));return background;}
    private LinearLayout.LayoutParams space(int width,int height,int top){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(width,height);lp.topMargin=dp(top);return lp;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private class PreviewView extends View {
        Bitmap bitmap;float zoom=1,panX,panY,lastX,lastY;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);final ScaleGestureDetector pinch;
        PreviewView(){super(LocalTranslationActivity.this);setContentDescription(UiText.get(R.string.ui_translation_preview_pinch_to_zoom_drag_to_move));pinch=new ScaleGestureDetector(getContext(),new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector detector){zoom=Math.max(1,Math.min(6,zoom*detector.getScaleFactor()));if(zoom==1)panX=panY=0;invalidate();return true;}});}
        void setBitmap(Bitmap value){if(bitmap!=value){bitmap=value;zoom=1;panX=panY=0;invalidate();}}
        @Override protected void onDraw(Canvas canvas){if(bitmap==null||bitmap.isRecycled())return;float fit=Math.min(getWidth()/(float)bitmap.getWidth(),getHeight()/(float)bitmap.getHeight());float w=bitmap.getWidth()*fit*zoom,h=bitmap.getHeight()*fit*zoom;canvas.drawBitmap(bitmap,null,new RectF((getWidth()-w)/2+panX,(getHeight()-h)/2+panY,(getWidth()+w)/2+panX,(getHeight()+h)/2+panY),paint);}
        @Override public boolean onTouchEvent(MotionEvent event){if(bitmap==null)return false;pinch.onTouchEvent(event);if(event.getActionMasked()==MotionEvent.ACTION_DOWN||event.getActionMasked()==MotionEvent.ACTION_POINTER_UP){lastX=event.getX();lastY=event.getY();}else if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&event.getPointerCount()==1&&!pinch.isInProgress()){if(zoom>1){panX+=event.getX()-lastX;panY+=event.getY()-lastY;invalidate();}lastX=event.getX();lastY=event.getY();}return true;}
    }
}

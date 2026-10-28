package com.example.myapplication;

import android.app.*;
import android.content.*;
import android.graphics.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import static org.junit.Assert.*;

public class MissingLanguageFlowTest {
    private final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private final Context context=inst.getTargetContext();
    private final List<Intent> requests=new CopyOnWriteArrayList<>();
    private Instrumentation.ActivityMonitor monitor;
    private Set<String> selected;
    @Before public void setup(){
        SharedPreferences preferences=context.getSharedPreferences("local-languages",0);selected=new HashSet<>(preferences.getStringSet("ocr-enabled",Collections.emptySet()));preferences.edit().putStringSet("ocr-enabled",Collections.emptySet()).commit();
        monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){if(intent.getComponent()!=null&&LanguageManagerActivity.class.getName().equals(intent.getComponent().getClassName())){requests.add(intent);return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);}return null;}};inst.addMonitor(monitor);
    }
    @After public void restore(){inst.removeMonitor(monitor);context.getSharedPreferences("local-languages",0).edit().putStringSet("ocr-enabled",selected).commit();}
    private Object field(Object object,String name){try{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}catch(Exception error){throw new AssertionError(error);}}
    private void set(Object object,String name,Object value){try{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}catch(Exception error){throw new AssertionError(error);}}
    @Test public void textTranslationRequestsTheMissingPackOnceAndPreservesTypedText()throws Exception{
        org.json.JSONArray catalog=LanguageModels.catalog(context,"translation");org.json.JSONObject missing=null;
        for(int i=0;i<catalog.length();i++){org.json.JSONObject entry=catalog.getJSONObject(i);if(entry.getString("code").startsWith("en-")&&!LanguageModels.installed(context,"translation",entry)){missing=entry;break;}}
        assertNotNull("Fixture must include an uninstalled translation pack",missing);String code=LanguageModels.packCode(missing);org.json.JSONObject entry=missing;java.io.File input=java.io.File.createTempFile("missing-text-pack-",".txt",context.getCacheDir());String original="This document must stay available after a missing language pack.";
        try(ActivityScenario<TextTranslationActivity> scenario=ActivityScenario.launch(new Intent(context,TextTranslationActivity.class).putExtra("text_path",input.getPath()))){
            AtomicReference<TextTranslationActivity.Task> task=new AtomicReference<>();scenario.onActivity(a->task.set(new androidx.lifecycle.ViewModelProvider(a).get(TextTranslationActivity.Task.class)));UiTestWait.until(()->!task.get().busy,"Text editor did not finish loading");
            scenario.onActivity(a->{task.get().from="en";task.get().to=code;android.widget.EditText editor=(android.widget.EditText)field(a,"input");editor.setText(original);editor.requestFocus();((android.widget.Button)field(a,"translate")).performClick();});
            UiTestWait.until(()->requests.size()==1&&!task.get().busy,"Missing text translation pack did not open Languages");assertEquals("translation",requests.get(0).getStringExtra("kind"));assertEquals(code,requests.get(0).getStringExtra("focus_language"));assertEquals(original,task.get().source);assertTrue(task.get().translated.isEmpty());assertNull(task.get().missingPack);assertFalse(LanguageModels.installed(context,"translation",entry));scenario.recreate();inst.waitForIdleSync();assertEquals(1,requests.size());scenario.onActivity(a->assertEquals(original,((android.widget.EditText)field(a,"input")).getText().toString()));
        }finally{input.delete();}
    }
    @Test public void batchAndEditableOfficeExportsRequestOcrWithoutLeavingPartialFiles()throws Exception{
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context);java.io.File input=java.io.File.createTempFile("missing-office-ocr-",".pdf",context.getCacheDir());
        try{try(com.tom_roush.pdfbox.pdmodel.PDDocument pdf=new com.tom_roush.pdfbox.pdmodel.PDDocument()){pdf.addPage(new com.tom_roush.pdfbox.pdmodel.PDPage(new com.tom_roush.pdfbox.pdmodel.common.PDRectangle(200,300)));pdf.save(input);}byte[] original=java.nio.file.Files.readAllBytes(input.toPath());
            for(String operation:new String[]{"Batch OCR","To Excel","To Word","To PowerPoint"}){
                int expected=requests.size()+1;ExecutorService[] worker=new ExecutorService[1];
                try(ActivityScenario<ExtendedToolsActivity> scenario=ActivityScenario.launch(new Intent(context,ExtendedToolsActivity.class).putExtra("operation",operation).putExtra("path",input.getPath()).putExtra("name","Scanned document.pdf"))){
                    AtomicReference<ExtendedToolsActivity> activity=new AtomicReference<>();scenario.onActivity(a->{activity.set(a);worker[0]=(ExecutorService)field(a,"worker");});UiTestWait.until(()->!(Boolean)field(activity.get(),"busy"),"Office source did not finish loading");
                    java.io.File documents=new java.io.File(context.getFilesDir(),"documents");Set<String> before=new HashSet<>();if(documents.isDirectory())Collections.addAll(before,Objects.requireNonNull(documents.list()));
                    scenario.onActivity(a->{set(a,"editable",true);((android.widget.TextView)field(a,"primary")).performClick();});UiTestWait.until(()->requests.size()==expected&&!(Boolean)field(activity.get(),"busy"),operation+" did not request an OCR language");assertEquals("ocr",requests.get(expected-1).getStringExtra("kind"));assertNull(field(activity.get(),"result"));assertNull(field(activity.get(),"work"));assertArrayEquals(original,java.nio.file.Files.readAllBytes(input.toPath()));Set<String> after=new HashSet<>();if(documents.isDirectory())Collections.addAll(after,Objects.requireNonNull(documents.list()));assertEquals("Failed export left a partial file",before,after);
                }assertTrue(worker[0].awaitTermination(15,TimeUnit.SECONDS));
            }
        }finally{input.delete();}
    }
    @Test public void ocrRedirectsToLanguagesWithoutStartingWork(){
        try(ActivityScenario<LocalTranslationActivity> scenario=ActivityScenario.launch(LocalTranslationActivity.class)){
            scenario.onActivity(activity->assertFalse(LanguageAccess.requireOcr(activity)));assertEquals(1,requests.size());assertEquals("ocr",requests.get(0).getStringExtra("kind"));
        }
    }
    @Test public void missingTranslationRedirectIsConsumedOnceAndKeepsTheSource()throws Exception{
        AtomicReference<LocalTranslationActivity.TranslationSession> session=new AtomicReference<>();
        try(ActivityScenario<LocalTranslationActivity> scenario=ActivityScenario.launch(LocalTranslationActivity.class)){
            Bitmap source=Bitmap.createBitmap(40,60,Bitmap.Config.ARGB_8888);
            scenario.onActivity(activity->{LocalTranslationActivity.TranslationSession task=(LocalTranslationActivity.TranslationSession)field(activity,"session");session.set(task);task.source=source;task.fail(new LanguageAccess.Missing("translation","de"));});
            UiTestWait.until(()->requests.size()==1,"Missing translation did not open the language manager");assertEquals("translation",requests.get(0).getStringExtra("kind"));assertEquals("de",requests.get(0).getStringExtra("focus_language"));
            scenario.onActivity(activity->{assertNull(session.get().missingPack);assertSame(source,session.get().source);assertFalse(source.isRecycled());session.get().publish();});scenario.recreate();inst.waitForIdleSync();assertEquals("Returning/cancelling must not reopen Languages",1,requests.size());
        }
        assertTrue(session.get().worker.awaitTermination(10,TimeUnit.SECONDS));
    }
    @Test public void missingPacksAreReportedBeforeAnyAutomaticDownload()throws Exception{
        LocalTranslationActivity.TranslationSession task=new LocalTranslationActivity.TranslationSession((Application)context.getApplicationContext()){
            @Override org.json.JSONObject language(String kind,String code)throws Exception{return new org.json.JSONObject().put("code",code).put("name","Fixture missing language");}
        };
        try{
            LanguageAccess.Missing error=assertThrows(LanguageAccess.Missing.class,()->task.ensureLanguage("translation","fixture-missing"));assertEquals("fixture-missing",error.code);assertEquals("translation",error.kind);
            assertSame(error,LanguageAccess.missing(new IllegalStateException("wrapped",error)));assertNull(LanguageAccess.missing(new java.io.IOException("unrelated")));
        }finally{inst.runOnMainSync(task::onCleared);assertTrue(task.worker.awaitTermination(10,TimeUnit.SECONDS));}
    }
}

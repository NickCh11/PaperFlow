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

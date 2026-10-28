package com.example.myapplication;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.widget.Button;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class TextSessionRegressionTest {
    private final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private TextTranslationActivity.Task task(TextTranslationActivity a){return new androidx.lifecycle.ViewModelProvider(a).get(TextTranslationActivity.Task.class);}
    private Object field(Object owner,String name){try{java.lang.reflect.Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(Exception e){throw new AssertionError(e);}}
    private void ready(AtomicReference<TextTranslationActivity> activity)throws Exception{UiTestWait.until(()->!task(activity.get()).loading&&!task(activity.get()).busy,"Text editor did not finish loading");}
    private void seed(TextTranslationActivity a){TextTranslationActivity.Task t=task(a);t.source="Original κείμενο";t.translated="Translated Ελληνικά\nSecond line";t.from="en";t.to="el";t.publish();}

    @Test public void invalidRestoredSessionStopsLoadingAndCannotBeLoadedTwice()throws Exception{
        Context context=inst.getTargetContext();File invalid=File.createTempFile("text-corrupt-",".json",context.getCacheDir());Files.write(invalid.toPath(),"{broken".getBytes(StandardCharsets.UTF_8));
        TextTranslationActivity.Task t=new TextTranslationActivity.Task((Application)context.getApplicationContext());
        try{inst.runOnMainSync(()->t.load(null,invalid.getPath()));UiTestWait.until(()->!t.loading&&!t.busy,"Invalid session left the editor busy");assertFalse(t.message.isEmpty());assertEquals("",t.source);
            inst.runOnMainSync(()->t.load("must-not-be-read",null));assertFalse(t.busy);assertEquals("",t.source);
        }finally{inst.runOnMainSync(t::onCleared);invalid.delete();}
    }

    @Test public void sameLanguageTranslationPreservesAllTextAcrossLongParagraphAndWordBoundaries(){
        Context context=inst.getTargetContext();
        for(String value:new String[]{"A".repeat(600)+"\n"+"B".repeat(600)+" "+"C".repeat(650),"word ".repeat(700),"X".repeat(2500)}){
            TextTranslationActivity.Task t=new TextTranslationActivity.Task((Application)context.getApplicationContext());
            try{inst.runOnMainSync(()->{t.source=value;t.from="en";t.to="en";t.translate();});UiTestWait.until(()->!t.busy,"Same-language translation did not finish");
                assertEquals(value.replaceAll("\\s",""),t.translated.replaceAll("\\s",""));assertEquals("en",t.detected);assertFalse(t.message.isEmpty());
            }finally{inst.runOnMainSync(t::onCleared);}
        }
    }

    @Test public void cancellationAndInputLimitsFailBeforeAnyModelDownload()throws Exception{
        Context context=inst.getTargetContext();TextTranslationActivity.Task t=new TextTranslationActivity.Task((Application)context.getApplicationContext());
        try{for(String value:new String[]{" ","x".repeat(1000001)})inst.runOnMainSync(()->{t.source=value;t.translate();assertFalse(t.busy);assertTrue(t.translated.isEmpty());assertFalse(t.message.isEmpty());});
            t.cancelled=true;assertThrows(java.io.InterruptedIOException.class,t::check);t.cancelled=false;
            try{Thread.currentThread().interrupt();assertThrows(java.io.InterruptedIOException.class,t::check);}finally{Thread.interrupted();}
            inst.runOnMainSync(()->{t.busy=true;t.translated="Keep existing result";t.translate();assertEquals("Keep existing result",t.translated);t.busy=false;t.onCleared();t.publish();t.status("late status");});
            assertThrows(java.io.InterruptedIOException.class,t::check);inst.waitForIdleSync();assertNotEquals("late status",t.message);
        }finally{inst.runOnMainSync(t::onCleared);}
    }

    @Test public void busyEditorCancelsWithoutReplacingTheRunningInputAndLoadingDoesNotWriteAPartialSnapshot()throws Exception{
        Context context=inst.getTargetContext();File file=File.createTempFile("text-busy-",".txt",context.getCacheDir());
        try(ActivityScenario<TextTranslationActivity> scenario=ActivityScenario.launch(new Intent(context,TextTranslationActivity.class).putExtra("text_path",file.getPath()))){
            AtomicReference<TextTranslationActivity> current=new AtomicReference<>();scenario.onActivity(current::set);ready(current);
            File snapshot=(File)field(current.get(),"sessionFile");
            scenario.onActivity(a->{TextTranslationActivity.Task t=task(a);t.source="Running original";t.translated="Completed result";t.publish();
                assertTrue(((Button)field(a,"copy")).isEnabled());((Button)field(a,"copy")).performClick();
                android.content.ClipboardManager clipboard=(android.content.ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);assertEquals("Completed result",clipboard.getPrimaryClip().getItemAt(0).getText().toString());
                t.busy=true;t.loading=true;t.publish();
                ((android.widget.EditText)field(a,"input")).setText("UI text must not replace the running input");assertFalse(((Button)field(a,"copy")).isEnabled());assertFalse(((Button)field(a,"share")).isEnabled());assertFalse(((Button)field(a,"saveButton")).isEnabled());
                a.onSaveInstanceState(new android.os.Bundle());assertEquals("Running original",t.source);((Button)field(a,"translate")).performClick();assertTrue(t.cancelled);
            });
            assertThrows(java.io.IOException.class,()->SessionSnapshots.read(snapshot));
            scenario.onActivity(a->{TextTranslationActivity.Task t=task(a);t.loading=false;a.onSaveInstanceState(new android.os.Bundle());t.busy=false;});
            org.json.JSONObject saved=new org.json.JSONObject(SessionSnapshots.read(snapshot));assertEquals("Running original",saved.getString("source"));assertEquals("Completed result",saved.getString("translated"));
        }finally{file.delete();}
    }

    @Test public void recreationWritesACompleteSnapshotThatANewSessionCanRestore()throws Exception{
        Context context=inst.getTargetContext();File input=File.createTempFile("text-empty-",".txt",context.getCacheDir());
        TextTranslationActivity.Task restored=new TextTranslationActivity.Task((Application)context.getApplicationContext());
        try(ActivityScenario<TextTranslationActivity> scenario=ActivityScenario.launch(new Intent(context,TextTranslationActivity.class).putExtra("text_path",input.getPath()))){
            AtomicReference<TextTranslationActivity> activity=new AtomicReference<>();scenario.onActivity(activity::set);ready(activity);
            scenario.onActivity(this::seed);scenario.recreate();scenario.onActivity(activity::set);ready(activity);
            File snapshot=(File)field(activity.get(),"sessionFile");
            inst.runOnMainSync(()->restored.load(null,snapshot.getPath()));
            UiTestWait.until(()->!restored.busy,"Queued text snapshot did not restore");
            assertEquals("Original κείμενο",restored.source);assertEquals("Translated Ελληνικά\nSecond line",restored.translated);assertEquals("en",restored.from);assertEquals("el",restored.to);
        }finally{restored.onCleared();input.delete();}
    }

    @Test public void saveAndShareUseTheCompleteUtf8Text()throws Exception{
        Context context=inst.getTargetContext();File input=File.createTempFile("text-input-",".txt",context.getCacheDir());
        File exports=new File(context.getCacheDir(),"translation-exports");assertTrue(exports.isDirectory()||exports.mkdirs());File saved=File.createTempFile("text-output-",".txt",exports);
        AtomicReference<Intent> shared=new AtomicReference<>();
        Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){if(Intent.ACTION_CHOOSER.equals(intent.getAction())){shared.set(intent);return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);}return null;}};
        inst.addMonitor(monitor);
        try(ActivityScenario<TextTranslationActivity> scenario=ActivityScenario.launch(new Intent(context,TextTranslationActivity.class).putExtra("text_path",input.getPath()))){
            AtomicReference<TextTranslationActivity> activity=new AtomicReference<>();scenario.onActivity(activity::set);ready(activity);scenario.onActivity(this::seed);
            Uri uri=androidx.core.content.FileProvider.getUriForFile(context,context.getPackageName()+".files",saved);
            scenario.onActivity(a->{try{java.lang.reflect.Method save=TextTranslationActivity.class.getDeclaredMethod("save",Uri.class);save.setAccessible(true);save.invoke(a,uri);}catch(Exception e){throw new AssertionError(e);}});
            UiTestWait.until(()->saved.length()>0,"Text save did not complete");assertEquals("Translated Ελληνικά\nSecond line",new String(Files.readAllBytes(saved.toPath()),StandardCharsets.UTF_8));
            scenario.onActivity(a->((Button)field(a,"share")).performClick());
            UiTestWait.until(()->shared.get()!=null,"Text share was not dispatched");
            Intent payload=shared.get().getParcelableExtra(Intent.EXTRA_INTENT);assertEquals("text/plain",payload.getType());assertTrue((payload.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0);
            Uri exported=payload.getParcelableExtra(Intent.EXTRA_STREAM);
            try(java.io.InputStream stream=context.getContentResolver().openInputStream(exported)){assertNotNull(stream);java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[1024];int count;while((count=stream.read(buffer))!=-1)bytes.write(buffer,0,count);assertEquals("Translated Ελληνικά\nSecond line",bytes.toString("UTF-8"));}
        }finally{inst.removeMonitor(monitor);input.delete();saved.delete();}
    }
}

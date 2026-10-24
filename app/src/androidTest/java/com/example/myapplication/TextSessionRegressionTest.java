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

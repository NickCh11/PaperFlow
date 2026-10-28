package com.example.myapplication;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;
import org.junit.*;
import static org.junit.Assert.*;

/** App-owned import and edit orchestration; inference is a recorded boundary, not a model benchmark. */
public class TranslationInputLifecycleTest {
    final android.app.Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    final Application app=(Application)inst.getTargetContext().getApplicationContext();
    RecordingSession session;
    final List<File> originals=new ArrayList<>();
    static class RecordingSession extends LocalTranslationActivity.TranslationSession {
        int recognitions,editCalls;JSONObject edits;String from,to;boolean reject;
        RecordingSession(Application app){super(app);}
        @Override void translate(boolean recognize){if(recognize)recognitions++;}
        @Override void renderNative(String from,String to,JSONObject edits)throws Exception{
            this.from=from;this.to=to;this.edits=edits;editCalls++;
            if(reject)throw new IOException("Controlled render failure");
            main.post(()->{busy=false;publish();});
        }
    }
    @Before public void open(){inst.runOnMainSync(()->session=new RecordingSession(app));}
    @After public void close()throws Exception{
        inst.runOnMainSync(()->{if(!session.disposed)session.onCleared();});assertTrue(session.worker.awaitTermination(10,TimeUnit.SECONDS));
        for(File file:originals)file.delete();
    }
    File image(int color)throws Exception{
        File file=File.createTempFile("translation-input-regression-",".png",app.getCacheDir());originals.add(file);
        Bitmap bitmap=Bitmap.createBitmap(96,128,Bitmap.Config.ARGB_8888);bitmap.eraseColor(color);
        try(OutputStream out=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}return file;
    }
    void idle(){UiTestWait.until(()->!session.busy,"Import/edit did not finish");}
    Set<String> importedFiles(){File[] files=new File(app.getFilesDir(),"translation-inputs").listFiles();Set<String> result=new HashSet<>();if(files!=null)for(File file:files)result.add(file.getPath());return result;}
    @Test public void importsOwnCopiesAndNavigationLoadsTheCorrectPageWithoutChangingOriginals()throws Exception{
        File red=image(Color.RED),blue=image(Color.BLUE);byte[] before=Files.readAllBytes(red.toPath());
        inst.runOnMainSync(()->session.importImages(Arrays.asList(Uri.fromFile(red),Uri.fromFile(blue))));idle();
        assertFalse(session.message,session.error);assertEquals(2,session.capturedPages.size());assertEquals(2,session.importedSources.size());
        assertEquals("Photo",session.captureMode);assertEquals(1,session.recognitions);assertEquals(Color.RED,session.source.getPixel(0,0));
        for(String path:session.capturedPages){assertEquals(new File(app.getFilesDir(),"translation-inputs").getCanonicalFile(),new File(path).getCanonicalFile().getParentFile());assertFalse(path.equals(red.getPath())||path.equals(blue.getPath()));}
        inst.runOnMainSync(()->session.selectPage(1));idle();assertEquals(Color.BLUE,session.source.getPixel(0,0));assertEquals(2,session.recognitions);
        inst.runOnMainSync(()->{session.selectPage(1);session.selectPage(-1);session.selectPage(2);session.busy=true;session.selectPage(0);session.importImages(Collections.singletonList(Uri.fromFile(red)));session.busy=false;session.importImages(Collections.emptyList());});
        assertEquals(1,session.capturedIndex);assertEquals(2,session.recognitions);assertArrayEquals(before,Files.readAllBytes(red.toPath()));
        List<String> owned=new ArrayList<>(session.capturedPages);inst.runOnMainSync(session::onCleared);assertTrue(session.worker.awaitTermination(10,TimeUnit.SECONDS));
        for(String path:owned)assertFalse("Session disposal must remove its imported copies",new File(path).exists());assertTrue(red.isFile());assertTrue(blue.isFile());
    }
    @Test public void unreadableSecondInputRollsBackEarlierCopiesAndKeepsThePreview()throws Exception{
        File red=image(Color.RED);Set<String> before=importedFiles();Bitmap preview=BitmapFactory.decodeFile(red.getPath());
        inst.runOnMainSync(()->{session.source=preview;session.importImages(Arrays.asList(Uri.fromFile(red),Uri.fromFile(new File(app.getCacheDir(),"missing-"+UUID.randomUUID()))));});idle();
        assertTrue(session.error);assertSame(preview,session.source);assertFalse(preview.isRecycled());assertTrue(session.capturedPages.isEmpty());assertEquals(before,importedFiles());assertEquals(0,session.recognitions);
    }
    @Test public void cancellationAfterCopyBeforePublicationDeletesAllStagedInputs()throws Exception{
        File red=image(Color.RED);Set<String> before=importedFiles();
        inst.runOnMainSync(()->{
            session.importImages(Collections.singletonList(Uri.fromFile(red)));
            // The worker only posts publication. Hold the UI until copying finishes,
            // then cancel before the posted callback can adopt those files.
            try{session.worker.submit(()->{}).get(10,TimeUnit.SECONDS);}catch(Exception error){throw new AssertionError(error);}
            session.cancelled=true;
        });idle();assertTrue(session.error);assertTrue(session.capturedPages.isEmpty());assertTrue(session.importedSources.isEmpty());assertEquals(before,importedFiles());assertEquals(0,session.recognitions);assertTrue(red.isFile());
    }
    @Test public void parentCancellationStopsAnImportBeforeAnyCopyIsAdopted()throws Exception{
        File red=image(Color.RED);Set<String> before=importedFiles();
        inst.runOnMainSync(()->{session.parentCancelled=()->true;session.importImages(Collections.singletonList(Uri.fromFile(red)));});idle();
        assertTrue(session.error);assertTrue(session.capturedPages.isEmpty());assertEquals(before,importedFiles());assertEquals(0,session.recognitions);
    }
    @Test public void editsSendBothTextListsAndRenderFailurePreservesTheSource()throws Exception{
        File red=image(Color.RED);Bitmap source=BitmapFactory.decodeFile(red.getPath());
        inst.runOnMainSync(()->{
            session.renderEdits();assertFalse(session.busy);assertEquals(0,session.editCalls);
            session.source=source;session.sourceLanguage="en";session.target="el";
            for(String text:new String[]{"FIRST","SECOND"}){com.paperflow.imageprocessing.OcrTextBox box=new com.paperflow.imageprocessing.OcrTextBox(text,new com.paperflow.imageprocessing.ImageRect(2,2,80,25),23,25);TranslationRenderer.Block block=new TranslationRenderer.Block(box);block.translation="Μετάφραση "+text;session.blocks.add(block);}
            session.busy=true;session.renderEdits();assertEquals(0,session.editCalls);session.busy=false;session.renderEdits();
        });idle();assertEquals(1,session.editCalls);assertEquals("en",session.from);assertEquals("el",session.to);
        assertEquals("SECOND",session.edits.getJSONArray("sourceEdits").getString(1));assertEquals("Μετάφραση FIRST",session.edits.getJSONArray("translationEdits").getString(0));
        inst.runOnMainSync(()->{session.reject=true;session.renderEdits();});idle();assertTrue(session.error);assertEquals("Controlled render failure",session.message);assertSame(source,session.source);assertFalse(source.isRecycled());assertEquals(2,session.blocks.size());
    }
}

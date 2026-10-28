package com.example.myapplication;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.Collections;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Real photo loading and cancellation at the translation-session boundary. */
@RunWith(AndroidJUnit4.class)
public class PhotoSessionInputTest {
    final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    final Context context=inst.getTargetContext();
    LocalTranslationActivity.TranslationSession session(){return new LocalTranslationActivity.TranslationSession((Application)context.getApplicationContext());}
    Bitmap preview(){Bitmap bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.YELLOW);return bitmap;}
    File photo()throws Exception{
        File file=File.createTempFile("translation-photo-",".png",context.getCacheDir());
        Bitmap image=Bitmap.createBitmap(3200,1200,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(Color.WHITE);
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.BLACK);paint.setTextSize(96);
        canvas.drawText("Invoice number 12345",120,250,paint);canvas.drawText("Hello world. This is a document.",120,440,paint);
        try(FileOutputStream out=new FileOutputStream(file)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}return file;
    }
    void stopped(LocalTranslationActivity.TranslationSession session)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+60000;
        while(SystemClock.elapsedRealtime()<deadline){inst.waitForIdleSync();if(!session.busy)return;Thread.sleep(25);}
        fail("Photo operation timed out: "+session.message);
    }
    void close(LocalTranslationActivity.TranslationSession session)throws Exception{
        inst.runOnMainSync(session::onCleared);assertTrue(session.worker.awaitTermination(5,TimeUnit.SECONDS));inst.waitForIdleSync();
    }

    @Test public void largePhotoUsesOfflineOcrAndReplacesThePreviousPreview()throws Exception{
        File input=photo();LocalTranslationActivity.TranslationSession session=session();
        Bitmap previousSource=preview(),previousOutput=preview();
        session.source=previousSource;session.result=new TranslationRenderer.Result(previousOutput,Collections.emptyList());
        try{
            inst.runOnMainSync(()->{session.sourceOverride="en";session.target="en";session.captureMode="Photo";session.load(Uri.fromFile(input));});stopped(session);
            assertFalse(session.message,session.error);assertNotNull(session.source);assertEquals(1600,session.source.getWidth());assertEquals(600,session.source.getHeight());
            assertTrue(previousSource.isRecycled());assertTrue(previousOutput.isRecycled());assertNotNull(session.result);assertFalse(session.result.bitmap.isRecycled());
            assertEquals("en",session.sourceLanguage);assertEquals("en",session.target);assertTrue(session.sourceText(session.blocks),session.sourceText(session.blocks).contains("12345"));
            assertNotNull(session.snapshot);assertTrue(new File(session.snapshot,"result.png").isFile());assertFalse(session.busy);
        }finally{close(session);input.delete();}
    }

    @Test public void corruptPhotoKeepsThePreviouslyVisibleImageAndReportsAnError()throws Exception{
        File input=File.createTempFile("corrupt-photo-",".png",context.getCacheDir());Files.write(input.toPath(),new byte[]{1,2,3});
        LocalTranslationActivity.TranslationSession session=session();Bitmap previous=preview();session.source=previous;
        try{
            inst.runOnMainSync(()->session.load(Uri.fromFile(input)));stopped(session);
            assertTrue(session.error);assertEquals(context.getString(R.string.ui_could_not_open_this_photo_choose_another_image),session.message);
            assertSame(previous,session.source);assertFalse(previous.isRecycled());assertNull(session.result);
        }finally{close(session);input.delete();}
    }

    @Test public void cancellingQueuedPhotoLoadKeepsThePreviousImage()throws Exception{
        File input=photo();LocalTranslationActivity.TranslationSession session=session();Bitmap previous=preview();session.source=previous;
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        session.worker.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
        try{
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            inst.runOnMainSync(()->{session.load(Uri.fromFile(input));session.cancel();});release.countDown();stopped(session);
            assertTrue(session.error);assertEquals(context.getString(R.string.ui_translation_cancelled),session.message);
            assertSame(previous,session.source);assertFalse(previous.isRecycled());assertNull(session.result);assertNull(session.snapshot);
        }finally{release.countDown();close(session);input.delete();}
    }

    @Test public void changingLanguageWithoutAPhotoClearsStalePreviewAndPersistsTarget()throws Exception{
        android.content.SharedPreferences prefs=context.getSharedPreferences("photo-translation",0);boolean had=prefs.contains("target");String before=prefs.getString("target","en");
        LocalTranslationActivity.TranslationSession session=session();Bitmap previous=preview();session.result=new TranslationRenderer.Result(previous,Collections.emptyList());
        File stale=new File(context.getCacheDir(),"stale-language-preview");session.snapshot=stale;session.lastExport=stale;session.pageSnapshots.put(0,stale);
        try{
            inst.runOnMainSync(()->{session.busy=true;session.chooseTarget("el");assertSame(stale,session.snapshot);session.busy=false;session.chooseTarget("el");});
            assertEquals("el",session.target);assertEquals("el",prefs.getString("target",""));assertTrue(previous.isRecycled());assertNull(session.result);assertNull(session.snapshot);assertNull(session.lastExport);assertTrue(session.pageSnapshots.isEmpty());
            inst.runOnMainSync(()->session.chooseSource("en"));assertEquals("en",session.sourceOverride);assertEquals("",session.sourceLanguage);assertFalse(session.busy);
            assertThrows(IOException.class,()->session.language("ocr","not-a-supported-language"));
        }finally{close(session);android.content.SharedPreferences.Editor edit=prefs.edit();if(had)edit.putString("target",before);else edit.remove("target");edit.commit();}
    }
}

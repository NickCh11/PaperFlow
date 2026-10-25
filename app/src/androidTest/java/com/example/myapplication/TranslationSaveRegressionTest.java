package com.example.myapplication;

import android.app.Application;
import android.app.Instrumentation;
import android.content.Context;
import android.net.Uri;
import android.provider.DocumentsContract;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TranslationSaveRegressionTest {
    final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    final Context context=inst.getTargetContext();
    LocalTranslationActivity.TranslationSession session(){return new LocalTranslationActivity.TranslationSession((Application)context.getApplicationContext());}
    Uri destination()throws Exception{
        Uri root=DocumentsContract.buildDocumentUri(context.getPackageName()+".translation-test-documents","root");
        return DocumentsContract.createDocument(context.getContentResolver(),root,"application/pdf","single-save-"+java.util.UUID.randomUUID()+".pdf");
    }
    void drain(LocalTranslationActivity.TranslationSession session)throws Exception{
        session.worker.submit(()->{}).get(5,TimeUnit.SECONDS);inst.waitForIdleSync();
        assertFalse("Save remained busy",session.busy);
    }
    byte[] read(Uri uri)throws Exception{try(InputStream in=context.getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){assertNotNull(in);byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();}}

    @Test public void singleExportSavesEveryByteThroughTheDocumentProvider()throws Exception{
        LocalTranslationActivity.TranslationSession session=session();File source=File.createTempFile("single-export-",".pdf",context.getCacheDir());Uri target=destination();
        byte[] bytes=new byte[131079];new java.util.Random(817).nextBytes(bytes);Files.write(source.toPath(),bytes);
        try{
            inst.runOnMainSync(()->{session.lastExport=source;session.copyOutput(target);});drain(session);
            assertFalse(session.message,session.error);assertArrayEquals(bytes,read(target));assertArrayEquals(bytes,Files.readAllBytes(source.toPath()));
            assertEquals(context.getString(R.string.ui_saved_to_device),session.message);
        }finally{session.worker.shutdownNow();source.delete();DocumentsContract.deleteDocument(context.getContentResolver(),target);}
    }

    @Test public void cancellingQueuedSavePreservesTheDestinationAndReportsCancellation()throws Exception{
        LocalTranslationActivity.TranslationSession session=session();File source=File.createTempFile("cancel-save-",".pdf",context.getCacheDir());Uri target=destination();
        byte[] original={8,1,7},replacement={9,2,6};Files.write(source.toPath(),replacement);
        try(OutputStream output=context.getContentResolver().openOutputStream(target,"w")){assertNotNull(output);output.write(original);}
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        session.worker.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}});
        try{
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            inst.runOnMainSync(()->{session.lastExport=source;session.copyOutput(target);assertTrue(session.busy);session.cancel();});
            release.countDown();drain(session);
            assertArrayEquals("Cancelled queued save must not truncate a destination",original,read(target));
            assertTrue("Cancelled save must report cancellation",session.error);
            assertEquals(context.getString(R.string.ui_translation_cancelled),session.message);
            assertArrayEquals(replacement,Files.readAllBytes(source.toPath()));
        }finally{release.countDown();session.worker.shutdownNow();source.delete();DocumentsContract.deleteDocument(context.getContentResolver(),target);}
    }

    @Test public void missingExportReportsAnErrorAndLeavesTheDestinationUntouched()throws Exception{
        LocalTranslationActivity.TranslationSession session=session();Uri target=destination();
        try{
            inst.runOnMainSync(()->session.copyOutput(target));drain(session);
            assertTrue(session.error);assertEquals(context.getString(R.string.ui_could_not_save_image),session.message);assertEquals(0,read(target).length);
            session.lastExport=new File(context.getCacheDir(),"absent-export-"+java.util.UUID.randomUUID());
            inst.runOnMainSync(()->session.copyOutput(target));drain(session);
            assertTrue(session.error);assertNotNull(session.message);assertEquals(0,read(target).length);
        }finally{session.worker.shutdownNow();DocumentsContract.deleteDocument(context.getContentResolver(),target);}
    }
}

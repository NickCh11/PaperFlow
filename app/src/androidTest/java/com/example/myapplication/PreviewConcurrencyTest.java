package com.example.myapplication;

import android.content.*;
import android.graphics.Bitmap;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.pdmodel.*;
import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import org.junit.Test;
import static org.junit.Assert.*;

public class PreviewConcurrencyTest {
    private Object field(Object owner,String name){try{java.lang.reflect.Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(Exception e){throw new AssertionError(e);}}
    private void request(ToolWorkflowActivity owner,int page,Consumer<Bitmap> listener){try{java.lang.reflect.Method m=ToolWorkflowActivity.class.getDeclaredMethod("requestThumbnail",int.class,boolean.class,Consumer.class);m.setAccessible(true);m.invoke(owner,page,true,listener);}catch(Exception e){throw new AssertionError(e);}}
    private File source()throws Exception{Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(c);File pdf=File.createTempFile("preview-race-",".pdf",c.getCacheDir());try(PDDocument doc=new PDDocument()){doc.addPage(new PDPage());doc.addPage(new PDPage());doc.save(pdf);}return pdf;}
    private ActivityScenario<ToolWorkflowActivity> open(File source){Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();return ActivityScenario.launch(new Intent(c,ToolWorkflowActivity.class).putExtra("operation","Extract PDF Pages").putExtra("path",source.getPath()));}

    @Test public void duplicateRequestsShareACachedBitmapAndFailedPagesCanRetry()throws Exception{
        File pdf=source();CountDownLatch release=new CountDownLatch(1);
        try(ActivityScenario<ToolWorkflowActivity> scenario=open(pdf)){
            AtomicReference<ToolWorkflowActivity> owner=new AtomicReference<>();scenario.onActivity(owner::set);
            UiTestWait.until(()->"options".equals(field(owner.get(),"stage")),"Preview options did not load");
            ExecutorService worker=(ExecutorService)field(owner.get(),"previewWorker");CountDownLatch blocked=new CountDownLatch(1);
            worker.execute(()->{blocked.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(blocked.await(5,TimeUnit.SECONDS));
            AtomicReference<Bitmap> first=new AtomicReference<>(),second=new AtomicReference<>();CountDownLatch ready=new CountDownLatch(2);
            scenario.onActivity(a->{request(a,0,b->{first.set(b);ready.countDown();});request(a,0,b->{second.set(b);ready.countDown();});});
            release.countDown();assertTrue(ready.await(5,TimeUnit.SECONDS));assertNotNull(first.get());assertSame(first.get(),second.get());
            scenario.onActivity(a->request(a,0,b->assertSame(first.get(),b)));
            for(int attempt=0;attempt<2;attempt++){CountDownLatch failed=new CountDownLatch(1);AtomicReference<Bitmap> result=new AtomicReference<>();scenario.onActivity(a->request(a,-1,b->{result.set(b);failed.countDown();}));assertTrue(failed.await(5,TimeUnit.SECONDS));assertNull(result.get());}
        }finally{release.countDown();pdf.delete();}
    }

    @Test public void invalidationAndDestructionNeverDeliverAStalePreview()throws Exception{
        File pdf=source();AtomicBoolean delivered=new AtomicBoolean();AtomicReference<ToolWorkflowActivity> owner=new AtomicReference<>();
        try(ActivityScenario<ToolWorkflowActivity> scenario=open(pdf)){
            scenario.onActivity(owner::set);UiTestWait.until(()->"options".equals(field(owner.get(),"stage")),"Preview options did not load");
            ExecutorService worker=(ExecutorService)field(owner.get(),"previewWorker");worker.submit(()->{}).get(5,TimeUnit.SECONDS);
            scenario.onActivity(a->{
                request(a,1,b->delivered.set(true));CountDownLatch rendered=new CountDownLatch(1);worker.execute(rendered::countDown);
                try{assertTrue(rendered.await(5,TimeUnit.SECONDS));java.lang.reflect.Method clear=ToolWorkflowActivity.class.getDeclaredMethod("clearThumbnails");clear.setAccessible(true);clear.invoke(a);}
                catch(Exception error){throw new AssertionError(error);}
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();assertFalse(delivered.get());
            scenario.onActivity(a->assertEquals(0,((android.util.LruCache<?,?>)field(a,"thumbnailCache")).size()));
        }finally{pdf.delete();}
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->request(owner.get(),0,b->delivered.set(true)));
        assertFalse(delivered.get());
    }
}

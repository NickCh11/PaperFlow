package com.example.myapplication;

import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BookCaptureSessionTest {
    private Application app(){return (Application)InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();}
    private void onMain(Runnable action){InstrumentationRegistry.getInstrumentation().runOnMainSync(action);}
    private File page(File directory,String name)throws Exception{
        assertTrue(directory.isDirectory()||directory.mkdirs());File file=new File(directory,name);Bitmap image=Bitmap.createBitmap(240,320,Bitmap.Config.ARGB_8888);image.eraseColor(Color.WHITE);
        try(FileOutputStream out=new FileOutputStream(file)){assertTrue(image.compress(Bitmap.CompressFormat.JPEG,95,out));}finally{image.recycle();}return file;
    }
    private void awaitIdle(CameraCaptureSession session)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+10000;boolean[] busy={true};
        do{onMain(()->busy[0]=session.busy);if(!busy[0])return;Thread.sleep(25);}while(SystemClock.elapsedRealtime()<deadline);
        fail("Book session did not become idle");
    }
    @Test public void storeFiltersMissingAndExternalPathsAndCompleteRetainsImages()throws Exception{
        File directory=new File(app().getCacheDir(),"book-store-test-"+UUID.randomUUID()),captures=new File(directory,"captures");File a=page(captures,"a.jpg"),b=page(captures,"b.jpg"),outside=page(directory,"outside.jpg");BookDraftStore store=new BookDraftStore(directory);
        try{
            store.save(Arrays.asList(a.getAbsolutePath(),b.getAbsolutePath()),false);assertTrue(b.delete());
            BookDraftStore.Snapshot loaded=store.read();assertFalse(loaded.doublePage);assertEquals(Arrays.asList(a.getCanonicalPath()),loaded.pages);
            store.save(loaded.pages,false);store.complete();assertTrue("Complete must retain editor inputs",a.isFile());assertTrue(store.read().pages.isEmpty());
            try{store.save(Arrays.asList(outside.getAbsolutePath()),true);fail("External page accepted");}catch(IOException expected){}
            String invalid="{\"version\":1,\"doublePage\":true,\"pages\":[\""+outside.getAbsolutePath().replace("\\","\\\\")+"\"]}";
            try(FileOutputStream out=new FileOutputStream(new File(directory,"book-draft.json"))){out.write(invalid.getBytes(StandardCharsets.UTF_8));}
            assertTrue(store.read().pages.isEmpty());store.discard();assertTrue("Discard deleted an external file",outside.isFile());
            store.save(Arrays.asList(a.getAbsolutePath()),true);store.discard();assertFalse(a.exists());assertTrue(store.read().pages.isEmpty());
        }finally{a.delete();b.delete();outside.delete();new File(directory,"book-draft.json").delete();new File(directory,"book-draft.json.bak").delete();new File(directory,"book-draft.json.new").delete();captures.delete();directory.delete();}
    }
    @Test public void bookCaptureStaysInCameraRestoresAndUndoPersists()throws Exception{
        AtomicReference<CameraCaptureSession> first=new AtomicReference<>(),restored=new AtomicReference<>();BookDraftStore store=new BookDraftStore(app());
        // Run in the clean instrumentation fixture; retain any existing draft index for the user.
        File index=new File(app().getFilesDir(),"book-draft.json");byte[] previous=index.isFile()?read(index):null;
        onMain(()->{CameraCaptureSession s=new CameraCaptureSession(app());s.mode="Book";s.busy=true;first.set(s);});
        CameraCaptureSession original=first.get();File source=page(new File(app().getFilesDir(),"captures"),"book-test-"+UUID.randomUUID()+".jpg");
        try{
            original.worker.execute(()->original.processCaptured(source,"Book",true));awaitIdle(original);
            onMain(()->{assertEquals("Explicit Double must retain two pages without a visible spine",2,original.pages.size());assertFalse("Book must stay in camera",original.returnRequested);assertNotNull(original.thumbnail);original.onCleared();});
            assertEquals(2,store.read().pages.size());
            onMain(()->{CameraCaptureSession s=new CameraCaptureSession(app());s.mode="Book";restored.set(s);s.restoreBookDraft();});CameraCaptureSession next=restored.get();awaitIdle(next);
            onMain(()->{assertEquals(2,next.pages.size());assertTrue(next.bookDoublePage);assertNotNull(next.thumbnail);next.undo();});awaitIdle(next);assertEquals(1,store.read().pages.size());onMain(next::undo);awaitIdle(next);assertEquals(0,store.read().pages.size());
            onMain(next::discardBookDraft);awaitIdle(next);assertTrue(store.read().pages.isEmpty());onMain(()->{assertTrue(next.pages.isEmpty());assertNull(next.thumbnail);next.onCleared();});
        }finally{
            source.delete();for(String path:original.pages)new File(path).delete();CameraCaptureSession knownRestored=restored.get();if(knownRestored!=null)for(String path:knownRestored.pages)new File(path).delete();store.complete();
            if(previous!=null)try(FileOutputStream output=new FileOutputStream(index)){output.write(previous);}
            CameraCaptureSession next=restored.get();if(next!=null&&!next.cleared)onMain(next::onCleared);if(!original.cleared)onMain(original::onCleared);
        }
    }
    @SuppressWarnings("unchecked") @Test public void singlePageSettingDoesNotSplitBook()throws Exception{
        CameraCaptureSession session=new CameraCaptureSession(app());File source=page(new File(app().getFilesDir(),"captures"),"book-single-test-"+UUID.randomUUID()+".jpg");
        Method transform=CameraCaptureSession.class.getDeclaredMethod("transform",File.class,String.class,float.class,boolean.class);transform.setAccessible(true);
        try{ArrayList<String> outputs=(ArrayList<String>)transform.invoke(session,source,"Book",0f,false);assertEquals(1,outputs.size());Bitmap actual=BitmapFactory.decodeFile(outputs.get(0));assertNotNull(actual);assertEquals(240,actual.getWidth());assertEquals(320,actual.getHeight());actual.recycle();}
        finally{source.delete();onMain(session::onCleared);}
    }
    @SuppressWarnings("unchecked") @Test public void consecutiveDoublePageCapturesStillSplitWhenTheSpineIsNotVisible()throws Exception{
        CameraCaptureSession session=new CameraCaptureSession(app());ArrayList<String> saved=new ArrayList<>();
        Method transform=CameraCaptureSession.class.getDeclaredMethod("transform",File.class,String.class,float.class,boolean.class);transform.setAccessible(true);
        try{
            for(int capture=0;capture<2;capture++){
                int width=capture==0?240:320,height=capture==0?320:240;
                File source=session.newFile("book-faint-spine");Bitmap image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);image.eraseColor(Color.WHITE);
                try(FileOutputStream out=new FileOutputStream(source)){assertTrue(image.compress(Bitmap.CompressFormat.JPEG,95,out));}finally{image.recycle();}
                try{
                    ArrayList<String> outputs=(ArrayList<String>)transform.invoke(session,source,"Book",0f,true);saved.addAll(outputs);
                    assertEquals("Double capture "+(capture+1)+" must produce both pages even without a visible spine",2,outputs.size());
                    for(String path:outputs){Bitmap result=BitmapFactory.decodeFile(path);try{assertNotNull(result);assertEquals(width>height?width/2:width,result.getWidth());assertEquals(width>height?height:height/2,result.getHeight());}finally{if(result!=null)result.recycle();}}
                }finally{source.delete();}
            }
            assertEquals(4,new java.util.HashSet<>(saved).size());for(String path:saved)assertTrue(new File(path).isFile());
        }finally{for(String path:saved)new File(path).delete();onMain(session::onCleared);}
    }
    private byte[] read(File file)throws IOException{try(InputStream input=new FileInputStream(file);ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);return output.toByteArray();}}
}

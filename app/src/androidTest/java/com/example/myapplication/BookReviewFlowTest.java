package com.example.myapplication;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BookReviewFlowTest {
    private final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    @Test public void repeatedBookCaptureReviewAndDoneSavesOnePdf()throws Exception{
        Context context=inst.getTargetContext();BookDraftStore store=new BookDraftStore(context);File index=new File(context.getFilesDir(),"book-draft.json");byte[] previous=index.isFile()?read(index):null;store.complete();
        File documents=new File(context.getFilesDir(),"documents");Set<String> before=new HashSet<>();if(documents.list()!=null)Collections.addAll(before,documents.list());
        Activity camera=null,editor=null;ArrayList<String> owned=new ArrayList<>();File saved=null;
        try{
            camera=inst.startActivitySync(new Intent(context,CameraCaptureActivity.class).putExtra("capture_mode","Book").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Field sf=CameraCaptureActivity.class.getDeclaredField("session");sf.setAccessible(true);CameraCaptureSession session=(CameraCaptureSession)sf.get(camera);awaitIdle(session);
            for(int capture=0;capture<2;capture++){
                File source=session.newFile("book-pdf-flow");Bitmap bitmap=Bitmap.createBitmap(480,640,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);try(OutputStream out=new FileOutputStream(source)){bitmap.compress(Bitmap.CompressFormat.JPEG,95,out);}bitmap.recycle();inst.runOnMainSync(()->session.busy=true);session.worker.execute(()->session.processCaptured(source,"Book",false));awaitIdle(session);assertFalse(camera.isFinishing());assertEquals(capture+1,session.pages.size());
            }
            owned.addAll(session.pages);Instrumentation.ActivityMonitor monitor=inst.addMonitor(ScanEditorActivity.class.getName(),null,false);Method finish=CameraCaptureActivity.class.getDeclaredMethod("finishCapture",boolean.class);finish.setAccessible(true);Activity activeCamera=camera;inst.runOnMainSync(()->{try{finish.invoke(activeCamera,false);}catch(Exception error){throw new RuntimeException(error);}});editor=inst.waitForMonitorWithTimeout(monitor,15000);inst.removeMonitor(monitor);assertNotNull(editor);awaitPage(editor);clickText("Next");awaitPage(editor);clickText("Done");
            long end=SystemClock.elapsedRealtime()+30000;while(SystemClock.elapsedRealtime()<end){File[] files=documents.listFiles();if(files!=null)for(File file:files)if(!before.contains(file.getName())&&file.length()>100)saved=file;if(saved!=null&&camera.isFinishing())break;Thread.sleep(100);}
            assertNotNull("Book PDF was not saved",saved);assertTrue("Book camera did not finish after saving",camera.isFinishing());try(com.tom_roush.pdfbox.pdmodel.PDDocument pdf=com.tom_roush.pdfbox.pdmodel.PDDocument.load(saved)){assertEquals(2,pdf.getNumberOfPages());}
            assertFalse("Saved Book draft index retained",index.exists());for(String path:owned)assertFalse("Saved capture not cleaned",new File(path).exists());
        }finally{
            if(editor!=null){Activity known=editor;inst.runOnMainSync(known::finish);}if(camera!=null){Activity known=camera;inst.runOnMainSync(known::finish);}for(String path:owned)new File(path).delete();store.complete();
            if(saved!=null){Thread.sleep(500);try(DocumentStore db=new DocumentStore(context)){db.db().delete("docs","path=?",new String[]{saved.getAbsolutePath()});}saved.delete();}
            if(previous!=null)try(OutputStream out=new FileOutputStream(index)){out.write(previous);}
        }
    }
    private void awaitPage(Activity activity)throws Exception{Field loading=ScanEditorActivity.class.getDeclaredField("loadingPage");loading.setAccessible(true);long end=SystemClock.elapsedRealtime()+15000;while(SystemClock.elapsedRealtime()<end){AtomicBoolean pending=new AtomicBoolean();inst.runOnMainSync(()->{try{pending.set(loading.getBoolean(activity));}catch(Exception error){throw new RuntimeException(error);}});if(!pending.get()&&activity.hasWindowFocus())return;Thread.sleep(50);}fail("Review page did not become ready");}
    @Test public void bookReviewCancelReturnsToCaptureWithoutLosingPages()throws Exception{
        Context context=inst.getTargetContext();File index=new File(context.getFilesDir(),"book-draft.json");byte[] previous=index.isFile()?read(index):null;
        Activity camera=inst.startActivitySync(new Intent(context,CameraCaptureActivity.class).putExtra("capture_mode","Book").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));Field sf=CameraCaptureActivity.class.getDeclaredField("session");sf.setAccessible(true);CameraCaptureSession session=(CameraCaptureSession)sf.get(camera);awaitIdle(session);int originalCount=session.pages.size();ArrayList<String> own=new ArrayList<>();Activity editor=null;
        try{
            File source=session.newFile("book-review-test");Bitmap bitmap=Bitmap.createBitmap(480,320,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);try(FileOutputStream out=new FileOutputStream(source)){bitmap.compress(Bitmap.CompressFormat.JPEG,95,out);}bitmap.recycle();inst.runOnMainSync(()->{session.busy=true;session.notifyChange();});session.worker.execute(()->session.processCaptured(source,"Book",true));awaitIdle(session);
            assertFalse(camera.isFinishing());assertFalse(session.returnRequested);assertEquals(originalCount+1,session.pages.size());own.addAll(session.pages.subList(originalCount,session.pages.size()));for(String path:own)assertTrue(new File(path).isFile());
            Instrumentation.ActivityMonitor monitor=inst.addMonitor(ScanEditorActivity.class.getName(),null,false);Method finish=CameraCaptureActivity.class.getDeclaredMethod("finishCapture",boolean.class);finish.setAccessible(true);inst.runOnMainSync(()->{try{finish.invoke(camera,false);}catch(Exception e){throw new RuntimeException(e);}});editor=inst.waitForMonitorWithTimeout(monitor,20000);inst.removeMonitor(monitor);assertNotNull("Book review did not open",editor);assertFalse(camera.isFinishing());assertTrue(editor.getIntent().getBooleanExtra("book_review",false));
            Activity current=editor;Field loading=ScanEditorActivity.class.getDeclaredField("loadingPage");loading.setAccessible(true);long end=SystemClock.elapsedRealtime()+60000;while(SystemClock.elapsedRealtime()<end){AtomicBoolean pending=new AtomicBoolean();inst.runOnMainSync(()->{try{pending.set(loading.getBoolean(current));}catch(Exception e){throw new RuntimeException(e);}});if(!pending.get())break;Thread.sleep(100);}
            long resumedEnd=SystemClock.elapsedRealtime()+10000;while(!current.hasWindowFocus()&&SystemClock.elapsedRealtime()<resumedEnd)Thread.sleep(100);
            Field stage=ScanEditorActivity.class.getDeclaredField("cropStage");stage.setAccessible(true);inst.runOnMainSync(()->{try{assertTrue("Book review should begin in Crop",stage.getBoolean(current));}catch(Exception e){throw new RuntimeException(e);}current.onBackPressed();});clickText("Continue capture");Thread.sleep(400);assertTrue(current.isFinishing()||current.isDestroyed());assertFalse(camera.isFinishing());assertEquals(originalCount+1,session.pages.size());for(String path:own)assertTrue("Review cancelled an original Book page",new File(path).isFile());
        }finally{if(editor!=null){Activity activity=editor;inst.runOnMainSync(activity::finish);}inst.runOnMainSync(camera::finish);for(String path:own)new File(path).delete();new BookDraftStore(context).complete();if(previous!=null)try(FileOutputStream out=new FileOutputStream(index)){out.write(previous);}}
    }
    private void awaitIdle(CameraCaptureSession session)throws Exception{AtomicBoolean busy=new AtomicBoolean(true);long end=SystemClock.elapsedRealtime()+60000;while(busy.get()&&SystemClock.elapsedRealtime()<end){inst.runOnMainSync(()->busy.set(session.busy));if(busy.get())Thread.sleep(100);}assertFalse("Book session remained busy",busy.get());}
    private byte[] read(File file)throws Exception{try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);return out.toByteArray();}}
    private void clickText(String text)throws Exception{long end=SystemClock.elapsedRealtime()+10000;while(SystemClock.elapsedRealtime()<end){inst.waitForIdleSync();android.view.accessibility.AccessibilityNodeInfo root=inst.getUiAutomation().getRootInActiveWindow();if(root!=null)for(android.view.accessibility.AccessibilityNodeInfo match:root.findAccessibilityNodeInfosByText(text)){if(match.getText()==null||!text.equalsIgnoreCase(match.getText().toString()))continue;for(android.view.accessibility.AccessibilityNodeInfo node=match;node!=null;node=node.getParent())if(node.isClickable()&&node.isEnabled()&&node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)){inst.waitForIdleSync();return;}}Thread.sleep(100);}StringBuilder visible=new StringBuilder();describe(inst.getUiAutomation().getRootInActiveWindow(),visible);fail("Control not found: "+text+"; visible="+visible);}
    private void describe(android.view.accessibility.AccessibilityNodeInfo node,StringBuilder out){if(node==null)return;if(node.getText()!=null)out.append('[').append(node.getText()).append(']');for(int i=0;i<node.getChildCount();i++)describe(node.getChild(i),out);}
}

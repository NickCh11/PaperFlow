package com.example.myapplication;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.SystemClock;
import android.view.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class MigrationUiTest {
    private final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private Object get(Object owner,String field)throws Exception{Field f=owner.getClass().getDeclaredField(field);f.setAccessible(true);return f.get(owner);}
    private void call(Object owner,String method)throws Exception{Method m=owner.getClass().getDeclaredMethod(method);m.setAccessible(true);inst.runOnMainSync(()->{try{m.invoke(owner);}catch(Exception e){throw new RuntimeException(e);}});}
    private interface Condition {boolean ready()throws Exception;}
    private void await(Condition check)throws Exception{long end=SystemClock.elapsedRealtime()+45000;while(SystemClock.elapsedRealtime()<end){AtomicBoolean ready=new AtomicBoolean();inst.runOnMainSync(()->{try{ready.set(check.ready());}catch(Exception e){throw new RuntimeException(e);}});if(ready.get())return;Thread.sleep(50);}fail("UI operation timed out");}
    private void screenshot(String name)throws Exception{Bitmap b=inst.getUiAutomation().takeScreenshot();assertNotNull(b);try(FileOutputStream out=new FileOutputStream(new File(inst.getTargetContext().getFilesDir(),name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
    @SuppressWarnings("unchecked") @Test public void allScanModesUseTwoFiltersAndDetectedCorners()throws Exception{
        Context c=inst.getTargetContext();File[] photos=TestFixtures.directory(c,"user-boundary-test2").listFiles((d,n)->(n.endsWith(".jpg")||n.endsWith(".png")));assertNotNull(photos);Arrays.sort(photos);assertTrue(photos.length>0);
        for(String mode:new String[]{"Single","ID Cards","Slides","Whiteboard","Timestamp","Sign"}){
            ArrayList<String> pages=new ArrayList<>();pages.add(Uri.fromFile(photos[0]).toString());Activity editor=inst.startActivitySync(new Intent(c,ScanEditorActivity.class).putStringArrayListExtra("pages",pages).putExtra("capture_mode",mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try{await(()->!(boolean)get(editor,"loadingPage")&&get(editor,"displayed")!=null);assertArrayEquals(new String[]{"Default","PaperScan Pro"},(String[])get(editor,"filterNames"));assertEquals(Integer.valueOf(1),((ArrayList<Integer>)get(editor,"filters")).get(0));assertTrue(((HashSet<String>)get(editor,"detectedPages")).contains(pages.get(0)+"#0"));
                Field stage=ScanEditorActivity.class.getDeclaredField("cropStage");stage.setAccessible(true);inst.runOnMainSync(()->{try{stage.setBoolean(editor,false);}catch(Exception e){throw new RuntimeException(e);}});call(editor,"render");await(()->!(boolean)get(editor,"loadingPage")&&get(editor,"displayed")!=null);if(mode.equals("Single"))screenshot("migration-editor.png");
            }finally{inst.runOnMainSync(editor::finish);}
        }
    }
    @SuppressWarnings("unchecked") @Test public void rotationPreviewMatchesExportAndKeepsVectorText()throws Exception{
        Context c=inst.getTargetContext();com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(c);File input=new File(c.getCacheDir(),"rotation-fixture.pdf");
        try(PDDocument doc=new PDDocument()){for(int i=0;i<2;i++){PDPage page=new PDPage(new PDRectangle(300,500));doc.addPage(page);try(PDPageContentStream stream=new PDPageContentStream(doc,page)){stream.beginText();stream.setFont(PDType1Font.HELVETICA,24);stream.newLineAtOffset(35,410);stream.showText("Vector page "+i);stream.endText();}}doc.save(input);}
        Activity activity=inst.startActivitySync(new Intent(c,ToolWorkflowActivity.class).putExtra("path",input.getAbsolutePath()).putExtra("name","Rotation preview").putExtra("operation","Rotate PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));File output=null;
        try{await(()->"options".equals(get(activity,"stage"))&&get(activity,"largeRotationPreview")!=null);HashMap<Integer,Integer> rotations=(HashMap<Integer,Integer>)get(activity,"pageRotations");inst.runOnMainSync(()->rotations.put(0,90));call(activity,"refreshRotation");await(()->{Bitmap b=(Bitmap)get(activity,"largeRotationPreview");return b!=null&&b.getWidth()>b.getHeight();});screenshot("migration-rotate.png");call(activity,"process");await(()->"result".equals(get(activity,"stage")));output=(File)get(activity,"resultFile");assertNotNull(output);try(PDDocument doc=PDDocument.load(output)){assertEquals(90,doc.getPage(0).getRotation());assertEquals(0,doc.getPage(1).getRotation());PDFTextStripper sortedStripper=new PDFTextStripper();sortedStripper.setSortByPosition(true);String text=sortedStripper.getText(doc);assertTrue(text,text.contains("Vector page"));assertFalse(doc.getPage(0).getResources().getXObjectNames().iterator().hasNext());}
        }finally{inst.runOnMainSync(activity::finish);input.delete();if(output!=null)output.delete();}
    }
    @Test public void erasePinchZoomDoesNotPaintAndSmallStrokesRestore()throws Exception{
        Context c=inst.getTargetContext();Activity activity=inst.startActivitySync(new Intent(c,EraseMarksActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{EraseMarksActivity.EraseSession session=(EraseMarksActivity.EraseSession)get(activity,"session");inst.runOnMainSync(()->{session.original=Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888);session.original.eraseColor(Color.WHITE);session.rebuildMask();session.publish();});View view=(View)get(activity,"image");assertNotNull(view);await(()->view.getWidth()>0&&view.getHeight()>0);int w=view.getWidth(),h=view.getHeight();assertTrue(w>0&&h>0);long start=SystemClock.uptimeMillis();
            inst.runOnMainSync(()->{MotionEvent down=MotionEvent.obtain(start,start,MotionEvent.ACTION_DOWN,w*.4f,h*.5f,0);view.dispatchTouchEvent(down);down.recycle();});
            for(int step=0;step<8;step++){final int s=step;inst.runOnMainSync(()->{MotionEvent.PointerProperties[] properties={new MotionEvent.PointerProperties(),new MotionEvent.PointerProperties()};properties[0].id=0;properties[1].id=1;properties[0].toolType=properties[1].toolType=MotionEvent.TOOL_TYPE_FINGER;MotionEvent.PointerCoords[] coords={new MotionEvent.PointerCoords(),new MotionEvent.PointerCoords()};coords[0].x=w*(.4f-s*.025f);coords[1].x=w*(.6f+s*.025f);coords[0].y=coords[1].y=h*.5f;coords[0].pressure=coords[1].pressure=1;coords[0].size=coords[1].size=1;int action=s==0?MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT):MotionEvent.ACTION_MOVE;MotionEvent event=MotionEvent.obtain(start,start+30+s*30,action,2,properties,coords,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);view.dispatchTouchEvent(event);event.recycle();});}
            assertTrue("Pinch increased zoom",session.zoom>1);assertTrue("Pinch must cancel provisional paint",session.strokes.isEmpty());screenshot("migration-erase-zoom.png");
            inst.runOnMainSync(()->{session.strokes.add(new EraseMarksActivity.Stroke(2,false));session.strokes.get(0).points.add(10f);session.strokes.get(0).points.add(10f);session.totalPoints=1;});android.os.Bundle state=new android.os.Bundle();inst.runOnMainSync(()->session.save(state));EraseMarksActivity.EraseSession restored=new EraseMarksActivity.EraseSession((Application)c.getApplicationContext());inst.runOnMainSync(()->restored.restore(state));assertEquals(2,restored.strokes.get(0).width,.01);inst.runOnMainSync(restored::onCleared);
        }finally{inst.runOnMainSync(activity::finish);}
    }
}

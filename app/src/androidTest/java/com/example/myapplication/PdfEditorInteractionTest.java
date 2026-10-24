package com.example.myapplication;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class PdfEditorInteractionTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private Object field(Object owner,String name){try{java.lang.reflect.Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(Exception e){throw new AssertionError(e);}}
    private void set(Object owner,String name,Object value){try{java.lang.reflect.Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);f.set(owner,value);}catch(Exception e){throw new AssertionError(e);}}
    private Object invoke(Object owner,String name,Class<?>[] types,Object... values){try{java.lang.reflect.Method m=owner.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(owner,values);}catch(Exception e){throw new AssertionError(e);}}
    @SuppressWarnings("unchecked") private ArrayList<PdfEditActivity.Mark> marks(PdfEditActivity a){return (ArrayList<PdfEditActivity.Mark>)field(a,"marks");}
    private File pdf()throws Exception{PDFBoxResourceLoader.init(context);File file=File.createTempFile("editor-interaction-", ".pdf",context.getCacheDir());try(PDDocument doc=new PDDocument()){doc.addPage(new PDPage(new PDRectangle(120,180)));doc.save(file);}return file;}
    private ActivityScenario<PdfEditActivity> open(File file,AtomicReference<PdfEditActivity> current){ActivityScenario<PdfEditActivity> scenario=ActivityScenario.launch(new Intent(context,PdfEditActivity.class).putExtra("path",file.getPath()));scenario.onActivity(current::set);ready(current);return scenario;}
    private void ready(AtomicReference<PdfEditActivity> current){UiTestWait.until(()->!((Boolean)field(current.get(),"busy"))&&field(current.get(),"bitmap")!=null&&((View)field(current.get(),"editor")).getHeight()>0,"PDF editor did not finish rendering");}
    private void touch(View editor,int action,float x,float y){RectF rect=(RectF)invoke(editor,"rect",new Class<?>[0]);long time=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(time,time,action,rect.left+x*rect.width(),rect.top+y*rect.height(),0);try{assertTrue(editor.dispatchTouchEvent(event));}finally{event.recycle();}}
    private void rectangle(PdfEditActivity a,float left,float top,float right,float bottom){set(a,"tool","Rectangle");View editor=(View)field(a,"editor");touch(editor,MotionEvent.ACTION_DOWN,left,top);touch(editor,MotionEvent.ACTION_MOVE,right,bottom);touch(editor,MotionEvent.ACTION_UP,right,bottom);}

    @Test public void selectingTheTopmostMarkAndDraggingStaysWithinThePageAndSupportsUndo()throws Exception{
        File file=pdf();AtomicReference<PdfEditActivity> current=new AtomicReference<>();
        try(ActivityScenario<PdfEditActivity> scenario=open(file,current)){
            scenario.onActivity(a->{
                View editor=(View)field(a,"editor");int[] clicks={0};editor.setOnClickListener(v->clicks[0]++);
                rectangle(a,.1f,.2f,.4f,.5f);rectangle(a,.2f,.3f,.5f,.6f);assertEquals(2,marks(a).size());assertEquals(2,clicks[0]);
                set(a,"tool","Select");touch(editor,MotionEvent.ACTION_DOWN,.3f,.4f);assertEquals(1,field(a,"selected"));
                touch(editor,MotionEvent.ACTION_MOVE,.9f,.9f);touch(editor,MotionEvent.ACTION_MOVE,1.1f,1.1f);touch(editor,MotionEvent.ACTION_UP,1.1f,1.1f);
                RectF moved=new RectF(marks(a).get(1).bounds);assertEquals(1,moved.right,.0001f);assertEquals(1,moved.bottom,.0001f);assertEquals(.3f,moved.width(),.0001f);
                assertEquals(.1f,marks(a).get(0).bounds.left,.0001f);assertEquals(3,((Deque<?>)field(a,"undo")).size());
                invoke(a,"history",new Class<?>[]{boolean.class},false);assertEquals(.2f,marks(a).get(1).bounds.left,.0001f);
                invoke(a,"history",new Class<?>[]{boolean.class},true);assertEquals(moved,marks(a).get(1).bounds);
                touch(editor,MotionEvent.ACTION_DOWN,.05f,.05f);touch(editor,MotionEvent.ACTION_MOVE,.08f,.08f);touch(editor,MotionEvent.ACTION_UP,.08f,.08f);assertEquals(-1,field(a,"selected"));assertEquals(2,marks(a).size());
            });
        }finally{file.delete();}
    }

    @Test public void twoFingerPanCancelsTheProvisionalMarkAndAllowsTheNextStroke()throws Exception{
        File file=pdf();AtomicReference<PdfEditActivity> current=new AtomicReference<>();
        try(ActivityScenario<PdfEditActivity> scenario=open(file,current)){
            scenario.onActivity(a->{
                set(a,"tool","Pen");View editor=(View)field(a,"editor");touch(editor,MotionEvent.ACTION_DOWN,.3f,.3f);
                long start=SystemClock.uptimeMillis();
                for(int step=0;step<3;step++){
                    MotionEvent.PointerProperties[] properties={new MotionEvent.PointerProperties(),new MotionEvent.PointerProperties()};
                    MotionEvent.PointerCoords[] coordinates={new MotionEvent.PointerCoords(),new MotionEvent.PointerCoords()};
                    for(int p=0;p<2;p++){properties[p].id=p;properties[p].toolType=MotionEvent.TOOL_TYPE_FINGER;coordinates[p].x=editor.getWidth()*(.3f+.3f*p)+step*12;coordinates[p].y=editor.getHeight()*.5f+step*8;coordinates[p].pressure=1;coordinates[p].size=1;}
                    int action=step==0?MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT):MotionEvent.ACTION_MOVE;
                    MotionEvent event=MotionEvent.obtain(start,start+30+step*30,action,2,properties,coordinates,0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);try{editor.dispatchTouchEvent(event);}finally{event.recycle();}
                }
                assertNull(field(editor,"active"));assertTrue(((Number)field(editor,"panX")).floatValue()>0);
                touch(editor,MotionEvent.ACTION_UP,.5f,.5f);assertFalse((Boolean)field(editor,"gesture"));assertTrue(marks(a).isEmpty());
                rectangle(a,.2f,.2f,.5f,.5f);assertEquals(1,marks(a).size());
            });
        }finally{file.delete();}
    }

    @Test public void busyOrMissingPreviewAndCancelledOrTinyGesturesNeverCreateAMark()throws Exception{
        File file=pdf();AtomicReference<PdfEditActivity> current=new AtomicReference<>();
        try(ActivityScenario<PdfEditActivity> scenario=open(file,current)){
            scenario.onActivity(a->{
                View editor=(View)field(a,"editor");set(a,"tool","Rectangle");set(a,"busy",true);
                touch(editor,MotionEvent.ACTION_DOWN,.2f,.2f);touch(editor,MotionEvent.ACTION_UP,.5f,.5f);set(a,"busy",false);
                Bitmap saved=(Bitmap)field(a,"bitmap");set(a,"bitmap",null);try{MotionEvent event=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,1,1,0);try{assertTrue(editor.dispatchTouchEvent(event));}finally{event.recycle();}}finally{set(a,"bitmap",saved);}
                touch(editor,MotionEvent.ACTION_DOWN,.2f,.2f);touch(editor,MotionEvent.ACTION_UP,.2f,.2f);
                touch(editor,MotionEvent.ACTION_DOWN,.2f,.2f);touch(editor,MotionEvent.ACTION_MOVE,.5f,.5f);touch(editor,MotionEvent.ACTION_CANCEL,.5f,.5f);
                assertTrue(marks(a).isEmpty());assertNull(field(editor,"active"));
            });
        }finally{file.delete();}
    }

    @Test public void aLongPenStrokeIsBoundedAndCanStillBeUndone()throws Exception{
        File file=pdf();AtomicReference<PdfEditActivity> current=new AtomicReference<>();
        try(ActivityScenario<PdfEditActivity> scenario=open(file,current)){
            scenario.onActivity(a->{
                set(a,"tool","Pen");View editor=(View)field(a,"editor");touch(editor,MotionEvent.ACTION_DOWN,.2f,.2f);
                for(int i=0;i<5000;i++)touch(editor,MotionEvent.ACTION_MOVE,.2f+(i%100)*.005f,.2f+(i%50)*.005f);
                touch(editor,MotionEvent.ACTION_UP,.7f,.45f);assertEquals(1,marks(a).size());PdfEditActivity.Mark mark=marks(a).get(0);
                assertEquals("Pen",mark.tool);assertTrue(mark.points.size()<=8192);assertTrue(mark.points.size()>1000);assertTrue(mark.bounds.width()>0);
                invoke(a,"history",new Class<?>[]{boolean.class},false);assertTrue(marks(a).isEmpty());
            });
        }finally{file.delete();}
    }

    @Test public void queuedSnapshotOwnsItsNestedGeometryAndRestoresBoundedUndoHistory()throws Exception{
        File file=pdf(),blocker=File.createTempFile("snapshot-barrier-", ".json",context.getCacheDir());AtomicReference<PdfEditActivity> current=new AtomicReference<>();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);AtomicReference<File> saved=new AtomicReference<>();
        try(ActivityScenario<PdfEditActivity> scenario=open(file,current)){
            SessionSnapshots.write(blocker,()->{entered.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new IOException("Snapshot test barrier timed out");return "barrier";});assertTrue(entered.await(5,TimeUnit.SECONDS));
            scenario.onActivity(a->{
                PdfEditActivity.Mark mark=new PdfEditActivity.Mark();mark.tool="Pen";mark.bounds.set(.1f,.2f,.5f,.6f);mark.points.addAll(Arrays.asList(.1f,.2f,.5f,.6f));mark.quads.addAll(Arrays.asList(.1f,.2f,.5f,.2f,.1f,.6f,.5f,.6f));mark.strokes.add(new ArrayList<>(mark.points));marks(a).add(mark);
                Deque<String> undo=(Deque<String>)field(a,"undo"),redo=(Deque<String>)field(a,"redo");for(int i=0;i<25;i++){undo.add("[]");redo.add("[]");}
                Bundle state=new Bundle();invoke(a,"onSaveInstanceState",new Class<?>[]{Bundle.class},state);saved.set(new File(state.getString("annotation_session")));
                mark.points.set(0,.9f);mark.quads.set(0,.9f);mark.strokes.get(0).set(0,.9f);mark.bounds.offset(.2f,.2f);
            });
            release.countDown();JSONObject snapshot=new JSONObject(SessionSnapshots.read(saved.get()));JSONObject mark=new JSONArray(snapshot.getString("marks")).getJSONObject(0);
            assertEquals(.1,mark.getJSONArray("points").getDouble(0),.0001);assertEquals(.1,mark.getJSONArray("quads").getDouble(0),.0001);assertEquals(.1,mark.getJSONArray("strokes").getJSONArray(0).getDouble(0),.0001);assertEquals(.1,mark.getJSONArray("rect").getDouble(0),.0001);
            scenario.onActivity(a->{((Deque<?>)field(a,"undo")).clear();((Deque<?>)field(a,"redo")).clear();invoke(a,"restoreSession",new Class<?>[0]);});ready(current);
            scenario.onActivity(a->{assertEquals(1,marks(a).size());assertEquals(.1f,marks(a).get(0).points.get(0),.0001f);assertEquals(20,((Deque<?>)field(a,"undo")).size());assertEquals(20,((Deque<?>)field(a,"redo")).size());assertTrue((Boolean)field(a,"dirty"));});
        }finally{release.countDown();SessionSnapshots.delete(blocker);file.delete();}
    }

    @Test public void corruptSnapshotFallsBackToTheOriginalPdf()throws Exception{
        File file=pdf(),bad=File.createTempFile("editor-corrupt-", ".json",context.getCacheDir());byte[] original=Files.readAllBytes(file.toPath());Files.write(bad.toPath(),"{".getBytes(StandardCharsets.UTF_8));AtomicReference<PdfEditActivity> current=new AtomicReference<>();
        try(ActivityScenario<PdfEditActivity> scenario=open(file,current)){
            scenario.onActivity(a->{set(a,"sessionFile",bad);invoke(a,"restoreSession",new Class<?>[0]);});ready(current);
            scenario.onActivity(a->{assertTrue(marks(a).isEmpty());assertFalse((Boolean)field(a,"dirty"));});assertArrayEquals(original,Files.readAllBytes(file.toPath()));
        }finally{file.delete();bad.delete();}
    }
}

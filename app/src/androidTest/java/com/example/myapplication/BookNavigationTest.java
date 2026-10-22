package com.example.myapplication;

import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BookNavigationTest {
    @Test public void cropAndPreviewNavigateEveryPageAndRetainIndependentCrops()throws Exception {
        Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();ArrayList<String> pages=new ArrayList<>();
        for(int i=0;i<3;i++){
            File f=File.createTempFile("ci-book-",".png",c.getCacheDir());Bitmap b=Bitmap.createBitmap(480,640,Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE);
            try(OutputStream out=new FileOutputStream(f)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}finally{b.recycle();}pages.add(android.net.Uri.fromFile(f).toString());
        }
        Intent intent=new Intent(c,ScanEditorActivity.class).putStringArrayListExtra("pages",pages).putExtra("capture_mode","Book").putExtra("start_crop",true).putExtra("auto_detect",false).putExtra("initial_filter",0).putExtra("recipe_dewarp",0);
        try(ActivityScenario<ScanEditorActivity> scenario=ActivityScenario.launch(intent)){
            ready(scenario);scenario.onActivity(a->{assertEquals(0,(int)(Integer)value(a,"index"));assertFalse(((View)value(a,"previousPage")).isEnabled());
                View overlay=value(a,"overlay");RectF r=invoke(overlay,"rect");gesture(overlay,r.left,r.top,r.left+r.width()*.12f,r.top+r.height()*.1f);
                assertEquals(0,(int)(Integer)value(a,"index"));assertTrue(((ArrayList<float[]>)value(a,"crops")).get(0)[0]>.05f);
            });
            swipe(scenario,true);ready(scenario);scenario.onActivity(a->{assertEquals(1,(int)(Integer)value(a,"index"));assertArrayEquals(new float[]{0,0,1,0,1,1,0,1},((ArrayList<float[]>)value(a,"crops")).get(1),.0001f);});
            swipe(scenario,true);ready(scenario);scenario.onActivity(a->{assertEquals(2,(int)(Integer)value(a,"index"));assertFalse(((View)value(a,"nextPage")).isEnabled());});
            swipe(scenario,true);ready(scenario);scenario.onActivity(a->assertEquals(2,(int)(Integer)value(a,"index")));
            scenario.recreate();ready(scenario);scenario.onActivity(a->{assertEquals(2,(int)(Integer)value(a,"index"));assertTrue(value(a,"cropStage"));});
            swipe(scenario,false);ready(scenario);swipe(scenario,false);ready(scenario);
            scenario.onActivity(a->{assertEquals(0,(int)(Integer)value(a,"index"));assertTrue(((ArrayList<float[]>)value(a,"crops")).get(0)[0]>.05f);set(a,"cropStage",false);invoke(a,"render");});
            ready(scenario);swipe(scenario,true);ready(scenario);scenario.onActivity(a->{assertEquals(1,(int)(Integer)value(a,"index"));assertFalse(value(a,"cropStage"));
                set(a,"busy",true);a.getOnBackPressedDispatcher().onBackPressed();assertFalse(a.isFinishing());set(a,"busy",false);
            });
        }finally{for(String path:pages)new File(android.net.Uri.parse(path).getPath()).delete();}
    }
    private void swipe(ActivityScenario<ScanEditorActivity> s,boolean left){s.onActivity(a->{View surface=value(a,"surface");float x=surface.getWidth()*.72f,y=surface.getHeight()*.53f;gesture(surface,left?x:surface.getWidth()*.28f,y,left?surface.getWidth()*.28f:x,y);});}
    private static void gesture(View view,float x,float y,float endX,float endY){long now=SystemClock.uptimeMillis();int[] actions={MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP};for(int i=0;i<actions.length;i++){MotionEvent e=MotionEvent.obtain(now,now+i*100,actions[i],i==0?x:endX,i==0?y:endY,0);view.dispatchTouchEvent(e);e.recycle();}}
    private void ready(ActivityScenario<ScanEditorActivity> s)throws Exception{long end=SystemClock.elapsedRealtime()+30000;while(SystemClock.elapsedRealtime()<end){AtomicBoolean ready=new AtomicBoolean();s.onActivity(a->ready.set(!(Boolean)value(a,"loadingPage")&&((View)value(a,"surface")).getWidth()>0));if(ready.get())return;Thread.sleep(50);}fail("Page remained loading");}
    @SuppressWarnings("unchecked") private static <T>T value(Object o,String name){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return (T)f.get(o);}catch(Exception e){throw new AssertionError(e);}}
    private static void set(Object o,String name,Object value){try{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}catch(Exception e){throw new AssertionError(e);}}
    @SuppressWarnings("unchecked") private static <T>T invoke(Object o,String name){try{Method m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);return (T)m.invoke(o);}catch(Exception e){throw new AssertionError(e);}}
}

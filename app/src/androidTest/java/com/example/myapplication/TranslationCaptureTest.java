package com.example.myapplication;

import android.Manifest;
import android.content.Intent;
import android.view.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TranslationCaptureTest {
    @Test public void translationCameraUsesOrdinaryPreviewWithoutStartingLiveTranslation()throws Exception {
        android.app.Instrumentation inst=InstrumentationRegistry.getInstrumentation();
        inst.getUiAutomation().grantRuntimePermission(inst.getTargetContext().getPackageName(),Manifest.permission.CAMERA);
        java.util.concurrent.atomic.AtomicReference<CameraCaptureActivity> current=new java.util.concurrent.atomic.AtomicReference<>();
        try(ActivityScenario<CameraCaptureActivity> scenario=ActivityScenario.launch(new Intent(inst.getTargetContext(),CameraCaptureActivity.class).putExtra("translation_capture",true).putExtra("capture_mode","Photo").putExtra("translation_source","auto").putExtra("translation_target","el"))){
            scenario.onActivity(activity->{try{
                current.set(activity);
                java.lang.reflect.Field field=CameraCaptureActivity.class.getDeclaredField("preview");field.setAccessible(true);View preview=(View)field.get(activity);assertTrue(preview instanceof androidx.camera.view.PreviewView);assertEquals(View.VISIBLE,preview.getVisibility());
                assertFalse(hasLiveRenderer(activity.findViewById(android.R.id.content)));
                for(String name:new String[]{"sourceChoice","targetChoice"}){field=CameraCaptureActivity.class.getDeclaredField(name);field.setAccessible(true);assertNotNull(field.get(activity));}
            }catch(ReflectiveOperationException error){throw new AssertionError(error);}});
            UiTestWait.until(()->get(current.get(),"camera")!=null&&((View)get(current.get(),"guide")).getWidth()>0,"Emulated camera did not bind");
            scenario.onActivity(activity->{
                android.widget.LinearLayout modes=(android.widget.LinearLayout)get(activity,"modeRow");
                for(String mode:new String[]{"Single","Photo","Photo"})for(int i=0;i<modes.getChildCount();i++)if(mode.equals(modes.getChildAt(i).getTag()))assertTrue(modes.getChildAt(i).performClick());
                View guide=(View)get(activity,"guide");int[] clicks={0};guide.setOnClickListener(v->clicks[0]++);long now=android.os.SystemClock.uptimeMillis();
                for(int i=0;i<2;i++){MotionEvent event=MotionEvent.obtain(now,now+i*32,i==0?MotionEvent.ACTION_DOWN:MotionEvent.ACTION_UP,guide.getWidth()*.25f,guide.getHeight()*.4f,0);try{assertTrue(guide.dispatchTouchEvent(event));}finally{event.recycle();}}
                assertEquals(1,clicks[0]);assertEquals(guide.getWidth()*.25f,(Float)get(guide,"focusX"),.01f);
                assertTrue(guide.performClick());assertEquals(2,clicks[0]);assertEquals(guide.getWidth()/2f,(Float)get(guide,"focusX"),.01f);
                MotionEvent cancel=MotionEvent.obtain(now,now+96,MotionEvent.ACTION_CANCEL,0,0,0);try{assertTrue(guide.onTouchEvent(cancel));}finally{cancel.recycle();}assertEquals(2,clicks[0]);
            });
        }
    }
    boolean hasLiveRenderer(View view){if(view.getClass().getName().contains("LiveGlSurfaceView"))return true;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(hasLiveRenderer(group.getChildAt(i)))return true;}return false;}

    private void set(Object target,String name,Object value){try{java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}catch(Exception error){throw new AssertionError(error);}}
    private Object get(Object target,String name){try{java.lang.reflect.Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}catch(Exception error){throw new AssertionError(error);}}
    private android.graphics.Bitmap frame(View view){android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(view.getWidth(),view.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);view.draw(new android.graphics.Canvas(bitmap));return bitmap;}
    @Test public void cameraGuidesDrawBookPagesAndClearPreviousOutlinesAndExpiredFocus()throws Exception{
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            java.util.ArrayList<android.graphics.Bitmap> frames=new java.util.ArrayList<>();
            try{
                Class<?> type=Class.forName(CameraCaptureActivity.class.getName()+"$GuideView");java.lang.reflect.Constructor<?> constructor=type.getDeclaredConstructor(android.content.Context.class);constructor.setAccessible(true);
                View guide=(View)constructor.newInstance(InstrumentationRegistry.getInstrumentation().getTargetContext());guide.layout(0,0,640,960);
                set(guide,"mode","Book");set(guide,"grid",true);set(guide,"bookDouble",true);android.graphics.Bitmap book=frame(guide);frames.add(book);
                set(guide,"bookDouble",false);android.graphics.Bitmap single=frame(guide);frames.add(single);assertFalse("Single and double book guides must differ",book.sameAs(single));
                set(guide,"mode","Single");set(guide,"grid",false);set(guide,"imageRatio",2f);set(guide,"documentQuad",new float[]{.1f,.1f,.9f,.1f,.9f,.9f,.1f,.9f});android.graphics.Bitmap wide=frame(guide);frames.add(wide);assertTrue(android.graphics.Color.alpha(wide.getPixel(320,96))>0);
                set(guide,"documentQuad",new float[]{.2f,.2f,.8f,.2f,.8f,.8f,.2f,.8f});android.graphics.Bitmap inset=frame(guide);frames.add(inset);assertEquals("Old contours must not accumulate in the reused path",0,android.graphics.Color.alpha(inset.getPixel(320,96)));
                set(guide,"imageRatio",.25f);android.graphics.Bitmap tall=frame(guide);frames.add(tall);assertFalse(inset.sameAs(tall));
                set(guide,"focusX",320f);set(guide,"focusY",480f);set(guide,"focusUntil",android.os.SystemClock.elapsedRealtime()+60000);android.graphics.Bitmap focused=frame(guide);frames.add(focused);assertFalse(tall.sameAs(focused));
                set(guide,"focusUntil",0L);android.graphics.Bitmap expired=frame(guide);frames.add(expired);assertTrue(tall.sameAs(expired));
                int[] clicks={0};set(guide,"accessibleFocus",(Runnable)()->clicks[0]++);assertTrue(guide.performClick());assertEquals(1,clicks[0]);
            }catch(Exception error){throw new AssertionError(error);}finally{for(android.graphics.Bitmap image:frames)image.recycle();}
        });
    }
}

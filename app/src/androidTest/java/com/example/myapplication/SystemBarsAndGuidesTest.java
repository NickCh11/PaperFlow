package com.example.myapplication;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.View;
import android.widget.LinearLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SystemBarsAndGuidesTest {
    @Test public void insetsPreservePaddingAcrossKeyboardRotationAndRebinding(){
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            LinearLayout root=new LinearLayout(context);root.setPadding(7,11,13,17);UiInsets.applyKeyboardAware(root);
            // Before API 30, platform dispatch cannot carry separate inset type
            // overrides: provide legacy stable/combined insets and a real cutout too.
            WindowInsetsCompat buttons=new WindowInsetsCompat.Builder().setStableInsets(Insets.of(0,24,0,48)).setInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars(),Insets.of(0,24,0,48)).setInsets(WindowInsetsCompat.Type.systemBars(),Insets.of(0,24,0,48)).build();
            ViewCompat.dispatchApplyWindowInsets(root,buttons);assertEquals(7,root.getPaddingLeft());assertEquals(35,root.getPaddingTop());assertEquals(65,root.getPaddingBottom());
            UiInsets.applyKeyboardAware(root);ViewCompat.dispatchApplyWindowInsets(root,buttons);assertEquals(65,root.getPaddingBottom());
            WindowInsetsCompat keyboard=new WindowInsetsCompat.Builder(buttons).setInsets(WindowInsetsCompat.Type.systemBars(),Insets.of(0,24,0,320)).setInsets(WindowInsetsCompat.Type.ime(),Insets.of(0,0,0,320)).build();ViewCompat.dispatchApplyWindowInsets(root,keyboard);assertEquals(337,root.getPaddingBottom());ViewCompat.dispatchApplyWindowInsets(root,buttons);assertEquals(65,root.getPaddingBottom());
            WindowInsetsCompat landscape=new WindowInsetsCompat.Builder().setStableInsets(Insets.of(0,24,64,0)).setDisplayCutout(new androidx.core.view.DisplayCutoutCompat(new android.graphics.Rect(30,0,0,0),java.util.Collections.singletonList(new android.graphics.Rect(0,0,30,60)))).setInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars(),Insets.of(0,24,64,0)).setInsets(WindowInsetsCompat.Type.systemBars(),Insets.of(0,24,64,0)).setInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout(),Insets.of(30,0,0,0)).setInsets(WindowInsetsCompat.Type.displayCutout(),Insets.of(30,0,0,0)).build();ViewCompat.dispatchApplyWindowInsets(root,landscape);assertEquals(37,root.getPaddingLeft());assertEquals(77,root.getPaddingRight());assertEquals(17,root.getPaddingBottom());
        });
    }
    @Test public void specialModesDrawOnlyTheDetectedOutline()throws Exception {
        android.app.Instrumentation inst=InstrumentationRegistry.getInstrumentation();Context context=inst.getTargetContext();
        for(String mode:new String[]{"ID Cards","Whiteboard","Slides"}){
            Activity activity=inst.startActivitySync(new Intent(context,CameraCaptureActivity.class).putExtra("capture_mode",mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            try{
                Field field=CameraCaptureActivity.class.getDeclaredField("guide");field.setAccessible(true);View guide=(View)field.get(activity);long end=SystemClock.elapsedRealtime()+15000;AtomicBoolean laidOut=new AtomicBoolean();
                do{inst.runOnMainSync(()->laidOut.set(guide.getWidth()>0&&guide.getHeight()>0));if(!laidOut.get())Thread.sleep(30);}while(!laidOut.get()&&SystemClock.elapsedRealtime()<end);assertTrue(laidOut.get());
                Field quad=guide.getClass().getDeclaredField("documentQuad");quad.setAccessible(true);Field grid=guide.getClass().getDeclaredField("grid");grid.setAccessible(true);
                inst.runOnMainSync(()->{try{
                    quad.set(guide,null);grid.setBoolean(guide,false);Bitmap image=Bitmap.createBitmap(guide.getWidth(),guide.getHeight(),Bitmap.Config.ARGB_8888);guide.draw(new Canvas(image));int[] pixels=new int[image.getWidth()*image.getHeight()];image.getPixels(pixels,0,image.getWidth(),0,0,image.getWidth(),image.getHeight());int content=0;for(int pixel:pixels)content|=pixel;assertEquals("Old fixed frame remains in "+mode,Color.TRANSPARENT,content);
                    quad.set(guide,new float[]{.15f,.15f,.85f,.15f,.85f,.85f,.15f,.85f});guide.draw(new Canvas(image));image.getPixels(pixels,0,image.getWidth(),0,0,image.getWidth(),image.getHeight());boolean outline=false;for(int pixel:pixels)if(Color.alpha(pixel)>0){outline=true;break;}assertTrue("New live outline missing",outline);image.recycle();
                }catch(ReflectiveOperationException e){throw new RuntimeException(e);}});
                inst.runOnMainSync(()->{View root=((android.view.ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);WindowInsetsCompat insets=ViewCompat.getRootWindowInsets(root);assertNotNull(insets);assertTrue(root.getPaddingBottom()>=insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars()).bottom);});
            }finally{inst.runOnMainSync(activity::finish);}
        }
    }
}

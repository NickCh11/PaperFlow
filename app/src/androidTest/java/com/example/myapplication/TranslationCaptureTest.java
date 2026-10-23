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
        try(ActivityScenario<CameraCaptureActivity> scenario=ActivityScenario.launch(new Intent(inst.getTargetContext(),CameraCaptureActivity.class).putExtra("translation_capture",true).putExtra("capture_mode","Photo").putExtra("translation_source","auto").putExtra("translation_target","el"))){
            scenario.onActivity(activity->{try{
                java.lang.reflect.Field field=CameraCaptureActivity.class.getDeclaredField("preview");field.setAccessible(true);View preview=(View)field.get(activity);assertTrue(preview instanceof androidx.camera.view.PreviewView);assertEquals(View.VISIBLE,preview.getVisibility());
                assertFalse(hasLiveRenderer(activity.findViewById(android.R.id.content)));
                for(String name:new String[]{"sourceChoice","targetChoice"}){field=CameraCaptureActivity.class.getDeclaredField(name);field.setAccessible(true);assertNotNull(field.get(activity));}
            }catch(ReflectiveOperationException error){throw new AssertionError(error);}});
        }
    }
    boolean hasLiveRenderer(View view){if(view.getClass().getName().contains("LiveGlSurfaceView"))return true;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(hasLiveRenderer(group.getChildAt(i)))return true;}return false;}
}

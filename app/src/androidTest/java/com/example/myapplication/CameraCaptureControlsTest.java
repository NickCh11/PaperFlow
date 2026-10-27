package com.example.myapplication;

import android.Manifest;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Real capture controls: language choices, persisted settings, and safe countdown cancellation. */
public class CameraCaptureControlsTest {
    final android.app.Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    final Context context=inst.getTargetContext();
    ActivityScenario<CameraCaptureActivity> scenario;CameraCaptureActivity activity;CameraCaptureSession session;
    SharedPreferences preferences;boolean hadAuto,oldAuto;
    Object field(Object owner,String name){try{Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(Exception e){throw new AssertionError(e);}}
    @Before public void open(){
        inst.getUiAutomation().grantRuntimePermission(context.getPackageName(),Manifest.permission.CAMERA);
        scenario=ActivityScenario.launch(new Intent(context,CameraCaptureActivity.class).putExtra("translation_capture",true).putExtra("capture_mode","Photo"));
        scenario.onActivity(a->{activity=a;session=(CameraCaptureSession)field(a,"session");preferences=a.getPreferences(0);hadAuto=preferences.contains("auto_capture");oldAuto=preferences.getBoolean("auto_capture",false);});
    }
    @After public void close()throws Exception{scenario.close();assertTrue(session.worker.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS));SharedPreferences.Editor editor=preferences.edit();if(hadAuto)editor.putBoolean("auto_capture",oldAuto);else editor.remove("auto_capture");editor.commit();}
    <T extends View>T find(View root,Class<T> type){if(type.isInstance(root)&&root.isShown())return type.cast(root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){T match=find(((ViewGroup)root).getChildAt(i),type);if(match!=null)return match;}return null;}
    ListView choices(){for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews())if(root.isShown()&&root.hasWindowFocus()){ListView list=find(root,ListView.class);if(list!=null)return list;}return null;}
    void choose(int position){UiTestWait.until(()->choices()!=null,"Capture choices did not open");scenario.onActivity(a->{ListView list=choices();assertTrue(position<list.getCount());assertTrue(list.performItemClick(null,position,list.getAdapter().getItemId(position)));});inst.waitForIdleSync();}
    void settings(int position){scenario.onActivity(a->assertTrue(((View)field(a,"more")).performClick()));choose(position);}
    @Test public void languageChoicesUpdateLabelsAndSurviveRecreation(){
        scenario.onActivity(a->assertTrue(((View)field(a,"sourceChoice")).performClick()));choose(0);assertEquals("auto",field(activity,"translationSource"));
        scenario.onActivity(a->assertTrue(((View)field(a,"sourceChoice")).performClick()));choose(1);String source=(String)field(activity,"translationSource");assertFalse(source.equals("auto"));
        scenario.onActivity(a->assertTrue(((View)field(a,"targetChoice")).performClick()));choose(1);String target=(String)field(activity,"translationTarget");
        scenario.recreate();scenario.onActivity(a->{activity=a;assertEquals(source,field(a,"translationSource"));assertEquals(target,field(a,"translationTarget"));assertFalse(((TextView)field(a,"sourceChoice")).getText().toString().isEmpty());assertFalse(((TextView)field(a,"targetChoice")).getText().toString().isEmpty());});
    }
    @Test public void settingsPersistAutoCaptureAndExposeEveryCountdownOption(){
        boolean grid=session.grid;settings(0);assertEquals(!grid,session.grid);settings(0);assertEquals(grid,session.grid);
        settings(2);assertEquals(3,session.timer);settings(3);assertEquals(5,session.timer);settings(1);assertEquals(0,session.timer);
        boolean auto=(Boolean)field(activity,"autoCapture");settings(4);assertEquals(!auto,field(activity,"autoCapture"));assertEquals(!auto,preferences.getBoolean("auto_capture",auto));
        scenario.recreate();scenario.onActivity(a->{activity=a;assertEquals(!auto,field(a,"autoCapture"));});
    }
    @Test public void leavingCameraDuringCountdownCancelsThePendingShot()throws Exception{
        UiTestWait.until(()->(Boolean)field(activity,"ready")&&((androidx.camera.view.PreviewView)field(activity,"preview")).getPreviewStreamState().getValue()==androidx.camera.view.PreviewView.StreamState.STREAMING,"Capture preview did not start");
        settings(2);scenario.onActivity(a->{assertTrue(((View)field(a,"shutter")).performClick());assertTrue(session.busy);assertEquals(true,field(a,"timerPending"));});
        scenario.moveToState(Lifecycle.State.CREATED);assertFalse(session.busy);assertEquals(false,field(activity,"timerPending"));
        Thread.sleep(3300);assertTrue("Stopping must not take a delayed photo",session.pages.isEmpty());
        scenario.moveToState(Lifecycle.State.RESUMED);scenario.onActivity(a->{assertFalse(session.busy);assertEquals(0,field(a,"countdown"));assertTrue(((View)field(a,"shutter")).isEnabled());});
    }
}

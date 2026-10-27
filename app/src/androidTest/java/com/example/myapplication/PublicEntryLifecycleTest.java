package com.example.myapplication;

import android.app.*;
import android.content.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class PublicEntryLifecycleTest {
    @Test public void launcherEntryForwardsOnlySanitizedCommandsAndFinishes(){
        Instrumentation inst=InstrumentationRegistry.getInstrumentation();Context context=inst.getTargetContext();AtomicReference<Intent> forwarded=new AtomicReference<>();
        Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){
            if(new ComponentName(context,MainActivity.class).equals(intent.getComponent())){forwarded.set(intent);return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);}return null;
        }};inst.addMonitor(monitor);
        try(ActivityScenario<DocumentEntryActivity> scenario=ActivityScenario.launch(new Intent(context,DocumentEntryActivity.class).setAction(Intent.ACTION_MAIN).putExtra("document_action","Delete").putExtra("path","/private/document.pdf"))){
            UiTestWait.until(()->forwarded.get()!=null,"Public entry did not dispatch to the app");Intent target=forwarded.get();assertEquals(Intent.ACTION_MAIN,target.getAction());assertFalse(target.hasExtra("document_action"));assertFalse(target.hasExtra("path"));
            assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED,scenario.getState());
        }finally{inst.removeMonitor(monitor);}
    }

    @Test public void failedForwardingDoesNotCrashOrKeepTheExportedEntryAlive(){
        Instrumentation inst=InstrumentationRegistry.getInstrumentation();Context context=inst.getTargetContext();boolean[] attempted={false};
        Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){
            if(new ComponentName(context,MainActivity.class).equals(intent.getComponent())){attempted[0]=true;throw new SecurityException("Simulated invalid provider grant");}return null;
        }};inst.addMonitor(monitor);
        try(ActivityScenario<DocumentEntryActivity> scenario=ActivityScenario.launch(new Intent(context,DocumentEntryActivity.class).setAction(Intent.ACTION_VIEW))){
            assertTrue(attempted[0]);assertEquals(androidx.lifecycle.Lifecycle.State.DESTROYED,scenario.getState());
        }finally{inst.removeMonitor(monitor);}
    }
}

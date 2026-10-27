package com.example.myapplication;

import android.Manifest;
import android.content.Context;
import android.view.Surface;
import android.widget.FrameLayout;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.davidv.translator.ui.components.LiveGlSurfaceView;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Real CameraX surface requests across delayed GL creation, cancellation and reattachment. */
public class LiveCameraSurfaceLifecycleTest {
    final android.app.Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    final Context context=inst.getTargetContext();
    ProcessCameraProvider provider;
    Object field(Object owner,String name){try{java.lang.reflect.Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(Exception e){throw new AssertionError(e);}}
    @Before public void camera()throws Exception{
        inst.getUiAutomation().grantRuntimePermission(context.getPackageName(),Manifest.permission.CAMERA);
        provider=ProcessCameraProvider.getInstance(context).get(15,TimeUnit.SECONDS);
    }
    @After public void unbind(){inst.runOnMainSync(provider::unbindAll);}
    void bind(MainActivity activity,LiveGlSurfaceView surface){
        Preview preview=new Preview.Builder().build();preview.setSurfaceProvider(ContextCompat.getMainExecutor(activity),request->surface.provideSurfaceRequest(request,ContextCompat.getMainExecutor(activity)));
        provider.bindToLifecycle(activity,CameraSelector.DEFAULT_BACK_CAMERA,preview);
    }
    void hasSurface(LiveGlSurfaceView view){UiTestWait.until(()->{synchronized(field(view,"surfaceLock")){return field(view,"cameraSurface")!=null;}},"Live camera surface did not initialize");}
    @Test public void pendingCameraRequestIsFulfilledAndReattachmentPreservesItsBufferSize(){
        AtomicReference<LiveGlSurfaceView> view=new AtomicReference<>();AtomicReference<FrameLayout> parent=new AtomicReference<>();AtomicReference<Thread> thread=new AtomicReference<>();AtomicReference<Surface> oldSurface=new AtomicReference<>();AtomicInteger resized=new AtomicInteger();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{FrameLayout root=new FrameLayout(a);a.setContentView(root);parent.set(root);LiveGlSurfaceView surface=new LiveGlSurfaceView(a);view.set(surface);surface.setOnSizeChanged((w,h)->{assertTrue(w>0&&h>0);resized.incrementAndGet();return kotlin.Unit.INSTANCE;});bind(a,surface);});
            UiTestWait.until(()->field(view.get(),"pendingRequest")!=null,"Camera did not request a surface before attachment");
            scenario.onActivity(a->{assertTrue((Integer)field(view.get(),"lastCamBufW")>0);assertTrue((Integer)field(view.get(),"lastCamBufH")>0);parent.get().addView(view.get(),new FrameLayout.LayoutParams(256,256));});
            hasSurface(view.get());UiTestWait.until(()->field(view.get(),"providedRequest")!=null,"Pending request was not fulfilled");
            scenario.onActivity(a->{assertNull(field(view.get(),"pendingRequest"));assertTrue(resized.get()>0);thread.set((Thread)field(view.get(),"glThread"));oldSurface.set((Surface)field(view.get(),"cameraSurface"));parent.get().removeView(view.get());});
            UiTestWait.until(()->field(view.get(),"glThread")==null,"Camera GL thread survived detach");assertFalse(thread.get().isAlive());assertFalse(oldSurface.get().isValid());
            scenario.onActivity(a->parent.get().addView(view.get(),new FrameLayout.LayoutParams(256,256)));hasSurface(view.get());
            UiTestWait.until(()->field(view.get(),"providedRequest")!=null,"Camera did not reconnect to the replacement surface");
            scenario.onActivity(a->{assertNotSame(thread.get(),field(view.get(),"glThread"));assertTrue((Integer)field(view.get(),"lastCamBufW")>0);provider.unbindAll();parent.get().removeView(view.get());view.get().releasePipeline();});
        }
    }
    @Test public void existingSurfaceAcceptsCameraRequestsAndNormalizesSensorRotation(){
        AtomicReference<LiveGlSurfaceView> view=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{FrameLayout root=new FrameLayout(a);a.setContentView(root);LiveGlSurfaceView surface=new LiveGlSurfaceView(a);view.set(surface);root.addView(surface,new FrameLayout.LayoutParams(256,256));});hasSurface(view.get());
            scenario.onActivity(a->{for(int degrees:new int[]{-90,0,90,180,270,450}){view.get().setCameraOrientationDegrees(degrees);assertEquals(((degrees%360+360)%360)/90,view.get().getRotQuadrant());}bind(a,view.get());});
            UiTestWait.until(()->field(view.get(),"providedRequest")!=null,"Existing surface was not handed to CameraX");
            scenario.onActivity(a->{assertNull(field(view.get(),"pendingRequest"));assertTrue(((Surface)field(view.get(),"cameraSurface")).isValid());provider.unbindAll();((FrameLayout)view.get().getParent()).removeView(view.get());view.get().releasePipeline();assertEquals(0,view.get().getPipelinePtr());});
        }
    }
    @Test public void cancelledRequestCannotBeHandedToALaterSurface(){
        AtomicReference<LiveGlSurfaceView> view=new AtomicReference<>();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{a.setContentView(new FrameLayout(a));LiveGlSurfaceView surface=new LiveGlSurfaceView(a);view.set(surface);bind(a,surface);});
            UiTestWait.until(()->field(view.get(),"pendingRequest")!=null,"No pending camera request");scenario.onActivity(a->provider.unbindAll());
            UiTestWait.until(()->field(view.get(),"pendingRequest")==null,"Cancelled camera request was retained");
            scenario.onActivity(a->{FrameLayout root=(FrameLayout)((android.view.ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);root.addView(view.get(),new FrameLayout.LayoutParams(256,256));});hasSurface(view.get());
            scenario.onActivity(a->{assertNull(field(view.get(),"providedRequest"));view.get().releasePipeline();((FrameLayout)view.get().getParent()).removeView(view.get());});
        }
    }
}

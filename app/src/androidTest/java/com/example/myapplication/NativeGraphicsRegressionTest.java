package com.example.myapplication;

import android.opengl.EGL14;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import dev.davidv.translator.GlEgl;
import dev.davidv.translator.LivePipelineJni;
import java.nio.ByteBuffer;
import org.junit.Test;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import dev.davidv.translator.ui.components.LiveGlSurfaceView;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

/** Real EGL/GLES/JNI contracts on the emulator, with no live camera or network. */
public class NativeGraphicsRegressionTest {
    private Object field(Object owner,String name) {
        try { java.lang.reflect.Field field=owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch(Exception error) { throw new AssertionError(error); }
    }
    @Test public void glesContextAndNativeRendererCanBeCreatedAndReleasedOnTheirOwningThread() {
        GlEgl.EglCore core = GlEgl.INSTANCE.initContext(new int[]{EGL14.EGL_RENDERABLE_TYPE, GlEgl.EGL_OPENGL_ES3_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_NONE});
        assertNotNull("The CI software GPU must support the app's GLES3 renderer", core);
        EGLSurface surface = EGL14.EGL_NO_SURFACE; int texture = 0; long renderer = 0;
        try {
            surface = EGL14.eglCreatePbufferSurface(core.getDisplay(), core.getConfig(), new int[]{EGL14.EGL_WIDTH, 32, EGL14.EGL_HEIGHT, 32, EGL14.EGL_NONE}, 0);
            assertNotEquals(EGL14.EGL_NO_SURFACE, surface);
            assertTrue(EGL14.eglMakeCurrent(core.getDisplay(), surface, surface, core.getContext()));
            GLES20.glViewport(0, 0, 32, 32); GLES20.glClearColor(1, 0, 0, 1); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            ByteBuffer pixel = ByteBuffer.allocateDirect(4); GLES20.glReadPixels(16, 16, 1, 1, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixel);
            assertEquals(255, pixel.get(0) & 255); assertEquals(0, pixel.get(1) & 255); assertEquals(255, pixel.get(3) & 255);
            texture = GlEgl.INSTANCE.createExternalOesTexture(); assertTrue(texture != 0);
            GLES20.glBindTexture(GlEgl.GL_TEXTURE_EXTERNAL_OES, texture); int[] parameter = new int[1];
            GLES20.glGetTexParameteriv(GlEgl.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, parameter, 0); assertEquals(GLES20.GL_LINEAR, parameter[0]);
            GLES20.glGetTexParameteriv(GlEgl.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, parameter, 0); assertEquals(GLES20.GL_CLAMP_TO_EDGE, parameter[0]);
            GLES20.glBindTexture(GlEgl.GL_TEXTURE_EXTERNAL_OES, 0); assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
            renderer = LivePipelineJni.createGlRenderer(); assertTrue("Native GLES renderer initialization failed", renderer != 0);
            LivePipelineJni.setRendererOverlayOnly(renderer); LivePipelineJni.setRendererOverlayAlpha(renderer, .4f);
        } finally {
            if (renderer != 0) LivePipelineJni.destroyGlRenderer(renderer);
            if (texture != 0) GLES20.glDeleteTextures(1, new int[]{texture}, 0);
            EGL14.eglMakeCurrent(core.getDisplay(), EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(core.getDisplay(), surface);
            GlEgl.INSTANCE.destroyContext(core.getDisplay(), core.getContext());
        }
    }

    @Test public void unavailableNativeHandlesReturnEmptyResultsInsteadOfDereferencingNull() {
        float[] identity = {1, 0, 0, 0, 1, 0, 0, 0, 1};
        assertEquals(0, LivePipelineJni.screenAcquireState(0)); LivePipelineJni.screenAbortAcquire(0);
        assertEquals(0, LivePipelineJni.screenMonitorTick(0, 1));
        assertEquals(0, LivePipelineJni.screenDispatchAcquire(0, 0, 0, 32, 32, 32, 32, identity));
        assertEquals(0, LivePipelineJni.screenPresentOverlayGl(0, 0, 32, 32, 32, 32));
        assertEquals(0, LivePipelineJni.screenMonitorFrameGl(0, 0, 0, 32, 32, identity, 1));
        assertEquals(0, LivePipelineJni.processFrameGl(0, 0, 0, 32, 32, 32, 32, identity, identity, 1));
        assertArrayEquals(new byte[0], LivePipelineJni.debugReadCanonicalRgba(0, 32, 32, identity));
        LivePipelineJni.setRendererOverlayOnly(0); LivePipelineJni.setRendererOverlayAlpha(0, 1); LivePipelineJni.destroyGlRenderer(0);
    }

    @Test public void invalidLiveGeometryAndShortTransformsAreRejectedBeforeRendering()throws Exception{
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        com.paperflow.translation.OfflineRuntime runtime=com.paperflow.translation.OfflineRuntime.get(context);long pipeline=runtime.createLive("en","en");assertTrue(pipeline!=0);
        try{
            GlEgl.EglCore core=GlEgl.INSTANCE.initContext(new int[]{EGL14.EGL_RENDERABLE_TYPE,GlEgl.EGL_OPENGL_ES3_BIT,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_NONE});assertNotNull(core);
            EGLSurface surface=EGL14.EGL_NO_SURFACE;long renderer=0;int texture=0;
            try{
                surface=EGL14.eglCreatePbufferSurface(core.getDisplay(),core.getConfig(),new int[]{EGL14.EGL_WIDTH,32,EGL14.EGL_HEIGHT,32,EGL14.EGL_NONE},0);assertNotEquals(EGL14.EGL_NO_SURFACE,surface);assertTrue(EGL14.eglMakeCurrent(core.getDisplay(),surface,surface,core.getContext()));
                renderer=LivePipelineJni.createGlRenderer();assertTrue(renderer!=0);texture=GlEgl.INSTANCE.createExternalOesTexture();assertTrue(texture!=0);
                float[] identity={1,0,0,0,1,0,0,0,1};
                assertEquals(0,LivePipelineJni.processFrameGl(pipeline,0,texture,32,32,32,32,identity,identity,1));
                for(int index=0;index<5;index++)for(int value:new int[]{0,-1}){
                    int[] dimensions={texture,32,32,32,32};dimensions[index]=value;
                    assertEquals(0,LivePipelineJni.processFrameGl(pipeline,renderer,dimensions[0],dimensions[1],dimensions[2],dimensions[3],dimensions[4],identity,identity,1));
                }
                assertArrayEquals(new byte[0],LivePipelineJni.debugReadCanonicalRgba(renderer,0,32,identity));assertArrayEquals(new byte[0],LivePipelineJni.debugReadCanonicalRgba(renderer,32,0,identity));
                final long gl=renderer;final int cameraTexture=texture;
                assertThrows(IndexOutOfBoundsException.class,()->LivePipelineJni.processFrameGl(pipeline,gl,cameraTexture,32,32,32,32,new float[8],identity,1));
                assertThrows(IndexOutOfBoundsException.class,()->LivePipelineJni.processFrameGl(pipeline,gl,cameraTexture,32,32,32,32,identity,new float[8],1));
                assertThrows(IndexOutOfBoundsException.class,()->LivePipelineJni.debugReadCanonicalRgba(gl,32,32,new float[8]));
            }finally{
                if(renderer!=0)LivePipelineJni.destroyGlRenderer(renderer);if(texture!=0)GLES20.glDeleteTextures(1,new int[]{texture},0);EGL14.eglMakeCurrent(core.getDisplay(),EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);if(surface!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(core.getDisplay(),surface);GlEgl.INSTANCE.destroyContext(core.getDisplay(),core.getContext());
            }
        }finally{runtime.destroyLive(pipeline);}
    }

    @Test public void liveSurfaceReleasesItsGlThreadAndCanBeAttachedAgain() {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<LiveGlSurfaceView> view=new AtomicReference<>(); AtomicReference<android.widget.FrameLayout> parent=new AtomicReference<>();
        AtomicReference<Thread> firstThread=new AtomicReference<>(); AtomicReference<android.view.Surface> firstSurface=new AtomicReference<>(); AtomicBoolean failed=new AtomicBoolean();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(new android.content.Intent(context,MainActivity.class))) {
            scenario.onActivity(a->{
                android.widget.FrameLayout frame=new android.widget.FrameLayout(a); parent.set(frame); a.setContentView(frame);
                LiveGlSurfaceView surface=new LiveGlSurfaceView(a); view.set(surface); surface.setOnFailure(()->{failed.set(true);return kotlin.Unit.INSTANCE;});
                frame.addView(surface,new android.widget.FrameLayout.LayoutParams(256,256));
            });
            UiTestWait.until(()->{synchronized(field(view.get(),"surfaceLock")){return failed.get()||field(view.get(),"cameraSurface")!=null;}},"Live GLES surface was not created");
            assertFalse("Live GLES initialization failed",failed.get());
            scenario.onActivity(a->{
                firstThread.set((Thread)field(view.get(),"glThread")); firstSurface.set((android.view.Surface)field(view.get(),"cameraSurface"));
                assertTrue(firstThread.get().isAlive()); assertTrue(firstSurface.get().isValid()); parent.get().removeView(view.get());
            });
            UiTestWait.until(()->field(view.get(),"glThread")==null,"Detached live surface retained its GL thread");
            assertFalse(firstThread.get().isAlive()); assertFalse(firstSurface.get().isValid());
            scenario.onActivity(a->parent.get().addView(view.get(),new android.widget.FrameLayout.LayoutParams(256,256)));
            UiTestWait.until(()->{synchronized(field(view.get(),"surfaceLock")){return failed.get()||field(view.get(),"cameraSurface")!=null;}},"Live surface did not recover after reattachment");
            scenario.onActivity(a->{assertFalse(failed.get());assertNotSame(firstThread.get(),field(view.get(),"glThread"));view.get().releasePipeline();assertEquals(0,view.get().getPipelinePtr());assertNull(field(view.get(),"glThread"));parent.get().removeView(view.get());});
        }
    }

    @Test public void livePipelinePresentsARealFrameAndReadsBackItsCanonicalPixels() throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        com.paperflow.translation.OfflineRuntime runtime=com.paperflow.translation.OfflineRuntime.get(context);
        long pipeline=runtime.createLive("auto","en"); assertTrue(pipeline!=0);
        com.paperflow.translation.RuntimeNative.changeLiveLanguages(pipeline,"en","en");
        // Rendering stays active while acquisition is suppressed: this test needs no OCR download.
        com.paperflow.translation.RuntimeNative.controlLive(pipeline,false,true,true);
        AtomicReference<LiveGlSurfaceView> view=new AtomicReference<>(); AtomicReference<byte[]> pixels=new AtomicReference<>(); AtomicReference<Throwable> callbackError=new AtomicReference<>(); AtomicLong packet=new AtomicLong(); AtomicBoolean failed=new AtomicBoolean();
        try {
            try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(new android.content.Intent(context,MainActivity.class))) {
                scenario.onActivity(a->{
                    android.widget.FrameLayout frame=new android.widget.FrameLayout(a);a.setContentView(frame);
                    LiveGlSurfaceView surface=new LiveGlSurfaceView(a);view.set(surface);surface.setPipelinePtr(pipeline);surface.setRotQuadrant(0);
                    surface.setOnFailure(()->{failed.set(true);return kotlin.Unit.INSTANCE;});
                    surface.setOnFrameResult(result->{
                        try {
                            Object thread=field(surface,"glThread");long renderer=((Number)field(thread,"rendererPtr")).longValue();
                            pixels.set(LivePipelineJni.debugReadCanonicalRgba(renderer,256,256,LiveGlSurfaceView.Companion.displayXform(256,256)));packet.set(result);
                        } catch(Throwable error) {callbackError.set(error);}
                        return kotlin.Unit.INSTANCE;
                    });
                    frame.addView(surface,new android.widget.FrameLayout.LayoutParams(256,256));
                });
                UiTestWait.until(()->{synchronized(field(view.get(),"surfaceLock")){return failed.get()||field(view.get(),"cameraSurface")!=null;}},"Native live frame surface did not initialize");assertFalse(failed.get());
                scenario.onActivity(a->{
                    Object thread=field(view.get(),"glThread");
                    try {java.lang.reflect.Method size=thread.getClass().getDeclaredMethod("setCameraBufferSize",int.class,int.class);size.setAccessible(true);size.invoke(thread,320,240);}catch(Exception error){throw new AssertionError(error);}
                    android.view.Surface source=(android.view.Surface)field(view.get(),"cameraSurface");android.graphics.Canvas canvas=source.lockCanvas(null);try{canvas.drawColor(android.graphics.Color.GREEN);}finally{source.unlockCanvasAndPost(canvas);}
                });
                UiTestWait.until(()->failed.get()||callbackError.get()!=null||packet.get()!=0,"Native live pipeline did not present the fixture frame");
                if(callbackError.get()!=null)throw new AssertionError(callbackError.get());assertFalse(failed.get());
                LivePipelineJni.FrameResult frame=LivePipelineJni.FrameResult.Companion.unpack(packet.get());assertNotNull(frame);assertTrue(frame.getCompositeOk());
                assertNotNull(pixels.get());assertEquals(256*256*4,pixels.get().length);int center=(128*256+128)*4;assertTrue((pixels.get()[center+1]&255)>240);assertTrue((pixels.get()[center]&255)<15);assertTrue((pixels.get()[center+2]&255)<15);
                scenario.onActivity(a->view.get().releasePipeline());
            }
        } finally {runtime.destroyLive(pipeline);}
    }
}

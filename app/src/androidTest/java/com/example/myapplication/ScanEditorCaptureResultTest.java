package com.example.myapplication;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.view.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.activity.result.ActivityResultLauncher;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import static org.junit.Assert.*;

/** Real ActivityResult delivery checks add/retake ownership without opening a physical camera. */
public class ScanEditorCaptureResultTest {
    final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    final Context context=inst.getTargetContext();
    final List<File> owned=new ArrayList<>();
    ActivityScenario<ScanEditorActivity> scenario;ScanEditorActivity activity;Instrumentation.ActivityMonitor monitor;
    File original;byte[] before;
    Object field(Object owner,String name){try{java.lang.reflect.Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(Exception e){throw new AssertionError(e);}}
    void set(String name,Object value){try{java.lang.reflect.Field f=ScanEditorActivity.class.getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}catch(Exception e){throw new AssertionError(e);}}
    @SuppressWarnings("unchecked") List<String> pages(){return (List<String>)field(activity,"pages");}
    File image(int color,boolean capture)throws Exception{File dir=capture?new File(context.getFilesDir(),"captures"):context.getCacheDir();assertTrue(dir.isDirectory()||dir.mkdirs());File file=File.createTempFile("editor-capture-result-",".png",dir);owned.add(file);Bitmap bitmap=Bitmap.createBitmap(120,160,Bitmap.Config.ARGB_8888);bitmap.eraseColor(color);try(OutputStream out=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}return file;}
    @Before public void open()throws Exception{original=image(Color.WHITE,false);before=Files.readAllBytes(original.toPath());scenario=ActivityScenario.launch(new Intent(context,ScanEditorActivity.class).putStringArrayListExtra("pages",new ArrayList<>(Collections.singletonList(Uri.fromFile(original).toString()))).putExtra("name","Capture result regression").putExtra("start_crop",false).putExtra("initial_filter",0).putExtra("auto_detect",false));scenario.onActivity(a->activity=a);ready();}
    void ready(){UiTestWait.until(()->field(activity,"displayed")!=null&&!(Boolean)field(activity,"loadingPage"),"Capture result preview did not render");scenario.onActivity(a->assertFalse("A 120px page must not be recycled as its own thumbnail",((Bitmap)field(a,"displayed")).isRecycled()));}
    @After public void close()throws Exception{if(monitor!=null)inst.removeMonitor(monitor);ExecutorService worker=(ExecutorService)field(activity,"worker");scenario.close();assertTrue(worker.awaitTermination(15,TimeUnit.SECONDS));assertArrayEquals(before,Files.readAllBytes(original.toPath()));for(File file:owned)file.delete();}
    Intent result(File...files){ArrayList<String> paths=new ArrayList<>();for(File file:files)paths.add(file.getPath());return new Intent().putStringArrayListExtra("pages",paths).putExtra("initial_filter",0).putExtra("auto_detect",false);}
    void returnFromCamera(int retake,int code,Intent result){
        monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){return new Instrumentation.ActivityResult(code,result);}};inst.addMonitor(monitor);
        scenario.onActivity(a->{set("retakeIndex",retake);@SuppressWarnings("unchecked") ActivityResultLauncher<Intent> launcher=(ActivityResultLauncher<Intent>)field(a,"camera");launcher.launch(new Intent(a,CameraCaptureActivity.class));});inst.waitForIdleSync();
    }
    @Test public void addedCameraPagesKeepTheirOrderAndCaptureOptions()throws Exception{
        File blue=image(Color.BLUE,true),red=image(Color.RED,true);returnFromCamera(-1,Activity.RESULT_OK,result(blue,red));
        UiTestWait.until(()->pages().size()==3,"Camera pages were not appended");ready();
        scenario.onActivity(a->{assertEquals(Arrays.asList(Uri.fromFile(original).toString(),Uri.fromFile(blue).toString(),Uri.fromFile(red).toString()),pages());assertEquals(1,field(a,"index"));assertEquals(true,field(a,"cropStage"));for(String list:new String[]{"rotations","filters","crops","brightness","contrast","pageConfigs"})assertEquals(3,((List<?>)field(a,list)).size());assertTrue(((Set<?>)field(a,"detectedPages")).contains(Uri.fromFile(blue)+"#0"));});
        assertTrue(blue.isFile());assertTrue(red.isFile());
    }
    @Test public void retakeReplacesOnlyTheSelectedPageAndDeletesUnusedOwnedCaptures()throws Exception{
        File blue=image(Color.BLUE,true),unused=image(Color.RED,true);
        scenario.onActivity(a->{@SuppressWarnings("unchecked") List<Integer> rotations=(List<Integer>)field(a,"rotations");rotations.set(0,180);@SuppressWarnings("unchecked") List<float[]> crops=(List<float[]>)field(a,"crops");crops.set(0,new float[]{.1f,.1f,.8f,.1f,.8f,.8f,.1f,.8f});});
        returnFromCamera(0,Activity.RESULT_OK,result(blue,unused));UiTestWait.until(()->pages().get(0).equals(Uri.fromFile(blue).toString()),"Retake did not replace the selected page");ready();
        scenario.onActivity(a->{assertEquals(1,pages().size());assertEquals(-1,field(a,"retakeIndex"));assertEquals(0,((List<?>)field(a,"rotations")).get(0));assertArrayEquals(new float[]{0,0,1,0,1,1,0,1},(float[])((List<?>)field(a,"crops")).get(0),0);assertTrue(((Set<?>)field(a,"detectedPages")).contains(Uri.fromFile(blue)+"#0"));});
        assertTrue("Retake must not delete an external editor input",original.isFile());assertTrue(blue.isFile());assertFalse("Unused extra capture must not leak",unused.exists());
    }
    @Test public void cancelledRetakeClearsItsIndexAndKeepsTheOriginalPage(){
        returnFromCamera(0,Activity.RESULT_CANCELED,null);scenario.onActivity(a->{assertEquals(-1,field(a,"retakeIndex"));assertEquals(Collections.singletonList(Uri.fromFile(original).toString()),pages());assertEquals(false,field(a,"cropStage"));});assertTrue(original.isFile());
    }
    @Test public void emptyOrMissingCameraResultsDoNotRemoveEditorPages(){
        returnFromCamera(-1,Activity.RESULT_OK,null);assertEquals(1,pages().size());inst.removeMonitor(monitor);monitor=null;
        returnFromCamera(-1,Activity.RESULT_OK,new Intent().putStringArrayListExtra("pages",new ArrayList<>()));assertEquals(Collections.singletonList(Uri.fromFile(original).toString()),pages());assertTrue(original.isFile());
    }
}

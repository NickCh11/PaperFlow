package com.example.myapplication;

import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import static org.junit.Assert.*;

/** Verifies user-facing tool actions at the outgoing activity boundary. */
public class MainToolRoutingTest {
    private final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private final Context context=inst.getTargetContext();
    private ActivityScenario<MainActivity> scenario;
    private Instrumentation.ActivityMonitor monitor;
    private final AtomicReference<Intent> launched=new AtomicReference<>();
    private boolean launchCamera;
    @Before public void prepare() {
        SharedPreferences prefs=context.getSharedPreferences("scan_defaults",0);launchCamera=prefs.getBoolean("launch_camera",false);prefs.edit().putBoolean("launch_camera",false).commit();
        scenario=ActivityScenario.launch(MainActivity.class);
        monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){launched.set(new Intent(intent));return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);}};
        inst.addMonitor(monitor);
        scenario.onActivity(a->{View tools=findTag(a.getWindow().getDecorView(),"Tools");assertNotNull(tools);assertTrue(tools.performClick());});
    }
    @After public void cleanup() {
        if(monitor!=null)inst.removeMonitor(monitor);
        if(scenario!=null)scenario.close();
        context.getSharedPreferences("scan_defaults",0).edit().putBoolean("launch_camera",launchCamera).commit();
    }
    private View findTag(View view,String tag) {
        if(tag.equals(view.getTag()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=findTag(((ViewGroup)view).getChildAt(i),tag);if(found!=null)return found;}
        return null;
    }
    private View action(View view,String label) {
        if(view.isClickable()&&label.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=action(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}
        return null;
    }
    private Intent click(String tool) {
        launched.set(null);
        scenario.onActivity(a->{View control=action(a.getWindow().getDecorView(),UiText.label(tool));assertNotNull("Tool missing: "+tool,control);assertTrue(control.performClick());});
        assertNotNull("Tool did not launch: "+tool,launched.get());return launched.get();
    }
    private void destination(Intent intent,String activity) {
        assertNotNull(intent.getComponent());assertEquals(context.getPackageName(),intent.getComponent().getPackageName());assertEquals(context.getPackageName()+"."+activity,intent.getComponent().getClassName());
    }
    @Test public void scanTilesPassTheirOwnCaptureMode() {
        for(String tool:new String[]{"ID Cards","Book","Slides","Whiteboard","Timestamp"}){
            Intent intent=click(tool);destination(intent,"CameraCaptureActivity");assertEquals(tool.equals("ID Cards")?"ID Card":tool,intent.getStringExtra("capture_mode"));
        }
    }
    @Test public void pdfToolsKeepTheirOperationAndMatchingLibraryLists() {
        for(String tool:new String[]{"To Word","OCR PDF","PDF to Images","PDF to Long Image","Sign","Add Watermark","Merge Files","Extract PDF Pages","Reorder Pages","Lock","Remove PDF password","Compress","Rotate PDF","Print"}){
            Intent intent=click(tool);destination(intent,"ToolWorkflowActivity");assertEquals(tool,intent.getStringExtra("operation"));assertNotNull(intent.getStringArrayListExtra("paths"));assertNotNull(intent.getStringArrayListExtra("names"));assertEquals(intent.getStringArrayListExtra("paths").size(),intent.getStringArrayListExtra("names").size());
        }
    }
    @Test public void dedicatedToolsOpenTheirSpecificScreens() {
        String[][] routes={{"ID Photo Maker","IdPhotoMakerActivity"},{"Photo Translation","LocalTranslationActivity"},{"Erase Marks","EraseMarksActivity"},{"Scan Code","ScanCodeActivity"}};
        for(String[] route:routes)destination(click(route[0]),route[1]);
        for(String tool:new String[]{"To Excel","To PowerPoint","Office to PDF","Batch OCR","Collage"}){
            Intent intent=click(tool);destination(intent,"ExtendedToolsActivity");assertEquals(tool,intent.getStringExtra("operation"));
        }
    }
    @Test public void importsRequestOnlyTheirSupportedDocumentTypes() {
        Intent images=click("Import Images");assertEquals(Intent.ACTION_OPEN_DOCUMENT,images.getAction());assertTrue(images.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false));assertArrayEquals(new String[]{"image/*"},images.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));assertEquals("*/*",images.getType());
        Intent files=click("Import Files");assertEquals(Intent.ACTION_OPEN_DOCUMENT,files.getAction());assertFalse(files.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false));assertEquals("*/*",files.getType());
        assertArrayEquals(new String[]{"application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/vnd.openxmlformats-officedocument.presentationml.presentation"},files.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));
    }
}

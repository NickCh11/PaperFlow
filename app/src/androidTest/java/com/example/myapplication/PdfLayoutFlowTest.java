package com.example.myapplication;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.*;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import static org.junit.Assert.*;

/** Real reader settings, preview generations and saved PDF bytes. */
public class PdfLayoutFlowTest {
    final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    final Context context=inst.getTargetContext();
    ActivityScenario<PdfLayoutActivity> scenario;PdfLayoutActivity activity;File source;byte[] original;
    final ArrayList<File> outputs=new ArrayList<>();
    final ArrayList<Intent> outgoing=new ArrayList<>();
    Instrumentation.ActivityMonitor monitor;
    Object field(Object owner,String name){try{java.lang.reflect.Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(Exception error){throw new AssertionError(error);}}
    <T>T main(Callable<T> action){AtomicReference<T> result=new AtomicReference<>();inst.runOnMainSync(()->{try{result.set(action.call());}catch(Exception error){throw new AssertionError(error);}});return result.get();}
    @Before public void fixture()throws Exception{
        PDFBoxResourceLoader.init(context);source=File.createTempFile("layout-ui-",".pdf",context.getCacheDir());
        try(PDDocument doc=new PDDocument()){for(int i=0;i<2;i++){PDPage page=new PDPage(new PDRectangle(120,180));doc.addPage(page);try(PDPageContentStream stream=new PDPageContentStream(doc,page)){stream.beginText();stream.setFont(PDType1Font.HELVETICA,10);stream.newLineAtOffset(12,90);stream.showText("Original page "+(i+1));stream.endText();}}doc.save(source);}
        original=Files.readAllBytes(source.toPath());
        monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){if(Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction())){outgoing.add(intent);return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);}return null;}};
        inst.addMonitor(monitor);
    }
    void open(){scenario=ActivityScenario.launch(new Intent(context,PdfLayoutActivity.class).putExtra("path",source.getPath()).putExtra("name","Layout fixture"));scenario.onActivity(a->activity=a);ready();}
    void ready(){UiTestWait.until(()->field(activity,"bitmap")!=null&&!((Boolean)field(activity,"busy")),"Page settings preview did not render");inst.waitForIdleSync();}
    View text(View root,String value){if(root.isShown()&&root instanceof TextView&&value.contentEquals(((TextView)root).getText()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=text(((ViewGroup)root).getChildAt(i),value);if(found!=null)return found;}return null;}
    void press(String label){main(()->{View action=text(activity.getWindow().getDecorView(),label);assertNotNull("Missing page action "+label,action);if(action instanceof CompoundButton){boolean checked=((CompoundButton)action).isChecked();action.performClick();assertNotEquals(checked,((CompoundButton)action).isChecked());}else assertTrue("Page action did not click: "+label,action.performClick());return null;});inst.waitForIdleSync();}
    View dialog(){for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews())if(root!=activity.getWindow().getDecorView()&&root.isShown()&&root.hasWindowFocus())return root;return null;}
    <T extends View>T find(View root,Class<T> type){if(root==null)return null;if(type.isInstance(root))return type.cast(root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){T found=find(((ViewGroup)root).getChildAt(i),type);if(found!=null)return found;}return null;}
    void choose(int index){UiTestWait.until(()->dialog()!=null&&find(dialog(),ListView.class)!=null,"Page settings choices did not attach");main(()->{ListView list=find(dialog(),ListView.class);assertTrue(list.performItemClick(list.getChildAt(index-list.getFirstVisiblePosition()),index,list.getAdapter().getItemId(index)));return null;});inst.waitForIdleSync();}
    void dialogButton(int id){UiTestWait.until(()->dialog()!=null&&dialog().findViewById(id)!=null,"Page settings dialog did not attach");main(()->{assertTrue(dialog().findViewById(id).performClick());return null;});inst.waitForIdleSync();}
    File create(){press(UiText.get(R.string.ui_create_pdf));UiTestWait.until(()->field(activity,"result")!=null&&!((Boolean)field(activity,"busy")),"Page settings did not create a PDF");File result=main(()->(File)field(activity,"result"));outputs.add(result);return result;}
    @After public void cleanup()throws Exception{
        if(scenario!=null)scenario.close();if(activity!=null)assertTrue(((ExecutorService)field(activity,"worker")).awaitTermination(15,TimeUnit.SECONDS));
        inst.removeMonitor(monitor);if(source!=null){assertArrayEquals(original,Files.readAllBytes(source.toPath()));source.delete();}
        for(File output:outputs)output.delete();
    }

    @Test public void chosenPagesAreResizedAndNumberedWhileOriginalAndCancelledSaveStayIntact()throws Exception{
        open();press(UiText.format(R.string.ui_size_value,UiText.label("Original")));choose(3);
        press(UiText.get(R.string.ui_add_page_numbers));
        main(()->{EditText first=find(activity.getWindow().getDecorView(),EditText.class);assertNotNull(first);first.setText("4");return null;});
        press(UiText.get(R.string.ui_show_final_page_number_x_total));
        press(UiText.format(R.string.ui_pages_value_value,2,2));choose(0);dialogButton(android.R.id.button1);
        press("›");File result=create();
        try(PDDocument doc=PDDocument.load(result)){
            assertEquals(2,doc.getNumberOfPages());assertEquals(120f,doc.getPage(0).getCropBox().getWidth(),.01f);
            assertEquals(419.528f,doc.getPage(1).getCropBox().getWidth(),.01f);assertEquals(595.276f,doc.getPage(1).getCropBox().getHeight(),.01f);
            PDFTextStripper stripper=new PDFTextStripper();stripper.setStartPage(2);assertTrue(stripper.getText(doc).contains("5 / 5"));
        }
        byte[] saved=Files.readAllBytes(result.toPath());press(UiText.get(R.string.ui_save_to_device));
        UiTestWait.until(()->!outgoing.isEmpty(),"Page settings did not launch its save picker");
        assertEquals("application/pdf",outgoing.get(0).getType());assertArrayEquals(saved,Files.readAllBytes(result.toPath()));
        scenario.recreate();scenario.onActivity(a->activity=a);ready();assertEquals(result,main(()->field(activity,"result")));
    }

    @Test public void invalidNumberingAndEmptyPageChoiceCannotCreateAnOutputAndCanBeCorrected()throws Exception{
        open();press(UiText.get(R.string.ui_add_page_numbers));
        main(()->{find(activity.getWindow().getDecorView(),EditText.class).setText("0");return null;});
        press(UiText.get(R.string.ui_create_pdf));assertNull(main(()->field(activity,"result")));assertFalse(main(()->(Boolean)field(activity,"busy")));
        main(()->{find(activity.getWindow().getDecorView(),EditText.class).setText("9");return null;});
        press(UiText.format(R.string.ui_pages_value_value,2,2));dialogButton(android.R.id.button3);dialogButton(android.R.id.button1);
        press(UiText.get(R.string.ui_create_pdf));assertNull(main(()->field(activity,"result")));
        press(UiText.format(R.string.ui_pages_value_value,0,2));dialogButton(android.R.id.button3);dialogButton(android.R.id.button1);
        File result=create();try(PDDocument doc=PDDocument.load(result)){assertEquals(2,doc.getNumberOfPages());assertTrue(new PDFTextStripper().getText(doc).contains("Original page 1"));}
    }

    @Test public void readOnlyPdfCannotBePublishedAsAModifiedLayout()throws Exception{
        File encrypted=File.createTempFile("layout-readonly-",".pdf",context.getCacheDir());
        try(PDDocument doc=PDDocument.load(source)){
            com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission access=new com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission();access.setCanModify(false);
            com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy policy=new com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy("layout-owner-fixture","",access);policy.setEncryptionKeyLength(128);doc.protect(policy);doc.save(encrypted);
        }
        Files.move(encrypted.toPath(),source.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        original=Files.readAllBytes(source.toPath());open();
        press(UiText.get(R.string.ui_create_pdf));UiTestWait.until(()->!((Boolean)field(activity,"busy")),"Read-only page settings did not return control");
        assertNull(main(()->field(activity,"result")));assertTrue(outgoing.isEmpty());
        try(PDDocument doc=PDDocument.load(source,"")){assertFalse(doc.getCurrentAccessPermission().canModify());assertEquals(2,doc.getNumberOfPages());}
    }

    @Test public void rapidNavigationDiscardsTheOldPreviewAndRecyclesItsBitmap()throws Exception{
        open();Bitmap old=main(()->(Bitmap)field(activity,"bitmap"));CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        ((ExecutorService)field(activity,"worker")).execute(()->{entered.countDown();try{release.await(30,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});
        try{
            assertTrue(entered.await(10,TimeUnit.SECONDS));press("›");press("‹");release.countDown();
            UiTestWait.until(()->field(activity,"bitmap")!=old,"Newest page preview was not published");
            assertEquals(0,main(()->(Integer)field(activity,"current")).intValue());assertTrue(old.isRecycled());
            assertTrue(main(()->((TextView)field(activity,"status")).getText().toString().contains("1")));
        }finally{release.countDown();}
    }
}

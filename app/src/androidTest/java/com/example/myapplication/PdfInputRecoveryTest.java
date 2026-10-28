package com.example.myapplication;

import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class PdfInputRecoveryTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    private Object field(Object owner,String name){try{java.lang.reflect.Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(Exception e){throw new AssertionError(e);}}
    private File pdf(int count,boolean locked)throws Exception{
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context);File file=File.createTempFile("pdf-recovery-",".pdf",context.getCacheDir());
        try(PDDocument doc=new PDDocument()){for(int i=0;i<count;i++){PDPage page=new PDPage(new PDRectangle(300,500));if(i==0)page.setRotation(90);doc.addPage(page);}if(locked){StandardProtectionPolicy policy=new StandardProtectionPolicy("fixture-owner","fixture-user",new AccessPermission());policy.setEncryptionKeyLength(128);policy.setPreferAES(true);doc.protect(policy);}doc.save(file);}return file;
    }
    private ActivityScenario<ToolWorkflowActivity> open(File file,String operation){return ActivityScenario.launch(new Intent(context,ToolWorkflowActivity.class).putExtra("path",file.getPath()).putExtra("operation",operation));}
    private EditText password(){for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews()){EditText result=password(root);if(result!=null&&result.isShown())return result;}return null;}
    private EditText password(View root){if(root instanceof EditText&&UiText.get(R.string.ui_pdf_password).contentEquals(((EditText)root).getHint()))return (EditText)root;if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){EditText found=password(g.getChildAt(i));if(found!=null)return found;}}return null;}
    private void submitPassword(){for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews()){Button button=root.findViewById(android.R.id.button1);if(button!=null&&button.isShown()){assertTrue(button.performClick());return;}}fail("Password dialog button missing");}
    private View text(View root,String label){if(root instanceof TextView&&label.contentEquals(((TextView)root).getText()))return root;if(root instanceof ViewGroup){ViewGroup g=(ViewGroup)root;for(int i=0;i<g.getChildCount();i++){View found=text(g.getChildAt(i),label);if(found!=null)return found;}}return null;}

    @Test public void passwordRetryKeepsRotationGeometryAndRestoresTheChosenRotation()throws Exception{
        File file=pdf(2,true);
        try(ActivityScenario<ToolWorkflowActivity> scenario=open(file,"Rotate PDF")){
            AtomicReference<ToolWorkflowActivity> owner=new AtomicReference<>();AtomicReference<EditText> first=new AtomicReference<>();scenario.onActivity(owner::set);
            UiTestWait.until(()->password()!=null,"Encrypted PDF did not request a password");
            scenario.onActivity(a->{first.set(password());submitPassword();assertEquals(UiText.get(R.string.ui_enter_password),first.get().getError().toString());first.get().setText("incorrect");submitPassword();});
            UiTestWait.until(()->password()!=null&&password()!=first.get(),"Incorrect password did not allow a retry");
            scenario.onActivity(a->{password().setText("fixture-user");submitPassword();});
            UiTestWait.until(()->"options".equals(field(owner.get(),"stage")),"Correct password did not open PDF options");
            scenario.onActivity(a->{List<float[]> sizes=(List<float[]>)field(a,"pageSizes");assertArrayEquals(new float[]{500,300},sizes.get(0),0);assertArrayEquals(new float[]{300,500},sizes.get(1),0);View rotate=text((View)field(a,"body"),UiText.label("Rotate right"));assertNotNull(rotate);assertTrue(rotate.performClick());});
            scenario.recreate();scenario.onActivity(owner::set);UiTestWait.until(()->"options".equals(field(owner.get(),"stage")),"Encrypted options did not restore");
            scenario.onActivity(a->assertEquals(Integer.valueOf(90),((Map<Integer,Integer>)field(a,"pageRotations")).get(0)));
        }finally{file.delete();}
    }

    @Test public void replacingAPdfWithFewerPagesDiscardsMissingSelectionsOnRecreation()throws Exception{
        File file=pdf(61,false),replacement=pdf(1,false);
        try(ActivityScenario<ToolWorkflowActivity> scenario=open(file,"Extract PDF Pages")){
            AtomicReference<ToolWorkflowActivity> owner=new AtomicReference<>();scenario.onActivity(owner::set);UiTestWait.until(()->"options".equals(field(owner.get(),"stage")),"Original PDF did not open");
            scenario.onActivity(a->{View next=text((View)field(a,"body"),UiText.get(R.string.ui_next));assertNotNull(next);assertTrue(next.performClick());});
            ((ExecutorService)field(owner.get(),"previewWorker")).submit(()->{}).get(5,TimeUnit.SECONDS);
            Files.move(replacement.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            scenario.recreate();scenario.onActivity(owner::set);UiTestWait.until(()->"options".equals(field(owner.get(),"stage")),"Replacement PDF did not restore");
            scenario.onActivity(a->{assertEquals(Collections.singleton(0),field(a,"selectedPages"));assertEquals(Collections.singletonList(0),field(a,"pageOrder"));assertEquals(0,field(a,"pageWindow"));});
        }finally{file.delete();replacement.delete();}
    }

    @Test public void malformedAndEmptyPdfsReturnToSourceWithoutDeletingTheInput()throws Exception{
        File invalid=File.createTempFile("bad-pdf-",".pdf",context.getCacheDir());Files.write(invalid.toPath(),new byte[]{1,2,3});File empty=pdf(0,false);
        try{for(File file:new File[]{invalid,empty})try(ActivityScenario<ToolWorkflowActivity> scenario=open(file,"Extract PDF Pages")){
            AtomicReference<ToolWorkflowActivity> owner=new AtomicReference<>();scenario.onActivity(owner::set);UiTestWait.until(()->"source".equals(field(owner.get(),"stage")),"Invalid PDF did not return to source selection");
            scenario.onActivity(a->{assertNull(field(a,"resultFile"));assertTrue(((List<?>)field(a,"pageSizes")).isEmpty());});assertTrue(file.isFile());
        }}finally{invalid.delete();empty.delete();}
    }
}

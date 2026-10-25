package com.example.myapplication;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.*;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class PdfViewerLifecycleTest {
    private final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private final Context context=inst.getTargetContext();
    private Object field(Object owner,String name){try{java.lang.reflect.Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(Exception error){throw new AssertionError(error);}}
    private Object call(Object owner,String name,Class<?>[] types,Object... values){try{java.lang.reflect.Method method=owner.getClass().getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(owner,values);}catch(Exception error){throw new AssertionError(error);}}
    private File pdf(int count,boolean encrypted)throws Exception{
        PDFBoxResourceLoader.init(context);File directory=new File(context.getFilesDir(),"documents");assertTrue(directory.isDirectory()||directory.mkdirs());File file=File.createTempFile("reader-flow-",".PDF",directory);
        try(PDDocument doc=new PDDocument()){
            for(int i=0;i<count;i++){PDPage page=new PDPage(new PDRectangle(120,180));if(i==1)page.setRotation(90);doc.addPage(page);try(PDPageContentStream stream=new PDPageContentStream(doc,page)){stream.beginText();stream.setFont(PDType1Font.HELVETICA,9);stream.newLineAtOffset(10,140);stream.showText("Reader page "+(i+1));stream.newLineAtOffset(0,-20);stream.showText(i==0?"Alpha Beta":"Gamma Delta");stream.endText();}}
            if(encrypted){StandardProtectionPolicy policy=new StandardProtectionPolicy("fixture-owner","fixture-user",new AccessPermission());policy.setEncryptionKeyLength(128);policy.setPreferAES(true);doc.protect(policy);}doc.save(file);
        }return file;
    }
    private ActivityScenario<PdfViewerActivity> open(File file,AtomicReference<PdfViewerActivity> current){ActivityScenario<PdfViewerActivity> scenario=ActivityScenario.launch(new Intent(context,PdfViewerActivity.class).putExtra("path",file.getPath()));scenario.onActivity(current::set);return scenario;}
    private List<?> slots(PdfViewerActivity activity){return (List<?>)field(activity,"slots");}
    private void ready(AtomicReference<PdfViewerActivity> current,int count){UiTestWait.until(()->slots(current.get()).size()==count&&field(slots(current.get()).get(0),"bitmap")!=null,"Reader did not render its first page");}
    private <T extends View>T descendant(View view,Class<T> type){if(type.isInstance(view))return type.cast(view);if(view instanceof ViewGroup){ViewGroup parent=(ViewGroup)view;for(int i=0;i<parent.getChildCount();i++){T found=descendant(parent.getChildAt(i),type);if(found!=null)return found;}}return null;}
    private TextView text(View view,String label){if(view instanceof TextView&&view.isShown()&&label.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup){ViewGroup parent=(ViewGroup)view;for(int i=0;i<parent.getChildCount();i++){TextView found=text(parent.getChildAt(i),label);if(found!=null)return found;}}return null;}
    private View window(String label){for(View view:android.view.inspector.WindowInspector.getGlobalWindowViews())if(text(view,label)!=null)return view;return null;}
    private View awaitWindow(String label){UiTestWait.until(()->window(label)!=null,"Reader dialog did not appear: "+label);return window(label);}
    private void positive(View window){Button button=window.findViewById(android.R.id.button1);assertNotNull(button);assertTrue(button.performClick());}
    private File destination()throws Exception{File directory=new File(context.getCacheDir(),"translation-exports");assertTrue(directory.isDirectory()||directory.mkdirs());return File.createTempFile("reader-output-",".bin",directory);}
    private Uri uri(File file){return androidx.core.content.FileProvider.getUriForFile(context,context.getPackageName()+".files",file);}
    private void barrier(PdfViewerActivity activity)throws Exception{((ExecutorService)field(activity,"worker")).submit(()->{}).get(15,TimeUnit.SECONDS);inst.waitForIdleSync();}

    @Test public void missingCorruptAndEmptyDocumentsOfferACleanExit()throws Exception{
        File missing=new File(context.getCacheDir(),"missing-"+UUID.randomUUID()+".pdf"),corrupt=File.createTempFile("reader-corrupt-",".pdf",context.getCacheDir()),empty=pdf(0,false);Files.write(corrupt.toPath(),new byte[]{1,2,3});
        try{for(File file:new File[]{missing,corrupt,empty}){AtomicReference<PdfViewerActivity> current=new AtomicReference<>();try(ActivityScenario<PdfViewerActivity> scenario=open(file,current)){
            View dialog=awaitWindow(UiText.get(R.string.ui_unable_to_open_document));scenario.onActivity(a->{assertTrue(slots(a).isEmpty());positive(dialog);});UiTestWait.until(()->current.get().isFinishing(),"Invalid reader did not close");
        }}}finally{corrupt.delete();empty.delete();}
    }

    @Test public void passwordValidationRetriesAndEncryptedPageGeometryUsesTheCorrectRotation()throws Exception{
        File file=pdf(3,true);AtomicReference<PdfViewerActivity> current=new AtomicReference<>();
        try(ActivityScenario<PdfViewerActivity> scenario=open(file,current)){
            View first=awaitWindow(UiText.get(R.string.ui_password_protected_pdf));
            scenario.onActivity(a->{EditText input=descendant(first,EditText.class);assertNotNull(input);positive(first);assertEquals(UiText.get(R.string.ui_enter_the_password),input.getError().toString());input.setText("wrong-password");positive(first);});
            UiTestWait.until(()->window(UiText.get(R.string.ui_incorrect_password_try_again))!=null,"Incorrect password did not offer another attempt");
            scenario.onActivity(a->{View retry=window(UiText.get(R.string.ui_incorrect_password_try_again));descendant(retry,EditText.class).setText("fixture-user");positive(retry);});ready(current,3);
            scenario.onActivity(a->{List<?> sizes=(List<?>)field(a,"sizes");assertArrayEquals(new float[]{120,180},(float[])sizes.get(0),0);assertArrayEquals(new float[]{180,120},(float[])sizes.get(1),0);assertEquals("fixture-user",field(a,"password"));assertNotNull(field(a,"boxDocument"));assertNull(field(a,"renderer"));});
        }finally{file.delete();}
    }

    @Test public void gridAndRecreationRecycleOldPageImagesAndRetainTheViewMode()throws Exception{
        File file=pdf(5,false);AtomicReference<PdfViewerActivity> current=new AtomicReference<>();List<Bitmap> previous=new ArrayList<>();
        try(ActivityScenario<PdfViewerActivity> scenario=open(file,current)){
            ready(current,5);scenario.onActivity(a->{Bitmap first=(Bitmap)field(slots(a).get(0),"bitmap");previous.add(first);call(a,"toggleGrid",new Class<?>[0]);assertTrue(first.isRecycled());assertEquals(true,field(a,"grid"));assertEquals(3,((ViewGroup)((ViewGroup)field(a,"pages")).getChildAt(1)).getChildCount());});
            UiTestWait.until(()->field(slots(current.get()).get(0),"bitmap")!=null,"Grid thumbnails did not render");scenario.onActivity(a->previous.add((Bitmap)field(slots(a).get(0),"bitmap")));
            scenario.recreate();scenario.onActivity(current::set);ready(current,5);scenario.onActivity(a->{assertEquals(true,field(a,"grid"));assertEquals(5,slots(a).size());});for(Bitmap bitmap:previous)assertTrue(bitmap.isRecycled());
        }finally{file.delete();}
        assertTrue(((ExecutorService)field(current.get(),"worker")).awaitTermination(15,TimeUnit.SECONDS));assertNull(field(current.get(),"renderer"));assertNull(field(current.get(),"descriptor"));
    }

    @Test public void scrollingToADistantPageReleasesTheOffscreenBitmap()throws Exception{
        File file=pdf(12,false);AtomicReference<PdfViewerActivity> current=new AtomicReference<>();AtomicReference<Bitmap> first=new AtomicReference<>();
        try(ActivityScenario<PdfViewerActivity> scenario=open(file,current)){
            ready(current,12);scenario.onActivity(a->{first.set((Bitmap)field(slots(a).get(0),"bitmap"));View last=(View)field(slots(a).get(11),"frame");((ScrollView)field(a,"scroll")).scrollTo(0,last.getTop());call(a,"refreshVisiblePages",new Class<?>[0]);});
            UiTestWait.until(()->field(slots(current.get()).get(11),"bitmap")!=null&&field(slots(current.get()).get(0),"bitmap")==null,"Distant page did not render while releasing the first page");assertTrue(first.get().isRecycled());
        }finally{file.delete();}
    }

    @Test public void savingAndSharingPreservePdfBytesAndGrantOnlyReadAccess()throws Exception{
        File source=pdf(2,false),output=destination();byte[] original=Files.readAllBytes(source.toPath());AtomicReference<PdfViewerActivity> current=new AtomicReference<>();AtomicReference<Intent> shared=new AtomicReference<>();
        Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){if(Intent.ACTION_CHOOSER.equals(intent.getAction())){shared.set(intent);return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);}return null;}};inst.addMonitor(monitor);
        try(ActivityScenario<PdfViewerActivity> scenario=open(source,current)){
            ready(current,2);scenario.onActivity(a->call(a,"saveToDevice",new Class<?>[]{Uri.class},uri(output)));barrier(current.get());assertArrayEquals(original,Files.readAllBytes(output.toPath()));
            scenario.onActivity(a->call(a,"shareDocument",new Class<?>[0]));assertNotNull(shared.get());Intent payload=shared.get().getParcelableExtra(Intent.EXTRA_INTENT);assertEquals(Intent.ACTION_SEND,payload.getAction());assertEquals("application/pdf",payload.getType());assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,payload.getFlags());assertEquals(uri(source),payload.getParcelableExtra(Intent.EXTRA_STREAM));assertArrayEquals(original,Files.readAllBytes(source.toPath()));
        }finally{inst.removeMonitor(monitor);source.delete();output.delete();}
    }

    @Test public void renameValidatesTheNameAndDispatchesTheOriginalDocumentPath()throws Exception{
        File file=pdf(1,false);AtomicReference<PdfViewerActivity> current=new AtomicReference<>();AtomicReference<Intent> renamed=new AtomicReference<>();
        Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){if(new ComponentName(context,MainActivity.class).equals(intent.getComponent())){renamed.set(intent);return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);}return null;}};inst.addMonitor(monitor);
        try(ActivityScenario<PdfViewerActivity> scenario=open(file,current)){
            ready(current,1);scenario.onActivity(a->call(a,"rename",new Class<?>[0]));View dialog=awaitWindow(UiText.get(R.string.ui_rename_document));
            scenario.onActivity(a->{EditText name=descendant(dialog,EditText.class);name.setText("  ");positive(dialog);assertEquals(UiText.get(R.string.ui_enter_a_name),name.getError().toString());name.setText("  Renamed reader document  ");positive(dialog);});
            UiTestWait.until(()->renamed.get()!=null,"Reader rename was not dispatched");assertEquals("Rename",renamed.get().getStringExtra("document_action"));assertEquals("Renamed reader document",renamed.get().getStringExtra("name"));assertEquals(file.getPath(),renamed.get().getStringExtra("path"));assertFalse(renamed.get().hasExtra("pdf_password"));assertTrue(file.isFile());
        }finally{inst.removeMonitor(monitor);file.delete();}
    }

    @Test public void selectedTextPagesExportInTheChosenOrderAndSurviveRecreation()throws Exception{
        File file=pdf(3,false),destination=destination();AtomicReference<PdfViewerActivity> current=new AtomicReference<>();AtomicReference<File> exported=new AtomicReference<>();
        try(ActivityScenario<PdfViewerActivity> scenario=open(file,current)){
            ready(current,3);scenario.onActivity(a->call(a,"extractText",new Class<?>[]{ArrayList.class},new ArrayList<>(Arrays.asList(2,0))));UiTestWait.until(()->field(current.get(),"textExport")!=null,"Reader did not export selected text");exported.set((File)field(current.get(),"textExport"));
            String value=new String(Files.readAllBytes(exported.get().toPath()),StandardCharsets.UTF_8);assertTrue(value,value.indexOf("Reader page 3")<value.indexOf("Reader page 1"));assertFalse(value.contains("Reader page 2"));assertTrue(value.contains("Alpha Beta"));
            scenario.onActivity(a->call(a,"saveText",new Class<?>[]{Uri.class},uri(destination)));barrier(current.get());assertEquals(value,new String(Files.readAllBytes(destination.toPath()),StandardCharsets.UTF_8));
            scenario.recreate();scenario.onActivity(current::set);ready(current,3);assertEquals(exported.get().getPath(),((File)field(current.get(),"textExport")).getPath());assertTrue(exported.get().isFile());
        }finally{file.delete();destination.delete();if(exported.get()!=null)exported.get().delete();}
    }

    @Test public void pageChooserRequiresASelectionAndCopiesOnlyTheChosenPage()throws Exception{
        File file=pdf(3,false);AtomicReference<PdfViewerActivity> current=new AtomicReference<>();AtomicReference<File> exported=new AtomicReference<>();
        try(ActivityScenario<PdfViewerActivity> scenario=open(file,current)){
            ready(current,3);scenario.onActivity(a->call(a,"chooseTextPages",new Class<?>[0]));View chooser=awaitWindow(UiText.get(R.string.ui_extract_text_selected_pages));
            scenario.onActivity(a->{ListView list=descendant(chooser,ListView.class);assertTrue(list.isItemChecked(0));Button all=chooser.findViewById(android.R.id.button3);all.performClick();for(int i=0;i<3;i++)assertTrue(list.isItemChecked(i));all.performClick();for(int i=0;i<3;i++)assertFalse(list.isItemChecked(i));positive(chooser);assertTrue(chooser.isShown());assertNull(field(a,"textExport"));list.performItemClick(list.getChildAt(1),1,list.getAdapter().getItemId(1));positive(chooser);});
            UiTestWait.until(()->field(current.get(),"textExport")!=null,"Selected reader page was not extracted");exported.set((File)field(current.get(),"textExport"));String selected=new String(Files.readAllBytes(exported.get().toPath()),StandardCharsets.UTF_8);assertTrue(selected,selected.contains("Reader page 2"));assertFalse(selected.contains("Reader page 1"));assertFalse(selected.contains("Reader page 3"));
            View result=awaitWindow(UiText.format(R.string.ui_text_from_value_page_s,1));scenario.onActivity(a->{TextView copy=text(result,UiText.get(R.string.ui_copy_all));assertNotNull(copy);copy.performClick();ClipboardManager clipboard=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);assertEquals(selected,clipboard.getPrimaryClip().getItemAt(0).getText().toString());});
        }finally{file.delete();if(exported.get()!=null)exported.get().delete();}
    }

    @Test public void searchingFindsNativeTextAndAnUnknownQueryOffersTheNoMatchState()throws Exception{
        File file=pdf(2,false);AtomicReference<PdfViewerActivity> current=new AtomicReference<>();
        try(ActivityScenario<PdfViewerActivity> scenario=open(file,current)){
            ready(current,2);scenario.onActivity(a->call(a,"findText",new Class<?>[0]));View find=awaitWindow(UiText.get(R.string.ui_find_in_document));scenario.onActivity(a->{descendant(find,EditText.class).setText("Alpha");positive(find);});
            View results=awaitWindow(UiText.format(R.string.ui_value_matching_page_s,1));UiTestWait.until(()->descendant(results,ListView.class).getChildCount()>0,"Search result row did not attach");
            scenario.onActivity(a->{ListView list=descendant(results,ListView.class);assertEquals(1,list.getAdapter().getCount());list.performItemClick(list.getChildAt(0),0,list.getAdapter().getItemId(0));});UiTestWait.until(()->field(current.get(),"selection")!=null,"Search hit did not open text selection");
            scenario.onActivity(a->{assertEquals(0,field(a,"currentPage"));assertFalse(((List<?>)field(field(a,"selection"),"highlights")).isEmpty());assertEquals("",call(field(a,"selection"),"selectedText",new Class<?>[0]));a.closeTextSelection();call(a,"findText",new Class<?>[0]);});
            View again=awaitWindow(UiText.get(R.string.ui_find_in_document));scenario.onActivity(a->{descendant(again,EditText.class).setText("not-in-this-document-xyz");positive(again);});View empty=awaitWindow(UiText.get(R.string.ui_no_matches));scenario.onActivity(a->{assertNull(field(a,"selection"));positive(empty);});
        }finally{file.delete();}
    }

    @Test public void documentToolsPassTheCurrentPathNameAndPasswordToTheirSpecificScreens()throws Exception{
        File file=pdf(1,false);AtomicReference<PdfViewerActivity> current=new AtomicReference<>();List<Intent> opened=new CopyOnWriteArrayList<>();
        Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){if(intent.getComponent()!=null&&!new ComponentName(context,PdfViewerActivity.class).equals(intent.getComponent())){opened.add(intent);return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);}return null;}};inst.addMonitor(monitor);
        try(ActivityScenario<PdfViewerActivity> scenario=open(file,current)){
            ready(current,1);String[] operations={"PDF page settings","To Excel","To PowerPoint","Batch OCR","Collage","Erase Marks","Rotate PDF","Conversion history"};Class<?>[] screens={PdfLayoutActivity.class,ExtendedToolsActivity.class,ExtendedToolsActivity.class,ExtendedToolsActivity.class,ExtendedToolsActivity.class,EraseMarksActivity.class,ToolWorkflowActivity.class,ConversionHistoryActivity.class};
            for(int i=0;i<operations.length;i++){String operation=operations[i];scenario.onActivity(a->call(a,"openDocumentTool",new Class<?>[]{String.class},operation));Intent intent=opened.get(opened.size()-1);assertEquals(new ComponentName(context,screens[i]),intent.getComponent());if(!operation.equals("Conversion history")){assertEquals(file.getPath(),intent.getStringExtra("path"));assertEquals("",intent.getStringExtra("pdf_password"));assertNotNull(intent.getStringExtra("name"));}}
            assertEquals(operations.length,opened.size());
        }finally{inst.removeMonitor(monitor);file.delete();}
    }
}

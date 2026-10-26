package com.example.myapplication;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.*;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Real staged exports: inspect PDF bytes, passwords, page text and archive images. */
public class PdfToolExportFlowTest {
    final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    final Context context=inst.getTargetContext();
    final List<File> owned=new ArrayList<>();
    final List<ExecutorService> workers=new ArrayList<>();
    ActivityScenario<ToolWorkflowActivity> scenario;
    ToolWorkflowActivity activity;
    File input;
    byte[] original;
    Instrumentation.ActivityMonitor monitor;
    final List<Intent> outgoing=new CopyOnWriteArrayList<>();
    boolean hadCopy,previousCopy;
    boolean restrictedSource;
    AccessPermission sourcePermissions(){AccessPermission permissions=new AccessPermission();if(restrictedSource){permissions.setCanPrint(false);permissions.setCanExtractContent(false);permissions.setCanAssembleDocument(true);}return permissions;}
    @Before public void prepare(){PDFBoxResourceLoader.init(context);SharedPreferences prefs=context.getSharedPreferences("paperflow_tools",0);hadCopy=prefs.contains("compress_save_copy");previousCopy=prefs.getBoolean("compress_save_copy",true);prefs.edit().putBoolean("compress_save_copy",false).commit();}
    @After public void close()throws Exception{
        if(monitor!=null)inst.removeMonitor(monitor);if(scenario!=null)scenario.close();for(ExecutorService worker:workers)assertTrue("PDF tool worker did not stop",worker.awaitTermination(15,TimeUnit.SECONDS));
        try(DocumentStore store=new DocumentStore(context)){for(File file:owned){store.db().delete("docs","path=?",new String[]{file.getPath()});store.db().delete("document_guards","path=?",new String[]{file.getPath()});file.delete();}}
        SharedPreferences.Editor edit=context.getSharedPreferences("paperflow_tools",0).edit();if(hadCopy)edit.putBoolean("compress_save_copy",previousCopy);else edit.remove("compress_save_copy");edit.commit();
    }
    Object field(Object owner,String name){try{java.lang.reflect.Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}catch(Exception error){throw new AssertionError(error);}}
    void remember(){for(String name:new String[]{"worker","previewWorker"}){ExecutorService worker=(ExecutorService)field(activity,name);if(!workers.contains(worker))workers.add(worker);}}
    <T>T main(Callable<T> action){scenario.onActivity(a->{activity=a;remember();});AtomicReference<T> result=new AtomicReference<>();inst.runOnMainSync(()->{try{result.set(action.call());}catch(Exception error){throw new AssertionError(error);}});return result.get();}
    void open(String operation,boolean encrypted)throws Exception{
        open(operation,encrypted,false);
    }
    void open(String operation,boolean encrypted,boolean marked)throws Exception{
        input=File.createTempFile("pdf-tool-flow-",".pdf",context.getCacheDir());owned.add(input);
        try(PDDocument doc=new PDDocument()){for(int i=0;i<3;i++){PDPage page=new PDPage(new PDRectangle(160,240));doc.addPage(page);try(PDPageContentStream stream=new PDPageContentStream(doc,page)){stream.beginText();stream.setFont(PDType1Font.HELVETICA,12);stream.newLineAtOffset(12,180);stream.showText("Export page "+(i+1));stream.endText();}}if(encrypted){StandardProtectionPolicy policy=new StandardProtectionPolicy("fixture-owner","fixture-user",sourcePermissions());policy.setEncryptionKeyLength(128);policy.setPreferAES(true);doc.protect(policy);}doc.save(input);}
        if(marked){ByteArrayOutputStream seeded=new ByteArrayOutputStream();try(PDDocument doc=PDDocument.load(input,encrypted?"fixture-user":"")){for(int i=0;i<3;i++)PdfWatermark.append(doc,i,"Existing watermark",Color.BLACK,40,30,0,false,30);if(encrypted){StandardProtectionPolicy policy=new StandardProtectionPolicy("fixture-owner","fixture-user",sourcePermissions());policy.setEncryptionKeyLength(128);policy.setPreferAES(true);doc.protect(policy);}doc.save(seeded);}Files.write(input.toPath(),seeded.toByteArray());}
        original=Files.readAllBytes(input.toPath());scenario=ActivityScenario.launch(new Intent(context,ToolWorkflowActivity.class).putExtra("operation",operation).putExtra("path",input.getPath()).putExtra("name","Export fixture").putExtra("pdf_password",encrypted?"fixture-user":""));scenario.onActivity(a->{activity=a;remember();});if(operation.equals("Merge Files"))UiTestWait.until(()->"source".equals(field(activity,"stage")),"Merge source list did not open");else options();
    }
    void options(){UiTestWait.until(()->"options".equals(field(activity,"stage")),"PDF tool options did not open");inst.waitForIdleSync();}
    <T extends View>T find(View root,Class<T> type){if(type.isInstance(root))return type.cast(root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){T view=find(((ViewGroup)root).getChildAt(i),type);if(view!=null)return view;}return null;}
    View action(View root,String value){if(root.isClickable()&&root.isShown()&&(value.contentEquals(root.getContentDescription()==null?"":root.getContentDescription())||root instanceof TextView&&value.contentEquals(((TextView)root).getText())))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View view=action(((ViewGroup)root).getChildAt(i),value);if(view!=null)return view;}return null;}
    void click(int label){main(()->{View view=action(activity.getWindow().getDecorView(),UiText.get(label));assertNotNull("Missing PDF action "+label,view);assertTrue(view.performClick());return null;});inst.waitForIdleSync();}
    void primary(){main(()->{View button=(View)field(activity,"primary");assertTrue(button.isShown());assertTrue(button.isEnabled());assertTrue(button.performClick());return null;});inst.waitForIdleSync();}
    void page(int index){main(()->{List<?> checks=(List<?>)field(activity,"pageChecks");CheckBox check=(CheckBox)checks.get(index);boolean previous=check.isChecked();check.performClick();assertEquals(!previous,check.isChecked());assertEquals(check.isChecked(),((Set<?>)field(activity,"selectedPages")).contains(index));return null;});}
    File process(){primary();return result();}
    File result(){if("OCR PDF".equals(field(activity,"operation"))){long deadline=android.os.SystemClock.elapsedRealtime()+90000;while(!main(()->"result".equals(field(activity,"stage")))&&android.os.SystemClock.elapsedRealtime()<deadline)android.os.SystemClock.sleep(30);assertEquals("OCR PDF did not produce a result","result",main(()->field(activity,"stage")));}else UiTestWait.until(()->"result".equals(field(activity,"stage")),"PDF tool did not produce a result");File file=main(()->(File)field(activity,"resultFile"));assertNotNull(file);assertTrue(file.isFile());owned.add(file);return file;}
    List<String> pageText(File file,String password)throws Exception{try(PDDocument doc=PDDocument.load(file,password)){List<String> text=new ArrayList<>();PDFTextStripper strip=new PDFTextStripper();for(int i=1;i<=doc.getNumberOfPages();i++){strip.setStartPage(i);strip.setEndPage(i);text.add(strip.getText(doc).trim());}return text;}}
    void unchanged()throws Exception{assertArrayEquals("An export must leave the original PDF untouched",original,Files.readAllBytes(input.toPath()));}
    void intercept(){monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){if(Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction())||Intent.ACTION_CHOOSER.equals(intent.getAction())||new ComponentName(context,MainActivity.class).equals(intent.getComponent())){outgoing.add(intent);return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);}return null;}};inst.addMonitor(monitor);}
    androidx.appcompat.app.AlertDialog watermarkDialog(){return (androidx.appcompat.app.AlertDialog)field(activity,"watermarkDialog");}
    void editWatermark(){click(R.string.ui_edit_watermark_text);UiTestWait.until(()->watermarkDialog()!=null&&watermarkDialog().isShowing()&&watermarkDialog().getWindow().getDecorView().hasWindowFocus(),"Watermark text dialog did not attach");}
    void watermarkButton(int id){main(()->{assertTrue(watermarkDialog().getButton(id).performClick());return null;});inst.waitForIdleSync();}
    File importFixture()throws Exception{
        File directory=new File(context.getCacheDir(),"translation-exports");assertTrue(directory.isDirectory()||directory.mkdirs());File file=File.createTempFile("imported-pdf-flow-",".pdf",directory);owned.add(file);try(PDDocument doc=new PDDocument()){PDPage page=new PDPage(new PDRectangle(160,240));doc.addPage(page);try(PDPageContentStream text=new PDPageContentStream(doc,page)){text.beginText();text.setFont(PDType1Font.HELVETICA,12);text.newLineAtOffset(12,180);text.showText("Imported page");text.endText();}doc.save(file);}return file;
    }
    Instrumentation.ActivityResult pickerResult(ClipData clip){Intent result=new Intent();result.setClipData(clip);return new Instrumentation.ActivityResult(Activity.RESULT_OK,result);}
    void importThroughPicker(File file,boolean invalid){
        android.net.Uri uri=androidx.core.content.FileProvider.getUriForFile(context,context.getPackageName()+".files",file);ClipData clip=new ClipData("PDF import",new String[]{"application/pdf"},new ClipData.Item(uri));if(invalid)clip.addItem(new ClipData.Item(android.net.Uri.parse("content://missing.paperflow.test/not-found")));
        monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){if(Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction())){outgoing.add(intent);return pickerResult(clip);}return null;}};inst.addMonitor(monitor);click(R.string.ui_import_pdf_files);
        UiTestWait.until(()->"source".equals(field(activity,"stage"))&&((List<?>)field(activity,"sources")).size()==2,"PDF import did not return to the source list");inst.removeMonitor(monitor);monitor=null;
        main(()->{for(Object source:(List<?>)field(activity,"sources")){File imported=new File((String)field(source,"path"));if(!imported.equals(input)&&!owned.contains(imported))owned.add(imported);}return null;});assertEquals(1,outgoing.size());assertTrue(outgoing.get(0).getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false));
    }

    @Test public void importedPdfCanBeReorderedBeforeMergingWithoutChangingEitherSource()throws Exception{
        open("Merge Files",false);File imported=importFixture();byte[] bytes=Files.readAllBytes(imported.toPath());importThroughPicker(imported,false);primary();options();click(R.string.ui_move_down);File output=process();assertEquals(Arrays.asList("Imported page","Export page 1","Export page 2","Export page 3"),pageText(output,""));assertArrayEquals(bytes,Files.readAllBytes(imported.toPath()));unchanged();
    }
    @Test public void mergingAnEncryptedSourceRequiresANewPasswordAndKeepsAllPages()throws Exception{
        open("Merge Files",true);importThroughPicker(importFixture(),false);primary();options();main(()->{assertNotNull(field(activity,"passwordInput"));((EditText)field(activity,"passwordInput")).setText("merged-secret");((EditText)field(activity,"confirmationInput")).setText("merged-secret");return null;});File output=process();assertThrows(InvalidPasswordException.class,()->PDDocument.load(output));assertEquals(Arrays.asList("Export page 1","Export page 2","Export page 3","Imported page"),pageText(output,"merged-secret"));try(PDDocument doc=PDDocument.load(output,"merged-secret")){assertTrue(doc.isEncrypted());assertTrue(doc.getCurrentAccessPermission().canPrint());}unchanged();
    }
    @Test public void mergingCannotBroadenAProtectedSourcesPrintOrExtractionPermissions()throws Exception{
        restrictedSource=true;open("Merge Files",true);importThroughPicker(importFixture(),false);primary();options();main(()->{((EditText)field(activity,"passwordInput")).setText("merged-secret");((EditText)field(activity,"confirmationInput")).setText("merged-secret");return null;});File output=process();try(PDDocument doc=PDDocument.load(output,"merged-secret")){assertEquals(4,doc.getNumberOfPages());assertFalse(doc.getCurrentAccessPermission().canPrint());assertFalse(doc.getCurrentAccessPermission().canExtractContent());assertTrue(doc.getCurrentAccessPermission().canAssembleDocument());}unchanged();
    }
    @Test public void aFailedImportDoesNotDiscardTheSuccessfulPdfOrLeaveAPartialCopy()throws Exception{
        open("Merge Files",false);File directory=new File(context.getFilesDir(),"documents");Set<String> previous=new HashSet<>();File[] old=directory.listFiles((dir,name)->name.startsWith("paperflow-source-"));if(old!=null)for(File file:old)previous.add(file.getName());importThroughPicker(importFixture(),true);
        assertEquals(2,main(()->((List<?>)field(activity,"selectedSources")).size()).intValue());File[] current=directory.listFiles((dir,name)->name.startsWith("paperflow-source-")&&!previous.contains(name));assertNotNull(current);assertEquals("Only the successful private copy may remain",1,current.length);assertEquals(Collections.singletonList("Imported page"),pageText(current[0],""));unchanged();
    }
    @Test public void mergingOneSourceOrCancellingImportCannotStartAnExport()throws Exception{
        open("Merge Files",false);primary();assertEquals("source",main(()->field(activity,"stage")));assertNull(main(()->field(activity,"resultFile")));monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){if(Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction())){outgoing.add(intent);return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);}return null;}};inst.addMonitor(monitor);click(R.string.ui_import_pdf_files);assertEquals(1,outgoing.size());assertEquals(1,main(()->((List<?>)field(activity,"sources")).size()).intValue());assertEquals(1,main(()->((List<?>)field(activity,"selectedSources")).size()).intValue());unchanged();
    }
    @Test public void savingThroughTheDocumentPickerCopiesTheExactExportBytes()throws Exception{
        open("Extract PDF Pages",false);File output=process();File directory=new File(context.getCacheDir(),"translation-exports");assertTrue(directory.isDirectory()||directory.mkdirs());File destination=File.createTempFile("pdf-tool-device-",".pdf",directory);owned.add(destination);android.net.Uri uri=androidx.core.content.FileProvider.getUriForFile(context,context.getPackageName()+".files",destination);
        monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){if(Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction())){outgoing.add(intent);return new Instrumentation.ActivityResult(Activity.RESULT_OK,new Intent().setData(uri));}return null;}};inst.addMonitor(monitor);primary();((ExecutorService)field(activity,"worker")).submit(()->{}).get(10,TimeUnit.SECONDS);assertEquals(1,outgoing.size());assertArrayEquals(Files.readAllBytes(output.toPath()),Files.readAllBytes(destination.toPath()));unchanged();
    }
    @Test public void finishingAnExportPassesItsOriginAndPageCountToTheLibrary()throws Exception{
        open("Extract PDF Pages",false);page(1);File output=process();intercept();click(R.string.ui_save_to_documents);assertEquals(1,outgoing.size());Intent registration=outgoing.get(0);assertEquals("Register",registration.getStringExtra("document_action"));assertEquals(output.getPath(),registration.getStringExtra("path"));assertEquals(2,registration.getIntExtra("pages",0));assertEquals(Collections.singletonList(input.getPath()),registration.getStringArrayListExtra("origin_paths"));assertTrue(activity.isFinishing());unchanged();
    }
    @Test public void backDuringProcessingKeepsTheJobAndResultBackReturnsToItsOptions()throws Exception{
        open("Extract PDF Pages",false);CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);((ExecutorService)field(activity,"worker")).execute(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(entered.await(5,TimeUnit.SECONDS));
        try{primary();main(()->{activity.handleBackPressed();return null;});assertEquals("progress",main(()->field(activity,"stage")));assertFalse(activity.isFinishing());}finally{release.countDown();}File output=result();click(R.string.ui_back);options();assertEquals(3,main(()->((Set<?>)field(activity,"selectedPages")).size()).intValue());click(R.string.ui_back);assertEquals("source",main(()->field(activity,"stage")));assertEquals(1,main(()->((List<?>)field(activity,"selectedSources")).size()).intValue());assertTrue(output.isFile());unchanged();
    }
    void withEnglishOcr(Callable<Void> action)throws Exception{
        SharedPreferences prefs=context.getSharedPreferences("local-languages",0);boolean present=prefs.contains("ocr-enabled");Set<String> previous=new HashSet<>(prefs.getStringSet("ocr-enabled",Collections.emptySet()));prefs.edit().putStringSet("ocr-enabled",Collections.singleton("en")).commit();
        try{assertEquals("The offline English OCR fixture must be installed","en",com.paperflow.scanengine.LocalOcr.activeLanguages(context));action.call();}finally{SharedPreferences.Editor edit=prefs.edit();if(present)edit.putStringSet("ocr-enabled",previous);else edit.remove("ocr-enabled");edit.commit();}
    }
    @Test public void offlineOcrExportsOnlyTheCheckedPageAndPreservesItsExistingText()throws Exception{
        withEnglishOcr(()->{open("OCR PDF",false);assertTrue(((Set<?>)field(activity,"selectedPages")).isEmpty());page(1);File output=process();assertEquals(Collections.singletonList("Export page 2"),pageText(output,""));unchanged();return null;});
    }
    @Test public void cancellingQueuedOfflineOcrDoesNotLeaveAPartialPdf()throws Exception{
        withEnglishOcr(()->{open("OCR PDF",false);page(0);File directory=new File(context.getFilesDir(),"documents");Set<String> previous=new HashSet<>();File[] before=directory.listFiles((dir,name)->name.startsWith("paperflow-tool-"));if(before!=null)for(File file:before)previous.add(file.getName());CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);((ExecutorService)field(activity,"worker")).execute(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(entered.await(5,TimeUnit.SECONDS));
            try{primary();click(R.string.ui_cancel_ocr);assertEquals(true,main(()->field(activity,"ocrCancelled")));}finally{release.countDown();}options();assertNull(main(()->field(activity,"resultFile")));File[] after=directory.listFiles((dir,name)->name.startsWith("paperflow-tool-")&&!previous.contains(name));assertNotNull(after);assertEquals(0,after.length);unchanged();return null;});
    }

    @Test public void watermarkTextValidatesBlankInputAndLimitsLengthBeforeExportingSelectedPages()throws Exception{
        open("Add Watermark",false);editWatermark();main(()->{EditText edit=(EditText)field(activity,"watermarkDraftInput");edit.setText("   ");watermarkDialog().getButton(-1).performClick();assertNotNull(edit.getError());edit.setText(String.join("",Collections.nCopies(60,"X")));assertEquals(50,edit.length());edit.setText("  Reviewed copy  ");return null;});watermarkButton(-1);assertEquals("Reviewed copy",main(()->field(activity,"watermark")));page(1);File output=process();
        try(PDDocument doc=PDDocument.load(output)){assertEquals("Reviewed copy",PdfWatermark.settings(doc.getPage(0)).getString("text"));assertNull(PdfWatermark.settings(doc.getPage(1)));assertEquals("Reviewed copy",PdfWatermark.settings(doc.getPage(2)).getString("text"));}assertEquals(Arrays.asList("Export page 1","Export page 2","Export page 3"),pageText(output,""));unchanged();
    }
    @Test public void cancellingAWatermarkDraftPreservesTheCommittedText()throws Exception{
        open("Add Watermark",false);String previous=main(()->(String)field(activity,"watermark"));editWatermark();main(()->{((EditText)field(activity,"watermarkDraftInput")).setText("Unsaved edit");return null;});watermarkButton(-2);assertEquals(previous,main(()->field(activity,"watermark")));assertNull(main(()->field(activity,"watermarkDraftInput")));assertNull(main(()->field(activity,"watermarkDraft")));unchanged();
    }
    @Test public void anOpenWatermarkDraftSurvivesRecreationAndCanBeConfirmed()throws Exception{
        open("Add Watermark",false);editWatermark();main(()->{((EditText)field(activity,"watermarkDraftInput")).setText("Draft across rotation");return null;});scenario.recreate();scenario.onActivity(a->{activity=a;remember();});options();UiTestWait.until(()->watermarkDialog()!=null&&watermarkDialog().isShowing(),"Watermark draft did not reopen");assertEquals("Draft across rotation",main(()->((EditText)field(activity,"watermarkDraftInput")).getText().toString()));watermarkButton(-1);assertEquals("Draft across rotation",main(()->field(activity,"watermark")));unchanged();
    }
    @Test public void tiledWatermarkStyleSurvivesRecreationAndKeepsPdfEncryption()throws Exception{
        open("Add Watermark",true);click(R.string.ui_tile);main(()->{View swatch=action((View)field(activity,"body"),UiText.format(R.string.ui_value_watermark,UiText.label("Red")));assertNotNull(swatch);assertTrue(swatch.performClick());List<SeekBar> sliders=new ArrayList<>();collectSliders((View)field(activity,"body"),sliders);assertEquals(4,sliders.size());int[] values={55,20,75,40};for(int i=0;i<values.length;i++)sliders.get(i).setProgress(values[i]);return null;});scenario.recreate();scenario.onActivity(a->{activity=a;remember();});options();main(()->{assertEquals(true,field(activity,"watermarkTile"));assertEquals(0xffe55050,field(activity,"watermarkColor"));assertEquals(55,field(activity,"opacity"));assertEquals(90,field(activity,"angle"));assertEquals(40,field(activity,"watermarkSpacing"));return null;});File output=process();assertThrows(InvalidPasswordException.class,()->PDDocument.load(output));try(PDDocument doc=PDDocument.load(output,"fixture-user")){assertTrue(doc.isEncrypted());org.json.JSONObject settings=PdfWatermark.settings(doc.getPage(0));assertEquals(0xffe55050,settings.getInt("color"));assertEquals(55,settings.getInt("opacity"));assertTrue(settings.getBoolean("tile"));assertEquals(90,settings.getInt("angle"));}unchanged();
    }
    void collectSliders(View root,List<SeekBar> sliders){if(root instanceof SeekBar)sliders.add((SeekBar)root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)collectSliders(((ViewGroup)root).getChildAt(i),sliders);}
    @Test public void removingAnExistingWatermarkChangesOnlyTheCheckedPages()throws Exception{
        open("Add Watermark",false,true);assertEquals("Existing watermark",main(()->field(activity,"watermark")));main(()->{CheckBox remove=(CheckBox)action((View)field(activity,"body"),UiText.get(R.string.ui_remove_paperflow_watermark_from_selected_pages));assertNotNull(remove);remove.performClick();assertTrue(remove.isChecked());return null;});page(1);File output=process();try(PDDocument doc=PDDocument.load(output)){assertNull(PdfWatermark.settings(doc.getPage(0)));assertEquals("Existing watermark",PdfWatermark.settings(doc.getPage(1)).getString("text"));assertNull(PdfWatermark.settings(doc.getPage(2)));}unchanged();
    }

    @Test public void extractingCheckedPagesPreservesTheirOrderAndTheSource()throws Exception{
        open("Extract PDF Pages",false);page(1);File output=process();assertEquals(Arrays.asList("Export page 1","Export page 3"),pageText(output,""));unchanged();
    }
    @Test public void reorderingWithTheVisibleArrowChangesExportedPageOrder()throws Exception{
        open("Reorder Pages",false);click(R.string.ui_move_down);assertEquals(Arrays.asList(1,0,2),main(()->field(activity,"pageOrder")));File output=process();assertEquals(Arrays.asList("Export page 2","Export page 1","Export page 3"),pageText(output,""));unchanged();
    }
    @Test public void lockValidatesBothPasswordFieldsAndProducesAnEncryptedCopy()throws Exception{
        open("Lock",false);main(()->{((EditText)field(activity,"passwordInput")).setText("short");return null;});primary();main(()->{assertNotNull(((EditText)field(activity,"passwordInput")).getError());((EditText)field(activity,"passwordInput")).setText("new-secret");((EditText)field(activity,"confirmationInput")).setText("mismatch");return null;});primary();main(()->{assertNotNull(((EditText)field(activity,"confirmationInput")).getError());((EditText)field(activity,"confirmationInput")).setText("new-secret");return null;});File output=process();assertThrows(InvalidPasswordException.class,()->PDDocument.load(output,"wrong"));try(PDDocument doc=PDDocument.load(output,"new-secret")){assertTrue(doc.isEncrypted());assertEquals(3,doc.getNumberOfPages());}assertEquals(Arrays.asList("Export page 1","Export page 2","Export page 3"),pageText(output,"new-secret"));unchanged();
    }
    @Test public void removingAPasswordProducesAnUnlockedCopyAndKeepsTheLockedOriginal()throws Exception{
        open("Remove PDF password",true);File output=process();try(PDDocument doc=PDDocument.load(output)){assertFalse(doc.isEncrypted());assertEquals(3,doc.getNumberOfPages());}assertThrows(InvalidPasswordException.class,()->PDDocument.load(input));unchanged();
    }
    @Test public void pngArchiveContainsOnlySelectedPagesAndDecodablePngImages()throws Exception{archive(true);}
    @Test public void jpegArchiveUsesTheSelectedQualityAndKeepsPageNames()throws Exception{archive(false);}
    void archive(boolean png)throws Exception{
        open("PDF to Images",false);page(1);main(()->{if(png)find((View)field(activity,"body"),Spinner.class).setSelection(1);find((View)field(activity,"body"),SeekBar.class).setProgress(75);return null;});UiTestWait.until(()->(png?"PNG":"JPEG").equals(field(activity,"imageFormat")),"Image format did not update");assertEquals(75,main(()->(Integer)field(activity,"quality")).intValue());File output=process();
        try(ZipFile zip=new ZipFile(output)){assertEquals(2,zip.size());for(int index:new int[]{1,3}){ZipEntry entry=zip.getEntry("page-"+index+(png?".png":".jpg"));assertNotNull(entry);try(InputStream in=zip.getInputStream(entry)){byte[] magic=new byte[4];assertEquals(4,in.read(magic));if(png)assertArrayEquals(new byte[]{(byte)137,80,78,71},magic);else{assertEquals((byte)255,magic[0]);assertEquals((byte)216,magic[1]);}}try(InputStream in=zip.getInputStream(entry)){Bitmap bitmap=BitmapFactory.decodeStream(in);assertNotNull(bitmap);try{assertTrue(bitmap.getWidth()>0);assertEquals(1.5f,bitmap.getHeight()/(float)bitmap.getWidth(),.001f);}finally{bitmap.recycle();}}}}unchanged();
    }
    @Test public void compressingAnAlreadySmallTextPdfKeepsItsOriginalBytes()throws Exception{
        open("Compress",false);main(()->{View card=action((View)field(activity,"body"),UiText.format(R.string.ui_value_compression,"High"));assertNotNull(card);assertTrue(card.performClick());return null;});assertEquals(1,main(()->(Integer)field(activity,"preset")).intValue());File output=process();assertArrayEquals(original,Files.readAllBytes(output.toPath()));assertFalse((Boolean)main(()->field(activity,"compressionRegistered")));unchanged();
    }
    @Test public void resultNavigationAndRecreationKeepTheOutputAndReleaseOldPreviews()throws Exception{
        open("Extract PDF Pages",false);File output=process();Bitmap first=main(()->(Bitmap)field(activity,"resultBitmap"));click(R.string.ui_next_page);UiTestWait.until(()->field(activity,"resultBitmap")!=first,"Result page preview did not change");assertTrue(first.isRecycled());assertEquals(1,main(()->(Integer)field(activity,"resultPage")).intValue());Bitmap second=main(()->(Bitmap)field(activity,"resultBitmap"));scenario.recreate();scenario.onActivity(a->{activity=a;remember();});UiTestWait.until(()->"result".equals(field(activity,"stage")),"Export result did not restore");assertEquals(output,main(()->field(activity,"resultFile")));assertEquals(1,main(()->(Integer)field(activity,"resultPage")).intValue());assertTrue(second.isRecycled());assertEquals(3,pageText(output,"").size());unchanged();
    }
    @Test public void sharingAndCancellingSaveKeepTheExportAndGrantReadAccessOnly()throws Exception{
        open("Extract PDF Pages",false);File output=process();byte[] bytes=Files.readAllBytes(output.toPath());intercept();click(R.string.ui_share);assertEquals(1,outgoing.size());Intent send=outgoing.get(0).getParcelableExtra(Intent.EXTRA_INTENT);assertEquals("application/pdf",send.getType());assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,send.getFlags());assertEquals(androidx.core.content.FileProvider.getUriForFile(context,context.getPackageName()+".files",output),send.getParcelableExtra(Intent.EXTRA_STREAM));primary();assertEquals(2,outgoing.size());Intent save=outgoing.get(1);assertEquals(Intent.ACTION_CREATE_DOCUMENT,save.getAction());assertEquals("application/pdf",save.getType());assertTrue(save.getStringExtra(Intent.EXTRA_TITLE).endsWith(".pdf"));assertArrayEquals(bytes,Files.readAllBytes(output.toPath()));assertEquals("result",main(()->field(activity,"stage")));unchanged();
    }
    @Test public void anEmptyPageSelectionCannotStartAnExportButCanBeCorrected()throws Exception{
        open("Extract PDF Pages",false);for(int i=0;i<3;i++)page(i);primary();assertEquals("options",main(()->field(activity,"stage")));assertNull(main(()->field(activity,"resultFile")));page(2);File output=process();assertEquals(Collections.singletonList("Export page 3"),pageText(output,""));unchanged();
    }
}

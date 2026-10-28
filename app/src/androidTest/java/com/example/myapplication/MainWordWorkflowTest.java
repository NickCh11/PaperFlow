package com.example.myapplication;

import android.app.*;
import android.content.*;
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
import java.util.zip.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Main-screen Word conversion, including real DOCX output and retained lifecycle state. */
public class MainWordWorkflowTest {
    final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    final Context context=inst.getTargetContext();
    final List<File> owned=new ArrayList<>();
    final List<ExecutorService> workers=new ArrayList<>();
    final List<CountDownLatch> releases=new ArrayList<>();
    final List<Intent> outgoing=new CopyOnWriteArrayList<>();
    ActivityScenario<MainActivity> scenario;
    MainActivity activity;
    MainActivity.WordState task;
    Instrumentation.ActivityMonitor monitor;
    File input;
    byte[] original;
    boolean hadCamera,camera,restricted;
    String name="Word workflow";
    @Before public void prepare(){PDFBoxResourceLoader.init(context);SharedPreferences prefs=context.getSharedPreferences("scan_defaults",0);hadCamera=prefs.contains("launch_camera");camera=prefs.getBoolean("launch_camera",false);prefs.edit().putBoolean("launch_camera",false).commit();}
    @After public void close()throws Exception{
        for(CountDownLatch release:releases)release.countDown();if(monitor!=null)inst.removeMonitor(monitor);if(scenario!=null)scenario.close();for(ExecutorService worker:workers)assertTrue("Main-screen worker did not stop",worker.awaitTermination(15,TimeUnit.SECONDS));
        if(input!=null){org.json.JSONArray history=ConversionHistory.list(context);for(int i=0;i<history.length();i++){org.json.JSONObject entry=history.getJSONObject(i);org.json.JSONArray origins=entry.optJSONArray("origins");boolean mine=false;if(origins!=null)for(int j=0;j<origins.length();j++)mine|=input.getPath().equals(origins.getString(j));if(mine){owned.add(new File(entry.getString("path")));owned.add(new File(entry.getString("source_result")));ConversionHistory.delete(context,entry.getString("id"));}}}
        try(DocumentStore store=new DocumentStore(context)){for(File file:owned){store.db().delete("document_guards","path=?",new String[]{file.getPath()});store.db().delete("docs","path=?",new String[]{file.getPath()});file.delete();}}
        SharedPreferences.Editor edit=context.getSharedPreferences("scan_defaults",0).edit();if(hadCamera)edit.putBoolean("launch_camera",camera);else edit.remove("launch_camera");edit.commit();
    }
    Object field(Object owner,String name){try{java.lang.reflect.Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}catch(Exception error){throw new AssertionError(error);}}
    void remember(){for(String name:new String[]{"pdfWorker","libraryWorker","thumbnailWorker"}){ExecutorService worker=(ExecutorService)field(activity,name);if(!workers.contains(worker))workers.add(worker);}task=(MainActivity.WordState)field(activity,"wordState");if(!workers.contains(task.worker))workers.add(task.worker);}
    <T>T main(Callable<T> action){scenario.onActivity(a->{activity=a;remember();});AtomicReference<T> value=new AtomicReference<>();inst.runOnMainSync(()->{try{value.set(action.call());}catch(Exception error){throw new AssertionError(error);}});return value.get();}
    void open(boolean encrypted,String password)throws Exception{
        File dir=new File(context.getFilesDir(),"documents");assertTrue(dir.isDirectory()||dir.mkdirs());input=File.createTempFile("main-word-flow-",".pdf",dir);owned.add(input);
        try(PDDocument doc=new PDDocument()){for(int i=1;i<=2;i++){PDPage page=new PDPage(new PDRectangle(160,240));doc.addPage(page);try(PDPageContentStream text=new PDPageContentStream(doc,page)){text.beginText();text.setFont(PDType1Font.HELVETICA,12);text.newLineAtOffset(12,180);text.showText("Workflow page "+i+" & value");text.endText();}}if(encrypted){AccessPermission permissions=new AccessPermission();permissions.setCanExtractContent(!restricted);StandardProtectionPolicy policy=new StandardProtectionPolicy("fixture-owner","fixture-user",permissions);policy.setEncryptionKeyLength(128);policy.setPreferAES(true);doc.protect(policy);}doc.save(input);}
        original=Files.readAllBytes(input.toPath());scenario=ActivityScenario.launch(new Intent(context,MainActivity.class).putExtra("document_action","To Word").putExtra("path",input.getPath()).putExtra("name",name).putExtra("pdf_password",password));scenario.onActivity(a->{activity=a;remember();});UiTestWait.until(()->routeRoot()!=null,"Word route chooser did not receive focus");assertFalse(activity.getIntent().hasExtra("pdf_password"));assertFalse(activity.getIntent().hasExtra("document_action"));
    }
    <T extends View>T find(View root,Class<T> type){if(type.isInstance(root))return type.cast(root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){T found=find(((ViewGroup)root).getChildAt(i),type);if(found!=null)return found;}return null;}
    View routeRoot(){for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews())if(root!=activity.getWindow().getDecorView()&&root.isShown()&&root.hasWindowFocus()&&find(root,RadioGroup.class)!=null)return root;return null;}
    void routeButton(int id){main(()->{View root=routeRoot();assertNotNull(root);Button button=root.findViewById(id);assertNotNull(button);assertTrue(button.performClick());return null;});inst.waitForIdleSync();}
    void convert(int route){main(()->{RadioGroup group=find(routeRoot(),RadioGroup.class);assertNotNull(group);RadioButton selected=group.findViewById(group.getCheckedRadioButtonId());assertEquals(WordConversion.AUTO,selected.getTag());for(int i=0;i<group.getChildCount();i++){RadioButton option=(RadioButton)group.getChildAt(i);if(Integer.valueOf(route).equals(option.getTag())){option.performClick();assertTrue(option.isChecked());assertEquals(option.getId(),group.getCheckedRadioButtonId());return null;}}throw new AssertionError("Missing Word route "+route);});routeButton(android.R.id.button1);}
    Dialog dialog(String field){return (Dialog)field(activity,field);}
    void awaitDialog(String field,int id){UiTestWait.until(()->{Dialog dialog=dialog(field);if(dialog==null||!dialog.isShowing()||!dialog.getWindow().getDecorView().hasWindowFocus())return false;Button button=dialog.findViewById(id);return button!=null&&button.isShown()&&button.isEnabled();},"Word dialog did not attach: "+field);}
    void dialogButton(String field,int id){awaitDialog(field,id);main(()->{Button button=dialog(field).findViewById(id);assertTrue(button.performClick());return null;});inst.waitForIdleSync();}
    WordConversion.Result converted(){awaitDialog("wordResultDialog",android.R.id.button1);WordConversion.Result result=main(()->task.result.getValue());assertNotNull(result);owned.add(result.file);assertTrue(result.file.isFile());assertFalse(task.running);return result;}
    String xml(File file)throws Exception{try(ZipFile zip=new ZipFile(file)){assertNotNull(zip.getEntry("[Content_Types].xml"));ZipEntry document=zip.getEntry("word/document.xml");assertNotNull(document);try(InputStream in=zip.getInputStream(document)){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);javax.xml.parsers.DocumentBuilderFactory factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);assertEquals("document",factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes.toByteArray())).getDocumentElement().getLocalName());return bytes.toString("UTF-8");}}}
    void unchanged()throws Exception{assertArrayEquals(original,Files.readAllBytes(input.toPath()));}
    List<org.json.JSONObject> history()throws Exception{List<org.json.JSONObject> own=new ArrayList<>();org.json.JSONArray rows=ConversionHistory.list(context);for(int i=0;i<rows.length();i++){org.json.JSONObject row=rows.getJSONObject(i);org.json.JSONArray origins=row.optJSONArray("origins");if(origins!=null)for(int j=0;j<origins.length();j++)if(input.getPath().equals(origins.getString(j)))own.add(row);}return own;}
    void intercept(){monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){if(Intent.ACTION_CHOOSER.equals(intent.getAction())||Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction())||new ComponentName(context,LanguageManagerActivity.class).equals(intent.getComponent())){outgoing.add(intent);return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);}return null;}};inst.addMonitor(monitor);}
    CountDownLatch blockWorker()throws Exception{CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);releases.add(release);task.worker.execute(()->{entered.countDown();try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});assertTrue(entered.await(5,TimeUnit.SECONDS));return release;}
    void stopped(){UiTestWait.until(()->!task.running&&task.error.getValue()==null&&field(activity,"wordProgressDialog")==null,"Word conversion did not stop cleanly");}

    @Test public void cancellingTheRouteChooserStartsNoConversion()throws Exception{open(false,"");routeButton(android.R.id.button2);assertFalse(task.running);assertNull(task.job);assertTrue(history().isEmpty());unchanged();}
    @Test public void missingOcrAutomaticallyOpensLanguagesAndKeepsThePdf()throws Exception{
        SharedPreferences languages=context.getSharedPreferences("local-languages",0);Set<String> previous=new HashSet<>(languages.getStringSet("ocr-enabled",Collections.emptySet()));
        try{
            assertTrue(languages.edit().putStringSet("ocr-enabled",Collections.emptySet()).commit());open(false,"");intercept();convert(WordConversion.LOCAL);
            UiTestWait.until(()->!outgoing.isEmpty()&&!task.running,"Missing OCR did not open Languages");
            assertEquals(1,outgoing.size());assertEquals(new ComponentName(context,LanguageManagerActivity.class),outgoing.get(0).getComponent());assertEquals("ocr",outgoing.get(0).getStringExtra("kind"));assertNull(task.result.getValue());assertNull(task.error.getValue());unchanged();
            scenario.recreate();inst.waitForIdleSync();assertEquals("Cancelling/returning must not reopen Languages",1,outgoing.size());
        }finally{languages.edit().putStringSet("ocr-enabled",previous).commit();}
    }
    @Test public void nativeTextConversionProducesMultipageWordAndAHistoryEntry()throws Exception{
        open(false,"");convert(WordConversion.NATIVE);WordConversion.Result result=converted();assertEquals(2,result.pages);assertEquals(2,result.nativePages);assertEquals(0,result.ocrPages);String document=xml(result.file);assertTrue(document.contains("Workflow page 1 &amp; value"));assertTrue(document.contains("Workflow page 2 &amp; value"));assertTrue(document.contains("w:type=\"page\""));assertEquals(1,history().size());assertEquals(result.file.getPath(),history().get(0).getString("source_result"));dialogButton("wordResultDialog",android.R.id.button2);assertNull(main(()->task.result.getValue()));unchanged();
    }
    @Test public void sharingWordGrantsOnlyReadAccessToTheActualDocx()throws Exception{
        open(false,"");convert(WordConversion.NATIVE);WordConversion.Result result=converted();intercept();dialogButton("wordResultDialog",android.R.id.button1);assertEquals(1,outgoing.size());Intent send=outgoing.get(0).getParcelableExtra(Intent.EXTRA_INTENT);assertEquals(Intent.ACTION_SEND,send.getAction());assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",send.getType());assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,send.getFlags());assertEquals(androidx.core.content.FileProvider.getUriForFile(context,context.getPackageName()+".files",result.file),send.getParcelableExtra(Intent.EXTRA_STREAM));assertEquals(name+".docx",send.getStringExtra(Intent.EXTRA_SUBJECT));assertNull(main(()->task.result.getValue()));assertTrue(result.file.isFile());unchanged();
    }
    @Test public void cancellingTheSavePickerSanitizesTheNameAndKeepsTheWordFile()throws Exception{
        name="Draft:/?*<>|";open(false,"");convert(WordConversion.NATIVE);WordConversion.Result result=converted();byte[] bytes=Files.readAllBytes(result.file.toPath());intercept();dialogButton("wordResultDialog",android.R.id.button3);assertEquals(1,outgoing.size());Intent save=outgoing.get(0);assertEquals(Intent.ACTION_CREATE_DOCUMENT,save.getAction());assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document",save.getType());assertEquals("Draft_______.docx",save.getStringExtra(Intent.EXTRA_TITLE));assertArrayEquals(bytes,Files.readAllBytes(result.file.toPath()));assertNull(main(()->task.result.getValue()));unchanged();
    }
    @Test public void savingWordCopiesItsExactBytesThroughTheDocumentProvider()throws Exception{
        open(false,"");convert(WordConversion.NATIVE);WordConversion.Result result=converted();File dir=new File(context.getCacheDir(),"translation-exports");assertTrue(dir.isDirectory()||dir.mkdirs());File destination=File.createTempFile("main-word-save-",".docx",dir);owned.add(destination);android.net.Uri uri=androidx.core.content.FileProvider.getUriForFile(context,context.getPackageName()+".files",destination);
        monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){if(Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction())){outgoing.add(intent);return new Instrumentation.ActivityResult(Activity.RESULT_OK,new Intent().setData(uri));}return null;}};inst.addMonitor(monitor);dialogButton("wordResultDialog",android.R.id.button3);((ExecutorService)field(activity,"pdfWorker")).submit(()->{}).get(10,TimeUnit.SECONDS);assertEquals(1,outgoing.size());assertArrayEquals(Files.readAllBytes(result.file.toPath()),Files.readAllBytes(destination.toPath()));unchanged();
    }
    @Test public void passwordRetryRejectsBlankAndWrongValuesBeforeConverting()throws Exception{
        open(true,"");convert(WordConversion.NATIVE);awaitDialog("wordErrorDialog",android.R.id.button1);Dialog first=main(()->dialog("wordErrorDialog"));dialogButton("wordErrorDialog",android.R.id.button1);main(()->{EditText input=find(first.getWindow().getDecorView(),EditText.class);assertNotNull(input.getError());input.setText("wrong-password");return null;});dialogButton("wordErrorDialog",android.R.id.button1);UiTestWait.until(()->dialog("wordErrorDialog")!=null&&dialog("wordErrorDialog")!=first,"Wrong Word password did not allow retry");awaitDialog("wordErrorDialog",android.R.id.button1);main(()->{find(dialog("wordErrorDialog").getWindow().getDecorView(),EditText.class).setText("fixture-user");return null;});dialogButton("wordErrorDialog",android.R.id.button1);WordConversion.Result result=converted();assertTrue(xml(result.file).contains("Workflow page 2"));assertNull(main(()->task.error.getValue()));unchanged();
    }
    @Test public void cancellingThePasswordPromptDoesNotReopenItAfterRecreation()throws Exception{
        open(true,"");convert(WordConversion.NATIVE);dialogButton("wordErrorDialog",android.R.id.button2);assertNull(main(()->task.error.getValue()));scenario.recreate();scenario.onActivity(a->{activity=a;remember();});inst.waitForIdleSync();assertFalse(task.running);assertNull(main(()->field(activity,"wordErrorDialog")));assertNull(main(()->task.result.getValue()));assertTrue(history().isEmpty());unchanged();
    }
    @Test public void queuedConversionCanBeCancelledWithoutCreatingWordOrHistoryFiles()throws Exception{
        open(false,"");CountDownLatch release=blockWorker();try{convert(WordConversion.NATIVE);dialogButton("wordProgressDialog",android.R.id.button2);assertTrue(task.job.cancelled);assertFalse(main(()->((Button)dialog("wordProgressDialog").findViewById(android.R.id.button2)).isEnabled()));}finally{release.countDown();}stopped();assertNull(main(()->task.result.getValue()));assertTrue(history().isEmpty());unchanged();
    }
    @Test public void runningConversionSurvivesRecreationWithoutReplacingItsJob()throws Exception{
        open(false,"");CountDownLatch release=blockWorker();try{convert(WordConversion.NATIVE);MainActivity.WordState retained=task;WordConversion.Job job=task.job;scenario.recreate();scenario.onActivity(a->{activity=a;remember();});awaitDialog("wordProgressDialog",android.R.id.button2);assertSame(retained,task);assertSame(job,task.job);assertTrue(task.running);assertFalse(task.disposed);}finally{release.countDown();}WordConversion.Result result=converted();assertTrue(xml(result.file).contains("Workflow page 1"));assertEquals(1,history().size());unchanged();
    }
    @Test public void completedConversionRestoresOneDialogAndOneHistoryEntry()throws Exception{
        open(false,"");convert(WordConversion.NATIVE);WordConversion.Result result=converted();scenario.recreate();scenario.onActivity(a->{activity=a;remember();});WordConversion.Result restored=converted();assertSame(result,restored);assertEquals(result.file,main(()->field(activity,"wordExport")));assertEquals(1,history().size());dialogButton("wordResultDialog",android.R.id.button2);scenario.recreate();scenario.onActivity(a->{activity=a;remember();});inst.waitForIdleSync();assertNull(main(()->task.result.getValue()));assertNull(main(()->field(activity,"wordResultDialog")));assertTrue(result.file.isFile());unchanged();
    }
    @Test public void aProtectedPdfCannotConvertWhenTextExtractionIsForbidden()throws Exception{
        restricted=true;open(true,"fixture-user");try(PDDocument doc=PDDocument.load(input,"fixture-user")){assertFalse("Fixture must actually prohibit text extraction",doc.getCurrentAccessPermission().canExtractContent());}convert(WordConversion.NATIVE);stopped();assertNull(main(()->task.result.getValue()));assertNull(main(()->field(activity,"wordErrorDialog")));assertTrue(history().isEmpty());unchanged();
    }
    @Test public void appearanceRouteProducesPageImagesInsteadOfEditableText()throws Exception{
        open(false,"");convert(WordConversion.APPEARANCE);WordConversion.Result result=converted();assertEquals(2,result.pages);String document=xml(result.file);assertTrue("Appearance output must contain page image markup",document.contains("w:pict"));assertFalse(document.contains("Workflow page"));try(ZipFile zip=new ZipFile(result.file)){assertEquals(2,Collections.list(zip.entries()).stream().filter(entry->entry.getName().startsWith("word/media/")).count());}unchanged();
    }
    @Test public void routeChooserOpensOfflineOcrLanguageSettingsWithoutStartingAJob()throws Exception{
        open(false,"");intercept();main(()->{View root=routeRoot();assertNotNull(root);clickText(root,UiText.get(R.string.ui_ocr_languages_84fba977));return null;});assertEquals(1,outgoing.size());assertEquals(new ComponentName(context,LanguageManagerActivity.class),outgoing.get(0).getComponent());assertEquals("ocr",outgoing.get(0).getStringExtra("kind"));assertFalse(task.running);unchanged();
    }
    void clickText(View root,String label){if(root instanceof TextView&&root.isClickable()&&label.contentEquals(((TextView)root).getText())){assertTrue(root.performClick());return;}if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View child=((ViewGroup)root).getChildAt(i);if(child instanceof TextView&&label.contentEquals(((TextView)child).getText())){assertTrue(child.performClick());return;}if(child instanceof ViewGroup)try{clickText(child,label);return;}catch(NoSuchElementException ignored){}}throw new NoSuchElementException(label);}
}

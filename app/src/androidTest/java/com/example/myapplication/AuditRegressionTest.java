package com.example.myapplication;

import android.content.*;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.core.app.ActivityScenario;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.junit.Test;
import java.io.File;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class AuditRegressionTest {
    private Context context(){return InstrumentationRegistry.getInstrumentation().getTargetContext();}

    @Test public void exportedEntryNeverForwardsInternalDocumentCommands()throws Exception{
        Context c=context();
        Intent hostile=new Intent(Intent.ACTION_MAIN).putExtra("document_action","Register").putExtra("path",new File(c.getFilesDir(),"documents/forged.pdf").getPath()).putExtra("pages",1);
        Intent sanitized=DocumentEntryActivity.sanitize(c,hostile);
        assertFalse(sanitized.hasExtra("document_action"));assertFalse(sanitized.hasExtra("path"));assertFalse(sanitized.hasExtra("pages"));
        ActivityInfo main=c.getPackageManager().getActivityInfo(new ComponentName(c,MainActivity.class),0);
        ActivityInfo entry=c.getPackageManager().getActivityInfo(new ComponentName(c,DocumentEntryActivity.class),0);
        assertFalse(main.exported);assertTrue(entry.exported);
    }

    @Test public void publicImportPreservesOnlyContentUrisAndReadGrants(){
        Context c=context();Uri source=Uri.parse("content://fixture.provider/document/one");
        Intent incoming=new Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM,source).putExtra("document_action","Rename").addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        Intent sanitized=DocumentEntryActivity.sanitize(c,incoming);
        assertEquals(source,sanitized.getParcelableExtra(Intent.EXTRA_STREAM));assertFalse(sanitized.hasExtra("document_action"));
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,sanitized.getFlags());
        assertEquals(source,sanitized.getClipData().getItemAt(0).getUri());
        incoming.putExtra(Intent.EXTRA_STREAM,Uri.parse("content://"+c.getPackageName()+".files/documents/private.pdf"));
        assertFalse(DocumentEntryActivity.sanitize(c,incoming).hasExtra(Intent.EXTRA_STREAM));
        incoming.putExtra(Intent.EXTRA_STREAM,Uri.parse("file:///data/private.pdf"));
        assertFalse(DocumentEntryActivity.sanitize(c,incoming).hasExtra(Intent.EXTRA_STREAM));
    }

    @Test public void memoryTrimReturnsWhileTheInferenceMonitorIsBusy()throws Exception{
        CountDownLatch held=new CountDownLatch(1),release=new CountDownLatch(1);
        ExecutorService owner=Executors.newSingleThreadExecutor(),caller=Executors.newSingleThreadExecutor();
        try{
            owner.submit(()->{synchronized(CurvedPaper.class){held.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}});
            assertTrue(held.await(2,TimeUnit.SECONDS));
            caller.submit(()->InstrumentationRegistry.getInstrumentation().runOnMainSync(()->((android.app.Application)context().getApplicationContext()).onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW))).get(2,TimeUnit.SECONDS);
        }finally{release.countDown();owner.shutdownNow();caller.shutdownNow();}
    }

    @Test public void backupRulesExcludeModelsAndTemporaryInputsButKeepUserDocuments()throws Exception{
        for(int resource:new int[]{R.xml.backup_rules,R.xml.data_extraction_rules}){
            android.content.res.XmlResourceParser parser=context().getResources().getXml(resource);
            int sections=0,modelExclusions=0;
            try{while(parser.next()!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT){
                if(parser.getEventType()!=org.xmlpull.v1.XmlPullParser.START_TAG)continue;
                if(parser.getName().equals("full-backup-content")||parser.getName().equals("cloud-backup")||parser.getName().equals("device-transfer"))sections++;
                if(!parser.getName().equals("exclude"))continue;
                String path=parser.getAttributeValue(null,"path");
                if("offline-language-packs/".equals(path))modelExclusions++;
                assertFalse("documents/".equals(path));assertFalse("scan-originals/".equals(path));assertFalse("paperflow.db".equals(path));assertFalse("folder_locks.xml".equals(path));
            }}finally{parser.close();}
            assertEquals(sections,modelExclusions);
        }
    }

    @Test public void snapshotReadFollowsQueuedWriteAndRetainsLastGoodCopy()throws Exception{
        File file=new File(context().getCacheDir(),"audit-snapshot-"+java.util.UUID.randomUUID()+".json");
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            SessionSnapshots.write(file,()->{entered.countDown();if(!release.await(2,TimeUnit.SECONDS))throw new IllegalStateException();return "first";});
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            Future<String> read=worker.submit(()->SessionSnapshots.read(file));assertFalse(read.isDone());release.countDown();
            assertEquals("first",read.get(3,TimeUnit.SECONDS));
            SessionSnapshots.write(file,()->{throw new java.io.IOException("fixture failure");});
            assertEquals("first",worker.submit(()->SessionSnapshots.read(file)).get(3,TimeUnit.SECONDS));
        }finally{SessionSnapshots.delete(file);worker.shutdownNow();}
    }

    @Test public void largeLibraryRendersOnlyOneWindowAndNextWindowIsReachable()throws Exception{
        Context c=context();String prefix="audit-window-"+java.util.UUID.randomUUID()+"-";
        try(DocumentStore store=new DocumentStore(c)){
            store.db().beginTransaction();try{for(int i=0;i<65;i++){String path=new File(c.getFilesDir(),"documents/"+prefix+i+".pdf").getPath();store.registerDocument(path,prefix+i,1);android.content.ContentValues indexed=new android.content.ContentValues();indexed.put("search_meta",DocumentStore.normalize(prefix+i));store.db().update("docs",indexed,"path=?",new String[]{path});}store.db().setTransactionSuccessful();}finally{store.db().endTransaction();}
        }
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(new Intent(c,MainActivity.class))){
            java.util.concurrent.atomic.AtomicReference<MainActivity> activity=new java.util.concurrent.atomic.AtomicReference<>();
            scenario.onActivity(a->{activity.set(a);set(a,"searchQuery",prefix);invoke(a,"renderHome");});
            UiTestWait.until(()->countNames((View)field(activity.get(),"content"),prefix)==30,"Library did not load its first bounded window");
            java.util.Set<String> first=new java.util.HashSet<>();scenario.onActivity(a->collectNames((View)field(a,"content"),prefix,first));
            scenario.onActivity(a->{set(a,"libraryOffset",30);invoke(a,"renderHome");});
            UiTestWait.until(()->countNames((View)field(activity.get(),"content"),prefix)==30,"Second library window did not load");
            scenario.onActivity(a->{java.util.Set<String> next=new java.util.HashSet<>();collectNames((View)field(a,"content"),prefix,next);assertTrue(java.util.Collections.disjoint(first,next));});
        }finally{try(DocumentStore store=new DocumentStore(c)){store.db().delete("docs","name LIKE ?",new String[]{prefix+"%"});}}
    }

    @Test public void pdfOptionsKeepPageSelectionAcrossBoundedThumbnailWindows()throws Exception{
        Context c=context();com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(c);
        File pdf=File.createTempFile("audit-pages-",".pdf",c.getCacheDir());
        try(com.tom_roush.pdfbox.pdmodel.PDDocument doc=new com.tom_roush.pdfbox.pdmodel.PDDocument()){
            for(int i=0;i<61;i++)doc.addPage(new com.tom_roush.pdfbox.pdmodel.PDPage());doc.save(pdf);
        }
        try(ActivityScenario<ToolWorkflowActivity> scenario=ActivityScenario.launch(new Intent(c,ToolWorkflowActivity.class).putExtra("operation","Add Watermark").putExtra("path",pdf.getPath()))){
            java.util.concurrent.atomic.AtomicReference<ToolWorkflowActivity> activity=new java.util.concurrent.atomic.AtomicReference<>();scenario.onActivity(activity::set);
            UiTestWait.until(()->"options".equals(field(activity.get(),"stage")),"PDF options did not become ready");
            scenario.onActivity(a->{assertEquals(30,((java.util.List<?>)field(a,"pageChecks")).size());assertEquals(61,((java.util.Set<?>)field(a,"selectedPages")).size());set(a,"pageWindow",30);invoke(a,"showOptions");assertEquals(30,((java.util.List<?>)field(a,"pageChecks")).size());assertEquals(61,((java.util.Set<?>)field(a,"selectedPages")).size());assertTrue(((android.util.LruCache<?,?>)field(a,"thumbnailCache")).size()<=4*1024*1024);});
            scenario.recreate();scenario.onActivity(activity::set);
            UiTestWait.until(()->"options".equals(field(activity.get(),"stage")),"PDF options did not recover after recreation");
            scenario.onActivity(a->assertEquals(61,((java.util.Set<?>)field(a,"selectedPages")).size()));
        }finally{pdf.delete();}
    }

    @Test public void closingTranslationReclaimsReturnedCameraFilesButKeepsBookDraft()throws Exception{
        Context c=context();File captures=new File(c.getFilesDir(),"captures");assertTrue(captures.isDirectory()||captures.mkdirs());
        File consumed=File.createTempFile("audit-camera-",".jpg",captures),draft=File.createTempFile("audit-draft-",".jpg",captures),external=File.createTempFile("audit-external-",".jpg",c.getCacheDir());
        BookDraftStore store=new BookDraftStore(c);BookDraftStore.Snapshot previous=store.read();
        LocalTranslationActivity.TranslationSession session=new LocalTranslationActivity.TranslationSession((android.app.Application)c.getApplicationContext());
        try{
            store.save(java.util.Collections.singletonList(draft.getPath()),true);
            session.ownInputs(java.util.Arrays.asList(consumed.getPath(),draft.getPath(),external.getPath()));
            session.onCleared();assertTrue(session.worker.awaitTermination(3,TimeUnit.SECONDS));
            assertFalse(consumed.exists());assertTrue(draft.exists());assertTrue(external.exists());
        }finally{store.save(previous.pages,previous.doublePage);consumed.delete();draft.delete();external.delete();session.worker.shutdownNow();}
    }

    @Test public void annotationRecreationRestoresTheQueuedSnapshot()throws Exception{
        Context c=context();com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(c);File pdf=File.createTempFile("audit-annotations-",".pdf",c.getCacheDir());
        try(com.tom_roush.pdfbox.pdmodel.PDDocument doc=new com.tom_roush.pdfbox.pdmodel.PDDocument()){doc.addPage(new com.tom_roush.pdfbox.pdmodel.PDPage());doc.save(pdf);}
        try(ActivityScenario<PdfEditActivity> scenario=ActivityScenario.launch(new Intent(c,PdfEditActivity.class).putExtra("path",pdf.getPath()))){
            java.util.concurrent.atomic.AtomicReference<PdfEditActivity> activity=new java.util.concurrent.atomic.AtomicReference<>();scenario.onActivity(activity::set);
            UiTestWait.until(()->Boolean.FALSE.equals(field(activity.get(),"busy")),"Annotation editor did not load");
            scenario.onActivity(a->{PdfEditActivity.Mark mark=new PdfEditActivity.Mark();mark.page=0;mark.tool="Rectangle";mark.bounds.set(.1f,.2f,.3f,.4f);((java.util.List<PdfEditActivity.Mark>)field(a,"marks")).add(mark);set(a,"dirty",true);});
            scenario.recreate();scenario.onActivity(activity::set);
            UiTestWait.until(()->Boolean.FALSE.equals(field(activity.get(),"busy")),"Annotation snapshot did not restore");
            scenario.onActivity(a->{assertEquals(1,((java.util.List<?>)field(a,"marks")).size());assertTrue((Boolean)field(a,"dirty"));});
        }finally{pdf.delete();}
    }

    private static Object field(Object target,String name){try{java.lang.reflect.Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}catch(Exception e){throw new AssertionError(e);}}
    private static void set(Object target,String name,Object value){try{java.lang.reflect.Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}catch(Exception e){throw new AssertionError(e);}}
    private static void invoke(Object target,String name){try{java.lang.reflect.Method method=target.getClass().getDeclaredMethod(name);method.setAccessible(true);method.invoke(target);}catch(Exception e){throw new AssertionError(e);}}
    private static int countNames(View view,String prefix){java.util.Set<String> names=new java.util.HashSet<>();collectNames(view,prefix,names);return names.size();}
    private static void collectNames(View view,String prefix,java.util.Set<String> names){if(view instanceof TextView&&!(view instanceof android.widget.EditText)&&((TextView)view).getText().toString().startsWith(prefix))names.add(((TextView)view).getText().toString());if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collectNames(((ViewGroup)view).getChildAt(i),prefix,names);}
}

package com.example.myapplication;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.paperflow.imageprocessing.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class TranslationFlowTest {
    final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    Context context(){Context context=inst.getTargetContext();PDFBoxResourceLoader.init(context);return context;}
    Object field(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    File snapshot(int color,String text)throws Exception {
        return snapshot(color,text,"en");
    }
    File snapshot(int color,String text,String target)throws Exception {
        Bitmap source=Bitmap.createBitmap(700,900,Bitmap.Config.ARGB_8888);source.eraseColor(color);
        Bitmap output=source.copy(Bitmap.Config.ARGB_8888,true);
        OcrTextBox word=new OcrTextBox(text,new ImageRect(60,70,550,140),70,140);
        ArrayList<TranslationRenderer.Block> blocks=TranslationRenderer.blocks(Collections.singletonList(word));blocks.get(0).translation=text;
        try{return TranslationSnapshot.write(context(),source,new TranslationRenderer.Result(output,Collections.singletonList(word)),blocks,"en",target,"en",true,false);}finally{source.recycle();output.recycle();}
    }
    void waitStopped(LocalTranslationActivity.TranslationSession session)throws Exception{long end=SystemClock.elapsedRealtime()+60000;while(SystemClock.elapsedRealtime()<end){inst.waitForIdleSync();if(!session.busy){Thread.sleep(50);inst.waitForIdleSync();if(!session.busy)return;}Thread.sleep(30);}assertFalse("Operation timed out",session.busy);}
    void waitReady(LocalTranslationActivity.TranslationSession session)throws Exception{waitStopped(session);assertFalse(session.message,session.error);}
    @Test public void orderedPdfImagesAndMultiShareContainEveryPage()throws Exception {
        List<File> snapshots=Arrays.asList(snapshot(Color.RED,"FIRST"),snapshot(Color.GREEN,"SECOND"),snapshot(Color.BLUE,"THIRD"));
        File root=new File(context().getCacheDir(),"translation-exports/test-"+System.nanoTime());
        try {
            ArrayList<File> pdf=TranslationBatch.export(context(),snapshots,new File(root,"pdf"),true,()->false);
            try(PDDocument document=PDDocument.load(pdf.get(0))){assertEquals(3,document.getNumberOfPages());PDFTextStripper text=new PDFTextStripper();String[] expected={"FIRST","SECOND","THIRD"};for(int i=0;i<3;i++){text.setStartPage(i+1);text.setEndPage(i+1);assertTrue(text.getText(document),text.getText(document).contains(expected[i]));}}
            ArrayList<File> images=TranslationBatch.export(context(),snapshots,new File(root,"images"),false,()->false);assertEquals(3,images.size());int[] colors={Color.RED,Color.GREEN,Color.BLUE};
            for(int i=0;i<3;i++){Bitmap image=BitmapFactory.decodeFile(images.get(i).getPath());try{assertEquals(colors[i],image.getPixel(10,10));}finally{image.recycle();}}
            Intent share=TranslationBatch.shareIntent(context(),images,"image/png");assertEquals(Intent.ACTION_SEND_MULTIPLE,share.getAction());assertEquals(3,share.getParcelableArrayListExtra(Intent.EXTRA_STREAM).size());assertEquals(3,share.getClipData().getItemCount());assertNotEquals(0,share.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent sharePdf=TranslationBatch.shareIntent(context(),pdf,"application/pdf");assertEquals(Intent.ACTION_SEND,sharePdf.getAction());assertEquals(1,sharePdf.getClipData().getItemCount());
        }finally{for(File directory:snapshots)TranslationSnapshot.remove(context(),directory);}
    }
    @Test public void cancellationKeepsSnapshotsAndRemovesPartialOutputs()throws Exception {
        File saved=snapshot(Color.WHITE,"CANCEL"),output=new File(context().getCacheDir(),"translation-cancel-"+System.nanoTime());
        try{try{TranslationBatch.export(context(),Collections.singletonList(saved),output,true,()->true);fail("Cancelled export succeeded");}catch(InterruptedIOException expected){}assertTrue(new File(saved,"result.png").isFile());assertFalse(output.exists());}finally{TranslationSnapshot.remove(context(),saved);}
    }
    @Test public void saveImagesWritesEveryPageThroughTheAndroidTreeProvider()throws Exception {
        List<File> saved=Arrays.asList(snapshot(Color.RED,"ONE"),snapshot(Color.GREEN,"TWO"),snapshot(Color.BLUE,"THREE"));
        File root=new File(context().getCacheDir(),"translation-exports/save-test-"+System.nanoTime());
        LocalTranslationActivity.TranslationSession session=new LocalTranslationActivity.TranslationSession((Application)context().getApplicationContext());
        String authority=context().getPackageName()+".translation-test-documents";
        android.net.Uri tree=android.provider.DocumentsContract.buildTreeDocumentUri(authority,"root");
        ArrayList<android.net.Uri> created=new ArrayList<>();
        try {
            ArrayList<File> images=TranslationBatch.export(context(),saved,root,false,()->false);
            inst.runOnMainSync(()->{session.exportFiles.addAll(images);session.lastExport=images.get(0);session.restoring=true;session.busy=true;session.copyOutput(tree);});
            TranslationSnapshot.restore(session,saved.get(0).getPath());waitReady(session);
            android.net.Uri children=android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(tree,"root");
            try(Cursor cursor=context().getContentResolver().query(children,new String[]{android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID},null,null,null)){
                assertNotNull(cursor);while(cursor.moveToNext())created.add(android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(cursor.getColumnIndexOrThrow(android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID))));
            }
            assertEquals("Every image must be saved",3,created.size());Set<Integer> colors=new HashSet<>();
            for(android.net.Uri uri:created)try(InputStream input=context().getContentResolver().openInputStream(uri)){Bitmap bitmap=BitmapFactory.decodeStream(input);assertNotNull(bitmap);colors.add(bitmap.getPixel(10,10));bitmap.recycle();}
            assertEquals(new HashSet<>(Arrays.asList(Color.RED,Color.GREEN,Color.BLUE)),colors);
        }finally{session.worker.shutdownNow();if(session.source!=null&&!session.source.isRecycled())session.source.recycle();if(session.result!=null&&!session.result.bitmap.isRecycled())session.result.bitmap.recycle();for(android.net.Uri uri:created)android.provider.DocumentsContract.deleteDocument(context().getContentResolver(),uri);for(File file:saved)TranslationSnapshot.remove(context(),file);}
    }
    @Test public void previewSwipesRestoreTheCorrectTranslatedPage()throws Exception {
        ArrayList<File> snapshots=new ArrayList<>(Arrays.asList(snapshot(Color.RED,"FIRST"),snapshot(Color.GREEN,"SECOND"),snapshot(Color.BLUE,"THIRD")));
        Activity activity=inst.startActivitySync(new Intent(context(),LocalTranslationActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        LocalTranslationActivity.TranslationSession session=(LocalTranslationActivity.TranslationSession)field(activity,"session");
        try{
            inst.runOnMainSync(()->{session.capturedPages.clear();for(int i=0;i<3;i++){session.capturedPages.add(new File(snapshots.get(i),"source.png").getPath());session.pageSnapshots.put(i,snapshots.get(i));session.snapshots.add(snapshots.get(i));}session.capturedIndex=0;session.busy=true;});
            TranslationSnapshot.restore(session,snapshots.get(0).getPath());waitReady(session);
            View preview=(View)field(activity,"preview");swipe(preview,true);waitReady(session);assertEquals(1,session.capturedIndex);assertEquals(Color.GREEN,session.result.bitmap.getPixel(10,10));
            OcrTextBox edited=new OcrTextBox("SECOND EDITED",new ImageRect(60,70,550,140),70,140);ArrayList<TranslationRenderer.Block> edits=TranslationRenderer.blocks(Collections.singletonList(edited));edits.get(0).translation="SECOND EDITED";
            Bitmap editedImage=session.result.bitmap.copy(Bitmap.Config.ARGB_8888,true);inst.runOnMainSync(()->session.busy=true);
            session.worker.execute(()->{try{session.publishResult(new TranslationRenderer.Result(editedImage,Collections.singletonList(edited)),edits,"en","en",false);}catch(Exception error){session.fail(error);}});waitReady(session);
            swipe(preview,true);waitReady(session);assertEquals(2,session.capturedIndex);assertEquals(Color.BLUE,session.result.bitmap.getPixel(10,10));
            swipe(preview,false);waitReady(session);assertEquals(1,session.capturedIndex);assertTrue(session.translatedText(),session.translatedText().contains("SECOND EDITED"));
            inst.runOnMainSync(()->session.changes.removeObservers((androidx.lifecycle.LifecycleOwner)activity));
            for(String mode:new String[]{"Batch","Book","Slides","Whiteboard","ID Cards"}){
                inst.runOnMainSync(()->{session.captureMode=mode;session.export(true,"test");});waitReady(session);try(PDDocument document=PDDocument.load(session.lastExport)){assertEquals("Export must include every page for "+mode,3,document.getNumberOfPages());assertTrue(new PDFTextStripper().getText(document).contains("SECOND EDITED"));}
            }
        }finally{inst.runOnMainSync(activity::finish);}
    }
    @Test public void failedPageNavigationKeepsThePreviousPreviewAndIndex()throws Exception {
        LocalTranslationActivity.TranslationSession session=new LocalTranslationActivity.TranslationSession((Application)context().getApplicationContext());
        Bitmap source=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);source.eraseColor(Color.RED);session.source=source;
        try{
            inst.runOnMainSync(()->{session.capturedPages.add("first");session.capturedPages.add("/missing/translation-page.png");session.selectPage(1);});waitStopped(session);
            assertTrue(session.error);assertEquals(0,session.capturedIndex);assertSame(source,session.source);assertFalse(source.isRecycled());
        }finally{session.worker.shutdownNow();source.recycle();}
    }
    @Test public void cancellingSnapshotRestoreNeverLeavesTheSessionBusy()throws Exception {
        File file=snapshot(Color.WHITE,"RESTORE");LocalTranslationActivity.TranslationSession session=new LocalTranslationActivity.TranslationSession((Application)context().getApplicationContext());
        try{session.busy=true;session.restoring=true;session.cancelled=true;TranslationSnapshot.restore(session,file.getPath());waitStopped(session);assertFalse(session.restoring);assertTrue(session.error);}
        finally{session.worker.shutdownNow();TranslationSnapshot.remove(context(),file);}
    }
    @Test public void exportTranslatesUnvisitedPagesAndPreservesBlankBookPages()throws Exception {
        File active=snapshot(Color.WHITE,"ΠΡΩΤΗ","el"),blank=new File(context().getCacheDir(),"translation-blank-"+System.nanoTime()+".png"),second=new File(context().getCacheDir(),"translation-second-"+System.nanoTime()+".png");
        Bitmap image=Bitmap.createBitmap(1200,700,Bitmap.Config.ARGB_8888);image.eraseColor(Color.WHITE);
        try(OutputStream output=new FileOutputStream(blank)){image.compress(Bitmap.CompressFormat.PNG,100,output);}
        Paint paint=new Paint(3);paint.setColor(Color.BLACK);paint.setTextSize(48);Canvas canvas=new Canvas(image);canvas.drawText("This is the second page of the document.",60,200,paint);canvas.drawText("Please read these instructions carefully.",60,320,paint);
        try(OutputStream output=new FileOutputStream(second)){image.compress(Bitmap.CompressFormat.PNG,100,output);}image.recycle();
        byte[] unchanged=java.nio.file.Files.readAllBytes(second.toPath());
        LocalTranslationActivity.TranslationSession session=new LocalTranslationActivity.TranslationSession((Application)context().getApplicationContext());
        try {
            inst.runOnMainSync(()->{session.captureMode="Book";session.capturedPages.add(new File(active,"source.png").getPath());session.capturedPages.add(blank.getPath());session.capturedPages.add(second.getPath());session.pageSnapshots.put(0,active);session.busy=true;});
            TranslationSnapshot.restore(session,active.getPath());waitReady(session);
            inst.runOnMainSync(()->session.export(true,"test"));waitReady(session);
            assertEquals(3,session.pageSnapshots.size());assertEquals(0,session.capturedIndex);
            try(PDDocument document=PDDocument.load(session.lastExport)){
                assertEquals(3,document.getNumberOfPages());PDFTextStripper text=new PDFTextStripper();text.setStartPage(2);text.setEndPage(2);assertTrue("Blank pages must remain blank",text.getText(document).trim().isEmpty());text.setStartPage(3);text.setEndPage(3);String translated=text.getText(document);assertTrue(translated,translated.matches("(?s).*[Α-Ωα-ω].*"));
            }
            assertArrayEquals(unchanged,java.nio.file.Files.readAllBytes(second.toPath()));
        }finally{session.worker.shutdownNow();for(File file:session.snapshots)TranslationSnapshot.remove(context(),file);if(session.source!=null&&!session.source.isRecycled())session.source.recycle();if(session.result!=null&&!session.result.bitmap.isRecycled())session.result.bitmap.recycle();blank.delete();second.delete();}
    }
    void swipe(View view,boolean forward){inst.runOnMainSync(()->{long now=SystemClock.uptimeMillis();float from=view.getWidth()*(forward?.85f:.15f),to=view.getWidth()*(forward?.15f:.85f),y=view.getHeight()*.5f;MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,from,y,0),up=MotionEvent.obtain(now,now+150,MotionEvent.ACTION_UP,to,y,0);try{view.dispatchTouchEvent(down);view.dispatchTouchEvent(up);}finally{down.recycle();up.recycle();}});}
    @Test public void signedRevisionIsStableInRecentAndOriginalStaysInLibrary()throws Exception {
        try(DocumentStore store=new DocumentStore(context())){
            String prefix=new File(context().getFilesDir(),"documents/sign-flow-"+System.nanoTime()).getPath(),original=prefix+"-original.pdf",signed=prefix+"-signed.pdf",second=prefix+"-signed-again.pdf";
            try{
                store.registerDocument(original,"Original",1);store.registerDerived(signed,"Signed",1,Collections.singletonList(original));store.signedRevision(original,signed);
                store.registerDerived(second,"Signed again",1,Collections.singletonList(signed));store.signedRevision(signed,second);
                for(int i=0;i<3;i++)try(Cursor rows=store.db().rawQuery("SELECT path FROM docs WHERE path IN (?,?,?) AND deleted_at=0 AND "+DocumentStore.recentRevisionSql(),new String[]{original,signed,second})){assertEquals(1,rows.getCount());assertTrue(rows.moveToFirst());assertEquals(second,rows.getString(0));}
                try(Cursor rows=store.db().rawQuery("SELECT path FROM docs WHERE path IN (?,?,?) AND deleted_at=0",new String[]{original,signed,second})){assertEquals("Originals must remain in the full library",3,rows.getCount());}
            }finally{store.db().delete("document_revisions","source_path IN (?,?,?)",new String[]{original,signed,second});store.db().delete("docs","path IN (?,?,?)",new String[]{original,signed,second});}
        }
    }
    boolean hasText(View view,String expected){if(view instanceof android.widget.TextView&&expected.contentEquals(((android.widget.TextView)view).getText()))return true;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)if(hasText(group.getChildAt(i),expected))return true;}return false;}
    @Test public void ocrExplainsCopyablePdfAndTranslationKeepsItsStableRoute()throws Exception {
        Activity activity=inst.startActivitySync(new Intent(context(),ToolWorkflowActivity.class).putExtra("operation","OCR PDF").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            assertTrue(hasText(activity.findViewById(android.R.id.content),UiText.get(R.string.ui_ocr_pdf_intro)));
            inst.runOnMainSync(()->{android.widget.PopupMenu menu=new android.widget.PopupMenu(activity,new View(activity));android.view.MenuItem item=UiText.add(menu.getMenu(),"Photo Translation");assertEquals("Photo Translation",UiText.canonical(item));assertEquals(UiText.get(R.string.ui_photo_translation),item.getTitle().toString());});
        }finally{inst.runOnMainSync(activity::finish);}
    }
}

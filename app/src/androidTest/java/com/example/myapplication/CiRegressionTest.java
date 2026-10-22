package com.example.myapplication;

import android.app.Application;
import android.content.Context;
import android.graphics.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.paperflow.imageprocessing.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.encryption.*;
import java.io.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class CiRegressionTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void ocrAddsScannedBodyDespiteNativeHeaderAndOnlyChangesSelectedPage()throws Exception {
        RemainingFeaturesTest fixtures=new RemainingFeaturesTest();fixtures.setup();File file=null;
        try{
            fixtures.installEnglish();file=fixtures.rasterPdf("ci-hybrid-"+UUID.randomUUID()+".pdf","ALPHA BODY","SECOND PAGE");
            try(PDDocument document=PDDocument.load(file)){
                PDPage first=document.getPage(0);
                try(PDPageContentStream stream=new PDPageContentStream(document,first,PDPageContentStream.AppendMode.APPEND,true,true)){
                    stream.beginText();stream.setFont(com.tom_roush.pdfbox.pdmodel.font.PDType1Font.HELVETICA,14);stream.newLineAtOffset(48,760);stream.showText("PAGE HEADER");stream.endText();
                    stream.transform(com.tom_roush.pdfbox.util.Matrix.getTranslateInstance(300,300));
                }
                first.setRotation(90);java.util.concurrent.atomic.AtomicInteger progress=new java.util.concurrent.atomic.AtomicInteger();
                PdfOcr.apply(context,document,Collections.singletonList(0),(done,total)->{assertEquals(1,total);progress.set(done);});
                assertEquals(1,progress.get());assertEquals(90,first.getRotation());
                com.tom_roush.pdfbox.text.PDFTextStripper text=new com.tom_roush.pdfbox.text.PDFTextStripper();text.setSortByPosition(true);text.setStartPage(1);text.setEndPage(1);
                String recognized=text.getText(document);assertTrue(recognized,recognized.contains("ALPHA"));assertTrue(recognized,recognized.contains("PAGE HEADER"));
                text.setStartPage(2);text.setEndPage(2);assertTrue("Unselected page acquired OCR",text.getText(document).trim().isEmpty());
                boolean positioned=false;
                for(PdfKeywordSearch.Glyph glyph:PdfKeywordSearch.glyphs(document,0))
                    if(glyph.reading.left<.2f&&glyph.reading.top>.08f&&glyph.reading.top<.2f)positioned=true;
                assertTrue("OCR inherited an old content stream transform",positioned);
            }
        }finally{fixtures.cleanup();if(file!=null)file.delete();}
    }

    @Test public void derivedDocumentKeepsAllFolderGuardsAfterMovingToRoot()throws Exception {
        ArrayList<Long> folders=new ArrayList<>();ArrayList<String> paths=new ArrayList<>();
        android.content.SharedPreferences locks=context.getSharedPreferences("folder_locks",0);
        try(DocumentStore store=new DocumentStore(context)){
            try{
                for(int i=0;i<2;i++){
                    android.content.ContentValues folder=new android.content.ContentValues();folder.put("name","ci-guard-"+UUID.randomUUID());
                    long id=store.db().insertOrThrow("folders",null,folder);folders.add(id);locks.edit().putString("pin_"+id,"test-locked").commit();
                    String path=new File(context.getCacheDir(),"ci-origin-"+UUID.randomUUID()+".pdf").getPath();paths.add(path);store.registerDocument(path,"origin",1);
                    android.content.ContentValues location=new android.content.ContentValues();location.put("folder_id",id);store.db().update("docs",location,"path=?",new String[]{path});
                }
                String derived=new File(context.getCacheDir(),"ci-derived-"+UUID.randomUUID()+".pdf").getPath();
                store.registerDerived(derived,"derived",1,new ArrayList<>(paths));paths.add(derived);
                android.content.ContentValues root=new android.content.ContentValues();root.put("folder_id",0);store.db().update("docs",root,"path=?",new String[]{derived});
                assertEquals(new HashSet<>(folders),store.protectionRoots(derived));
                for(int i=0;i<2;i++)store.db().delete("docs","path=?",new String[]{paths.get(i)});
                assertEquals(new HashSet<>(folders),store.protectionRoots(derived));
                try(android.database.Cursor visible=store.db().rawQuery("SELECT path FROM docs WHERE path=? AND ("+FolderLock.visibleSql(context,store)+")",new String[]{derived})){
                    assertFalse("Locked derivative leaked from root",visible.moveToFirst());
                }
            }finally{
                for(String path:paths){store.db().delete("document_guards","path=?",new String[]{path});store.db().delete("docs","path=?",new String[]{path});}
                for(Long id:folders){locks.edit().remove("pin_"+id).commit();store.db().delete("folders","id=?",new String[]{id.toString()});}
            }
        }
    }
    @Test public void versionSixUpgradeAddsGuardStorageWithoutLosingDocuments()throws Exception {
        try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.create(null);DocumentStore helper=new DocumentStore(context)){
            db.execSQL("CREATE TABLE docs (id INTEGER PRIMARY KEY, path TEXT)");db.execSQL("INSERT INTO docs VALUES (1,'original.pdf')");
            helper.onUpgrade(db,6,7);
            db.execSQL("INSERT INTO document_guards VALUES ('copy.pdf',42)");
            try(android.database.Cursor rows=db.rawQuery("SELECT path FROM docs WHERE id=1",null)){assertTrue(rows.moveToFirst());assertEquals("original.pdf",rows.getString(0));}
            try(android.database.Cursor rows=db.rawQuery("SELECT folder_id FROM document_guards WHERE path='copy.pdf'",null)){assertTrue(rows.moveToFirst());assertEquals(42,rows.getLong(0));}
        }
    }

    @Test public void mergedPdfRetainsIntersectionOfEverySourcePermission()throws Exception {
        PDFBoxResourceLoader.init(context);
        try(PDDocument first=restricted(true);PDDocument second=restricted(false);PDDocument output=new PDDocument()){
            output.addPage(new PDPage());
            PdfProtection.inheritAll(Arrays.asList(first,second),output,"reader");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();output.save(bytes);
            try(PDDocument reopened=PDDocument.load(bytes.toByteArray(),"reader")){
                assertTrue(reopened.isEncrypted());AccessPermission p=reopened.getCurrentAccessPermission();
                assertFalse(p.canPrint());assertFalse(p.canPrintDegraded());assertFalse(p.canModify());
                assertFalse(p.canModifyAnnotations());assertFalse(p.canExtractContent());
                assertFalse(p.canExtractForAccessibility());assertFalse(p.canFillInForm());assertFalse(p.canAssembleDocument());
            }
        }
    }
    private PDDocument restricted(boolean first)throws Exception {
        try(PDDocument document=new PDDocument()){
            document.addPage(new PDPage());AccessPermission p=new AccessPermission();
            if(first){p.setCanPrint(false);p.setCanPrintDegraded(false);p.setCanModify(false);p.setCanExtractContent(false);}
            else{p.setCanModifyAnnotations(false);p.setCanExtractForAccessibility(false);p.setCanFillInForm(false);p.setCanAssembleDocument(false);}
            StandardProtectionPolicy policy=new StandardProtectionPolicy("owner","reader",p);policy.setEncryptionKeyLength(128);document.protect(policy);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();document.save(bytes);
            return PDDocument.load(bytes.toByteArray(),"reader");
        }
    }
    @Test public void translationSnapshotRestoresIntoAFreshSession()throws Exception {
        Bitmap source=Bitmap.createBitmap(96,64,Bitmap.Config.ARGB_8888);source.eraseColor(Color.WHITE);
        Bitmap translated=source.copy(Bitmap.Config.ARGB_8888,true);translated.setPixel(10,10,Color.BLUE);
        OcrTextBox word=new OcrTextBox("hello",new ImageRect(4,8,60,25),17,25);
        TranslationRenderer.Block block=new TranslationRenderer.Block(word);block.translation="translated";
        File snapshot=TranslationSnapshot.write(context,source,new TranslationRenderer.Result(translated,Collections.singletonList(word)),Collections.singletonList(block),"en","el","en",true,true);
        LocalTranslationActivity.TranslationSession fresh=new LocalTranslationActivity.TranslationSession((Application)context.getApplicationContext());
        try{
            fresh.restoring=true;TranslationSnapshot.restore(fresh,snapshot.getPath());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            assertNotNull(fresh.result);assertFalse(fresh.restoring);assertEquals("en",fresh.sourceLanguage);assertEquals("el",fresh.target);
            assertTrue(fresh.explicitTarget);assertTrue(fresh.readableLayout);assertEquals("en",fresh.sourceOverride);
            assertEquals("translated",fresh.blocks.get(0).translation);assertEquals("hello",fresh.result.words.get(0).getText());
            assertEquals(4,fresh.result.words.get(0).getBox().getLeft());assertEquals(Color.BLUE,fresh.result.bitmap.getPixel(10,10));
        }finally{fresh.onCleared();fresh.worker.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS);TranslationSnapshot.remove(context,snapshot);source.recycle();translated.recycle();}
    }
    @Test public void translationSnapshotRejectsPathsOutsidePrivateSessions()throws Exception {
        File outside=new File(context.getCacheDir(),"not-a-session");
        try{TranslationSnapshot.owned(context,outside.getPath(),"translation-sessions");fail("Accepted external session");}catch(IOException expected){}
        try{TranslationSnapshot.owned(context,new File(context.getFilesDir(),"translation-sessions/../other").getPath(),"translation-sessions");fail("Accepted traversal");}catch(IOException expected){}
    }
}

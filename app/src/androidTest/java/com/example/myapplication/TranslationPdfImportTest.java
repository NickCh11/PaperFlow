package com.example.myapplication;

import android.app.Application;
import android.graphics.*;
import android.net.Uri;
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
import java.util.concurrent.TimeUnit;
import org.junit.*;
import static org.junit.Assert.*;

public class TranslationPdfImportTest {
    private final android.app.Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private final Application app=(Application)inst.getTargetContext().getApplicationContext();
    private final List<File> originals=new ArrayList<>();
    private LocalTranslationActivity.TranslationSession session;
    @Before public void init(){PDFBoxResourceLoader.init(app);inst.runOnMainSync(()->session=new TranslationInputLifecycleTest.RecordingSession(app));}
    @After public void close()throws Exception{
        inst.runOnMainSync(session::onCleared);assertTrue(session.worker.awaitTermination(15,TimeUnit.SECONDS));
        for(File file:originals)file.delete();
    }
    private void idle(){UiTestWait.until(()->!session.busy,"Translation import did not finish: "+session.message);}
    private Set<String> staged(){File[] files=new File(app.getFilesDir(),"translation-inputs").listFiles();Set<String> result=new HashSet<>();if(files!=null)for(File file:files)result.add(file.getName());return result;}
    private File pdf(int count,boolean text)throws Exception{
        // Deliberately wrong extension: contents must identify PDFs independently of provider metadata.
        File file=File.createTempFile("translate-pdf-",".png",app.getCacheDir());originals.add(file);
        try(PDDocument doc=new PDDocument()){
            for(int i=0;i<count;i++){
                PDPage page=new PDPage(new PDRectangle(240,320));doc.addPage(page);
                if(!text&&i==1)page.setRotation(90);
                try(PDPageContentStream out=new PDPageContentStream(doc,page)){
                    int color=text?Color.WHITE:i==0?Color.GREEN:Color.BLUE;out.setNonStrokingColor(Color.red(color),Color.green(color),Color.blue(color));out.addRect(0,0,240,320);out.fill();
                    if(text){out.setNonStrokingColor(0,0,0);out.beginText();out.setFont(PDType1Font.HELVETICA,14);out.newLineAtOffset(15,260);out.showText("Hello world. This is a book.");out.newLineAtOffset(0,-30);out.showText("Document page "+(i+1));out.endText();}
                }
            }
            doc.save(file);
        }return file;
    }
    private File image()throws Exception{
        File file=File.createTempFile("translate-image-",".pdf",app.getCacheDir());originals.add(file);
        Bitmap bitmap=Bitmap.createBitmap(96,128,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.RED);
        try(OutputStream out=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}return file;
    }
    @Test public void mixedImageAndPdfContentsKeepEveryPageInSelectionOrder()throws Exception{
        File image=image(),pdf=pdf(2,false);byte[] original=Files.readAllBytes(pdf.toPath());
        inst.runOnMainSync(()->session.importFiles(Arrays.asList(Uri.fromFile(image),Uri.fromFile(pdf))));idle();
        assertFalse(session.message,session.error);assertEquals(3,session.capturedPages.size());assertEquals(3,session.importedSources.size());assertEquals(Color.RED,session.source.getPixel(5,5));
        inst.runOnMainSync(()->session.selectPage(1));idle();assertEquals(Color.GREEN,session.source.getPixel(5,5));assertTrue(session.source.getHeight()>session.source.getWidth());
        inst.runOnMainSync(()->session.selectPage(2));idle();assertEquals(Color.BLUE,session.source.getPixel(5,5));assertTrue("PDF rotation must be retained",session.source.getWidth()>session.source.getHeight());assertTrue(Math.max(session.source.getWidth(),session.source.getHeight())<=2600);
        assertArrayEquals(original,Files.readAllBytes(pdf.toPath()));
    }
    @Test public void malformedPdfAfterValidPagesRollsBackTheWholeImport()throws Exception{
        File valid=pdf(1,false),broken=File.createTempFile("translate-broken-",".pdf",app.getCacheDir());originals.add(broken);Files.write(broken.toPath(),"%PDF-1.7\ninvalid".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        Set<String> before=staged();Bitmap previous=Bitmap.createBitmap(20,20,Bitmap.Config.ARGB_8888);
        inst.runOnMainSync(()->{session.source=previous;session.importFiles(Arrays.asList(Uri.fromFile(valid),Uri.fromFile(broken)));});idle();
        assertTrue(session.error);assertSame(previous,session.source);assertFalse(previous.isRecycled());assertEquals(before,staged());assertTrue(session.capturedPages.isEmpty());assertTrue(valid.isFile());
    }
    @Test public void emptyOversizedAndProtectedPdfsFailWithoutAdoptingPages()throws Exception{
        File empty=pdf(0,false),oversized=pdf(101,false),locked=pdf(1,true),restricted=pdf(1,true);
        protect(locked,"reader",true);protect(restricted,"",false);Set<String> before=staged();
        for(File file:Arrays.asList(empty,oversized,locked,restricted)){
            inst.runOnMainSync(()->session.importFiles(Collections.singletonList(Uri.fromFile(file))));idle();
            assertTrue("Invalid input accepted: "+file,session.error);assertEquals(before,staged());assertTrue(session.capturedPages.isEmpty());assertTrue(session.importedSources.isEmpty());
            if(file==locked)assertEquals(UiText.get(R.string.ui_translation_locked_pdf),session.message);
            if(file==restricted)assertEquals(UiText.get(R.string.ui_this_pdf_does_not_allow_text_extraction),session.message);
        }
    }
    private void protect(File file,String password,boolean extraction)throws Exception{
        File output=File.createTempFile("translate-encrypted-",".pdf",app.getCacheDir());originals.add(output);
        try(PDDocument doc=PDDocument.load(file)){AccessPermission permission=new AccessPermission();permission.setCanExtractContent(extraction);StandardProtectionPolicy policy=new StandardProtectionPolicy("fixture-owner",password,permission);policy.setEncryptionKeyLength(128);doc.protect(policy);doc.save(output);}
        Files.copy(output.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    @Test public void cancellingAfterPdfRenderingBeforePublicationRemovesAllStagedPages()throws Exception{
        File pdf=pdf(2,false);Set<String> before=staged();
        inst.runOnMainSync(()->{
            session.importFiles(Collections.singletonList(Uri.fromFile(pdf)));
            try{session.worker.submit(()->{}).get(15,TimeUnit.SECONDS);}catch(Exception error){throw new AssertionError(error);}
            session.cancelled=true;
        });idle();assertTrue(session.error);assertEquals(before,staged());assertTrue(session.capturedPages.isEmpty());assertTrue(session.importedSources.isEmpty());assertTrue(pdf.isFile());
    }
    @Test public void multipagePdfTranslatesOfflineAndExportsEveryPageWithSelectableText()throws Exception{
        inst.runOnMainSync(session::onCleared);assertTrue(session.worker.awaitTermination(10,TimeUnit.SECONDS));
        inst.runOnMainSync(()->{session=new LocalTranslationActivity.TranslationSession(app);session.sourceOverride="en";session.target="el";});
        File pdf=pdf(2,true);byte[] original=Files.readAllBytes(pdf.toPath());
        inst.runOnMainSync(()->session.importFiles(Collections.singletonList(Uri.fromFile(pdf))));waitTranslation();
        assertFalse(session.message,session.error);assertEquals(2,session.capturedPages.size());assertNotNull(session.result);assertTrue(session.translatedText(),session.translatedText().matches("(?s).*[Α-Ωα-ω].*"));
        File output=null;
        try{
            inst.runOnMainSync(()->session.export(true,"share"));waitTranslation();assertFalse(session.message,session.error);output=session.lastExport;assertNotNull(output);assertEquals(2,session.exportPageCount);
            try(PDDocument translated=PDDocument.load(output)){assertEquals(2,translated.getNumberOfPages());PDFTextStripper text=new PDFTextStripper();for(int page=1;page<=2;page++){text.setStartPage(page);text.setEndPage(page);assertTrue("Translated PDF page "+page+" must retain selectable Greek text",text.getText(translated).matches("(?s).*[Α-Ωα-ω].*"));}}
            assertArrayEquals(original,Files.readAllBytes(pdf.toPath()));
        }finally{if(output!=null){output.delete();output.getParentFile().delete();}}
    }
    private void waitTranslation()throws Exception{
        long end=android.os.SystemClock.elapsedRealtime()+120000;
        while(session.busy&&android.os.SystemClock.elapsedRealtime()<end){inst.waitForIdleSync();Thread.sleep(50);}
        assertFalse("PDF translation timed out: "+session.message,session.busy);
    }
}

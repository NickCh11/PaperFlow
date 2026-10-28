package com.example.myapplication;

import android.content.Context;
import android.graphics.Bitmap;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.zip.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class WordConversionRoutesTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Before public void init(){PDFBoxResourceLoader.init(context);}
    @Test public void protectedPdfExtractionIsRejectedByEveryWordRouteButAllowedForTheOwner()throws Exception{
        File source=pdf(true,1),locked=File.createTempFile("word-restricted-",".pdf",context.getCacheDir());
        try{
            try(PDDocument doc=PDDocument.load(source)){com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission permission=new com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission();permission.setCanExtractContent(false);com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy protection=new com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy("fixture-owner","fixture-user",permission);protection.setEncryptionKeyLength(128);protection.setPreferAES(true);doc.protect(protection);doc.save(locked);}
            byte[] original=Files.readAllBytes(locked.toPath());try(PDDocument doc=PDDocument.load(locked,"fixture-user")){assertFalse(doc.getCurrentAccessPermission().canExtractContent());}
            File directory=new File(context.getFilesDir(),"documents");assertTrue(directory.isDirectory()||directory.mkdirs());Set<String> before=new HashSet<>(Arrays.asList(Objects.requireNonNull(directory.list())));
            for(int route:new int[]{WordConversion.AUTO,WordConversion.NATIVE,WordConversion.LOCAL,WordConversion.LAYOUT,WordConversion.APPEARANCE}){
                IOException denied=assertThrows(IOException.class,()->{WordConversion.Result unexpected=WordConversion.convert(context,locked.getPath(),"fixture-user",route,new WordConversion.Job(),(d,t,m)->{});try{fail("A restricted user password must not allow Word export, route="+route);}finally{unexpected.file.delete();}});
                assertEquals(UiText.get(R.string.ui_this_pdf_does_not_allow_text_extraction),denied.getMessage());assertEquals(before,new HashSet<>(Arrays.asList(Objects.requireNonNull(directory.list()))));
            }
            WordConversion.Result owner=WordConversion.convert(context,locked.getPath(),"fixture-owner",WordConversion.NATIVE,new WordConversion.Job(),(d,t,m)->{});
            try{assertEquals(1,owner.nativePages);assertTrue(document(owner.file).contains("Page 1"));}finally{owner.file.delete();}
            assertArrayEquals(original,Files.readAllBytes(locked.toPath()));
        }finally{source.delete();locked.delete();}
    }
    private File pdf(boolean withText,int pages)throws Exception{
        File file=File.createTempFile("word-route-",".pdf",context.getCacheDir());
        try(PDDocument doc=new PDDocument()){for(int i=0;i<pages;i++){PDPage page=new PDPage(new PDRectangle(120,180));doc.addPage(page);
            if(withText)try(PDPageContentStream stream=new PDPageContentStream(doc,page)){stream.beginText();stream.setFont(PDType1Font.HELVETICA,10);stream.newLineAtOffset(12,140);stream.showText("Page "+(i+1)+" <tag> & value");stream.endText();}}
            doc.save(file);
        }return file;
    }
    private String document(File file)throws Exception{
        try(ZipFile zip=new ZipFile(file)){assertNotNull(zip.getEntry("[Content_Types].xml"));assertNotNull(zip.getEntry("_rels/.rels"));
            byte[] value;try(InputStream in=zip.getInputStream(zip.getEntry("word/document.xml"))){value=read(in);}
            DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);
            assertEquals("document",factory.newDocumentBuilder().parse(new ByteArrayInputStream(value)).getDocumentElement().getLocalName());
            return new String(value,StandardCharsets.UTF_8);
        }
    }
    private byte[] read(InputStream in)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);return out.toByteArray();}
    private Object call(String name,Class<?>[] types,Object... args)throws Exception{
        Method method=WordConversion.class.getDeclaredMethod(name,types);method.setAccessible(true);
        try{return method.invoke(null,args);}catch(InvocationTargetException error){Throwable cause=error.getCause();if(cause instanceof Exception)throw (Exception)cause;throw new AssertionError(cause);}
    }

    @Test public void nativeAndAutomaticTextRoutesCreateValidEscapedMultipageWordFiles()throws Exception{
        File source=pdf(true,2);
        try{for(int route:new int[]{WordConversion.NATIVE,WordConversion.AUTO}){
            ArrayList<Integer> progress=new ArrayList<>();WordConversion.Result result=WordConversion.convert(context,source.getPath(),"",route,new WordConversion.Job(),(done,total,message)->{assertEquals(2,total);progress.add(done);});
            try{assertEquals(2,result.pages);assertEquals(2,result.nativePages);assertEquals(0,result.ocrPages);
                String xml=document(result.file);assertTrue(xml.contains("Page 1 &lt;tag&gt; &amp; value"));assertTrue(xml.contains("Page 2"));assertTrue(xml.contains("w:type=\"page\""));assertEquals(Integer.valueOf(2),progress.get(progress.size()-1));
            }finally{result.file.delete();}
        }}finally{source.delete();}
    }

    @Test public void visualAndEditableLayoutRoutesKeepImagesAndOnlyEditableRouteHasTextBoxes()throws Exception{
        File source=pdf(true,1);
        try{for(int route:new int[]{WordConversion.LAYOUT,WordConversion.APPEARANCE}){
            WordConversion.Result result=WordConversion.convert(context,source.getPath(),"",route,new WordConversion.Job(),(done,total,message)->{});
            try{assertEquals(1,result.pages);assertEquals(0,result.nativePages);assertEquals(0,result.ocrPages);String xml=document(result.file);
                assertEquals(route==WordConversion.LAYOUT,xml.contains("<w:txbxContent>"));
                try(ZipFile zip=new ZipFile(result.file)){assertNotNull(zip.getEntry("word/media/page1.jpg"));assertNotNull(zip.getEntry("word/_rels/document.xml.rels"));}
            }finally{result.file.delete();}
        }}finally{source.delete();}
    }

    @Test public void pageImagesDoNotReadOcrSettingsAndKeepRotatedPageDimensions()throws Exception{
        File source=pdf(false,1);
        try{
            try(PDDocument pdf=PDDocument.load(source)){pdf.getPage(0).setRotation(90);File rotated=File.createTempFile("word-rotated-",".pdf",context.getCacheDir());try{pdf.save(rotated);java.nio.file.Files.copy(rotated.toPath(),source.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}finally{rotated.delete();}}
            Context noOcr=new android.content.ContextWrapper(context){@Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){throw new AssertionError("Page images must not consult OCR settings");}};
            WordConversion.Result result=WordConversion.convert(noOcr,source.getPath(),"",WordConversion.APPEARANCE,new WordConversion.Job(),(d,t,m)->{});
            try{String xml=document(result.file);assertFalse(xml.contains("<w:txbxContent>"));assertTrue(xml.contains("w:w=\"3600\" w:h=\"2400\""));try(ZipFile zip=new ZipFile(result.file)){assertNotNull(zip.getEntry("word/media/page1.jpg"));}}finally{result.file.delete();}
        }finally{source.delete();}
    }

    @Test public void scannedPagesProduceEditableTextOnlyWhenOcrIsRequested()throws Exception{
        android.content.SharedPreferences preferences=context.getSharedPreferences("local-languages",0);
        Set<String> previous=new HashSet<>(preferences.getStringSet("ocr-enabled",Collections.emptySet()));
        File source=File.createTempFile("word-scan-",".pdf",context.getCacheDir());
        Bitmap scan=Bitmap.createBitmap(1600,1000,Bitmap.Config.ARGB_8888);
        try{
            android.graphics.Canvas canvas=new android.graphics.Canvas(scan);canvas.drawColor(android.graphics.Color.WHITE);
            android.graphics.Paint paint=new android.graphics.Paint(3);paint.setColor(android.graphics.Color.BLACK);paint.setTextSize(64);
            canvas.drawText("PaperFlow document conversion",90,200,paint);canvas.drawText("This is a scanned book page.",90,350,paint);
            try(PDDocument doc=new PDDocument()){PDPage page=new PDPage(new PDRectangle(480,300));doc.addPage(page);try(PDPageContentStream stream=new PDPageContentStream(doc,page)){stream.drawImage(com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory.createFromImage(doc,scan),0,0,480,300);}doc.save(source);}
            preferences.edit().putStringSet("ocr-enabled",new HashSet<>(Collections.singletonList("en"))).commit();
            assertFalse("English OCR fixture must be installed",com.paperflow.scanengine.LocalOcr.activeLanguages(context).isEmpty());
            byte[] original=Files.readAllBytes(source.toPath());
            assertThrows(IllegalStateException.class,()->WordConversion.convert(context,source.getPath(),"",WordConversion.NATIVE,new WordConversion.Job(),(d,t,m)->{}));
            for(int route:new int[]{WordConversion.AUTO,WordConversion.LOCAL,WordConversion.LAYOUT,WordConversion.APPEARANCE}){
                WordConversion.Result result=WordConversion.convert(context,source.getPath(),"",route,new WordConversion.Job(),(d,t,m)->{});
                try{String xml=document(result.file);assertEquals(1,result.pages);
                    if(route==WordConversion.AUTO||route==WordConversion.LOCAL){assertEquals(1,result.ocrPages);assertTrue(xml.matches("(?s).*<w:t[^>]*>[^<]*[A-Za-z]{4}.*"));assertFalse(xml.contains("<w:txbxContent>"));try(ZipFile zip=new ZipFile(result.file)){assertNull(zip.getEntry("word/media/page1.jpg"));}}
                    else{assertEquals(route==WordConversion.LAYOUT,xml.contains("<w:txbxContent>"));try(ZipFile zip=new ZipFile(result.file)){assertNotNull(zip.getEntry("word/media/page1.jpg"));}}
                }finally{result.file.delete();}
            }
            assertArrayEquals(original,Files.readAllBytes(source.toPath()));
        }finally{scan.recycle();source.delete();preferences.edit().putStringSet("ocr-enabled",previous).commit();}
    }

    @Test public void emptyOrUnreadableInputFailsWithoutCreatingAnOutput()throws Exception{
        File noPages=pdf(false,0),blank=pdf(false,1),malformed=File.createTempFile("word-bad-",".pdf",context.getCacheDir());Files.write(malformed.toPath(),new byte[]{1,2,3});
        File directory=new File(context.getFilesDir(),"documents");directory.mkdirs();Set<String> before=new HashSet<>(Arrays.asList(Objects.requireNonNull(directory.list())));
        try{for(File source:new File[]{noPages,blank})assertThrows(IllegalStateException.class,()->WordConversion.convert(context,source.getPath(),"",WordConversion.NATIVE,new WordConversion.Job(),(d,t,m)->{}));
            assertThrows(IOException.class,()->WordConversion.convert(context,malformed.getPath(),"",WordConversion.NATIVE,new WordConversion.Job(),(d,t,m)->{}));
            assertEquals(before,new HashSet<>(Arrays.asList(Objects.requireNonNull(directory.list()))));
            assertTrue(noPages.isFile());assertTrue(blank.isFile());assertEquals(3,malformed.length());
        }finally{noPages.delete();blank.delete();malformed.delete();}
    }

    @Test public void cancellationBeforeAndDuringWritingLeavesNoPartialWordFile()throws Exception{
        File source=pdf(true,1);File directory=new File(context.getFilesDir(),"documents");directory.mkdirs();
        try{for(int route:new int[]{WordConversion.NATIVE,WordConversion.LAYOUT,WordConversion.APPEARANCE}){
            Set<String> before=new HashSet<>(Arrays.asList(Objects.requireNonNull(directory.list())));
            WordConversion.Job cancelled=new WordConversion.Job();cancelled.cancel();assertThrows(CancellationException.class,()->WordConversion.convert(context,source.getPath(),"",route,cancelled,(d,t,m)->{}));
            WordConversion.Job during=new WordConversion.Job();assertThrows(CancellationException.class,()->WordConversion.convert(context,source.getPath(),"",route,during,(d,t,m)->{if(d==t)during.cancel();}));
            assertEquals(before,new HashSet<>(Arrays.asList(Objects.requireNonNull(directory.list()))));
        }}finally{source.delete();}
    }

    @Test public void jobCancellationRecognizesInterruption()throws Exception{
        WordConversion.Job job=new WordConversion.Job();job.check();job.cancel();assertThrows(CancellationException.class,job::check);
        WordConversion.Job interrupted=new WordConversion.Job();try{Thread.currentThread().interrupt();assertThrows(CancellationException.class,interrupted::check);}finally{Thread.interrupted();}interrupted.check();
    }

    @Test public void textCleanupReadabilityAndXmlEscapingHandleCorruptAndMultilingualText()throws Exception{
        assertEquals("",call("clean",new Class<?>[]{String.class},(Object)null));
        assertEquals("a\nb\nc\nd",call("clean",new Class<?>[]{String.class}," \u0000a\r\nb\rc\fd\u0001 "));
        for(String value:new String[]{"English", "Ελληνικά", "日本語文", "1234", "abcd\ufffd"})assertEquals(true,call("readable",new Class<?>[]{String.class},value));
        for(String value:new String[]{"", "abc", "abcd\ufffd\ufffd"})assertEquals(false,call("readable",new Class<?>[]{String.class},value));
        assertEquals("&amp;&lt;&gt;&quot;&apos;",call("xml",new Class<?>[]{String.class},"&<>\"'"));
    }

    @Test public void cancelledLocalOcrReleasesItsBitmap()throws Exception{
        Bitmap image=Bitmap.createBitmap(20,30,Bitmap.Config.ARGB_8888);WordConversion.Job cancelled=new WordConversion.Job();cancelled.cancel();
        try{assertThrows(CancellationException.class,()->call("localText",new Class<?>[]{Context.class,Bitmap.class,WordConversion.Job.class},context,image,cancelled));assertTrue(image.isRecycled());}
        finally{if(!image.isRecycled())image.recycle();}
    }
}

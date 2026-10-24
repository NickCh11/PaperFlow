package com.example.myapplication;

import android.content.Context;
import android.graphics.*;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.cos.*;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.*;
import com.tom_roush.pdfbox.rendering.PDFRenderer;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.*;
import java.util.*;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class PdfOverlayExportTest {
    private final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    @Before public void init(){PDFBoxResourceLoader.init(context);}
    private int ink(Bitmap bitmap){int count=0;for(int y=0;y<bitmap.getHeight();y++)for(int x=0;x<bitmap.getWidth();x++)if(Color.alpha(bitmap.getPixel(x,y))>0)count++;return count;}
    private void text(PDDocument doc,PDPage page)throws Exception{try(PDPageContentStream stream=new PDPageContentStream(doc,page)){stream.beginText();stream.setFont(PDType1Font.HELVETICA,12);stream.newLineAtOffset(30,60);stream.showText("Original native text");stream.endText();}}

    @Test public void invisibleWatermarksLeavePixelsAndPdfAnnotationsUntouched()throws Exception{
        Bitmap bitmap=Bitmap.createBitmap(120,180,Bitmap.Config.ARGB_8888);
        try{for(Object[] value:new Object[][]{{" ",120f,180f,80},{"text",120f,180f,0},{"text",0f,180f,80},{"text",120f,0f,80}})
            PdfWatermark.draw(new Canvas(bitmap),(float)value[1],(float)value[2],(String)value[0],Color.RED,(int)value[3],50,0,false,50);
            assertEquals(0,ink(bitmap));
        }finally{bitmap.recycle();}
        try(PDDocument doc=new PDDocument()){PDPage page=new PDPage(new PDRectangle(120,180));doc.addPage(page);assertNull(PdfWatermark.settings(page));
            PdfWatermark.append(doc,0,"  ",Color.RED,90,50,0,false,50);PdfWatermark.append(doc,0,"text",Color.RED,0,50,0,false,50);assertTrue(page.getAnnotations().isEmpty());}
    }

    @Test public void centeredAndTiledWatermarksFitAndNeverDrawOutsideTheRequestedPage(){
        for(boolean tile:new boolean[]{false,true})for(int angle:new int[]{0,45,-35}){
            Bitmap bitmap=Bitmap.createBitmap(160,200,Bitmap.Config.ARGB_8888);
            try{PdfWatermark.draw(new Canvas(bitmap),120,180,"A deliberately long watermark Ελληνικά",Color.BLUE,55,90,angle,tile,20);
                assertTrue("The watermark must contain visible pixels",ink(bitmap)>0);
                for(int y=0;y<200;y++)for(int x=0;x<160;x++)if(x>=120||y>=180)assertEquals(0,bitmap.getPixel(x,y));
            }finally{bitmap.recycle();}
        }
    }

    @Test public void invalidPdfDimensionsFailBeforeAddingAWatermark()throws Exception{
        for(PDRectangle box:new PDRectangle[]{new PDRectangle(0,180),new PDRectangle(120,0)})try(PDDocument doc=new PDDocument()){
            PDPage page=new PDPage(box);doc.addPage(page);
            assertThrows(IllegalArgumentException.class,()->PdfWatermark.append(doc,0,"DRAFT",Color.RED,80,50,0,false,50));
            assertTrue(page.getAnnotations().isEmpty());
        }
    }

    @Test public void exportedWatermarksRoundTripAtEveryRotationAndRemovalPreservesOtherAnnotations()throws Exception{
        for(int rotation:new int[]{0,90,180,270,-90})try(PDDocument doc=new PDDocument()){
            PDPage page=new PDPage(new PDRectangle(15,25,120,180));page.setRotation(rotation);doc.addPage(page);text(doc,page);
            String originalText=new PDFTextStripper().getText(doc);assertTrue(originalText.trim().length()>4);
            PDAnnotationText note=new PDAnnotationText();note.setContents("Keep this note");note.setRectangle(new PDRectangle(25,40,12,12));page.getAnnotations().add(note);
            PdfWatermark.append(doc,0,"DRAFT",Color.RED,65,45,30,true,35);
            assertEquals(2,page.getAnnotations().size());assertEquals("DRAFT",PdfWatermark.settings(page).getString("text"));
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();doc.save(bytes);
            try(PDDocument restored=PDDocument.load(bytes.toByteArray())){
                PDPage saved=restored.getPage(0);assertEquals(65,PdfWatermark.settings(saved).getInt("opacity"));
                Bitmap image=new PDFRenderer(restored).renderImage(0,.5f);try{
                    int red=0;for(int y=0;y<image.getHeight();y++)for(int x=0;x<image.getWidth();x++){int pixel=image.getPixel(x,y);if(Color.red(pixel)>Color.green(pixel)+30&&Color.red(pixel)>Color.blue(pixel)+30)red++;}
                    assertTrue("The serialized watermark must render at rotation "+rotation,red>10);
                }finally{image.recycle();}
                PdfWatermark.remove(saved);assertNull(PdfWatermark.settings(saved));assertEquals(1,saved.getAnnotations().size());assertEquals("Keep this note",saved.getAnnotations().get(0).getContents());
                assertEquals(originalText,new PDFTextStripper().getText(restored));PdfWatermark.remove(saved);assertEquals(1,saved.getAnnotations().size());
            }
        }
    }

    private COSArray array(float... values){COSArray a=new COSArray();for(float value:values)a.add(new COSFloat(value));return a;}
    @Test public void resizingTransformsNativeContentAnnotationRectanglesAndCoordinateArrays()throws Exception{
        try(PDDocument doc=new PDDocument()){
            PDPage page=new PDPage(new PDRectangle(10,20,100,200));doc.addPage(page);text(doc,page);
            PDAnnotationText note=new PDAnnotationText();note.setRectangle(new PDRectangle(20,40,10,20));note.getCOSObject().setItem("QuadPoints",array(20,40,30,60));
            COSArray mixed=array(20,40);mixed.add(new COSString("not a coordinate"));mixed.add(new COSFloat(90));mixed.add(new COSFloat(999));note.getCOSObject().setItem("Vertices",mixed);
            COSArray invalidPair=array(20);invalidPair.add(new COSString("not a y coordinate"));note.getCOSObject().setItem("L",invalidPair);
            COSArray ink=new COSArray();ink.add(array(20,40,30,60));ink.add(new COSString("unknown stroke"));note.getCOSObject().setItem("InkList",ink);page.getAnnotations().add(note);
            PDAnnotationText withoutRectangle=new PDAnnotationText();page.getAnnotations().add(withoutRectangle);
            PdfPageLayout.Options options=new PdfPageLayout.Options();options.selected.add(0);options.width=200;options.height=400;PdfPageLayout.apply(doc,0,options);
            assertEquals(200,page.getCropBox().getWidth(),0);assertEquals(400,page.getCropBox().getHeight(),0);
            PDRectangle transformed=note.getRectangle();assertEquals(20,transformed.getLowerLeftX(),0);assertEquals(40,transformed.getLowerLeftY(),0);assertEquals(20,transformed.getWidth(),0);assertEquals(40,transformed.getHeight(),0);
            assertArrayEquals(new float[]{20,40,40,80},((COSArray)note.getCOSObject().getDictionaryObject("QuadPoints")).toFloatArray(),0);
            assertArrayEquals(new float[]{20,40,40,80},((COSArray)ink.getObject(0)).toFloatArray(),0);
            assertEquals("not a coordinate",((COSString)mixed.getObject(2)).getString());assertEquals(999,((COSNumber)mixed.getObject(4)).floatValue(),0);
            assertNull(withoutRectangle.getRectangle());assertTrue(new PDFTextStripper().getText(doc).contains("Original native text"));
        }
    }

    @Test public void selectedPageOrientationNumberingAndAlignmentSurviveEveryRotation()throws Exception{
        for(boolean landscape:new boolean[]{false,true})for(int rotation:new int[]{0,90,180,270})for(int orientation=0;orientation<3;orientation++)for(int align=0;align<3;align++)try(PDDocument doc=new PDDocument()){
            float width=landscape?180:120,height=landscape?120:180;PDPage page=new PDPage(new PDRectangle(width,height));page.setRotation(rotation);doc.addPage(page);text(doc,page);
            PdfPageLayout.Options options=new PdfPageLayout.Options();options.orientation=orientation;options.align=align;options.start=7;options.numbers=true;options.total=align==1;
            PdfPageLayout.apply(doc,0,options);assertEquals(width,page.getCropBox().getWidth(),0); // Unselected page is untouched.
            options.selected.add(0);if(align!=0){options.width=align==1?100:160;options.height=align==1?160:100;}PdfPageLayout.apply(doc,0,options);
            page=doc.getPage(0); // PDPage caches its media box; reopen the updated dictionary as callers do.
            float visualW=(rotation==90||rotation==270)?page.getCropBox().getHeight():page.getCropBox().getWidth();
            float visualH=(rotation==90||rotation==270)?page.getCropBox().getWidth():page.getCropBox().getHeight();
            if(orientation==1)assertTrue(visualW<=visualH);if(orientation==2)assertTrue(visualW>=visualH);
            String value=new PDFTextStripper().getText(doc);assertTrue(value.contains("7"));if(options.total)assertTrue(value.contains("7 / 7"));
            assertEquals(page.getCropBox().getWidth(),page.getMediaBox().getWidth(),0);assertEquals(page.getCropBox().getHeight(),page.getArtBox().getHeight(),0);
        }
    }
}

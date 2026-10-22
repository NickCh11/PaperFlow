package com.example.myapplication;

import com.tom_roush.pdfbox.cos.*;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.*;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import com.tom_roush.pdfbox.util.Matrix;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Page geometry changes keep native PDF content and the existing annotation objects. */
final class PdfPageLayout {
    static final class Options {
        float width,height,font=11;int orientation,start=1,align=1;boolean numbers,total;
        final HashSet<Integer> selected=new HashSet<>();
    }
    static void apply(PDDocument doc,int index,Options o)throws Exception{
        if(!o.selected.contains(index))return;
        PDPage page=doc.getPage(index);PDRectangle crop=page.getCropBox();int turn=((page.getRotation()%360)+360)%360;boolean sideways=turn==90||turn==270;
        float oldW=crop.getWidth(),oldH=crop.getHeight();float visualW=o.width>0?o.width:sideways?oldH:oldW,visualH=o.height>0?o.height:sideways?oldW:oldH;
        boolean wasLandscape=sideways?oldH>oldW:oldW>oldH;
        if(o.orientation==0&&o.width>0&&wasLandscape&&visualW<visualH||o.orientation==1&&visualW>visualH||o.orientation==2&&visualW<visualH){float temp=visualW;visualW=visualH;visualH=temp;}
        float w=sideways?visualH:visualW,h=sideways?visualW:visualH;
        float scale=Math.min(w/oldW,h/oldH),dx=(w-oldW*scale)/2-crop.getLowerLeftX()*scale,dy=(h-oldH*scale)/2-crop.getLowerLeftY()*scale;
        List<PDStream> streams=new ArrayList<>();PDStream before=new PDStream(doc);
        String prefix=String.format(Locale.ROOT,"q\n%.8f 0 0 %.8f %.8f %.8f cm\n%.8f %.8f %.8f %.8f re W n\n",scale,scale,dx,dy,crop.getLowerLeftX(),crop.getLowerLeftY(),oldW,oldH);
        try(OutputStream out=before.createOutputStream()){out.write(prefix.getBytes(StandardCharsets.US_ASCII));}streams.add(before);
        Iterator<PDStream> contents=page.getContentStreams();while(contents.hasNext())streams.add(contents.next());PDStream after=new PDStream(doc);try(OutputStream out=after.createOutputStream()){out.write("\nQ\n".getBytes(StandardCharsets.US_ASCII));}streams.add(after);page.setContents(streams);
        for(PDAnnotation annotation:page.getAnnotations()){
            PDRectangle rect=annotation.getRectangle();if(rect!=null)annotation.setRectangle(new PDRectangle(rect.getLowerLeftX()*scale+dx,rect.getLowerLeftY()*scale+dy,rect.getWidth()*scale,rect.getHeight()*scale));
            for(String key:new String[]{"QuadPoints","Vertices","L","CL"})transform(annotation.getCOSObject().getDictionaryObject(COSName.getPDFName(key)),scale,dx,dy);
            COSBase ink=annotation.getCOSObject().getDictionaryObject(COSName.getPDFName("InkList"));if(ink instanceof COSArray)for(COSBase stroke:(COSArray)ink)transform(stroke,scale,dx,dy);
        }
        PDRectangle box=new PDRectangle(w,h);page.setMediaBox(box);page.setCropBox(box);page.setTrimBox(box);page.setBleedBox(box);page.setArtBox(box);
        if(o.numbers){String label=Integer.toString(o.start+index)+(o.total?" / "+(o.start+doc.getNumberOfPages()-1):"");float length=PDType1Font.HELVETICA.getStringWidth(label)/1000*o.font;float x=o.align==0?18:o.align==2?visualW-length-18:(visualW-length)/2;
            try(PDPageContentStream stream=new PDPageContentStream(doc,page,PDPageContentStream.AppendMode.APPEND,true,true)){
                stream.saveGraphicsState();if(turn==90)stream.transform(new Matrix(0,1,-1,0,w,0));else if(turn==180)stream.transform(new Matrix(-1,0,0,-1,w,h));else if(turn==270)stream.transform(new Matrix(0,-1,1,0,0,h));
                stream.setNonStrokingColor(255,255,255);stream.addRect(Math.max(0,x-3),11,length+6,o.font+5);stream.fill();stream.setNonStrokingColor(30,30,30);stream.beginText();stream.setFont(PDType1Font.HELVETICA,o.font);stream.newLineAtOffset(x,15);stream.showText(label);stream.endText();stream.restoreGraphicsState();
            }
        }
    }
    private static void transform(COSBase value,float scale,float dx,float dy){if(!(value instanceof COSArray))return;COSArray a=(COSArray)value;for(int i=0;i+1<a.size();i+=2)if(a.getObject(i) instanceof COSNumber&&a.getObject(i+1) instanceof COSNumber){float x=((COSNumber)a.getObject(i)).floatValue(),y=((COSNumber)a.getObject(i+1)).floatValue();a.set(i,new COSFloat(x*scale+dx));a.set(i+1,new COSFloat(y*scale+dy));}}
}

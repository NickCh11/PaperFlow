package com.example.myapplication;

import android.content.Context;
import android.graphics.*;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.text.*;
import java.io.*;
import java.util.*;

/** Search embedded glyphs, with local OCR fallback, without changing the source PDF. */
final class PdfKeywordSearch {
    static final class Hit {final int page;final ArrayList<RectF> areas;Hit(int page,ArrayList<RectF> areas){this.page=page;this.areas=areas;}}
    static final class Result {final ArrayList<Hit> hits=new ArrayList<>();int pagesNeedingOcr;}
    static final class Glyph {final String text;final RectF box,reading;Glyph(String text,RectF box){this(text,box,box);}Glyph(String text,RectF box,RectF reading){this.text=text;this.box=box;this.reading=reading;}}
    interface Progress {void page(int done,int total);}
    static Result search(Context context,PDDocument doc,String query,boolean wholeWord,Progress progress)throws Exception {
        if(!doc.getCurrentAccessPermission().canExtractContent())throw new IOException(UiText.get(R.string.ui_this_pdf_does_not_allow_text_extraction));
        String needle=DocumentStore.normalize(query).trim().replaceAll("\\s+"," ");if(needle.isEmpty())throw new IOException(UiText.get(R.string.ui_enter_a_word_or_phrase));Result result=new Result();
        for(int index=0;index<doc.getNumberOfPages();index++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException(UiText.get(R.string.ui_cancelled));
            ArrayList<Glyph> glyphs=glyphs(doc,index);
            if(glyphs.isEmpty()){
                if(com.paperflow.scanengine.LocalOcr.activeLanguages(context).isEmpty())result.pagesNeedingOcr++;
                else {PDPage page=doc.getPage(index);PDRectangle box=page.getCropBox();boolean turn=Math.abs(page.getRotation())%180==90;float width=turn?box.getHeight():box.getWidth(),height=turn?box.getWidth():box.getHeight();Bitmap image=new com.tom_roush.pdfbox.rendering.PDFRenderer(doc).renderImage(index,Math.min(2200/width,3000/height));
                    try{for(com.paperflow.imageprocessing.OcrTextBox word:com.paperflow.scanengine.LocalOcr.recognize(context,image)){com.paperflow.imageprocessing.ImageRect r=word.getBox();glyphs.add(new Glyph(word.getText(),new RectF(r.getLeft()/(float)image.getWidth(),r.getTop()/(float)image.getHeight(),r.getRight()/(float)image.getWidth(),r.getBottom()/(float)image.getHeight())));}}finally{image.recycle();}}
            }
            result.hits.addAll(find(index,glyphs,needle,wholeWord));if(result.hits.size()>10000)throw new IOException(UiText.get(R.string.ui_too_many_matches_use_a_more_specific_phrase));progress.page(index+1,doc.getNumberOfPages());
        }return result;
    }
    static ArrayList<Glyph> glyphs(PDDocument doc,int index)throws IOException {
        ArrayList<Glyph> found=new ArrayList<>();PDPage page=doc.getPage(index);PDRectangle box=page.getCropBox();int rotation=(page.getRotation()%360+360)%360;
        page.setRotation(0);try{PDFTextStripper stripper=new PDFTextStripper(){@Override protected void writeString(String text,List<TextPosition> positions)throws IOException{for(TextPosition p:positions){String value=p.getUnicode();if(value==null||value.trim().isEmpty())continue;float x=p.getX()/box.getWidth(),y=p.getY()/box.getHeight(),h=p.getHeight()/box.getHeight();RectF r=new RectF(x,y-h,x+Math.max(.001f,p.getWidth()/box.getWidth()),y+h*.15f);found.add(new Glyph(value,PdfTextSelectionActivity.rotate(r,rotation),r));}super.writeString(text,positions);}};stripper.setSortByPosition(true);stripper.setStartPage(index+1);stripper.setEndPage(index+1);stripper.getText(doc);}finally{page.setRotation(rotation);}return found;
    }
    static ArrayList<Hit> find(int page,ArrayList<Glyph> glyphs,String needle,boolean wholeWord){
        StringBuilder text=new StringBuilder();ArrayList<Integer> map=new ArrayList<>();
        for(int i=0;i<glyphs.size();i++){Glyph glyph=glyphs.get(i);String value=DocumentStore.normalize(glyph.text).replaceAll("\\s+"," ");if(i>0){RectF previous=glyphs.get(i-1).reading;boolean line=Math.abs(glyph.reading.centerY()-previous.centerY())>Math.max(glyph.reading.height(),previous.height())*.8f;if(line||glyph.reading.left-previous.right>Math.max(glyph.reading.height(),previous.height())*.2f){text.append(' ');map.add(-1);}}for(int j=0;j<value.length();j++){text.append(value.charAt(j));map.add(i);}}
        ArrayList<Hit> hits=new ArrayList<>();int from=0,at;
        while((at=text.indexOf(needle,from))>=0){int end=at+needle.length();from=end;
            if(wholeWord&&(at>0&&Character.isLetterOrDigit(text.codePointBefore(at))||end<text.length()&&Character.isLetterOrDigit(text.codePointAt(end))))continue;
            ArrayList<RectF> areas=new ArrayList<>();int previous=-2;for(int j=at;j<end;j++){int owner=map.get(j);if(owner<0||owner==previous)continue;RectF r=glyphs.get(owner).box;if(!areas.isEmpty()){RectF last=areas.get(areas.size()-1);if(Math.abs(last.centerY()-r.centerY())<Math.max(last.height(),r.height())*.8f){last.union(r);previous=owner;continue;}}areas.add(new RectF(r));previous=owner;}
            for(RectF area:areas){area.inset(-.002f,-.002f);area.intersect(0,0,1,1);}if(!areas.isEmpty())hits.add(new Hit(page,areas));
        }return hits;
    }
}

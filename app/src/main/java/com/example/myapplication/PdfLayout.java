package com.example.myapplication;

import android.content.Context;
import android.graphics.*;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.text.*;
import java.io.*;
import java.util.*;

/** Spatial text shared by Office exporters. Scanned pages fall back to the user's enabled local OCR. */
final class PdfLayout {
    static class Word {String text;float x,y,w,h;boolean bold;Word(String s,float x,float y,float w,float h,boolean b){text=s;this.x=x;this.y=y;this.w=w;this.h=h;bold=b;}}
    static class Page {float width,height;final ArrayList<Word> words=new ArrayList<>();boolean ocr;}
    static Page read(Context context,PDDocument document,int index)throws Exception{
        if(!document.getCurrentAccessPermission().canExtractContent())throw new IOException(UiText.get(R.string.ui_this_pdf_does_not_allow_text_extraction));PDPage page=document.getPage(index);PDRectangle box=page.getCropBox();int rotation=(page.getRotation()%360+360)%360;Page result=new Page();result.width=rotation%180==0?box.getWidth():box.getHeight();result.height=rotation%180==0?box.getHeight():box.getWidth();
        try{page.setRotation(0);PDFTextStripper stripper=new PDFTextStripper(){@Override protected void writeString(String text,List<TextPosition> positions)throws IOException{
            StringBuilder value=new StringBuilder();RectF bounds=null;boolean bold=false;TextPosition previous=null;
            for(TextPosition position:positions){String s=position.getUnicode();if(s==null)continue;float x=position.getX(),y=position.getY(),h=Math.max(1,position.getHeight()),w=Math.max(.1f,position.getWidth());boolean gap=previous!=null&&(x-previous.getX()-previous.getWidth()>h*.25f||Math.abs(y-previous.getY())>h*.7f);
                if(s.trim().isEmpty()||gap){if(value.length()>0&&bounds!=null){add(result,value.toString(),bounds,box,rotation,bold);value.setLength(0);bounds=null;bold=false;}if(s.trim().isEmpty()){previous=position;continue;}}
                value.append(s);RectF r=new RectF(x,y-h,x+w,y+h*.15f);if(bounds==null)bounds=r;else bounds.union(r);bold|=position.getFont().getName().toLowerCase(Locale.ROOT).contains("bold");previous=position;
            }if(value.length()>0&&bounds!=null)add(result,value.toString(),bounds,box,rotation,bold);super.writeString(text,positions);
        }};stripper.setSortByPosition(true);stripper.setStartPage(index+1);stripper.setEndPage(index+1);stripper.getText(document);}finally{page.setRotation(rotation);}
        if(result.words.isEmpty()&&!com.paperflow.scanengine.LocalOcr.activeLanguages(context).isEmpty()){Bitmap image=render(document,index,2200,3000);try{for(com.paperflow.imageprocessing.OcrTextBox word:com.paperflow.scanengine.LocalOcr.recognize(context,image)){com.paperflow.imageprocessing.ImageRect r=word.getBox();result.words.add(new Word(word.getText(),r.getLeft()*result.width/image.getWidth(),r.getTop()*result.height/image.getHeight(),r.getWidth()*result.width/image.getWidth(),r.getHeight()*result.height/image.getHeight(),false));}result.ocr=true;}finally{image.recycle();}}
        return result;
    }
    private static void add(Page page,String value,RectF bounds,PDRectangle box,int rotation,boolean bold){RectF normalized=new RectF(bounds.left/box.getWidth(),bounds.top/box.getHeight(),bounds.right/box.getWidth(),bounds.bottom/box.getHeight());RectF r=PdfTextSelectionActivity.rotate(normalized,rotation);page.words.add(new Word(value,r.left*page.width,r.top*page.height,Math.max(.1f,r.width()*page.width),Math.max(1,r.height()*page.height),bold));}
    static Bitmap render(PDDocument doc,int page,int maxWidth,int maxHeight)throws Exception{PDPage p=doc.getPage(page);PDRectangle b=p.getCropBox();boolean turn=Math.abs(p.getRotation())%180==90;return new com.tom_roush.pdfbox.rendering.PDFRenderer(doc).renderImage(page,Math.min(maxWidth/(turn?b.getHeight():b.getWidth()),maxHeight/(turn?b.getWidth():b.getHeight())));}
    static ArrayList<ArrayList<Word>> rows(Page page){ArrayList<Word> sorted=new ArrayList<>(page.words);sorted.sort(Comparator.comparingDouble((Word w)->w.y+w.h*.5).thenComparingDouble(w->w.x));ArrayList<ArrayList<Word>> rows=new ArrayList<>();for(Word word:sorted){ArrayList<Word> row=rows.isEmpty()?null:rows.get(rows.size()-1);if(row==null||Math.abs((row.get(0).y+row.get(0).h*.5)-(word.y+word.h*.5))>Math.max(3,Math.min(row.get(0).h,word.h)*.6)){row=new ArrayList<>();rows.add(row);}row.add(word);}for(ArrayList<Word> row:rows)row.sort(Comparator.comparingDouble(w->w.x));return rows;}
    static String plain(Page page){StringBuilder text=new StringBuilder();for(ArrayList<Word> row:rows(page)){if(text.length()>0)text.append('\n');for(Word word:row){if(text.length()>0&&text.charAt(text.length()-1)!='\n')text.append(' ');text.append(word.text);}}return text.toString();}
}

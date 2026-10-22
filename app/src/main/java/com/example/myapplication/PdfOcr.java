package com.example.myapplication;

import android.content.Context;
import android.graphics.Bitmap;
import com.paperflow.scanengine.LocalOcr;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.rendering.PDFRenderer;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.util.List;

final class PdfOcr {
    interface Progress { void page(int done,int total); }
    static void apply(Context context,PDDocument document,List<Integer> selected,Progress progress)throws Exception {
        apply(context,document,selected,progress,()->false);
    }
    static void apply(Context context,PDDocument document,List<Integer> selected,Progress progress,java.util.function.BooleanSupplier cancel)throws Exception {
        if(LocalOcr.activeLanguages(context).isEmpty())throw new IllegalStateException(UiText.get(R.string.ui_download_and_enable_ocr_languages_first));
        PdfPermissions.modify(document);PdfPermissions.extract(document);
        PDFTextStripper stripper=new PDFTextStripper();PDFRenderer renderer=new PDFRenderer(document);int done=0;boolean hasText=false;
        for(int index:selected){
            if(cancel.getAsBoolean()||Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException(UiText.get(R.string.ui_ocr_cancelled));
            stripper.setStartPage(index+1);stripper.setEndPage(index+1);
            {
                PDPage page=document.getPage(index);int rotation=page.getRotation();Bitmap image=null;
                try{
                    java.util.ArrayList<PdfKeywordSearch.Glyph> existing=PdfKeywordSearch.glyphs(document,index);
                    page.setRotation(0);float scale=Math.min(2400/page.getCropBox().getWidth(),3200/page.getCropBox().getHeight());image=renderer.renderImage(index,scale);
                    java.util.ArrayList<com.paperflow.imageprocessing.OcrTextBox> additions=new java.util.ArrayList<>();
                    for(com.paperflow.imageprocessing.OcrTextBox word:LocalOcr.recognize(context,image)){
                        com.paperflow.imageprocessing.ImageRect box=word.getBox();
                        android.graphics.RectF area=new android.graphics.RectF(box.getLeft()/(float)image.getWidth(),box.getTop()/(float)image.getHeight(),box.getRight()/(float)image.getWidth(),box.getBottom()/(float)image.getHeight());
                        // Glyphless PDF fonts report approximate vertical metrics. Match
                        // the actual text in the same word area as well as its ink coverage,
                        // so running OCR again does not duplicate short or accented words.
                        StringBuilder nearby=new StringBuilder();
                        for(PdfKeywordSearch.Glyph glyph:existing){
                            android.graphics.RectF r=glyph.reading;
                            if(r.centerX()>=area.left-.002f&&r.centerX()<=area.right+.002f&&Math.abs(r.centerY()-area.centerY())<=Math.max(area.height(),r.height()))nearby.append(glyph.text);
                        }
                        String expected=DocumentStore.normalize(word.getText()).replaceAll("\\s+","");
                        if(!expected.isEmpty()&&expected.equals(DocumentStore.normalize(nearby.toString()).replaceAll("\\s+","")))continue;
                        android.graphics.Region covered=new android.graphics.Region();
                        for(PdfKeywordSearch.Glyph glyph:existing){android.graphics.RectF r=glyph.reading;if(Math.abs(area.centerY()-r.centerY())<Math.max(area.height(),r.height())*.6f){int left=Math.max(box.getLeft(),(int)(r.left*image.getWidth())),top=Math.max(box.getTop(),(int)(r.top*image.getHeight())),right=Math.min(box.getRight(),(int)Math.ceil(r.right*image.getWidth())),bottom=Math.min(box.getBottom(),(int)Math.ceil(r.bottom*image.getHeight()));if(right>left&&bottom>top)covered.op(left,top,right,bottom,android.graphics.Region.Op.UNION);}}
                        long overlap=0;android.graphics.RegionIterator regions=new android.graphics.RegionIterator(covered);android.graphics.Rect part=new android.graphics.Rect();while(regions.next(part))overlap+=(long)part.width()*part.height();
                        if(overlap<.45*(long)box.getWidth()*box.getHeight())additions.add(word);
                    }
                    if(!additions.isEmpty())new com.paperflow.scanengine.OcrDocument(document,context.getAssets()).addPage(page,additions,new com.paperflow.scanengine.PageDimensions(image.getWidth(),image.getHeight(),page.getCropBox().getWidth(),page.getCropBox().getHeight()));
                }
                finally{page.setRotation(rotation);if(image!=null)image.recycle();}
            }
            hasText|=!stripper.getText(document).trim().isEmpty();
            progress.page(++done,selected.size());
        }
        if(!hasText)throw new java.io.IOException(UiText.get(R.string.ui_no_text_was_recognized_on_the_selected_pages_check_the_ocr_languages_and_sc));
    }
}

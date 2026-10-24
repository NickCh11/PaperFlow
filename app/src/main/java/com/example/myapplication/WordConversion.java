package com.example.myapplication;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.rendering.PDFRenderer;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** PDF text and OCR conversion using the shared offline-translator OCR engine. */
final class WordConversion {
    static final int AUTO=0, NATIVE=1, LOCAL=2, LAYOUT=4, APPEARANCE=5;
    interface Progress { void update(int completed,int total,String message); }
    static final class Result {
        final File file; final int pages,nativePages,ocrPages;
        Result(File file,int pages,int nativePages,int ocrPages){this.file=file;this.pages=pages;this.nativePages=nativePages;this.ocrPages=ocrPages;}
    }
    static final class Job {
        volatile boolean cancelled;
        void cancel(){cancelled=true;}
        void check(){if(cancelled||Thread.currentThread().isInterrupted())throw new CancellationException("Conversion cancelled");}
    }

    static Result convert(Context context,String path,String password,int route,Job job,Progress progress)throws Exception {
        if(route!=AUTO&&route!=NATIVE&&route!=LOCAL&&route!=LAYOUT&&route!=APPEARANCE)throw new IllegalArgumentException("Unsupported Word conversion route");
        if(route==LAYOUT||route==APPEARANCE){File dir=new File(context.getFilesDir(),"documents");if(!dir.isDirectory()&&!dir.mkdirs())throw new java.io.IOException(UiText.get(R.string.ui_storage_unavailable));File file=new File(dir,"paperflow-word-"+UUID.randomUUID()+".docx");try{int count=OfficeExport.word(context,new File(path),password,file,route==LAYOUT,null,(done,total)->{job.check();progress.update(done,total,UiText.get(R.string.ui_writing_word_layout));});job.check();return new Result(file,count,0,0);}catch(Exception|OutOfMemoryError e){file.delete();throw e;}}
        File output=null;boolean success=false;ArrayList<String> pages=new ArrayList<>();int nativeCount=0,ocrCount=0;
        try(PDDocument document=PDDocument.load(new File(path),password,MemoryUsageSetting.setupMixed(24*1024*1024))){
            job.check();int total=document.getNumberOfPages();if(total==0)throw new IllegalStateException(UiText.get(R.string.ui_this_pdf_has_no_pages_b4f57307));
            PDFTextStripper stripper=new PDFTextStripper();stripper.setSortByPosition(true);stripper.setLineSeparator("\n");stripper.setPageStart("");stripper.setPageEnd("");
            boolean anyText=false;
            for(int i=0;i<total;i++){
                job.check();String text="";
                if(route==AUTO||route==NATIVE){progress.update(i,total,UiText.format(R.string.ui_reading_pdf_text_page_value_of_value, (i+1), total));stripper.setStartPage(i+1);stripper.setEndPage(i+1);text=clean(stripper.getText(document));if(route==NATIVE){if(!text.isEmpty())nativeCount++;}else if(readable(text)){nativeCount++;}else text="";}
                if(route==LOCAL||(route==AUTO&&text.isEmpty())){
                    job.check();progress.update(i,total,UiText.format(R.string.ui_value_page_value_of_value, UiText.get(R.string.ui_on_device_ocr), (i+1), total));
                    Bitmap image=render(document,i,2000,2600);
                    text=localText(context,image,job); // localText owns and releases its bitmap, including deferred cancellation.
                    ocrCount++;
                }
                job.check();text=clean(text);pages.add(text);if(!text.trim().isEmpty())anyText=true;progress.update(i+1,total,UiText.format(R.string.ui_completed_page_value_of_value, (i+1), total));
            }
            if(!anyText)throw new IllegalStateException(route==NATIVE ? UiText.get(R.string.ui_no_embedded_pdf_text_use_offline_ocr) : UiText.get(R.string.ui_no_readable_text_found_check_the_active_ocr_languages_and_image_clarity));
            job.check();File dir=new File(context.getFilesDir(),"documents");if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException(UiText.get(R.string.ui_storage_unavailable));output=new File(dir,"paperflow-word-"+UUID.randomUUID()+".docx");progress.update(total,total,UiText.get(R.string.ui_writing_word_document));writeDocx(pages,output,job);job.check();success=true;return new Result(output,pages.size(),nativeCount,ocrCount);
        }finally{if(!success&&output!=null)output.delete();}
    }

    private static String clean(String text){if(text==null)return "";return text.replace("\r\n","\n").replace('\r','\n').replace('\f','\n').replaceAll("[\\x00-\\x08\\x0B\\x0E-\\x1F]","").trim();}
    private static boolean readable(String text){int letters=0,bad=0;for(int i=0;i<text.length();i++){char c=text.charAt(i);if(Character.isLetterOrDigit(c))letters++;if(c=='\ufffd')bad++;}return letters>=4&&bad<=Math.max(1,letters/20);}
    private static Bitmap render(PDDocument document,int index,int maxWidth,int maxHeight)throws Exception{PDPage page=document.getPage(index);PDRectangle box=page.getCropBox();boolean turn=Math.abs(page.getRotation())%180==90;float w=turn?box.getHeight():box.getWidth(),h=turn?box.getWidth():box.getHeight();float scale=Math.min(maxWidth/w,maxHeight/h);return new PDFRenderer(document).renderImage(index,scale);}

    /** Waits on the worker; if cancelled, completion owns cleanup while the engine exits immediately. */
    private static String localText(Context context,Bitmap bitmap,Job job)throws Exception{
        try{job.check();if(com.paperflow.scanengine.LocalOcr.activeLanguages(context).isEmpty())throw new IllegalStateException(UiText.get(R.string.ui_download_and_enable_ocr_languages_in_ocr_languages_first));String text=com.paperflow.scanengine.LocalOcr.text(context,bitmap);job.check();return text;}finally{bitmap.recycle();}
    }
    private static void writeDocx(ArrayList<String> pages,File output,Job job)throws Exception{
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(output),StandardCharsets.UTF_8)){
            entry(zip,"[Content_Types].xml","<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
            entry(zip,"_rels/.rels","<?xml version=\"1.0\" encoding=\"UTF-8\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            zip.putNextEntry(new ZipEntry("word/document.xml"));write(zip,"<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
            for(int page=0;page<pages.size();page++){
                job.check();if(page>0)write(zip,"<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");
                for(String line:pages.get(page).split("\\n",-1)){job.check();write(zip,"<w:p><w:r><w:t xml:space=\"preserve\">"+xml(line)+"</w:t></w:r></w:p>");}
            }
            write(zip,"<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/></w:sectPr></w:body></w:document>");zip.closeEntry();
        }
    }
    private static String xml(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}
    private static void write(OutputStream out,String text)throws Exception{out.write(text.getBytes(StandardCharsets.UTF_8));}
    private static void entry(ZipOutputStream zip,String name,String text)throws Exception{zip.putNextEntry(new ZipEntry(name));write(zip,text);zip.closeEntry();}
}

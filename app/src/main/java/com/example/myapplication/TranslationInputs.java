package com.example.myapplication;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.tom_roush.pdfbox.rendering.PDFRenderer;
import java.io.*;
import java.util.List;
import java.util.UUID;

/** Detect actual file contents, then stage bounded page images for the shared offline translator. */
final class TranslationInputs {
    static final int MAX_PAGES=100;
    private static final long MAX_FILE_BYTES=256L*1024*1024;
    interface Cancellation {void check()throws InterruptedIOException;}
    interface Progress {void page(int current,int total);}
    private TranslationInputs(){}

    static void append(Context context,Uri uri,List<File> pages,Cancellation cancellation,Progress progress)throws Exception{
        cancellation.check();checkCount(pages.size(),1);
        File root=new File(context.getFilesDir(),"translation-inputs");
        if(!root.isDirectory()&&!root.mkdirs())throw new IOException(UiText.get(R.string.ui_storage_unavailable));
        File staged=new File(root,UUID.randomUUID()+".image");boolean retained=false;
        try{
            copy(context,uri,staged,cancellation);
            if(isPdf(staged)){
                appendPdf(context,staged,root,pages,cancellation,progress);
            }else{
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                BitmapFactory.decodeFile(staged.getPath(),bounds);
                if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException(UiText.get(R.string.ui_translation_unsupported_input));
                cancellation.check();pages.add(staged);retained=true;
            }
        }finally{if(!retained)staged.delete();}
    }
    private static void appendPdf(Context context,File staged,File root,List<File> pages,Cancellation cancellation,Progress progress)throws Exception{
        PDFBoxResourceLoader.init(context);
        try(PDDocument pdf=PDDocument.load(staged,"",MemoryUsageSetting.setupTempFileOnly())){
            PdfPermissions.extract(pdf);int count=pdf.getNumberOfPages();
            if(count==0)throw new IOException(UiText.get(R.string.ui_this_pdf_has_no_pages_b4f57307));
            checkCount(pages.size(),count);PDFRenderer renderer=new PDFRenderer(pdf);renderer.setSubsamplingAllowed(true);
            for(int index=0;index<count;index++){
                cancellation.check();progress.page(index+1,count);
                File page=new File(root,UUID.randomUUID()+".png");pages.add(page);
                Bitmap image=render(pdf,renderer,index);
                try{cancellation.check();try(OutputStream output=new FileOutputStream(page)){if(!image.compress(Bitmap.CompressFormat.PNG,100,output))throw new IOException(UiText.get(R.string.ui_could_not_save_image));}}
                finally{image.recycle();}
            }
        }catch(InvalidPasswordException locked){throw new IOException(UiText.get(R.string.ui_translation_locked_pdf),locked);}
    }
    private static void checkCount(int existing,int additional)throws IOException{
        if(additional>MAX_PAGES-existing)throw new IOException(UiText.get(R.string.ui_translation_import_page_limit));
    }
    private static void copy(Context context,Uri uri,File target,Cancellation cancellation)throws IOException{
        try(InputStream input=context.getContentResolver().openInputStream(uri);OutputStream output=new FileOutputStream(target)){
            if(input==null)throw new IOException(UiText.get(R.string.ui_translation_unsupported_input));
            byte[] buffer=new byte[65536];long total=0;int read;
            while((read=input.read(buffer))!=-1){cancellation.check();total+=read;if(total>MAX_FILE_BYTES)throw new IOException(UiText.get(R.string.ui_translation_import_size_limit));output.write(buffer,0,read);}
        }
    }
    private static boolean isPdf(File file)throws IOException{
        byte[] header=new byte[1024];int count=0;
        try(InputStream input=new FileInputStream(file)){int read;while(count<header.length&&(read=input.read(header,count,header.length-count))!=-1)count+=read;}
        // PDF readers permit a short prefix before the header. Names and provider MIME types can be wrong.
        for(int i=0;i+4<count;i++)if(header[i]=='%'&&header[i+1]=='P'&&header[i+2]=='D'&&header[i+3]=='F'&&header[i+4]=='-')return true;
        return false;
    }
    private static Bitmap render(PDDocument pdf,PDFRenderer renderer,int index)throws IOException{
        PDRectangle box=pdf.getPage(index).getCropBox();float longest=Math.max(box.getWidth(),box.getHeight());
        if(!Float.isFinite(longest)||!Float.isFinite(box.getWidth())||!Float.isFinite(box.getHeight())||box.getWidth()<=0||box.getHeight()<=0)throw new IOException(UiText.get(R.string.ui_translation_invalid_pdf_page));
        return renderer.renderImage(index,2600f/longest);
    }
}

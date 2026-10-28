package com.example.myapplication;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.paperflow.scanengine.OcrDocument;
import com.paperflow.scanengine.PageDimensions;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Exports complete ordered page snapshots, with one bitmap in memory at a time. */
final class TranslationBatch {
    static android.content.Intent shareIntent(Context context,List<File> files,String mime){
        ArrayList<android.net.Uri> uris=new ArrayList<>();for(File file:files)uris.add(androidx.core.content.FileProvider.getUriForFile(context,context.getPackageName()+".files",file));
        if(uris.isEmpty())throw new IllegalArgumentException("No translated files");
        android.content.Intent intent=new android.content.Intent(uris.size()>1?android.content.Intent.ACTION_SEND_MULTIPLE:android.content.Intent.ACTION_SEND).setType(mime).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if(uris.size()>1)intent.putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM,uris);else intent.putExtra(android.content.Intent.EXTRA_STREAM,uris.get(0));
        android.content.ClipData clip=android.content.ClipData.newRawUri("Translation",uris.get(0));for(int i=1;i<uris.size();i++)clip.addItem(new android.content.ClipData.Item(uris.get(i)));intent.setClipData(clip);return intent;
    }
    static ArrayList<File> export(Context context,List<File> snapshots,File directory,boolean pdf,BooleanSupplier cancelled)throws Exception {
        if(snapshots.isEmpty())throw new IOException(UiText.get(R.string.ui_no_translated_pages));
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException(UiText.get(R.string.ui_storage_unavailable));
        ArrayList<File> outputs=new ArrayList<>();
        try {
            if(pdf){
                File file=new File(directory,"Translated document.pdf");outputs.add(file);
                try(PDDocument document=new PDDocument(com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())){
                    for(File snapshot:snapshots){check(cancelled);append(context,document,snapshot);}
                    check(cancelled);document.save(file);
                }
            }else for(int i=0;i<snapshots.size();i++){
                check(cancelled);File snapshot=TranslationSnapshot.owned(context,snapshots.get(i).getPath(),"translation-sessions");
                File image=new File(directory,String.format(Locale.ROOT,"Translated-page-%03d.png",i+1));outputs.add(image);Files.copy(new File(snapshot,"result.png").toPath(),image.toPath());
            }
            check(cancelled);return outputs;
        }catch(Exception|OutOfMemoryError error){for(File file:outputs)file.delete();directory.delete();throw error;}
    }
    static void append(Context context,PDDocument document,File directory)throws Exception {
        File snapshot=TranslationSnapshot.owned(context,directory.getPath(),"translation-sessions");
        Bitmap image=BitmapFactory.decodeFile(new File(snapshot,"result.png").getPath());if(image==null)throw new IOException(UiText.get(R.string.ui_translation_snapshot_unavailable));
        try {
            org.json.JSONObject data=new org.json.JSONObject(new String(Files.readAllBytes(new File(snapshot,"session.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
            float width=595,height=width*image.getHeight()/image.getWidth();PDPage page=new PDPage(new PDRectangle(width,height));document.addPage(page);
            try(PDPageContentStream stream=new PDPageContentStream(document,page)){stream.drawImage(JPEGFactory.createFromImage(document,image,.94f),0,0,width,height);}
            new OcrDocument(document,context.getAssets()).addPage(page,TranslationSnapshot.readWords(data.getJSONArray("words")),new PageDimensions(image.getWidth(),image.getHeight(),width,height));
        }finally{image.recycle();}
    }
    static void check(BooleanSupplier cancelled)throws InterruptedIOException{if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException(UiText.get(R.string.ui_translation_cancelled));}
}

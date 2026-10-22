package com.example.myapplication;

import android.content.Context;
import android.graphics.Bitmap;
import com.paperflow.scanengine.*;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.paperflow.imageprocessing.OcrTextBox;
import java.util.List;

/** The final pixels and their OCR share the same coordinate space. */
final class SearchablePdf {
    static void append(Context context,PDDocument document,Bitmap image,float width,float height)throws Exception {
        PDPage page=new PDPage(new PDRectangle(width,height));document.addPage(page);
        try(PDPageContentStream stream=new PDPageContentStream(document,page)){
            stream.drawImage(JPEGFactory.createFromImage(document,image,.94f),0,0,width,height);
        }
        addText(context,document,page,image);
    }
    static void addText(Context context,PDDocument document,PDPage page,Bitmap image)throws Exception {
        List<OcrTextBox> words=LocalOcr.recognize(context,image);
        if(!words.isEmpty())new OcrDocument(document,context.getAssets()).addPage(page,words,
            new PageDimensions(image.getWidth(),image.getHeight(),page.getCropBox().getWidth(),page.getCropBox().getHeight()));
    }
}

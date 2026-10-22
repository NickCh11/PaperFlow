package com.example.myapplication;

import android.graphics.Bitmap;
import com.tom_roush.pdfbox.cos.*;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.image.*;
import java.util.*;

/** Recompress photograph XObjects while retaining text, vectors, annotations and page geometry. */
final class PdfCompression {
    static void images(PDDocument document,int preset)throws Exception{
        Set<COSDictionary> visited=Collections.newSetFromMap(new IdentityHashMap<>());
        for(PDPage page:document.getPages())resources(document,page.getResources(),preset,visited);
    }
    private static void resources(PDDocument document,PDResources resources,int preset,Set<COSDictionary> visited)throws Exception{
        if(resources==null||!visited.add(resources.getCOSObject()))return;
        ArrayList<COSName> names=new ArrayList<>();for(COSName name:resources.getXObjectNames())names.add(name);
        for(COSName name:names){
            PDXObject object=resources.getXObject(name);
            if(object instanceof PDFormXObject){resources(document,((PDFormXObject)object).getResources(),preset,visited);continue;}
            if(!(object instanceof PDImageXObject))continue;
            PDImageXObject image=(PDImageXObject)object;
            // Masked, indexed/monochrome and tiny graphics remain untouched.
            if(image.isStencil()||image.getBitsPerComponent()!=8||image.getCOSObject().containsKey(COSName.SMASK)||image.getCOSObject().containsKey(COSName.MASK)||image.getWidth()<256||image.getHeight()<256)continue;
            int edge=preset==0?2200:1400,sample=1;while(Math.max(image.getWidth(),image.getHeight())/sample>edge*2)sample*=2;
            Bitmap bitmap=image.getImage(new android.graphics.Rect(0,0,image.getWidth(),image.getHeight()),sample);if(bitmap==null)continue;
            try{
                // PDF image transparency is represented by masks, excluded above.
                // An ARGB bitmap flag alone does not imply transparent source pixels.
                float scale=Math.min(1f,edge/(float)Math.max(bitmap.getWidth(),bitmap.getHeight()));
                Bitmap resized=scale<1?Bitmap.createScaledBitmap(bitmap,Math.max(1,Math.round(bitmap.getWidth()*scale)),Math.max(1,Math.round(bitmap.getHeight()*scale)),true):bitmap;
                try{PDImageXObject replacement=JPEGFactory.createFromImage(document,resized,preset==0?.75f:.5f);
                    if(replacement.getCOSObject().getInt(COSName.LENGTH)<image.getCOSObject().getInt(COSName.LENGTH))resources.put(name,replacement);
                }finally{if(resized!=bitmap)resized.recycle();}
            }finally{bitmap.recycle();}
        }
    }
}

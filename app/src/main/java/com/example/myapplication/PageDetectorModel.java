package com.example.myapplication;
import android.content.Context;
import android.graphics.Bitmap;
import com.paperflow.scanengine.ScanEngine;
/** Compatibility data adapter for Book crease detection, backed by the local scan engine. */
final class PageDetectorModel {
    static void configure(Context c){ScanEngine.configure(c);}
    static String version(){return "page-segmentation-v1";}
    static final class Prediction { final int width,height;final byte[] page;final float[] corners;final float confidence;
        Prediction(int w,int h,byte[] p,float[] q,float c){width=w;height=h;page=p;corners=q;confidence=c;} }
    static byte[] resampleMask(float[] mask,int sourceWidth,int sourceHeight,int width,int height){
        if(mask==null||sourceWidth<=0||sourceHeight<=0||(long)sourceWidth*sourceHeight>mask.length||width<=0||height<=0||(long)width*height>Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid page mask dimensions");
        byte[] result=new byte[width*height];
        for(int y=0;y<height;y++){
            int sourceY=(int)((long)y*sourceHeight/height);
            for(int x=0;x<width;x++){
                int sourceX=(int)((long)x*sourceWidth/width);
                float value=mask[sourceY*sourceWidth+sourceX];
                result[y*width+x]=(byte)Math.round(255*Math.max(0,Math.min(1,value)));
            }
        }
        return result;
    }
    static Prediction predict(Bitmap b,int limit,int ignored){ScanEngine.Analysis a=ScanEngine.analyze(b,false);float scale=Math.min(1,limit/(float)Math.max(b.getWidth(),b.getHeight()));int w=Math.max(1,Math.round(b.getWidth()*scale)),h=Math.max(1,Math.round(b.getHeight()*scale));return new Prediction(w,h,resampleMask(a.mask,a.width,a.height,w,h),a.corners,1);}
}

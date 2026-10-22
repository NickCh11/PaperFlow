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
    static Prediction predict(Bitmap b,int limit,int ignored){ScanEngine.Analysis a=ScanEngine.analyze(b,false);float scale=Math.min(1,limit/(float)Math.max(b.getWidth(),b.getHeight()));int w=Math.max(1,Math.round(b.getWidth()*scale)),h=Math.max(1,Math.round(b.getHeight()*scale));byte[] p=new byte[w*h];for(int y=0;y<h;y++)for(int x=0;x<w;x++)p[y*w+x]=(byte)Math.round(255*a.mask[Math.min(a.height-1,y*a.height/h)*a.width+Math.min(a.width-1,x*a.width/w)]);return new Prediction(w,h,p,a.corners,1);}
}

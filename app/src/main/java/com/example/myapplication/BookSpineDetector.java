package com.example.myapplication;

import android.graphics.*;
import java.util.*;

/** Independently authored crease detector using the ready page model's mask and image contrast.
 * This is not the reference application's separate learned Book model.
 */
final class BookSpineDetector {
    static DocumentEdgeLines.Line find(Bitmap source,PageDetectorModel.Prediction prediction){
        int w=prediction.width,h=prediction.height;
        Bitmap small=Bitmap.createScaledBitmap(source,w,h,true);
        int[] pixels=new int[w*h];small.getPixels(pixels,0,w,0,0,w,h);if(small!=source)small.recycle();
        byte[] gray=new byte[pixels.length];for(int i=0;i<pixels.length;i++){int c=pixels[i];gray[i]=(byte)((77*Color.red(c)+150*Color.green(c)+29*Color.blue(c))>>8);}
        return find(gray,prediction.page,w,h);
    }

    static DocumentEdgeLines.Line find(byte[] gray,byte[] page,int w,int h){
        if(gray==null||page==null||w<32||h<32||gray.length!=w*h||page.length!=gray.length)return null;
        DocumentEdgeLines.Line best=null;double bestScore=0;
        double flank=Math.max(5,Math.min(w,h)*.025),step=3;
        for(int axis=0;axis<2;axis++)for(int tilt=-25;tilt<=25;tilt+=5){
            double radians=Math.toRadians((axis==0?90:0)+tilt),dx=Math.cos(radians),dy=Math.sin(radians),nx=-dy,ny=dx;
            int span=axis==0?w:h;
            for(int position=(int)(span*.15);position<span*.85;position++){
                double cx=axis==0?position:w*.5,cy=axis==0?h*.5:position;
                int valid=0,strong=0;double sum=0,first=Double.NaN,last=0;
                double extent=Math.hypot(w,h)*.5;
                for(double t=-extent;t<=extent;t+=step){
                    double x=cx+t*dx,y=cy+t*dy;
                    if(!inside(x,y,w,h)||!inside(x+nx*flank*2,y+ny*flank*2,w,h)||!inside(x-nx*flank*2,y-ny*flank*2,w,h))continue;
                    if(sample(page,x+nx*flank*2,y+ny*flank*2,w)<180||sample(page,x-nx*flank*2,y-ny*flank*2,w)<180)continue;
                    valid++;
                    double center=(sample(gray,x,y,w)+sample(gray,x+nx,y+ny,w)+sample(gray,x-nx,y-ny,w))/3.;
                    double left=(sample(gray,x+nx*flank,y+ny*flank,w)+sample(gray,x+nx*flank*2,y+ny*flank*2,w))/2.;
                    double right=(sample(gray,x-nx*flank,y-ny*flank,w)+sample(gray,x-nx*flank*2,y-ny*flank*2,w))/2.;
                    double contrast=Math.min(left,right)-center;
                    double broadContrast=Math.min(left,right)-Math.max(sample(gray,x+2*nx,y+2*ny,w),sample(gray,x-2*nx,y-2*ny,w));
                    if(contrast>=12&&broadContrast>=8){strong++;sum+=Math.min(90,contrast);if(Double.isNaN(first))first=t;last=t;}
                }
                if(valid*step<Math.min(w,h)*.5||strong<valid*.67||Double.isNaN(first)||last-first<Math.min(w,h)*.5)continue;
                double score=sum/valid*Math.sqrt(strong*step);
                if(score>bestScore){bestScore=score;best=new DocumentEdgeLines.Line(cx+first*dx,cy+first*dy,cx+last*dx,cy+last*dy);}
            }
        }
        return best;
    }
    private static boolean inside(double x,double y,int w,int h){return x>=0&&y>=0&&x<w-1&&y<h-1;}
    private static int sample(byte[] values,double x,double y,int w){return values[(int)Math.round(y)*w+(int)Math.round(x)]&255;}
}

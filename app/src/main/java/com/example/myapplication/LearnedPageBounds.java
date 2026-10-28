package com.example.myapplication;

import android.graphics.Bitmap;
import java.util.*;

/** Stateless full-photo response model followed by independently authored supported-line geometry. */
final class LearnedPageBounds {
    static float[] detect(Bitmap bitmap)throws Exception{
        PageDetectorModel.Prediction prediction=PageDetectorModel.predict(bitmap,416,416);
        return prediction==null?null:quad(prediction.page,prediction.width,prediction.height,bitmap);
    }
    /** Explicit Double always produces two pages; a visible spine refines the split. */
    static List<float[]> book(Bitmap bitmap,boolean doublePage)throws Exception{
        PageDetectorModel.Prediction prediction=PageDetectorModel.predict(bitmap,416,416);
        if(prediction==null)return bookFallback(bitmap,full(),doublePage);
        int w=prediction.width,h=prediction.height;byte[] page=PageResponseGeometry.filled(prediction.page,w,h);
        float[] whole=orFull(PageBoundaryDetector.refine(bitmap,prediction));
        if(!doublePage)return Collections.singletonList(whole);
        // The single-sheet network can leave an entire half of a spread unlabelled. For Book,
        // add visible neutral paper inside the independently measured outer spread only.
        addPaperSupport(bitmap,page,whole,w,h);
        DocumentEdgeLines.Line selected=BookSpineDetector.find(bitmap,new PageDetectorModel.Prediction(w,h,page,prediction.corners,prediction.confidence));
        if(selected==null)return bookFallback(bitmap,whole,true);
        boolean rotated=Math.abs(selected.x2-selected.x1)>=Math.abs(selected.y2-selected.y1);
        if(rotated){page=rotate(page,w,h);selected=new DocumentEdgeLines.Line(h-1-selected.y1,selected.x1,h-1-selected.y2,selected.x2);int swap=w;w=h;h=swap;}
        byte[] left=new byte[w*h],right=new byte[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int i=y*w+x;double side=selected.a*x+selected.b*y+selected.c;int probability=page[i]&255;
            if(side<=1.5)left[i]=page[i];if(side>=-1.5)right[i]=page[i];
            if(Math.abs(side)<=1.5&&probability>64){left[i]=page[i];right[i]=page[i];}
        }
        float[] a=quad(left,w,h,bitmap),b=quad(right,w,h,bitmap);
        if(a==null||b==null||!separated(a,b,selected,w,h))return bookFallback(bitmap,whole,true);
        if(a[0]+a[2]+a[4]+a[6]>b[0]+b[2]+b[4]+b[6]){float[] swap=a;a=b;b=swap;}
        if(rotated){a=restore(a);b=restore(b);}
        return Arrays.asList(a,b);
    }
    /** Keep the selected capture mode when automatic spine detection is uncertain. */
    static List<float[]> bookFallback(Bitmap image,float[] detected,boolean doublePage){
        float[] p=detected!=null&&PageBoundaryDetector.valid(detected)?detected:full();
        if(!doublePage)return Collections.singletonList(p);
        double width=(edge(image,p,0,1)+edge(image,p,3,2))*.5;
        double height=(edge(image,p,0,3)+edge(image,p,1,2))*.5;
        if(width>=height){
            float tx=(p[0]+p[2])*.5f,ty=(p[1]+p[3])*.5f,bx=(p[6]+p[4])*.5f,by=(p[7]+p[5])*.5f;
            return Arrays.asList(new float[]{p[0],p[1],tx,ty,bx,by,p[6],p[7]},new float[]{tx,ty,p[2],p[3],p[4],p[5],bx,by});
        }
        float lx=(p[0]+p[6])*.5f,ly=(p[1]+p[7])*.5f,rx=(p[2]+p[4])*.5f,ry=(p[3]+p[5])*.5f;
        return Arrays.asList(new float[]{p[0],p[1],p[2],p[3],rx,ry,lx,ly},new float[]{lx,ly,rx,ry,p[4],p[5],p[6],p[7]});
    }
    private static double edge(Bitmap image,float[] points,int a,int b){return Math.hypot((points[a*2]-points[b*2])*image.getWidth(),(points[a*2+1]-points[b*2+1])*image.getHeight());}
    private static void addPaperSupport(Bitmap source,byte[] page,float[] whole,int w,int h){
        Bitmap small=Bitmap.createScaledBitmap(source,w,h,true);int[] pixels=new int[w*h];small.getPixels(pixels,0,w,0,0,w,h);if(small!=source)small.recycle();
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int c=pixels[y*w+x],r=(c>>16)&255,g=(c>>8)&255,b=c&255;if(Math.min(r,Math.min(g,b))<100||Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))>40)continue;
            double px=(x+.5)/w,py=(y+.5)/h;boolean inside=false;for(int i=0,j=3;i<4;j=i++){double xi=whole[2*i],yi=whole[2*i+1],xj=whole[2*j],yj=whole[2*j+1];if((yi>py)!=(yj>py)&&px<(xj-xi)*(py-yi)/(yj-yi)+xi)inside=!inside;}if(inside)page[y*w+x]=(byte)255;
        }
    }
    private static boolean separated(float[] a,float[] b,DocumentEdgeLines.Line spine,int w,int h){
        double ax=0,ay=0,bx=0,by=0;for(int i=0;i<4;i++){ax+=a[2*i]*w/4;ay+=a[2*i+1]*h/4;bx+=b[2*i]*w/4;by+=b[2*i+1]*h/4;}
        return spine.a*ax+spine.b*ay+spine.c<0&&spine.a*bx+spine.b*by+spine.c>0&&Math.hypot(ax-bx,ay-by)>Math.min(w,h)*.1;
    }
    private static byte[] rotate(byte[] src,int w,int h){byte[] dst=new byte[src.length];for(int y=0;y<h;y++)for(int x=0;x<w;x++)dst[x*h+(h-1-y)]=src[y*w+x];return dst;}
    // Retain the rotated rectangle's corner sequence: perspective mapping restores its orientation.
    private static float[] restore(float[] rotated){float[] result=new float[8];for(int i=0;i<4;i++){result[2*i]=rotated[2*i+1];result[2*i+1]=1-rotated[2*i];}return result;}
    private static float[] quad(byte[] mask,int w,int h,Bitmap image){float[] values=new float[mask.length];for(int i=0;i<values.length;i++)values[i]=(mask[i]&255)/255f;return com.paperflow.scanengine.ScanEngine.quad(values,w,h,image.getWidth(),image.getHeight(),false);}
    private static float[] orFull(float[] value){return value==null?full():value;}
    private static float[] full(){return new float[]{0,0,1,0,1,1,0,1};}
}

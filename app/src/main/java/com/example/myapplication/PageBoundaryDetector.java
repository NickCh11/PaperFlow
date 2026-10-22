package com.example.myapplication;
import android.graphics.Bitmap;
import org.opencv.core.Point;
import java.util.*;
import com.paperflow.scanengine.ScanEngine;
/** Local document segmentation and geometry. */
final class PageBoundaryDetector {
    static boolean ready(){return ScanEngine.ready();}
    static float[] detect(Bitmap source){try{return ScanEngine.analyze(source,false).corners;}catch(Exception|LinkageError e){android.util.Log.w("PageDetection","Document analysis unavailable",e);return null;}}
    static float[] detectImageGeometry(Bitmap source){return detect(source);}
    static float[] refine(Bitmap source,PageDetectorModel.Prediction ignored){return detect(source);}
    static float[] ordered(Point[] points,int width,int height){
        double cx=0,cy=0;for(Point p:points){cx+=p.x/4;cy+=p.y/4;}final double x=cx,y=cy;
        Arrays.sort(points,Comparator.comparingDouble(p->Math.atan2(p.y-y,p.x-x)));
        int first=0;for(int i=1;i<4;i++)if(points[i].x+points[i].y<points[first].x+points[first].y)first=i;
        float[] result=new float[8];for(int i=0;i<4;i++){Point p=points[(first+i)%4];result[2*i]=(float)p.x/width;result[2*i+1]=(float)p.y/height;}return result;
    }
    static boolean valid(float[] p){
        if(p==null||p.length!=8)return false;double min=10,max=0;
        for(float v:p)if(!Float.isFinite(v)||v<0||v>1)return false;
        for(int i=0;i<4;i++){int j=(i+1)%4,k=(i+2)%4;double ax=p[2*j]-p[2*i],ay=p[2*j+1]-p[2*i+1],bx=p[2*k]-p[2*j],by=p[2*k+1]-p[2*j+1];if(ax*by-ay*bx<=.0003)return false;double length=Math.hypot(ax,ay);min=Math.min(min,length);max=Math.max(max,length);double cosine=Math.abs((ax*bx+ay*by)/(length*Math.hypot(bx,by)));if(cosine>.88)return false;}
        return min>.045&&max/min<12&&area(p)>.025;
    }
    private static double area(float[] p){double sum=0;for(int i=0;i<4;i++){int j=(i+1)%4;sum+=p[2*i]*p[2*j+1]-p[2*j]*p[2*i+1];}return sum/2;}
}

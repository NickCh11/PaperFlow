package com.example.myapplication;

import org.opencv.core.*;
import org.opencv.imgproc.*;
import java.util.*;

/** Recovers a page from independent straight edges when shadows/objects break its contour. */
final class PageBoundaryLines {
    private static LineSegmentDetector detector;
    private static synchronized void detectSegments(Mat image,Mat segments){
        if(detector==null)detector=Imgproc.createLineSegmentDetector(Imgproc.LSD_REFINE_STD);
        detector.detect(image,segments);
    }
    static final class Line {
        double x,y,u,v,a,b,c,length,angle,rank;
        Line(double[] p,byte[] gray,int w,int h){x=p[0];y=p[1];u=p[2];v=p[3];double dx=u-x,dy=v-y;length=Math.hypot(dx,dy);a=-dy/length;b=dx/length;c=-a*x-b*y;angle=Math.atan2(dy,dx);if(angle<0)angle+=Math.PI;
            double contrast=0;for(int i=1;i<=9;i++){double t=i/10.;double px=x+t*dx,py=y+t*dy;contrast+=Math.abs(sample(gray,w,h,px+a*3,py+b*3)-sample(gray,w,h,px-a*3,py-b*3));}rank=length*(.7+.3*Math.min(1,contrast/9/25));}
    }
    static List<float[]> candidates(Mat image,byte[] gray){
        return candidates(image,gray,.65);
    }
    static List<float[]> candidates(Mat image,byte[] gray,double supportFraction){
        int w=image.cols(),h=image.rows();Mat segments=new Mat();ArrayList<Line> lines=new ArrayList<>();
        try{detectSegments(image,segments);for(int i=0;i<segments.rows();i++){double[] p=segments.get(i,0);if(Math.hypot(p[2]-p[0],p[3]-p[1])<Math.min(w,h)*.10)continue;lines.add(new Line(p,gray,w,h));}}finally{segments.release();}
        lines.sort((a,b)->Double.compare(b.rank,a.rank));if(lines.size()>60)lines.subList(60,lines.size()).clear();
        ArrayList<Double> directions=new ArrayList<>();for(Line line:lines){double angle=line.angle%(Math.PI/2);boolean duplicate=false;for(double d:directions)if(axisDistance(angle,d)<Math.toRadians(20)){duplicate=true;break;}if(!duplicate)directions.add(angle);if(directions.size()>=3)break;}
        ArrayList<float[]> result=new ArrayList<>();
        for(double direction:directions){ArrayList<Line> horizontal=select(lines,direction),vertical=select(lines,direction+Math.PI/2);
            for(int a=0;a<horizontal.size();a++)for(int b=a+1;b<horizontal.size();b++){
                Line top=horizontal.get(a),bottom=horizontal.get(b);if(angleDistance(top.angle,bottom.angle)>Math.toRadians(35))continue;
                for(int c=0;c<vertical.size();c++)for(int d=c+1;d<vertical.size();d++){
                    Line left=vertical.get(c),right=vertical.get(d);if(angleDistance(left.angle,right.angle)>Math.toRadians(35))continue;
                    Point p=intersection(top,left),q=intersection(top,right),r=intersection(bottom,right),s=intersection(bottom,left);if(p==null||q==null||r==null||s==null)continue;
                    if(!supported(top,p,q,supportFraction)||!supported(bottom,s,r,supportFraction)||!supported(left,p,s,supportFraction)||!supported(right,q,r,supportFraction))continue;
                    float[] corners=PageBoundaryDetector.ordered(new Point[]{p,q,r,s},w,h);if(!PageBoundaryDetector.valid(corners))continue;
                    result.add(corners);
                }
            }
        }return result;
    }
    private static ArrayList<Line> select(List<Line> lines,double angle){ArrayList<Line> selected=new ArrayList<>();for(Line line:lines){if(angleDistance(line.angle,angle)>Math.toRadians(28))continue;boolean duplicate=false;for(Line other:selected)if(angleDistance(line.angle,other.angle)<Math.toRadians(3)&&Math.abs(other.a*line.x+other.b*line.y+other.c)<4){duplicate=true;break;}if(!duplicate)selected.add(line);if(selected.size()>=12)break;}return selected;}
    private static boolean supported(Line l,Point p,Point q,double fraction){double length=Math.hypot(p.x-q.x,p.y-q.y);if(length<1||l.length/length<fraction)return false;double dx=(q.x-p.x)/length,dy=(q.y-p.y)/length,t0=(l.x-p.x)*dx+(l.y-p.y)*dy,t1=(l.u-p.x)*dx+(l.v-p.y)*dy;return Math.min(length,Math.max(t0,t1))-Math.max(0,Math.min(t0,t1))>length*fraction;}
    private static Point intersection(Line a,Line b){double det=a.a*b.b-b.a*a.b;if(Math.abs(det)<.2)return null;return new Point((a.b*b.c-b.b*a.c)/det,(a.c*b.a-b.c*a.a)/det);}
    private static double angleDistance(double a,double b){double diff=Math.abs(a-b)%Math.PI;return Math.min(diff,Math.PI-diff);}
    private static double axisDistance(double a,double b){double diff=Math.abs(a-b)%(Math.PI/2);return Math.min(diff,Math.PI/2-diff);}
    private static int sample(byte[] gray,int w,int h,double x,double y){int px=Math.max(0,Math.min(w-1,(int)Math.round(x))),py=Math.max(0,Math.min(h-1,(int)Math.round(y)));return gray[py*w+px]&255;}
}

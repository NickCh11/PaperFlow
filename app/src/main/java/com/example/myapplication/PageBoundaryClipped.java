package com.example.myapplication;

import java.util.*;

/** Visible top/bottom edges of a bright page whose sides extend past the frame. */
final class PageBoundaryClipped {
    private static final int COLUMNS=35;
    private static final class Peak {final double x,y,contrast;final int column;Peak(double x,double y,double contrast,int column){this.x=x;this.y=y;this.contrast=contrast;this.column=column;}}
    private static final class Edge {double slope,intercept;Edge(double slope,double intercept){this.slope=slope;this.intercept=intercept;}double y(double x){return slope*x+intercept;}}
    static float[] detect(byte[] gray,int w,int h){return detect(gray,w,h,false);}
    static float[] detect(byte[] gray,int w,int h,boolean relaxed){
        if(w<=h)return detectPortrait(gray,w,h,relaxed);
        byte[] rotated=new byte[gray.length];for(int y=0;y<h;y++)for(int x=0;x<w;x++)rotated[x*h+h-1-y]=gray[y*w+x];
        float[] points=detectPortrait(rotated,h,w,relaxed);if(points==null)return null;org.opencv.core.Point[] mapped=new org.opencv.core.Point[4];for(int i=0;i<4;i++)mapped[i]=new org.opencv.core.Point(points[i*2+1]*w,(1-points[i*2])*h);return PageBoundaryDetector.ordered(mapped,w,h);
    }
    private static float[] detectPortrait(byte[] gray,int w,int h,boolean relaxed){
        if(w<80||h<120)return null;
        Edge top=relaxed?edge(gray,w,h,true):edge(gray,w,h,true,24),bottom=relaxed?edge(gray,w,h,false):edge(gray,w,h,false,24);if(top==null&&bottom==null)return null;
        if(top==null){if(!brightFrameRow(gray,w,h,true,relaxed))return null;top=new Edge(0,0);}
        if(bottom==null){if(!brightFrameRow(gray,w,h,false,relaxed))return null;bottom=new Edge(0,h);}
        double tl=top.y(0),tr=top.y(w-1),bl=bottom.y(0),br=bottom.y(w-1);
        if(tl<0||tr<0||bl>h||br>h||Math.min(bl-tl,br-tr)<h*.60)return null;
        int left=0,right=0,total=21;
        for(int i=0;i<total;i++){
            double t=.12+.76*i/(total-1.);int cy=(int)Math.round((tl+tr)/2+t*((bl+br-tl-tr)/2));
            int center=median(gray,w,h,new int[]{(int)(w*.35),(int)(w*.5),(int)(w*.65)},cy),threshold=Math.max(relaxed?65:105,(int)(center*(relaxed?.55:.68)));
            if(median(gray,w,h,new int[]{(int)(w*.04),(int)(w*.06),(int)(w*.08)},cy)>=threshold)left++;
            if(median(gray,w,h,new int[]{(int)(w*.92),(int)(w*.94),(int)(w*.96)},cy)>=threshold)right++;
        }
        if(left<total*(relaxed?.65:.80)||right<total*(relaxed?.65:.80))return null;
        return new float[]{0,(float)tl/h,1,(float)tr/h,1,(float)br/h,0,(float)bl/h};
    }
    private static Edge edge(byte[] gray,int w,int h,boolean top){
        Edge strong=edge(gray,w,h,top,20);return strong!=null?strong:edge(gray,w,h,top,12);
    }
    private static Edge edge(byte[] gray,int w,int h,boolean top,int minimumContrast){
        int distance=Math.max(3,Math.round(h*.006f)),start=top?distance+2:(int)(h*.65),end=top?(int)(h*.35):h-distance-3;
        ArrayList<Peak> peaks=new ArrayList<>();
        for(int column=0;column<COLUMNS;column++){
            int x=Math.round(w*(.03f+.94f*column/(COLUMNS-1)));double[] prefix=new double[h+1];
            for(int y=0;y<h;y++)prefix[y+1]=prefix[y]+((gray[y*w+Math.max(0,x-1)]&255)+(gray[y*w+x]&255)+(gray[y*w+Math.min(w-1,x+1)]&255))/3.;
            double[] scores=new double[Math.max(0,end-start+1)];
            for(int y=start;y<=end;y++){
                double above=(prefix[y-1]-prefix[y-distance-1])/distance,below=(prefix[y+distance+2]-prefix[y+2])/distance;
                double inside=top?below:above,contrast=top?below-above:above-below;
                int a=top?Math.max(0,y-distance*6):Math.min(h,y+distance*2),b=top?Math.max(0,y-distance*2):Math.min(h,y+distance*6);
                double outside=b>a?(prefix[b]-prefix[a])/(b-a):inside;
                if(inside>=(minimumContrast==24?125:80)&&contrast>=minimumContrast&&inside-outside>=minimumContrast)scores[y-start]=contrast;
            }
            for(int k=0;k<3;k++){int best=-1;double value=0;for(int i=0;i<scores.length;i++)if(scores[i]>value){value=scores[i];best=i;}if(best<0)break;peaks.add(new Peak(x,best+start,value,column));for(int i=Math.max(0,best-distance*3);i<=Math.min(scores.length-1,best+distance*3);i++)scores[i]=0;}
        }
        Edge best=null;double bestScore=0;double tolerance=Math.max(3,h*(top?.014:.008));
        for(int a=0;a<peaks.size();a++)for(int b=a+1;b<peaks.size();b++){
            Peak p=peaks.get(a),q=peaks.get(b);if(Math.abs(q.x-p.x)<w*.5)continue;double slope=(q.y-p.y)/(q.x-p.x);if(Math.abs(slope)>.35)continue;
            Edge candidate=new Edge(slope,p.y-slope*p.x);Peak[] matches=matches(peaks,candidate,tolerance);int count=0;double quality=0;
            for(Peak match:matches)if(match!=null){count++;quality+=Math.min(140,match.contrast);}if(count<COLUMNS*.72)continue;
            double score=count+quality/(COLUMNS*200.);if(score>bestScore){bestScore=score;best=candidate;}
        }
        if(best==null)return null;
        Peak[] points=matches(peaks,best,tolerance);double sx=0,sy=0,sxx=0,sxy=0;int n=0;
        for(Peak p:points)if(p!=null){sx+=p.x;sy+=p.y;sxx+=p.x*p.x;sxy+=p.x*p.y;n++;}
        double denominator=n*sxx-sx*sx;if(Math.abs(denominator)<1)return null;double slope=(n*sxy-sx*sy)/denominator,intercept=(sy-slope*sx)/n;
        double[] residuals=new double[n];int index=0;for(Peak p:points)if(p!=null)residuals[index++]=top?slope*p.x+intercept-p.y:p.y-(slope*p.x+intercept);Arrays.sort(residuals);
        double margin=Math.max(1,residuals[(int)((residuals.length-1)*.90)]);return new Edge(slope,intercept+(top?-margin:margin));
    }
    private static Peak[] matches(List<Peak> points,Edge edge,double tolerance){Peak[] best=new Peak[COLUMNS];for(Peak p:points){double error=Math.abs(edge.y(p.x)-p.y);if(error>tolerance)continue;Peak previous=best[p.column];if(previous==null||error<Math.abs(edge.y(previous.x)-previous.y))best[p.column]=p;}return best;}
    private static boolean brightFrameRow(byte[] gray,int w,int h,boolean top,boolean relaxed){int good=0,y=top?Math.min(h-1,2):h-3;for(int i=0;i<COLUMNS;i++){int x=Math.round(w*(.03f+.94f*i/(COLUMNS-1)));if((gray[y*w+x]&255)>=(relaxed?80:130))good++;}return good>=COLUMNS*(relaxed&&!top?.15:.85);}
    private static int median(byte[] gray,int w,int h,int[] xs,int y){int[] values=new int[xs.length];y=Math.max(0,Math.min(h-1,y));for(int i=0;i<xs.length;i++)values[i]=gray[y*w+Math.max(0,Math.min(w-1,xs[i]))]&255;Arrays.sort(values);return values[values.length/2];}
}

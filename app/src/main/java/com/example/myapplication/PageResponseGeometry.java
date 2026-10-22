package com.example.myapplication;

/** Converts an interior-probability mask into a clean page boundary response. */
final class PageResponseGeometry {
    static byte[] filled(byte[] probabilities,int w,int h){
        int n=w*h;byte[] result=new byte[n];boolean[] seen=new boolean[n];int[] queue=new int[n],largest=new int[0];java.util.ArrayList<int[]> components=new java.util.ArrayList<>();
        for(int start=0;start<n;start++){
            if(seen[start]||(probabilities[start]&255)<128)continue;
            int head=0,tail=0;queue[tail++]=start;seen[start]=true;
            while(head<tail){int i=queue[head++],x=i%w,y=i/w;
                if(x>0)tail=add(i-1,probabilities,seen,queue,tail);if(x+1<w)tail=add(i+1,probabilities,seen,queue,tail);
                if(y>0)tail=add(i-w,probabilities,seen,queue,tail);if(y+1<h)tail=add(i+w,probabilities,seen,queue,tail);
            }
            int[] component=java.util.Arrays.copyOf(queue,tail);components.add(component);if(tail>largest.length)largest=component;
        }
        for(int i:largest)result[i]=(byte)255;
        // Shadows can disconnect portions of one sheet. Retain substantial components
        // aligned along its long axis, without merging a neighboring book page sideways.
        if(largest.length>0){int[] main=extent(largest,w);boolean vertical=h>w||main[3]-main[1]>main[2]-main[0];
            for(int[] component:components){if(component==largest||component.length<Math.max(32,largest.length*.04))continue;int[] box=extent(component,w);
                int lo=vertical?0:1,hi=vertical?2:3,alongLo=vertical?1:0,alongHi=vertical?3:2;double overlap=Math.max(0,Math.min(main[hi],box[hi])-Math.max(main[lo],box[lo])+1),span=Math.min(main[hi]-main[lo]+1,box[hi]-box[lo]+1),center=Math.abs(main[lo]+main[hi]-box[lo]-box[hi])*.5;
                double gap=Math.max(0,Math.max(main[alongLo],box[alongLo])-Math.min(main[alongHi],box[alongHi]));
                if(overlap>=span*.7&&center<(vertical?w:h)*.2&&gap<(vertical?h:w)*.35)for(int i:component)result[i]=(byte)255;
            }
        }
        // Flood exterior background; enclosed text/mask holes belong to the page interior.
        java.util.Arrays.fill(seen,false);int head=0,tail=0;
        for(int x=0;x<w;x++){tail=exterior(x,result,seen,queue,tail);tail=exterior((h-1)*w+x,result,seen,queue,tail);}
        for(int y=0;y<h;y++){tail=exterior(y*w,result,seen,queue,tail);tail=exterior(y*w+w-1,result,seen,queue,tail);}
        while(head<tail){int i=queue[head++],x=i%w,y=i/w;if(x>0)tail=exterior(i-1,result,seen,queue,tail);if(x+1<w)tail=exterior(i+1,result,seen,queue,tail);if(y>0)tail=exterior(i-w,result,seen,queue,tail);if(y+1<h)tail=exterior(i+w,result,seen,queue,tail);}
        for(int i=0;i<n;i++)if(!seen[i])result[i]=(byte)255;
        return result;
    }
    private static int add(int i,byte[] values,boolean[] seen,int[] q,int tail){if(!seen[i]&&(values[i]&255)>=128){seen[i]=true;q[tail++]=i;}return tail;}
    private static int[] extent(int[] component,int w){int[] b={Integer.MAX_VALUE,Integer.MAX_VALUE,0,0};for(int i:component){int x=i%w,y=i/w;b[0]=Math.min(b[0],x);b[1]=Math.min(b[1],y);b[2]=Math.max(b[2],x);b[3]=Math.max(b[3],y);}return b;}
    private static int exterior(int i,byte[] values,boolean[] seen,int[] q,int tail){if(!seen[i]&&values[i]==0){seen[i]=true;q[tail++]=i;}return tail;}
    static byte[] boundary(byte[] filled,int w,int h){
        byte[] response=new byte[w*h];for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int min=255,max=0;for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){int xx=x+dx,yy=y+dy;int value=xx<0||yy<0||xx>=w||yy>=h?0:filled[yy*w+xx]&255;min=Math.min(min,value);max=Math.max(max,value);}response[y*w+x]=(byte)(max-min);
        }return response;
    }
    static float[] fit(byte[] probabilities,int w,int h){return DocumentQuadFitter.fit(boundary(filled(probabilities,w,h),w,h),w,h);}
    /** A separate enclosing proposal preserves mask lobes a supported-line fit can omit. */
    static float[] enclosing(byte[] probabilities,int w,int h){
        byte[] mask=filled(probabilities,w,h);java.util.ArrayList<double[]> points=new java.util.ArrayList<>();
        for(int y=0;y<h;y++){int left=w,right=-1;for(int x=0;x<w;x++)if(mask[y*w+x]!=0){left=Math.min(left,x);right=x;}if(right>=0){points.add(new double[]{left,y});points.add(new double[]{left,y+1});points.add(new double[]{right+1,y});points.add(new double[]{right+1,y+1});}}
        if(points.size()<8)return null;double best=Double.POSITIVE_INFINITY;double[][] corners=null;
        for(int degrees=0;degrees<90;degrees++){double a=Math.toRadians(degrees),c=Math.cos(a),s=Math.sin(a),loX=1e9,loY=1e9,hiX=-1e9,hiY=-1e9;
            for(double[] p:points){double x=c*p[0]+s*p[1],y=-s*p[0]+c*p[1];loX=Math.min(loX,x);hiX=Math.max(hiX,x);loY=Math.min(loY,y);hiY=Math.max(hiY,y);}
            double area=(hiX-loX)*(hiY-loY);if(area>=best)continue;best=area;corners=new double[][]{{loX,loY},{hiX,loY},{hiX,hiY},{loX,hiY}};
            for(double[] p:corners){double x=p[0],y=p[1];p[0]=Math.max(0,Math.min(w,c*x-s*y));p[1]=Math.max(0,Math.min(h,s*x+c*y));}
        }
        int first=0;for(int i=1;i<4;i++)if(corners[i][0]/w+corners[i][1]/h<corners[first][0]/w+corners[first][1]/h)first=i;
        float[] result=new float[8];for(int i=0;i<4;i++){result[2*i]=(float)(corners[(first+i)%4][0]/w);result[2*i+1]=(float)(corners[(first+i)%4][1]/h);}return result;
    }
    static double coverage(byte[] page,int w,int h,float[] quad){int total=0,covered=0;for(int y=0;y<h;y+=3)for(int x=0;x<w;x+=3)if((page[y*w+x]&255)>180){total++;if(inside((x+.5)/w,(y+.5)/h,quad))covered++;}return total==0?0:covered/(double)total;}
    private static boolean inside(double x,double y,float[] q){boolean inside=false;for(int i=0,j=3;i<4;j=i++){double xi=q[2*i],yi=q[2*i+1],xj=q[2*j],yj=q[2*j+1];if((yi>y)!=(yj>y)&&x<(xj-xi)*(y-yi)/(yj-yi)+xi)inside=!inside;}return inside;}
}

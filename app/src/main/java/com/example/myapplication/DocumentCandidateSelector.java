package com.example.myapplication;

import java.util.*;

/** Scores both page coverage and excess background, with evidence for all four image edges. */
final class DocumentCandidateSelector {
    static final class Image {
        final int width,height;final int[] rgb;final byte[] page;
        Image(int width,int height,int[] rgb,byte[] page){this.width=width;this.height=height;this.rgb=rgb;this.page=page;}
    }
    static double score(Image image,float[] quad){
        double intersection=0,union=0,chroma=0,totalMask=0;int interior=0;
        for(int y=0;y<image.height;y+=3)for(int x=0;x<image.width;x+=3){boolean inside=inside((x+.5)/image.width,(y+.5)/image.height,quad);double probability=(image.page[y*image.width+x]&255)/255.;intersection+=inside?probability:0;union+=inside?1:probability;totalMask+=probability;
            if(inside){int c=image.rgb[y*image.width+x],r=(c>>16)&255,g=(c>>8)&255,b=c&255,max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b));chroma+=(max-min)/(double)Math.max(32,max);interior++;}
        }
        if(union==0)return -1;
        double weakest=1,mean=0;
        for(int i=0;i<4;i++){int j=(i+1)%4;double side=edge(image,quad[2*i]*image.width,quad[2*i+1]*image.height,quad[2*j]*image.width,quad[2*j+1]*image.height);weakest=Math.min(weakest,side);mean+=side/4;}
        return .4*intersection/union+.2*weakest+.4*mean-.65*chroma/Math.max(1,interior)-.5*Math.max(0,.8-intersection/Math.max(1,totalMask));
    }
    static float[] select(Image image,List<float[]> candidates){
        return select(image,candidates,candidates.isEmpty()?null:candidates.get(0));
    }
    static float[] select(Image image,List<float[]> candidates,float[] measured){
        float[] best=null;double score=-1;for(float[] quad:candidates){if(quad==null)continue;double current=score(image,quad);if(current>score){score=current;best=quad;}}
        // Different proposals can recover different physical sides. Do not require a good top
        // to inherit a bad bottom from the same coarse quadrilateral.
        List<Side>[] sides=new List[4];for(int i=0;i<4;i++){
            sides[i]=new ArrayList<>();int j=(i+1)%4;
            for(float[] q:candidates){if(q==null)continue;Side side=new Side(q[2*i]*image.width,q[2*i+1]*image.height,q[2*j]*image.width,q[2*j+1]*image.height);side.support=edge(image,side.x,side.y,side.u,side.v);sides[i].add(side);}
            sides[i].sort((a,b)->Double.compare(b.support,a.support));List<Side> distinct=new ArrayList<>();for(Side side:sides[i]){boolean duplicate=false;for(Side prior:distinct)if(Math.hypot(side.x-prior.x,side.y-prior.y)+Math.hypot(side.u-prior.u,side.v-prior.v)<4){duplicate=true;break;}if(!duplicate)distinct.add(side);if(distinct.size()==3)break;}sides[i]=distinct;
        }
        for(Side a:sides[0])for(Side b:sides[1])for(Side c:sides[2])for(Side d:sides[3]){float[] q=intersections(new Side[]{a,b,c,d},image);if(!valid(q))continue;double current=score(image,q);if(current>score){score=current;best=q;}}
        // Require a meaningful score gain before replacing the first measured proposal;
        // tiny mask/rounding differences must not defeat a supported physical boundary.
        if(measured!=null&&best!=measured){
            int supported=0;for(int i=0;i<4;i++){int j=(i+1)%4;if(edge(image,best[2*i]*image.width,best[2*i+1]*image.height,best[2*j]*image.width,best[2*j+1]*image.height)>=.25)supported++;}
            // Weak paper/adjacent-paper contrast must not force a small printed diagram
            // when it explains almost none of the independently predicted page interior.
            double measuredCoverage=PageResponseGeometry.coverage(image.page,image.width,image.height,measured),bestCoverage=PageResponseGeometry.coverage(image.page,image.width,image.height,best);
            boolean excludesMostPage=measuredCoverage<.55&&bestCoverage>.85&&maskIou(image,best)>.25&&score>score(image,measured)+.1;
            if((supported<2&&!excludesMostPage)||score<score(image,measured)+.025)return measured;
        }
        return best;
    }
    private static double maskIou(Image image,float[] quad){double intersection=0,union=0;for(int y=0;y<image.height;y+=3)for(int x=0;x<image.width;x+=3){boolean in=inside((x+.5)/image.width,(y+.5)/image.height,quad);double p=(image.page[y*image.width+x]&255)/255.;intersection+=in?p:0;union+=in?1:p;}return intersection/Math.max(1,union);}
    static String describe(Image image,float[] quad){if(quad==null)return "null";double[] edges=new double[4];for(int i=0;i<4;i++){int j=(i+1)%4;edges[i]=edge(image,quad[2*i]*image.width,quad[2*i+1]*image.height,quad[2*j]*image.width,quad[2*j+1]*image.height);}return Arrays.toString(quad)+" score="+score(image,quad)+" sides="+Arrays.toString(edges);}
    private static final class Side{final double x,y,u,v,a,b,c;double support;Side(double x,double y,double u,double v){this.x=x;this.y=y;this.u=u;this.v=v;a=y-v;b=u-x;c=-a*x-b*y;}}
    private static float[] intersections(Side[] sides,Image image){float[] result=new float[8];for(int i=0;i<4;i++){Side a=sides[(i+3)%4],b=sides[i];double det=a.a*b.b-b.a*a.b;if(Math.abs(det)<1e-6)return null;double x=(a.b*b.c-b.b*a.c)/det/image.width,y=(a.c*b.a-b.c*a.a)/det/image.height;if(x<-.01||y<-.01||x>1.01||y>1.01)return null;result[2*i]=(float)Math.max(0,Math.min(1,x));result[2*i+1]=(float)Math.max(0,Math.min(1,y));}return result;}
    private static boolean valid(float[] q){if(q==null)return false;double area=0;for(int i=0;i<4;i++){int j=(i+1)%4,k=(i+2)%4;double ax=q[2*j]-q[2*i],ay=q[2*j+1]-q[2*i+1],bx=q[2*k]-q[2*j],by=q[2*k+1]-q[2*j+1];if(ax*by-ay*bx<.0003)return false;if(Math.hypot(ax,ay)<.045)return false;area+=q[2*i]*q[2*j+1]-q[2*j]*q[2*i+1];}return area>.05&&area<1.96;}
    static float[] snap(Image image,float[] quad){
        if(quad==null)return null;double[][] lines=new double[4][];double radius=Math.max(3,Math.min(image.width,image.height)*.04);
        for(int i=0;i<4;i++){int j=(i+1)%4;double x=quad[2*i]*image.width,y=quad[2*i+1]*image.height,u=quad[2*j]*image.width,v=quad[2*j+1]*image.height;
            double length=Math.hypot(u-x,v-y),cx=(x+u)/2,cy=(y+v)/2,angle=Math.atan2(v-y,u-x);if(length<1)return null;
            double best=-1,bx=x,by=y,bu=u,bv=v;
            for(int degrees=-3;degrees<=3;degrees++){
                double theta=angle+Math.toRadians(degrees),dx=Math.cos(theta),dy=Math.sin(theta),nx=-dy,ny=dx;
                for(int offset=-(int)radius;offset<=radius;offset++){
                    double ax=cx+nx*offset-dx*length/2,ay=cy+ny*offset-dy*length/2,ex=ax+dx*length,ey=ay+dy*length;
                    if(ax< -1||ay< -1||ex< -1||ey< -1||ax>image.width+1||ex>image.width+1||ay>image.height+1||ey>image.height+1)continue;
                    double support=edge(image,ax,ay,ex,ey)-.035*Math.abs(offset)/radius-.005*Math.abs(degrees);
                    if(support>best){best=support;bx=ax;by=ay;bu=ex;bv=ey;}
                }
            }
            double dx=bu-bx,dy=bv-by;lines[i]=new double[]{-dy,dx,dy*bx-dx*by};
        }
        float[] result=new float[8];for(int i=0;i<4;i++){double[] a=lines[(i+3)%4],b=lines[i];double determinant=a[0]*b[1]-b[0]*a[1];if(Math.abs(determinant)<1e-6)return null;double x=(a[1]*b[2]-b[1]*a[2])/determinant/image.width,y=(a[2]*b[0]-b[2]*a[0])/determinant/image.height;if(x<-.015||y<-.015||x>1.015||y>1.015)return null;result[2*i]=(float)Math.max(0,Math.min(1,x));result[2*i+1]=(float)Math.max(0,Math.min(1,y));}return result;
    }
    private static double edge(Image image,double x,double y,double u,double v){
        double length=Math.hypot(u-x,v-y);if(length<1)return 0;double nx=-(v-y)/length,ny=(u-x)/length,distance=Math.max(2,Math.min(image.width,image.height)*.009);
        double[] contrast=new double[32];int strong=0;
        for(int k=0;k<32;k++){double t=(k+2)/35.,px=x+(u-x)*t,py=y+(v-y)*t;
            double ix=px+nx*distance,iy=py+ny*distance,ox=px-nx*distance,oy=py-ny*distance;
            if(!inImage(ix,iy,image)||!inImage(ox,oy,image)){
                // A clipped side needs semantic paper support along the actual image boundary.
                int index=index(px,py,image),pixel=image.rgb[index],r=(pixel>>16)&255,g=(pixel>>8)&255,b=pixel&255;
                boolean lightPaper=Math.min(r,Math.min(g,b))>=160&&Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))<35;
                contrast[k]=(image.page[index]&255)>=180||lightPaper?32:0;
            }else{int a=bandColor(image,px,py,nx,ny,distance),b=bandColor(image,px,py,-nx,-ny,distance);contrast[k]=Math.min(colorDistance(a,b),colorDistance(image.rgb[index(ix,iy,image)],image.rgb[index(ox,oy,image)]));}
            if(contrast[k]>=8)strong++;
        }
        Arrays.sort(contrast);return Math.min(1,contrast[12]/32)*strong/32.;
    }
    // A page edge separates two sustained regions. A printed rule only changes a thin band;
    // three distances on each side reject that line without discarding a real paper/desk step.
    private static int bandColor(Image image,double x,double y,double nx,double ny,double d){int a=image.rgb[index(x+nx*d,y+ny*d,image)],b=image.rgb[index(x+nx*d*2,y+ny*d*2,image)],c=image.rgb[index(x+nx*d*3,y+ny*d*3,image)];int r=median((a>>16)&255,(b>>16)&255,(c>>16)&255),g=median((a>>8)&255,(b>>8)&255,(c>>8)&255),blue=median(a&255,b&255,c&255);return (r<<16)|(g<<8)|blue;}
    private static int median(int a,int b,int c){return a+b+c-Math.min(a,Math.min(b,c))-Math.max(a,Math.max(b,c));}
    private static double colorDistance(int a,int b){double dr=((a>>16)&255)-((b>>16)&255),dg=((a>>8)&255)-((b>>8)&255),db=(a&255)-(b&255);return Math.sqrt((dr*dr+dg*dg+db*db)/3);}
    private static int index(double x,double y,Image image){return Math.max(0,Math.min(image.height-1,(int)Math.round(y)))*image.width+Math.max(0,Math.min(image.width-1,(int)Math.round(x)));}
    private static boolean inImage(double x,double y,Image image){return x>=0&&y>=0&&x<image.width&&y<image.height;}
    private static boolean inside(double x,double y,float[] q){boolean yes=false;for(int i=0,j=3;i<4;j=i++){double xi=q[2*i],yi=q[2*i+1],xj=q[2*j],yj=q[2*j+1];if((yi>y)!=(yj>y)&&x<(xj-xi)*(y-yi)/(yj-yi)+xi)yes=!yes;}return yes;}
}


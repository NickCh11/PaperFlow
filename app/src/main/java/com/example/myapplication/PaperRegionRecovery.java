package com.example.myapplication;

/** Conservative local paper-color recovery for fragmented, low-confidence page masks. */
final class PaperRegionRecovery {
    private PaperRegionRecovery(){}
    static byte[] support(DocumentCandidateSelector.Image image){
        int w=image.width,h=image.height,n=w*h,left=w,right=-1,top=h,bottom=-1;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)if((image.page[y*w+x]&255)>=180){left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);}
        if(right<left||bottom<top)return null;
        // Keep the model's cross-page location. A missing shadowed footer may extend
        // farther along the page; never flood freely into an adjacent book page.
        int x0=Math.max(0,left-Math.round(w*.12f)),x1=Math.min(w-1,right+Math.round(w*.02f));
        int y0=top,y1=h-1;
        int[] rowLeft=new int[h],rowRight=new int[h];int rows=0;
        for(int y=top;y<=bottom;y++){int a=w,b=-1,count=0;for(int x=left;x<=right;x++)if((image.page[y*w+x]&255)>=180){a=Math.min(a,x);b=x;count++;}if(count>=w*.15){rowLeft[rows]=a;rowRight[rows++]=b;}}
        if(rows>0){java.util.Arrays.sort(rowLeft,0,rows);java.util.Arrays.sort(rowRight,0,rows);int a=rowLeft[rows/2],b=rowRight[rows/2];int crease=crease(image,a,top,bottom);if(crease>=0)x0=Math.max(x0,crease+2);crease=crease(image,b,top,bottom);if(crease>=0)x1=Math.min(x1,crease-2);}
        boolean[] allowed=new boolean[n],seen=new boolean[n];int[] queue=new int[n];int head=0,tail=0;
        for(int y=y0;y<=y1;y++)for(int x=x0;x<=x1;x++){int i=y*w+x,c=image.rgb[i],r=(c>>16)&255,g=(c>>8)&255,b=c&255;allowed[i]=Math.min(r,Math.min(g,b))>=70&&Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))<=50;
            if(allowed[i]&&(image.page[i]&255)>=180){seen[i]=true;queue[tail++]=i;}
        }
        while(head<tail){int i=queue[head++],x=i%w,y=i/w;if(x>x0)tail=visit(i-1,allowed,seen,queue,tail);if(x<x1)tail=visit(i+1,allowed,seen,queue,tail);if(y>y0)tail=visit(i-w,allowed,seen,queue,tail);if(y<y1)tail=visit(i+w,allowed,seen,queue,tail);}
        // If white material continues through our search limit, no physical footer was
        // observed. Do not turn a white desk or background sheet into page evidence.
        int openFooter=0;for(int x=x0;x<=x1;x++)if(seen[y1*w+x])openFooter++;
        if(bottom<h*.9&&x0>0&&x1<w-1&&openFooter>=Math.max(4,(x1-x0+1)*.25))return null;
        if(tail<n*.1)return null;byte[] paper=image.page.clone();for(int i=0;i<tail;i++)paper[queue[i]]=(byte)255;
        return paper;
    }
    private static int visit(int i,boolean[] allowed,boolean[] seen,int[] queue,int tail){if(allowed[i]&&!seen[i]){seen[i]=true;queue[tail++]=i;}return tail;}
    /** A sustained dark fold with neutral paper on both sides bounds sideways growth. */
    private static int crease(DocumentCandidateSelector.Image image,int near,int top,int bottom){
        int w=image.width,h=image.height,flank=Math.max(4,Math.round(w*.025f)),best=-1;double bestScore=0;
        for(int x=Math.max(flank*2,near-Math.round(w*.12f));x<=Math.min(w-1-flank*2,near+Math.round(w*.05f));x++){
            int strong=0,total=0;double score=0;
            for(int k=0;k<32;k++){int y=Math.min(h-1,top+(bottom-top)*(k+2)/35);int c=gray(image.rgb[y*w+x]),l=gray(image.rgb[y*w+x-flank]),r=gray(image.rgb[y*w+x+flank]);int broad=Math.max(gray(image.rgb[y*w+x-1]),gray(image.rgb[y*w+x+1]));int contrast=Math.min(l,r)-Math.max(c,broad);total++;if(contrast>=10&&Math.min(l,r)>=100){strong++;score+=contrast;}}
            if(strong>=total*.65&&score>bestScore){bestScore=score;best=x;}
        }return best;
    }
    private static int gray(int c){return (77*((c>>16)&255)+150*((c>>8)&255)+29*(c&255))>>8;}
}

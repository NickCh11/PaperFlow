package com.example.myapplication;

import android.graphics.*;
import java.util.Arrays;

/** Perspective-normalized motion, settled-frame confirmation and brightness-tolerant page fingerprints. */
final class BookPageTracker {
    private static final double[][] COS=new double[8][32];static{for(int k=0;k<8;k++)for(int x=0;x<32;x++)COS[k][x]=Math.cos(Math.PI*(2*x+1)*k/64);}
    private int[] previous;private long accepted;private boolean baseline,motion;private int moving,settled;
    static int[] frame(Bitmap image,float[] quad){if(quad==null)return null;Bitmap square=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888);try{float[] src=new float[8];for(int i=0;i<8;i++)src[i]=quad[i]*(i%2==0?image.getWidth():image.getHeight());Matrix map=new Matrix();map.setPolyToPoly(src,0,new float[]{0,0,31,0,31,31,0,31},0,4);Canvas c=new Canvas(square);c.drawColor(Color.WHITE);c.drawBitmap(image,map,new Paint(3));int[] pixels=new int[1024];square.getPixels(pixels,0,32,0,0,32,32);for(int i=0;i<pixels.length;i++){int p=pixels[i];pixels[i]=(((p>>16)&255)*3+((p>>8)&255)*6+(p&255))/10;}return pixels;}finally{square.recycle();}}
    boolean observe(int[] frame){if(frame==null){previous=null;settled=0;return false;}if(previous!=null){float mean=0,oldMean=0;for(int i=0;i<1024;i++){mean+=frame[i];oldMean+=previous[i];}float shift=(mean-oldMean)/1024;int changed=0;float difference=0;for(int i=0;i<1024;i++){float d=Math.abs(frame[i]-previous[i]-shift);difference+=d;if(d>18)changed++;}if(changed>190){if(++moving>=2)motion=true;settled=0;}else{moving=0;if(difference/1024<6)settled++;else settled=0;}}previous=frame.clone();return baseline&&motion&&settled>=6&&changed(frame);}
    boolean changed(int[] frame){return !baseline||frame!=null&&Long.bitCount(fingerprint(frame)^accepted)>=9;}
    boolean hasBaseline(){return baseline;}
    void accept(int[] frame){if(frame==null||frame.length!=1024)return;accepted=fingerprint(frame);baseline=true;motion=false;moving=settled=0;previous=frame.clone();}
    private static long fingerprint(int[] image){double[] values=new double[64];int index=0;for(int v=0;v<8;v++)for(int u=0;u<8;u++){double sum=0;for(int y=0;y<32;y++)for(int x=0;x<32;x++)sum+=image[y*32+x]*COS[u][x]*COS[v][y];values[index++]=sum;}double[] median=Arrays.copyOfRange(values,1,64);Arrays.sort(median);long bits=0;for(int i=1;i<64;i++)if(values[i]>median[31])bits|=1L<<i;return bits;}
}

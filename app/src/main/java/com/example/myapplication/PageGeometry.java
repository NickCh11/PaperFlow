package com.example.myapplication;

import android.graphics.*;

/** Shared document rectification. Consumes source only when a replacement is created. */
final class PageGeometry {
    static Bitmap crop(Bitmap source,float[] p){float[] full={0,0,1,0,1,1,0,1};boolean unchanged=p!=null&&p.length==8;for(int i=0;unchanged&&i<8;i++)unchanged=Math.abs(p[i]-full[i])<.00001f;if(unchanged)return source;Bitmap out=com.paperflow.scanengine.ScanEngine.crop(source,p);if(out!=source)source.recycle();return out;}
    static Bitmap detectAndCrop(Bitmap source){float[] corners=PageBoundaryDetector.detect(source);return corners==null?source:crop(source,corners);}
}

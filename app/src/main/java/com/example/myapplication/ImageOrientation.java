package com.example.myapplication;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import androidx.exifinterface.media.ExifInterface;

final class ImageOrientation {
    private ImageOrientation(){}
    /** EXIF's horizontal mirror precedes rotation, including transpose/transverse (5/7). */
    static Bitmap upright(Bitmap source,ExifInterface exif){
        Matrix matrix=new Matrix();if(exif.isFlipped())matrix.postScale(-1,1);
        if(exif.getRotationDegrees()!=0)matrix.postRotate(exif.getRotationDegrees());
        if(matrix.isIdentity())return source;
        Bitmap result=Bitmap.createBitmap(source,0,0,source.getWidth(),source.getHeight(),matrix,true);
        if(result!=source)source.recycle();return result;
    }
}

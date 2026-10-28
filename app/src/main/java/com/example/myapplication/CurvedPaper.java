package com.example.myapplication;

import android.content.Context;
import android.graphics.*;
import ai.onnxruntime.*;
import org.opencv.android.Utils;
import org.opencv.core.*;
import org.opencv.imgproc.Imgproc;
import java.io.*;
import java.nio.*;
import java.security.MessageDigest;
import java.util.*;

/** UVDoc neural backward grid, RGB / 255, 488 x 712; bilinear remap uses align_corners=true. */
final class CurvedPaper {
    private static OrtSession session;
    private static final LinkedHashMap<String,float[][][]> grids=new LinkedHashMap<>();
    private static final String HASH="41796fa7aef61d96b7e053d652d0e9e5992f4134ed11daa3bbd3179db5069200";
    private static synchronized OrtSession session(Context context)throws Exception{
        if(session!=null)return session;File model=new File(context.getNoBackupFilesDir(),"uvdoc-v1.onnx");
        if(!model.isFile()||!hash(model).equals(HASH)){File pending=new File(model+".part");try(InputStream in=context.getAssets().open("models/uvdoc.onnx");OutputStream out=new FileOutputStream(pending)){byte[] buffer=new byte[65536];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}if(!hash(pending).equals(HASH)){pending.delete();throw new IOException(UiText.get(R.string.ui_curved_paper_model_is_damaged));}if(!pending.renameTo(model))throw new IOException(UiText.get(R.string.ui_could_not_prepare_curved_paper_model));}
        try(OrtSession.SessionOptions options=new OrtSession.SessionOptions()){options.setIntraOpNumThreads(Math.min(4,Runtime.getRuntime().availableProcessors()));options.setInterOpNumThreads(1);session=OrtEnvironment.getEnvironment().createSession(model.getAbsolutePath(),options);}return session;
    }
    private static String hash(File file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}StringBuilder s=new StringBuilder();for(byte v:digest.digest())s.append(String.format(Locale.ROOT,"%02x",v&255));return s.toString();}
    static synchronized Bitmap flatten(Context context,Bitmap source,String key)throws Exception{
        if(!com.paperflow.scanengine.ScanEngine.ready())throw new IOException(UiText.get(R.string.ui_image_processing_is_unavailable));
        boolean landscape=source.getWidth()>source.getHeight();Bitmap portrait=source;if(landscape){Matrix rotate=new Matrix();rotate.postRotate(90);portrait=Bitmap.createBitmap(source,0,0,source.getWidth(),source.getHeight(),rotate,true);}
        Bitmap small=null,output=null;Mat rgba=new Mat(),mapped=new Mat(),mx=new Mat(),my=new Mat();
        try{
            float[][][] grid=grids.get(key);
            if(grid==null){small=Bitmap.createScaledBitmap(portrait,488,712,true);int[] pixels=new int[488*712];small.getPixels(pixels,0,488,0,0,488,712);FloatBuffer input=ByteBuffer.allocateDirect(pixels.length*3*4).order(ByteOrder.nativeOrder()).asFloatBuffer();for(int channel=0;channel<3;channel++)for(int p:pixels)input.put(((p>>(16-channel*8))&255)/255f);input.rewind();try(OnnxTensor tensor=OnnxTensor.createTensor(OrtEnvironment.getEnvironment(),input,new long[]{1,3,712,488});OrtSession.Result result=session(context).run(Collections.singletonMap("image",tensor))){grid=((float[][][][])result.get(0).getValue())[0];}
                if(grid.length!=2||grid[0].length<2||grid[0][0].length<2)throw new IOException(UiText.get(R.string.ui_invalid_curved_paper_grid));for(float[][] channel:grid)for(float[] row:channel)for(float value:row)if(!Float.isFinite(value)||Math.abs(value)>1.5f)throw new IOException(UiText.get(R.string.ui_could_not_estimate_this_page_s_curvature));
                grids.put(key,grid);while(grids.size()>24)grids.remove(grids.keySet().iterator().next());
            }
            int width=portrait.getWidth(),height=portrait.getHeight(),gh=grid[0].length,gw=grid[0][0].length;
            // Bound native map memory: full-page float maps alone cost eight bytes per pixel.
            // Remap a narrow band at a time into the final bitmap instead.
            float[] xrow=new float[width],yrow=new float[width];
            Utils.bitmapToMat(portrait,rgba);output=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(output);
            for(int start=0;start<height;start+=64){
                int rows=Math.min(64,height-start);mx.create(rows,width,CvType.CV_32FC1);my.create(rows,width,CvType.CV_32FC1);
                for(int row=0;row<rows;row++){
                    int y=start+row;float gy=y*(gh-1f)/Math.max(1,height-1);int top=Math.min(gh-2,(int)gy);float fy=gy-top;
                    for(int x=0;x<width;x++){
                        float gx=x*(gw-1f)/Math.max(1,width-1);int left=Math.min(gw-2,(int)gx);float fx=gx-left;
                        float xx=bilinear(grid[0],top,left,fx,fy),yy=bilinear(grid[1],top,left,fx,fy);
                        xrow[x]=(xx+1)*.5f*(width-1);yrow[x]=(yy+1)*.5f*(height-1);
                    }
                    mx.put(row,0,xrow);my.put(row,0,yrow);
                }
                Imgproc.remap(rgba,mapped,mx,my,Imgproc.INTER_LINEAR,Core.BORDER_CONSTANT,new Scalar(255,255,255,255));
                Bitmap band=Bitmap.createBitmap(width,rows,Bitmap.Config.ARGB_8888);
                try{Utils.matToBitmap(mapped,band);canvas.drawBitmap(band,0,start,null);}finally{band.recycle();}
            }
            if(landscape){Matrix rotate=new Matrix();rotate.postRotate(-90);Bitmap turned=Bitmap.createBitmap(output,0,0,width,height,rotate,true);if(turned!=output)output.recycle();output=turned;}Bitmap ready=output;output=null;return ready;
        }finally{if(output!=null&&!output.isRecycled())output.recycle();if(small!=null&&small!=portrait)small.recycle();if(portrait!=source)portrait.recycle();rgba.release();mapped.release();mx.release();my.release();}
    }
    private static float bilinear(float[][] a,int y,int x,float fx,float fy){return(a[y][x]*(1-fx)+a[y][x+1]*fx)*(1-fy)+(a[y+1][x]*(1-fx)+a[y+1][x+1]*fx)*fy;}
    static synchronized void release(){grids.clear();if(session!=null){try{session.close();}catch(Exception ignored){}session=null;}}
}

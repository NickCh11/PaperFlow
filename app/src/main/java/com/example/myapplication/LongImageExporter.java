package com.example.myapplication;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.rendering.PDFRenderer;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.CancellationException;

/** Local JPEG stitching; reference quality labels do not establish its native export resolution. */
final class LongImageExporter {
    static final class Job {
        volatile boolean cancelled;
        void check() { if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException(); }
    }
    interface Progress { void update(int done, int total); }
    static final class Plan {
        final int width, height; final int[] pageHeights;
        Plan(int width, int height, int[] heights) { this.width=width; this.height=height; pageHeights=heights; }
    }
    static float[] dimensions(PDPage page) {
        PDRectangle box=page.getCropBox();
        float w=box.getWidth(),h=box.getHeight();
        if (!Float.isFinite(w)||!Float.isFinite(h)||w<=0||h<=0) throw new IllegalArgumentException(UiText.get(R.string.ui_invalid_pdf_page_dimensions));
        return Math.abs(page.getRotation())%180==90?new float[]{h,w}:new float[]{w,h};
    }
    static Plan plan(List<float[]> dimensions, List<Integer> selected, int quality) {
        if (selected.isEmpty()) throw new IllegalArgumentException(UiText.get(R.string.ui_select_at_least_one_page));
        int low=250,high=quality==0?1000:1600,best=0;
        while(low<=high){int mid=(low+high)/2;long height=height(dimensions,selected,mid);
            if(height<=16000){best=mid;low=mid+1;}else high=mid-1;}
        if(best==0)throw new IllegalArgumentException(UiText.get(R.string.ui_select_fewer_pages_for_a_readable_long_image));
        int[] heights=new int[selected.size()];int total=0;
        for(int i=0;i<heights.length;i++){float[] d=dimensions.get(selected.get(i));heights[i]=(int)Math.max(1,Math.round(best*(double)d[1]/d[0]));total+=heights[i];}
        return new Plan(best,total,heights);
    }
    private static long height(List<float[]> dimensions,List<Integer> selected,int width){
        long total=0;for(int page:selected){float[] d=dimensions.get(page);double h=width*(double)d[1]/d[0];
            if(!Double.isFinite(h)||h>16000)return 16001;total+=Math.max(1,Math.round(h));if(total>16000)return total;}
        return total;
    }
    static void write(PDDocument document,List<float[]> dimensions,List<Integer> selected,int quality,
                      File output,Job job,Progress progress)throws Exception {
        Bitmap combined=null;boolean success=false;
        try{
            job.check();Plan plan=plan(dimensions,selected,quality);
            combined=Bitmap.createBitmap(plan.width,plan.height,Bitmap.Config.RGB_565);
            Canvas canvas=new Canvas(combined);canvas.drawColor(Color.WHITE);
            PDFRenderer renderer=new PDFRenderer(document);renderer.setSubsamplingAllowed(true);
            int y=0;
            for(int i=0;i<selected.size();i++){
                job.check();int page=selected.get(i),height=plan.pageHeights[i];float[] d=dimensions.get(page);
                // PDFBox mutates this Paint; each page needs a fresh white FILL background.
                Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);paint.setColor(Color.WHITE);
                int saved=canvas.save();
                try{
                    canvas.clipRect(0,y,plan.width,y+height);canvas.translate(0,y);
                    // Draw directly into the stack: no additional full-page ARGB bitmap allocation.
                    renderer.renderPageToGraphics(page,paint,canvas,plan.width/d[0],height/d[1]);
                }finally{canvas.restoreToCount(saved);}
                job.check();y+=height;progress.update(i+1,selected.size());
            }
            job.check();try(FileOutputStream stream=new FileOutputStream(output)){
                if(!combined.compress(Bitmap.CompressFormat.JPEG,quality==0?90:97,stream))throw new IllegalStateException(UiText.get(R.string.ui_could_not_encode_jpeg));
            }
            job.check();if(output.length()==0)throw new IllegalStateException(UiText.get(R.string.ui_empty_jpeg_output));success=true;
        }finally{if(combined!=null)combined.recycle();if(!success)output.delete();}
    }
}

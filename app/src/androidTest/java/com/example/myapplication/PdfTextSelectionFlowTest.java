package com.example.myapplication;

import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class PdfTextSelectionFlowTest {
    Object field(Object owner,String name){try{java.lang.reflect.Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(Exception error){throw new AssertionError(error);}}
    View text(View root,String value){if(root instanceof TextView&&value.contentEquals(((TextView)root).getText()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=text(((ViewGroup)root).getChildAt(i),value);if(found!=null)return found;}return null;}
    @Test public void draggingCopiesTheEmbeddedTextAndClosingReleasesItsRenderedPage()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();PDFBoxResourceLoader.init(context);
        File source=File.createTempFile("selection-ui-",".pdf",context.getCacheDir());Bitmap[] rendered={null};
        try{
            try(PDDocument doc=new PDDocument()){PDPage page=new PDPage(new PDRectangle(160,240));doc.addPage(page);try(PDPageContentStream stream=new PDPageContentStream(doc,page)){stream.beginText();stream.setFont(PDType1Font.HELVETICA,12);stream.newLineAtOffset(12,160);stream.showText("Select these words");stream.endText();}doc.save(source);}
            byte[] original=Files.readAllBytes(source.toPath());AtomicReference<PdfTextSelectionActivity> current=new AtomicReference<>();
            try(ActivityScenario<PdfTextSelectionActivity> scenario=ActivityScenario.launch(new Intent(context,PdfTextSelectionActivity.class).putExtra("path",source.getPath()).putExtra("pdf_password","").putExtra("query","words"))){
                scenario.onActivity(current::set);UiTestWait.until(()->field(current.get(),"bitmap")!=null&&((View)field(current.get(),"view")).getWidth()>0,"PDF text did not render");
                scenario.onActivity(a->{
                    View view=(View)field(a,"view");Bitmap image=(Bitmap)field(a,"bitmap");rendered[0]=image;
                    @SuppressWarnings("unchecked") ArrayList<PdfTextSelectionActivity.Glyph> glyphs=(ArrayList<PdfTextSelectionActivity.Glyph>)field(a,"glyphs");
                    float fit=Math.min(view.getWidth()/(float)image.getWidth(),view.getHeight()/(float)image.getHeight()),width=fit*image.getWidth(),height=fit*image.getHeight(),left=(view.getWidth()-width)/2,top=(view.getHeight()-height)/2;
                    int[] clicks={0};view.setOnClickListener(v->clicks[0]++);long now=SystemClock.uptimeMillis();
                    for(int i=0;i<3;i++){RectF glyph=glyphs.get(i==0?0:glyphs.size()-1).rect;MotionEvent event=MotionEvent.obtain(now,now+i*16,i==0?MotionEvent.ACTION_DOWN:i==1?MotionEvent.ACTION_MOVE:MotionEvent.ACTION_UP,left+glyph.centerX()*width,top+glyph.centerY()*height,0);try{assertTrue(view.onTouchEvent(event));}finally{event.recycle();}}
                    assertEquals(1,clicks[0]);MotionEvent cancel=MotionEvent.obtain(now,now+64,MotionEvent.ACTION_CANCEL,0,0,0);try{assertTrue(view.onTouchEvent(cancel));}finally{cancel.recycle();}assertEquals(1,clicks[0]);
                    assertTrue(text(a.getWindow().getDecorView(),UiText.get(R.string.ui_copy_selection)).performClick());
                    assertEquals("Select these words",((ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip().getItemAt(0).getText().toString());
                    Bitmap frame=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);try{view.draw(new Canvas(frame));}finally{frame.recycle();}
                });
            }
            assertNotNull(rendered[0]);assertTrue(rendered[0].isRecycled());assertArrayEquals(original,Files.readAllBytes(source.toPath()));
        }finally{source.delete();}
    }
}

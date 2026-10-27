package com.example.myapplication;

import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.text.*;
import java.io.*;
import java.util.*;

/** Select actual embedded PDF characters over a rendered page. */
public class PdfTextSelectionActivity extends AppCompatActivity {
    static class Glyph {String text;RectF rect;Glyph(String t,RectF r){text=t;rect=r;}}
    private final ArrayList<Glyph> glyphs=new ArrayList<>();private Bitmap bitmap;private boolean destroyed;private Selection view;private TextView status;private int start=-1,end=-1;private String query;private boolean[] matches;
    @Override public void onCreate(Bundle state){super.onCreate(state);PDFBoxResourceLoader.init(this);query=getIntent().getStringExtra("query");
        LinearLayout root=new LinearLayout(this);root.setOrientation(android.widget.LinearLayout.VERTICAL);root.setBackgroundColor(AppTheme.background());UiInsets.apply(root);setContentView(root);LinearLayout row=new LinearLayout(this);root.addView(row);Button back=new Button(this);back.setText(UiText.get(R.string.ui_back));row.addView(back);back.setOnClickListener(v->finish());Button copy=new Button(this);copy.setText(UiText.get(R.string.ui_copy_selection));row.addView(copy);copy.setOnClickListener(v->{if(start<0)return;StringBuilder text=new StringBuilder();RectF previous=null;for(int i=Math.min(start,end);i<=Math.max(start,end);i++){Glyph g=glyphs.get(i);if(previous!=null){if(Math.abs(g.rect.centerY()-previous.centerY())>Math.max(g.rect.height(),previous.height())*.8f)text.append('\n');else if(g.rect.left-previous.right>g.rect.height()*.3f)text.append(' ');}text.append(g.text);previous=g.rect;}ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("PDF text",text));});
        status=new TextView(this);status.setText(UiText.get(R.string.ui_loading_selectable_text));status.setPadding(16,8,16,8);root.addView(status);view=new Selection();root.addView(view,new LinearLayout.LayoutParams(-1,0,1));
        new Thread(()->{try(PDDocument doc=PDDocument.load(new File(getIntent().getStringExtra("path")),getIntent().getStringExtra("pdf_password"))){
            if(!doc.getCurrentAccessPermission().canExtractContent())throw new IOException(UiText.get(R.string.ui_this_pdf_does_not_allow_text_copying));int index=getIntent().getIntExtra("page",0);PDPage page=doc.getPage(index);PDRectangle b=page.getCropBox();int rotation=(page.getRotation()%360+360)%360;boolean turn=rotation==90||rotation==270;float width=turn?b.getHeight():b.getWidth(),height=turn?b.getWidth():b.getHeight();Bitmap image=new com.tom_roush.pdfbox.rendering.PDFRenderer(doc).renderImage(index,Math.min(2200/width,3000/height));
            ArrayList<Glyph> found=new ArrayList<>();page.setRotation(0);PDFTextStripper stripper=new PDFTextStripper(){@Override protected void writeString(String text,List<TextPosition> positions)throws IOException{
                for(TextPosition t:positions){float x=t.getX()/b.getWidth(),y=t.getY()/b.getHeight(),w=t.getWidth()/b.getWidth(),h=t.getHeight()/b.getHeight();if(t.getUnicode()==null||t.getUnicode().isEmpty())continue;RectF source=new RectF(x,y-h,x+Math.max(w,.001f),y+h*.15f);RectF display=rotate(source,rotation);found.add(new Glyph(t.getUnicode(),display));}super.writeString(text,positions);
            }};stripper.setSortByPosition(true);stripper.setStartPage(index+1);stripper.setEndPage(index+1);stripper.getText(doc);
            runOnUiThread(()->{if(destroyed){image.recycle();return;}bitmap=image;glyphs.addAll(found);if(query!=null&&!query.isEmpty()){matches=new boolean[glyphs.size()];StringBuilder text=new StringBuilder();ArrayList<Integer> map=new ArrayList<>();for(int k=0;k<glyphs.size();k++){Glyph glyph=glyphs.get(k);if(k>0){RectF previous=glyphs.get(k-1).rect;if(Math.abs(glyph.rect.centerY()-previous.centerY())>Math.max(glyph.rect.height(),previous.height())*.8f||glyph.rect.left-previous.right>glyph.rect.height()*.3f){text.append(' ');map.add(k);}}String normalized=DocumentStore.normalize(glyph.text);text.append(normalized);for(int j=0;j<normalized.length();j++)map.add(k);}String needle=DocumentStore.normalize(query);int at=0;while(!needle.isEmpty()&&(at=text.indexOf(needle,at))>=0){for(int k=at;k<at+needle.length();k++)matches[map.get(k)]=true;at+=needle.length();}}status.setText(found.isEmpty() ? UiText.get(R.string.ui_no_selectable_text_run_ocr_pdf_first) : UiText.get(R.string.ui_drag_across_text_to_select_pinch_to_zoom_two_fingers_to_pan));view.invalidate();});
        }catch(Exception|OutOfMemoryError e){runOnUiThread(()->{if(!destroyed)status.setText(UiText.format(R.string.ui_could_not_select_text_value, e.getMessage()));});}},"pdf-text-selection").start();
    }
    static RectF rotate(RectF r,int rotation){switch(rotation){case 90:return new RectF(1-r.bottom,r.left,1-r.top,r.right);case 180:return new RectF(1-r.right,1-r.bottom,1-r.left,1-r.top);case 270:return new RectF(r.top,1-r.right,r.bottom,1-r.left);default:return r;}}
    private class Selection extends View {
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);float zoom=1,panX,panY;final PagePanGesture pan=new PagePanGesture();final ScaleGestureDetector detector=new ScaleGestureDetector(PdfTextSelectionActivity.this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector d){zoom=Math.max(1,Math.min(8,zoom*d.getScaleFactor()));invalidate();return true;}});
        @Override public boolean performClick(){super.performClick();return true;}
        Selection(){super(PdfTextSelectionActivity.this);setClickable(true);setContentDescription(UiText.get(R.string.ui_drag_across_the_page_to_select_pdf_text));}
        RectF page(){if(bitmap==null)return new RectF();float fit=Math.min(getWidth()/(float)bitmap.getWidth(),getHeight()/(float)bitmap.getHeight())*zoom,w=fit*bitmap.getWidth(),h=fit*bitmap.getHeight();return new RectF((getWidth()-w)/2+panX,(getHeight()-h)/2+panY,(getWidth()+w)/2+panX,(getHeight()+h)/2+panY);}
        @Override protected void onDraw(Canvas c){if(bitmap==null)return;RectF p=page();c.drawBitmap(bitmap,null,p,paint);paint.setColor(0x663577e8);for(int i=0;i<glyphs.size();i++){Glyph g=glyphs.get(i);boolean selected=start>=0&&i>=Math.min(start,end)&&i<=Math.max(start,end);if(selected||matches!=null&&matches[i]){paint.setColor(selected?0x663577e8:0x88ffcc00);RectF r=g.rect;c.drawRect(p.left+r.left*p.width(),p.top+r.top*p.height(),p.left+r.right*p.width(),p.top+r.bottom*p.height(),paint);}}paint.setColor(Color.WHITE);}
        int nearest(float x,float y){RectF p=page();float nx=(x-p.left)/p.width(),ny=(y-p.top)/p.height(),best=Float.MAX_VALUE;int index=-1;for(int i=0;i<glyphs.size();i++){RectF r=glyphs.get(i).rect;float dx=Math.max(r.left-nx,Math.max(0,nx-r.right)),dy=Math.max(r.top-ny,Math.max(0,ny-r.bottom));float d=dx*dx+dy*dy*4;if(d<best){best=d;index=i;}}return index;}
        @Override public boolean onTouchEvent(MotionEvent e){if(destroyed||bitmap==null||bitmap.isRecycled())return false;detector.onTouchEvent(e);if(pan.onTouch(e)){panX+=pan.deltaX;panY+=pan.deltaY;invalidate();return true;}if(e.getActionMasked()==MotionEvent.ACTION_DOWN){start=end=nearest(e.getX(),e.getY());}else if(e.getActionMasked()==MotionEvent.ACTION_MOVE&&start>=0)end=nearest(e.getX(),e.getY());else if(e.getActionMasked()==MotionEvent.ACTION_UP)performClick();invalidate();return true;}
    }
    @Override protected void onResume(){super.onResume();FolderLock.guard(this,getIntent().getStringExtra("path"));}
    @Override protected void onDestroy(){destroyed=true;if(bitmap!=null)bitmap.recycle();super.onDestroy();}
}

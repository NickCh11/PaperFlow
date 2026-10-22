package com.example.myapplication;

import android.content.*;
import android.graphics.*;
import android.view.*;
import java.util.*;

/** Text selection directly over a reader page, with draggable endpoints and native copy controls. */
final class PdfPageSelection extends View {
    private Bitmap image;private final ArrayList<PdfKeywordSearch.Glyph> glyphs;private final ArrayList<RectF> highlights=new ArrayList<>();private final Runnable dismissed;
    private final Paint paint=new Paint(3);private int start=-1,end=-1,handle;private float zoom=1,dx,dy,lastX,lastY;private boolean gesture,closed;private ActionMode actionMode;
    private final ScaleGestureDetector scale;
    PdfPageSelection(Context context,Bitmap image,ArrayList<PdfKeywordSearch.Glyph> glyphs,String query,Runnable dismissed){super(context);this.image=image;this.glyphs=glyphs;this.dismissed=dismissed;setContentDescription(UiText.get(R.string.ui_select_text_on_pdf_page_drag_text_or_selection_handles_pinch_to_zoom));
        if(query!=null&&!query.trim().isEmpty())for(PdfKeywordSearch.Hit hit:PdfKeywordSearch.find(0,glyphs,DocumentStore.normalize(query).trim(),false))highlights.addAll(hit.areas);
        scale=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector detector){zoom=Math.max(1,Math.min(8,zoom*detector.getScaleFactor()));invalidate();return true;}});post(this::controls);
    }
    private void controls(){if(closed||actionMode!=null)return;actionMode=startActionMode(new ActionMode.Callback2(){
        public boolean onCreateActionMode(ActionMode mode,Menu menu){menu.add(0,1,0,UiText.get(R.string.ui_copy)).setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);menu.add(0,3,1,UiText.get(R.string.ui_translate)).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);menu.add(0,2,2,UiText.get(R.string.ui_select_all)).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);return true;}
        public boolean onPrepareActionMode(ActionMode mode,Menu menu){menu.findItem(1).setEnabled(start>=0);menu.findItem(3).setEnabled(start>=0);return true;}
        public boolean onActionItemClicked(ActionMode mode,MenuItem item){if(item.getItemId()==3){if(start>=0&&getContext() instanceof PdfViewerActivity)((PdfViewerActivity)getContext()).translateSelection(selectedText());return true;}if(item.getItemId()==2){if(!glyphs.isEmpty()){start=0;end=glyphs.size()-1;invalidate();mode.invalidate();}return true;}if(start>=0){((ClipboardManager)getContext().getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("PDF text",selectedText()));}return true;}
        public void onDestroyActionMode(ActionMode mode){actionMode=null;close();}
        @Override public void onGetContentRect(ActionMode mode,View view,Rect out){RectF bounds=page();if(start>=0){PointF p=endpoint(Math.min(start,end),false);out.set((int)p.x,(int)p.y,(int)p.x+20,(int)p.y+30);}else out.set((int)bounds.left,(int)bounds.top,(int)bounds.right,(int)bounds.top+40);}
    },ActionMode.TYPE_FLOATING);}
    String selectedText(){StringBuilder text=new StringBuilder();RectF previous=null;for(int i=Math.min(start,end);i<=Math.max(start,end)&&i>=0;i++){PdfKeywordSearch.Glyph glyph=glyphs.get(i);if(previous!=null){if(Math.abs(glyph.reading.centerY()-previous.centerY())>Math.max(glyph.reading.height(),previous.height())*.8f)text.append('\n');else if(glyph.reading.left-previous.right>glyph.reading.height()*.2f)text.append(' ');}text.append(glyph.text);previous=glyph.reading;}return text.toString();}
    private RectF page(){if(image==null)return new RectF();float fit=Math.min(getWidth()/(float)image.getWidth(),getHeight()/(float)image.getHeight())*zoom,w=fit*image.getWidth(),h=fit*image.getHeight();return new RectF((getWidth()-w)/2+dx,(getHeight()-h)/2+dy,(getWidth()+w)/2+dx,(getHeight()+h)/2+dy);}
    private RectF display(RectF r){RectF p=page();return new RectF(p.left+r.left*p.width(),p.top+r.top*p.height(),p.left+r.right*p.width(),p.top+r.bottom*p.height());}
    private PointF endpoint(int index,boolean right){RectF r=display(glyphs.get(index).box);return new PointF(right?r.right:r.left,r.bottom);}
    @Override protected void onDraw(Canvas canvas){if(image==null)return;RectF p=page();paint.setColor(Color.WHITE);canvas.drawRect(0,0,getWidth(),getHeight(),paint);canvas.drawBitmap(image,null,p,paint);paint.setColor(0x88ffcc00);for(RectF hit:highlights)canvas.drawRect(display(hit),paint);if(start>=0){paint.setColor(0x663577e8);for(int i=Math.min(start,end);i<=Math.max(start,end);i++)canvas.drawRect(display(glyphs.get(i).box),paint);paint.setColor(0xff1252cc);float radius=10*getResources().getDisplayMetrics().density;for(PointF point:new PointF[]{endpoint(Math.min(start,end),false),endpoint(Math.max(start,end),true)})canvas.drawCircle(point.x,point.y,radius,paint);}}
    private int nearest(float x,float y){RectF p=page();if(p.width()==0)return -1;float nx=(x-p.left)/p.width(),ny=(y-p.top)/p.height(),best=Float.MAX_VALUE;int found=-1;for(int i=0;i<glyphs.size();i++){RectF r=glyphs.get(i).box;float a=Math.max(r.left-nx,Math.max(0,nx-r.right)),b=Math.max(r.top-ny,Math.max(0,ny-r.bottom)),distance=a*a+b*b*4;if(distance<best){best=distance;found=i;}}return found;}
    @Override public boolean onTouchEvent(MotionEvent event){scale.onTouchEvent(event);getParent().requestDisallowInterceptTouchEvent(true);if(event.getPointerCount()>1){float x=(event.getX(0)+event.getX(1))/2,y=(event.getY(0)+event.getY(1))/2;if(gesture&&event.getActionMasked()==MotionEvent.ACTION_MOVE){dx+=x-lastX;dy+=y-lastY;}lastX=x;lastY=y;gesture=true;invalidate();return true;}
        if(gesture){if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){gesture=false;getParent().requestDisallowInterceptTouchEvent(false);}return true;}
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){handle=0;if(start>=0){PointF left=endpoint(Math.min(start,end),false),right=endpoint(Math.max(start,end),true);float radius=32*getResources().getDisplayMetrics().density;if(Math.hypot(event.getX()-left.x,event.getY()-left.y)<radius)handle=1;else if(Math.hypot(event.getX()-right.x,event.getY()-right.y)<radius)handle=2;int low=Math.min(start,end),high=Math.max(start,end);start=low;end=high;}if(handle==0)start=end=nearest(event.getX(),event.getY());}
        else if(event.getActionMasked()==MotionEvent.ACTION_MOVE){int next=nearest(event.getX(),event.getY());if(next>=0){if(handle==1)start=next;else end=next;}}
        else if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL)getParent().requestDisallowInterceptTouchEvent(false);
        invalidate();if(actionMode!=null){actionMode.invalidate();actionMode.invalidateContentRect();}return true;
    }
    void close(){if(closed)return;closed=true;if(actionMode!=null){ActionMode mode=actionMode;actionMode=null;mode.finish();}if(image!=null){image.recycle();image=null;}dismissed.run();}
    @Override protected void onDetachedFromWindow(){close();super.onDetachedFromWindow();}
}

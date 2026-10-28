package com.example.myapplication;

import android.app.Application;
import android.content.Intent;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.exifinterface.media.ExifInterface;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;
import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;

/** Local portrait segmentation, manual framing and physical-size photo/sheet export. */
public class IdPhotoMakerActivity extends BackHandlingActivity {
    private final int BRAND = AppTheme.accent(),INK = AppTheme.ink(),MUTED = AppTheme.muted();
    private PhotoSession session;
    private ActivityResultLauncher<String[]> picker;
    private ActivityResultLauncher<Intent> camera;
    private ActivityResultLauncher<Intent> savePicker;
    private PortraitView portrait;
    private boolean destroyed;
    private SeekBar zoom;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);session=new ViewModelProvider(this).get(PhotoSession.class);
        if(!session.initialized){session.initialized=true;if(state!=null)session.restore(state);}
        picker=registerForActivityResult(new ActivityResultContracts.OpenDocument(),uri->{if(uri!=null&&!session.busy)session.load(uri,false);});
        camera=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{
            if(result.getResultCode()!=RESULT_OK||result.getData()==null)return;
            ArrayList<String> paths=result.getData().getStringArrayListExtra("pages");
            if(paths!=null&&!paths.isEmpty()){session.load(Uri.fromFile(new File(paths.get(0))),true);for(int i=1;i<paths.size();i++)session.deleteOwnedCapture(paths.get(i));}
            else if(result.getData().getBooleanExtra("import_pdf",false)){Toast.makeText(this,UiText.get(R.string.ui_choose_a_portrait_image),Toast.LENGTH_LONG).show();picker.launch(new String[]{"image/*"});}
        });
        savePicker=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{if(result.getResultCode()==RESULT_OK&&result.getData()!=null&&result.getData().getData()!=null)session.copyOutput(result.getData().getData());});
        session.changes.observe(this,revision->render());render();
    }
    private void render(){
        if(destroyed)return;
        LinearLayout root=EditorViews.root(this);
        LinearLayout header=EditorViews.header(this);
        EditorViews.addBack(header,INK,this::back);
        TextView title=text(UiText.get(R.string.ui_id_photo_maker),19,INK);title.setTypeface(null,android.graphics.Typeface.BOLD);header.addView(title,new LinearLayout.LayoutParams(0,dp(56),1));root.addView(header);
        ScrollView scroll=new ScrollView(this);LinearLayout body=new LinearLayout(this);body.setOrientation(android.widget.LinearLayout.VERTICAL);body.setPadding(dp(18),dp(16),dp(18),dp(22));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(session.busy){ProgressBar progress=new ProgressBar(this);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(dp(44),dp(44));lp.gravity=Gravity.CENTER;lp.topMargin=dp(85);body.addView(progress,lp);TextView status=text(session.status,16,INK);status.setGravity(Gravity.CENTER);body.addView(status,new LinearLayout.LayoutParams(-1,dp(90)));}
        else if(session.source==null){
            ImageView icon=new ImageView(this);icon.setImageDrawable(new AppIcon("ID Photo Maker",BRAND));icon.setPadding(dp(40),dp(30),dp(40),dp(30));body.addView(icon,new LinearLayout.LayoutParams(-1,dp(200)));
            heading(body,"Choose a portrait");note(body,"Use a clear photo with one person facing the camera. You can adjust the framing and background on this device.");sourceButtons(body);
        }else if(session.output!=null){
            heading(body,"Ready");note(body,session.summary());View preview=new OutputView();preview.setBackgroundColor(AppTheme.border());body.addView(preview,new LinearLayout.LayoutParams(-1,dp(350)));
            TextView share=button(UiText.get(R.string.ui_share),"Share",true);body.addView(share,space(-1,dp(48),16));share.setOnClickListener(v->share());
            TextView save=button(UiText.get(R.string.ui_save_to_device),"Import Files",false);body.addView(save,space(-1,dp(48),10));save.setOnClickListener(v->{Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(session.mime).putExtra(Intent.EXTRA_TITLE,session.output.getName());savePicker.launch(intent);});
            if(session.mime.equals("application/pdf")){TextView library=button(UiText.get(R.string.ui_save_to_documents),"All Documents",false);body.addView(library,space(-1,dp(48),10));library.setOnClickListener(v->{Intent intent=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("document_action","Register").putExtra("path",session.output.getAbsolutePath()).putExtra("name","ID photos · "+session.dimensionLabel()).putExtra("pages",1);startActivity(intent);finish();});}
            TextView edit=button(UiText.get(R.string.ui_edit_photo),"Crop",false);body.addView(edit,space(-1,dp(48),10));edit.setOnClickListener(v->{session.output=null;render();});
        }else{
            note(body,"Drag to position · Pinch or use Zoom to adjust");portrait=new PortraitView();portrait.setBackgroundColor(AppTheme.border());body.addView(portrait,new LinearLayout.LayoutParams(-1,dp(340)));
            TextView size=button(UiText.format(R.string.ui_size_value, session.dimensionLabel()),"ID Cards",false);body.addView(size,space(-1,dp(46),12));size.setOnClickListener(v->chooseSize());
            TextView zoomLabel=text(UiText.get(R.string.ui_zoom),13,MUTED);body.addView(zoomLabel,space(-1,-2,10));zoom=new SeekBar(this);zoom.setContentDescription(UiText.get(R.string.ui_portrait_zoom));zoom.setMax(250);zoom.setProgress(Math.round((session.magnification-1)*100));body.addView(zoom);zoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean user){if(user){session.magnification=1+p/100f;session.constrainPan();portrait.invalidate();}}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});
            TextView rotate=button(UiText.get(R.string.ui_rotate),"Rotate",false);body.addView(rotate,space(-1,dp(40),4));rotate.setOnClickListener(v->{session.rotation=(session.rotation+90)%360;session.panX=session.panY=0;portrait.invalidate();});
            heading(body,"Background");RadioGroup backgrounds=new RadioGroup(this);backgrounds.setOrientation(android.widget.LinearLayout.HORIZONTAL);String[] colors={"Original","White","Blue","Red"};for(int i=0;i<colors.length;i++){final int index=i;RadioButton radio=new RadioButton(this);radio.setId(View.generateViewId());radio.setText(UiText.label(colors[i]));radio.setTextSize(12);radio.setEnabled(i==0||session.foreground!=null);backgrounds.addView(radio,new RadioGroup.LayoutParams(0,dp(46),1));radio.setChecked(session.background==i);radio.setOnClickListener(v->{session.background=index;portrait.invalidate();});}body.addView(backgrounds);
            note(body,session.maskNotice);
            heading(body,"Output");RadioGroup formats=new RadioGroup(this);String[] outputs={"Single JPEG · 300 DPI","4 × 6 inch print sheet · PDF","A4 print sheet · PDF"};for(int i=0;i<outputs.length;i++){final int index=i;RadioButton radio=new RadioButton(this);radio.setId(View.generateViewId());radio.setText(UiText.label(outputs[i]));radio.setTextSize(14);formats.addView(radio);radio.setChecked(session.layout==i);radio.setOnClickListener(v->session.layout=index);}body.addView(formats);
            note(body,"Sizes are editing templates. Check the photo rules of the organisation receiving it.");sourceButtons(body);
        }
        if(session.source!=null&&!session.busy&&session.output==null){LinearLayout bottom=new LinearLayout(this);bottom.setPadding(dp(16),dp(10),dp(16),dp(12));bottom.setBackgroundColor(AppTheme.surface());TextView create=button(UiText.get(R.string.ui_create_photo),"Done",true);bottom.addView(create,new LinearLayout.LayoutParams(-1,dp(48)));create.setOnClickListener(v->session.export());root.addView(bottom);}
        UiInsets.apply(root);setContentView(root);
        if(session.message!=null){Toast.makeText(this,session.message,Toast.LENGTH_LONG).show();session.message=null;}
    }
    private void sourceButtons(LinearLayout body){LinearLayout row=new LinearLayout(this);TextView take=button(UiText.get(R.string.ui_camera),"Camera",false),importPhoto=button(UiText.get(R.string.ui_import_images),"Import Images",false);row.addView(take,new LinearLayout.LayoutParams(0,dp(48),1));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(48),1);lp.leftMargin=dp(8);row.addView(importPhoto,lp);body.addView(row,space(-1,dp(48),16));take.setOnClickListener(v->camera.launch(new Intent(this,CameraCaptureActivity.class).putExtra("capture_mode","Single").putExtra("document_boundaries",false)));importPhoto.setOnClickListener(v->picker.launch(new String[]{"image/*"}));}
    private void chooseSize(){new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_photo_size)).setItems(new String[]{UiText.get(R.string.ui_35_45_mm), UiText.get(R.string.ui_25_35_mm), UiText.get(R.string.ui_50_50_mm), UiText.get(R.string.ui_custom_size)},(d,w)->{if(w==3){customSize();return;}session.mmWidth=w==0?35:w==1?25:50;session.mmHeight=w==0?45:w==1?35:50;session.panX=session.panY=0;render();}).show();}
    private void customSize(){LinearLayout fields=new LinearLayout(this);fields.setOrientation(android.widget.LinearLayout.VERTICAL);fields.setPadding(dp(20),dp(8),dp(20),0);EditText width=new EditText(this),height=new EditText(this);width.setHint(UiText.get(R.string.ui_width_in_mm));height.setHint(UiText.get(R.string.ui_height_in_mm));width.setText(String.valueOf(session.mmWidth));height.setText(String.valueOf(session.mmHeight));width.setInputType(8194);height.setInputType(8194);fields.addView(width);fields.addView(height);AlertDialog dialog=new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_custom_photo_size)).setView(fields).setNegativeButton(UiText.get(R.string.ui_cancel),null).setPositiveButton(UiText.get(R.string.ui_apply),null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{float w=Float.parseFloat(width.getText().toString().replace(',','.')),h=Float.parseFloat(height.getText().toString().replace(',','.'));if(!Float.isFinite(w)||!Float.isFinite(h)||w<8||w>100||h<8||h>100)throw new IllegalArgumentException();session.mmWidth=w;session.mmHeight=h;session.panX=session.panY=0;dialog.dismiss();render();}catch(Exception e){width.setError(UiText.get(R.string.ui_use_dimensions_from_8_to_100_mm));}}));dialog.show();}
    private void share(){try{Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",session.output);Intent send=new Intent(Intent.ACTION_SEND).setType(session.mime).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(send,UiText.get(R.string.ui_share_id_photo)));}catch(Exception e){Toast.makeText(this,UiText.get(R.string.ui_could_not_share_photo),Toast.LENGTH_LONG).show();}}
    private void back(){if(session.busy){Toast.makeText(this,UiText.get(R.string.ui_please_wait_for_processing_to_finish),Toast.LENGTH_SHORT).show();return;}if(session.output!=null){session.output=null;render();}else finish();}
    @Override public void handleBackPressed(){back();}
    @Override protected void onSaveInstanceState(Bundle state){session.save(state);super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){destroyed=true;super.onDestroy();}
    private TextView text(String value,int size,int color){return EditorViews.text(this,value,size,color);}
    private TextView button(String title,String name,boolean filled){TextView view=text(title,14,filled ? AppTheme.onAccent() :BRAND);view.setGravity(Gravity.CENTER);view.setContentDescription(title);android.graphics.drawable.GradientDrawable bg=new android.graphics.drawable.GradientDrawable();bg.setColor(filled ? BRAND : AppTheme.surface());bg.setCornerRadius(dp(6));view.setBackground(bg);view.setPadding(dp(10),0,dp(10),0);AppIcon icon=new AppIcon(name,filled ? AppTheme.onAccent() :BRAND);icon.setBounds(0,0,dp(21),dp(21));view.setCompoundDrawables(icon,null,null,null);view.setCompoundDrawablePadding(dp(6));return view;}
    private void heading(LinearLayout body,String value){TextView title=text(UiText.label(value),18,INK);title.setTypeface(null,android.graphics.Typeface.BOLD);body.addView(title,space(-1,-2,15));}
    private void note(LinearLayout body,String value){TextView note=text(UiText.label(value),13,MUTED);note.setLineSpacing(dp(3),1);body.addView(note,space(-1,-2,10));}
    private LinearLayout.LayoutParams space(int w,int h,int top){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(w,h);lp.topMargin=dp(top);return lp;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}

    private class PortraitView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final RectF oval=new RectF();private float lastX,lastY;private final ScaleGestureDetector pinch;
        @Override public boolean performClick(){super.performClick();return true;}
        PortraitView(){super(IdPhotoMakerActivity.this);setClickable(true);setContentDescription(UiText.get(R.string.ui_portrait_crop_drag_to_move_pinch_to_zoom));pinch=new ScaleGestureDetector(IdPhotoMakerActivity.this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector detector){session.magnification=Math.max(1,Math.min(3.5f,session.magnification*detector.getScaleFactor()));session.constrainPan();if(zoom!=null)zoom.setProgress(Math.round((session.magnification-1)*100));invalidate();return true;}});}
        private RectF frame(){float w=getWidth()-dp(32),h=getHeight()-dp(24),aspect=session.mmWidth/session.mmHeight;if(w/h>aspect)w=h*aspect;else h=w/aspect;return new RectF((getWidth()-w)/2,(getHeight()-h)/2,(getWidth()+w)/2,(getHeight()+h)/2);}
        @Override protected void onDraw(Canvas canvas){if(session.source==null)return;RectF r=frame();int save=canvas.save();canvas.translate(r.left,r.top);PhotoSpec spec=session.spec();spec.draw(canvas,r.width(),r.height());paint.setColor(0x99FFFFFF);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));oval.set(r.width()*.27f,r.height()*.14f,r.width()*.73f,r.height()*.63f);canvas.drawOval(oval,paint);canvas.drawLine(r.width()*.2f,r.height()*.79f,r.width()*.8f,r.height()*.79f,paint);canvas.restoreToCount(save);paint.setColor(BRAND);canvas.drawRect(r,paint);}
        @Override public boolean onTouchEvent(MotionEvent event){getParent().requestDisallowInterceptTouchEvent(true);pinch.onTouchEvent(event);if(event.getActionMasked()==MotionEvent.ACTION_DOWN||event.getActionMasked()==MotionEvent.ACTION_POINTER_UP){lastX=event.getX();lastY=event.getY();}else if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&event.getPointerCount()==1&&!pinch.isInProgress()){RectF r=frame();session.panX+=(event.getX()-lastX)/r.width();session.panY+=(event.getY()-lastY)/r.height();session.constrainPan();lastX=event.getX();lastY=event.getY();invalidate();}else if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){getParent().requestDisallowInterceptTouchEvent(false);if(event.getActionMasked()==MotionEvent.ACTION_UP)performClick();}return true;}
    }
    private class OutputView extends View {
        OutputView(){super(IdPhotoMakerActivity.this);setContentDescription(UiText.get(R.string.ui_id_photo_output_preview));}
        @Override protected void onDraw(Canvas canvas){if(session.source==null)return;PhotoSpec spec=session.spec();float width=session.layout==0?session.mmWidth:session.layout==1?101.6f:210,height=session.layout==0?session.mmHeight:session.layout==1?152.4f:297;float scale=Math.min((getWidth()-dp(24))/width,(getHeight()-dp(24))/height);int save=canvas.save();canvas.translate((getWidth()-width*scale)/2,(getHeight()-height*scale)/2);canvas.scale(scale,scale);if(session.layout==0)spec.draw(canvas,width,height);else drawSheet(canvas,width,height,spec,session.mmWidth,session.mmHeight);canvas.restoreToCount(save);}
    }
    private static class PhotoSpec {
        final Bitmap image;final int backgroundColor,rotation;final float zoom,panX,panY;
        PhotoSpec(Bitmap image,int color,int rotation,float zoom,float x,float y){this.image=image;backgroundColor=color;this.rotation=rotation;this.zoom=zoom;panX=x;panY=y;}
        void draw(Canvas canvas,float w,float h){int save=canvas.save();canvas.clipRect(0,0,w,h);canvas.drawColor(backgroundColor);float iw=rotation%180==0?image.getWidth():image.getHeight(),ih=rotation%180==0?image.getHeight():image.getWidth();float scale=Math.max(w/iw,h/ih)*zoom;float maxX=Math.max(0,(iw*scale-w)/2),maxY=Math.max(0,(ih*scale-h)/2);float x=Math.max(-maxX,Math.min(maxX,panX*w)),y=Math.max(-maxY,Math.min(maxY,panY*h));canvas.translate(w/2+x,h/2+y);canvas.rotate(rotation);canvas.scale(scale,scale);canvas.drawBitmap(image,-image.getWidth()/2f,-image.getHeight()/2f,new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));canvas.restoreToCount(save);}
    }
    private static int drawSheet(Canvas canvas,float pageWidth,float pageHeight,PhotoSpec photo,float photoWidth,float photoHeight){
        canvas.drawColor(Color.WHITE);float gap=3,margin=5;int cols=Math.max(1,(int)((pageWidth-margin*2+gap)/(photoWidth+gap))),rows=Math.max(1,(int)((pageHeight-margin*2+gap)/(photoHeight+gap)));float startX=(pageWidth-(cols*photoWidth+(cols-1)*gap))/2,startY=(pageHeight-(rows*photoHeight+(rows-1)*gap))/2;Paint marks=new Paint(Paint.ANTI_ALIAS_FLAG);marks.setColor(0xFFBBBBBB);marks.setStyle(Paint.Style.STROKE);marks.setStrokeWidth(.15f);
        for(int y=0;y<rows;y++)for(int x=0;x<cols;x++){float left=startX+x*(photoWidth+gap),top=startY+y*(photoHeight+gap);int saved=canvas.save();canvas.translate(left,top);photo.draw(canvas,photoWidth,photoHeight);canvas.restoreToCount(saved);canvas.drawRect(left,top,left+photoWidth,top+photoHeight,marks);}return cols*rows;
    }

    public static class PhotoSession extends AndroidViewModel {
        final MutableLiveData<Integer> changes=new MutableLiveData<>(0);final ExecutorService worker=Executors.newSingleThreadExecutor();final Handler main=new Handler(Looper.getMainLooper());
        Bitmap source,foreground;String sourcePath,status=UiText.get(R.string.ui_preparing_photo),maskNotice=UiText.get(R.string.ui_background_separation_runs_on_this_device),message,mime="image/jpeg";File output;
        boolean initialized,busy;volatile boolean cleared;float mmWidth=35,mmHeight=45,magnification=1,panX,panY;int rotation,background=1,layout;
        public PhotoSession(Application app){super(app);}
        void publish(){if(!cleared)changes.setValue(changes.getValue()==null?1:changes.getValue()+1);}
        String dimensionLabel(){return String.format(Locale.US,"%s × %s mm",number(mmWidth),number(mmHeight));}
        String number(float value){return value==(int)value?String.valueOf((int)value):String.format(Locale.US,"%.1f",value);}
        String summary(){return layout==0?dimensionLabel()+" · "+Math.round(mmWidth/25.4f*300)+" × "+Math.round(mmHeight/25.4f*300)+" pixels · 300 DPI":(layout==1?"4 × 6 inch":"A4")+" PDF print sheet · "+dimensionLabel()+" photos";}
        PhotoSpec spec(){int color=background==2?0xFF3887D9:background==3?0xFFD83940:Color.WHITE;return new PhotoSpec(background==0||foreground==null?source:foreground,color,rotation,magnification,panX,panY);}
        void constrainPan(){if(source==null)return;float aspect=mmWidth/mmHeight,iw=rotation%180==0?source.getWidth():source.getHeight(),ih=rotation%180==0?source.getHeight():source.getWidth(),scale=Math.max(aspect/iw,1/ih)*magnification,maxX=Math.max(0,(iw*scale-aspect)/(2*aspect)),maxY=Math.max(0,(ih*scale-1)/2);panX=Math.max(-maxX,Math.min(maxX,panX));panY=Math.max(-maxY,Math.min(maxY,panY));}
        private File captures(){return DocumentDirectories.captures(getApplication());}
        private File documents(){return DocumentDirectories.documents(getApplication());}
        void deleteOwnedCapture(String path){try{File file=new File(path);if(file.getCanonicalFile().getParentFile().equals(captures().getCanonicalFile()))file.delete();}catch(Exception ignored){}}
        void load(Uri uri,boolean owned){if(busy)return;busy=true;status="Separating portrait background…";publish();worker.execute(()->{File copy=new File(captures(),"id-photo-input-"+UUID.randomUUID()+".jpg");try(InputStream in=getApplication().getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(copy)){if(in==null)throw new IOException();byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}catch(Exception e){copy.delete();fail("Could not import portrait");return;}if(owned&&"file".equals(uri.getScheme()))deleteOwnedCapture(uri.getPath());prepare(copy,false);});}
        private void prepare(File file,boolean restoring){
            if(cleared){file.delete();return;}
            Bitmap image=null,cutout=null;String notice=UiText.get(R.string.ui_local_background_separation_check_hair_and_clothing_edges_before_exporting);
            try{
                image=decode(file);final Bitmap input=image;
                try{
                    com.paperflow.scanengine.PortraitSegmenter.Mask mask=com.paperflow.scanengine.PortraitSegmenter.mask(getApplication(),input);int width=mask.width,height=mask.height;
                    int[] pixels=new int[width*height];double sum=0;
                    for(int i=0;i<pixels.length;i++){float confidence=mask.values[i];sum+=confidence;int alpha=Math.round(Math.max(0,Math.min(1,(confidence-.15f)/.7f))*255);pixels[i]=Color.argb(alpha,255,255,255);}
                    if(sum/pixels.length<.01||sum/pixels.length>.995)notice=UiText.get(R.string.ui_no_clear_person_mask_was_found_use_the_original_background_or_choose_anothe);
                    else{Bitmap matte=Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888);cutout=Bitmap.createBitmap(image.getWidth(),image.getHeight(),Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(cutout);canvas.drawBitmap(image,0,0,null);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));canvas.drawBitmap(matte,null,new Rect(0,0,image.getWidth(),image.getHeight()),paint);matte.recycle();}
                }catch(Exception e){notice=UiText.get(R.string.ui_background_separation_could_not_finish_you_can_still_crop_and_export_the_or);}finally{}
                final Bitmap ready=image,mask=cutout;final String info=notice;main.post(()->{if(cleared){ready.recycle();if(mask!=null)mask.recycle();file.delete();return;}String previous=sourcePath;source=ready;foreground=mask;sourcePath=file.getAbsolutePath();busy=false;maskNotice=info;if(!restoring){output=null;rotation=0;panX=panY=0;magnification=1;}if(mask==null)background=0;if(previous!=null&&!previous.equals(sourcePath))deleteOwnedCapture(previous);publish();});
            }catch(Exception|OutOfMemoryError e){if(image!=null&&!image.isRecycled())image.recycle();if(cutout!=null&&!cutout.isRecycled())cutout.recycle();if(!restoring)file.delete();fail("Could not prepare this photo. Choose a smaller image.");}
        }
        private Bitmap decode(File file)throws Exception{BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException();BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=1;while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>2200)options.inSampleSize*=2;Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath(),options);if(bitmap==null)throw new IOException();ExifInterface exif=new ExifInterface(file.getAbsolutePath());Matrix matrix=new Matrix();matrix.postRotate(exif.getRotationDegrees());if(exif.isFlipped())matrix.postScale(-1,1);if(!matrix.isIdentity()){Bitmap transformed=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);if(transformed!=bitmap){bitmap.recycle();bitmap=transformed;}}return bitmap;}
        void export(){
            if(busy||source==null)return;final PhotoSpec photo=spec();final float width=mmWidth,height=mmHeight;final int outputLayout=layout;busy=true;status="Creating photo…";publish();
            worker.execute(()->{File file=null;Bitmap bitmap=null;try{
                String name="id-photo-"+UUID.randomUUID();
                if(outputLayout==0){bitmap=Bitmap.createBitmap(Math.max(1,Math.round(width/25.4f*300)),Math.max(1,Math.round(height/25.4f*300)),Bitmap.Config.ARGB_8888);photo.draw(new Canvas(bitmap),bitmap.getWidth(),bitmap.getHeight());file=new File(documents(),name+".jpg");try(FileOutputStream out=new FileOutputStream(file)){if(!bitmap.compress(Bitmap.CompressFormat.JPEG,97,out))throw new IOException();}ExifInterface exif=new ExifInterface(file.getAbsolutePath());exif.setAttribute(ExifInterface.TAG_X_RESOLUTION,"300/1");exif.setAttribute(ExifInterface.TAG_Y_RESOLUTION,"300/1");exif.setAttribute(ExifInterface.TAG_RESOLUTION_UNIT,"2");exif.saveAttributes();setJfifDensity(file);}
                else{float paperWidth=outputLayout==1?101.6f:210,paperHeight=outputLayout==1?152.4f:297;file=new File(documents(),name+".pdf");PdfDocument pdf=new PdfDocument();try{int pw=Math.round(paperWidth*72/25.4f),ph=Math.round(paperHeight*72/25.4f);PdfDocument.Page page=pdf.startPage(new PdfDocument.PageInfo.Builder(pw,ph,1).create());Canvas canvas=page.getCanvas();canvas.scale(72/25.4f,72/25.4f);drawSheet(canvas,paperWidth,paperHeight,photo,width,height);pdf.finishPage(page);try(FileOutputStream out=new FileOutputStream(file)){pdf.writeTo(out);}}finally{pdf.close();}}
                File saved=file;main.post(()->{if(cleared){saved.delete();return;}output=saved;mime=outputLayout==0?"image/jpeg":"application/pdf";busy=false;publish();});
            }catch(Exception|OutOfMemoryError e){if(file!=null)file.delete();fail("Could not create photo. Please try again.");}finally{if(bitmap!=null)bitmap.recycle();}});
        }
        void copyOutput(Uri uri){if(busy||output==null)return;File file=output;busy=true;status="Saving file…";publish();worker.execute(()->{String notice=UiText.get(R.string.ui_file_saved);try(InputStream in=new FileInputStream(file);OutputStream out=getApplication().getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException();byte[] buffer=new byte[16384];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}catch(Exception e){notice=UiText.get(R.string.ui_could_not_save_file);}String done=notice;main.post(()->{if(cleared)return;busy=false;message=done;publish();});});}
        private static void setJfifDensity(File file)throws IOException {
            try(RandomAccessFile jpeg=new RandomAccessFile(file,"rw")){
                if(jpeg.readUnsignedShort()!=0xffd8)throw new IOException(UiText.get(R.string.ui_invalid_jpeg));
                while(jpeg.getFilePointer()+4<jpeg.length()){
                    int marker=jpeg.readUnsignedShort();if(marker==0xffda||marker==0xffd9)return;int length=jpeg.readUnsignedShort();long start=jpeg.getFilePointer();if(length<2||start+length-2>jpeg.length())throw new IOException(UiText.get(R.string.ui_invalid_jpeg_segment));
                    if(marker==0xffe0&&length>=16){byte[] identifier=new byte[5];jpeg.readFully(identifier);if(Arrays.equals(identifier,new byte[]{74,70,73,70,0})){jpeg.seek(start+7);jpeg.writeByte(1);jpeg.writeShort(300);jpeg.writeShort(300);return;}}
                    jpeg.seek(start+length-2);
                }
            }
        }
        private void fail(String text){main.post(()->{if(cleared)return;busy=false;message=text;publish();});}
        void save(Bundle state){state.putString("portrait_source",sourcePath);state.putFloat("portrait_width",mmWidth);state.putFloat("portrait_height",mmHeight);state.putFloat("portrait_zoom",magnification);state.putFloat("portrait_x",panX);state.putFloat("portrait_y",panY);state.putInt("portrait_rotation",rotation);state.putInt("portrait_background",background);state.putInt("portrait_layout",layout);state.putString("portrait_output",output==null?null:output.getAbsolutePath());state.putString("portrait_mime",mime);}
        void restore(Bundle state){sourcePath=state.getString("portrait_source");mmWidth=state.getFloat("portrait_width",35);mmHeight=state.getFloat("portrait_height",45);magnification=state.getFloat("portrait_zoom",1);panX=state.getFloat("portrait_x");panY=state.getFloat("portrait_y");rotation=state.getInt("portrait_rotation");background=state.getInt("portrait_background",1);layout=state.getInt("portrait_layout");String result=state.getString("portrait_output");if(result!=null&&new File(result).isFile())output=new File(result);mime=state.getString("portrait_mime","image/jpeg");if(sourcePath!=null&&new File(sourcePath).isFile()){busy=true;worker.execute(()->prepare(new File(sourcePath),true));}}
        @Override protected void onCleared(){cleared=true;worker.shutdown();if(sourcePath!=null)deleteOwnedCapture(sourcePath);}
    }
}

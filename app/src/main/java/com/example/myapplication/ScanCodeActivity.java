package com.example.myapplication;

import android.Manifest;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.exifinterface.media.ExifInterface;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Original local QR/barcode workflow; decoded content never launches an action automatically. */
public class ScanCodeActivity extends AppCompatActivity {
    private final int BRAND = AppTheme.accent(), INK = AppTheme.ink(), MUTED = AppTheme.muted();
    private ScanSession session;
    private final ExecutorService analysisExecutor=Executors.newSingleThreadExecutor();
    private ProcessCameraProvider provider;
    private Preview cameraPreview;
    private ImageAnalysis imageAnalysis;
    private Camera camera;
    private PreviewView preview;
    private FrameLayout cameraArea;
    private ScrollView resultScroll;
    private LinearLayout resultCards;
    private TextView status,torchButton,againButton,importButton;
    private ProgressBar progress;
    private boolean destroyed,binding;
    private ActivityResultLauncher<String> permission;
    private ActivityResultLauncher<String[]> photoPicker;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        session=new ViewModelProvider(this).get(ScanSession.class);
        if(!session.initialized) { session.initialized=true; if(state!=null)session.restore(state); }
        permission=registerForActivityResult(new ActivityResultContracts.RequestPermission(),granted->{
            if(granted) {session.message=UiText.get(R.string.ui_align_a_qr_code_or_barcode_inside_the_frame);session.publish();bindCamera();}
            else {session.message=UiText.get(R.string.ui_camera_access_is_unavailable_you_can_still_import_an_image);session.publish();}
        });
        photoPicker=registerForActivityResult(new ActivityResultContracts.OpenDocument(),uri->{
            session.pickerOpen=false;
            if(uri==null)session.resumeAfterPicker();else session.importImage(uri);
        });
        buildUi(); session.changes.observe(this,revision->render()); render();
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED&&!session.permissionAsked) {
            session.permissionAsked=true;permission.launch(Manifest.permission.CAMERA);
        }
    }

    private void buildUi() {
        getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.BLACK);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);header.setPadding(dp(4),0,dp(10),0);
        ImageView back=new ImageView(this);back.setImageDrawable(new AppIcon("Back",Color.WHITE));back.setPadding(dp(14),dp(14),dp(14),dp(14));back.setContentDescription(UiText.get(R.string.ui_back));header.addView(back,new LinearLayout.LayoutParams(dp(48),dp(56)));back.setOnClickListener(v->finish());
        TextView title=text(UiText.get(R.string.ui_scan_code),19,Color.WHITE);title.setTypeface(null,android.graphics.Typeface.BOLD);header.addView(title,new LinearLayout.LayoutParams(0,dp(56),1));
        torchButton=text(UiText.get(R.string.ui_flash_off),13,Color.WHITE);torchButton.setGravity(Gravity.CENTER);torchButton.setContentDescription(UiText.get(R.string.ui_flash_off));header.addView(torchButton,new LinearLayout.LayoutParams(dp(92),dp(48)));torchButton.setOnClickListener(v->toggleTorch());root.addView(header);
        cameraArea=new FrameLayout(this);preview=new PreviewView(this);preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);cameraArea.addView(preview,new FrameLayout.LayoutParams(-1,-1));cameraArea.addView(new ScanGuide(),new FrameLayout.LayoutParams(-1,-1));root.addView(cameraArea,new LinearLayout.LayoutParams(-1,0,1));
        resultScroll=new ScrollView(this);resultScroll.setFillViewport(true);resultScroll.setBackgroundColor(AppTheme.background());resultCards=new LinearLayout(this);resultCards.setOrientation(android.widget.LinearLayout.VERTICAL);resultCards.setPadding(dp(18),dp(18),dp(18),dp(24));resultScroll.addView(resultCards);root.addView(resultScroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout feedback=new LinearLayout(this);feedback.setGravity(Gravity.CENTER_VERTICAL);feedback.setPadding(dp(18),dp(8),dp(18),dp(8));
        progress=new ProgressBar(this);feedback.addView(progress,new LinearLayout.LayoutParams(dp(24),dp(24)));status=text("",13,Color.WHITE);status.setGravity(Gravity.CENTER);feedback.addView(status,new LinearLayout.LayoutParams(0,dp(62),1));root.addView(feedback);
        LinearLayout actions=new LinearLayout(this);actions.setPadding(dp(14),dp(4),dp(14),dp(20));
        importButton=button(UiText.get(R.string.ui_import_images),"Import Images",false);actions.addView(importButton,new LinearLayout.LayoutParams(0,dp(52),1));importButton.setOnClickListener(v->{session.pickerOpen=true;session.paused=true;session.generation++;session.publish();photoPicker.launch(new String[]{"image/*"});});
        againButton=button(UiText.get(R.string.ui_scan_again),"Camera",true);LinearLayout.LayoutParams againLp=new LinearLayout.LayoutParams(0,dp(52),1);againLp.leftMargin=dp(10);actions.addView(againButton,againLp);againButton.setOnClickListener(v->{session.restart();if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)permission.launch(Manifest.permission.CAMERA);});root.addView(actions);
        UiInsets.apply(root);setContentView(root);
    }

    private void render() {
        if(destroyed)return;
        boolean hasResult=!session.results.isEmpty();
        cameraArea.setVisibility(hasResult?View.GONE:View.VISIBLE);resultScroll.setVisibility(hasResult?View.VISIBLE:View.GONE);
        progress.setVisibility(session.importing?View.VISIBLE:View.GONE);status.setText(session.message);
        importButton.setEnabled(!session.importing&&!session.pickerOpen);againButton.setEnabled(!session.importing&&!session.pickerOpen);againButton.setVisibility(hasResult||!hasPermission()?View.VISIBLE:View.GONE);
        torchButton.setText(session.torchRequested ? UiText.get(R.string.ui_flash_on) : UiText.get(R.string.ui_flash_off));torchButton.setContentDescription(session.torchRequested ? UiText.get(R.string.ui_flash_on) : UiText.get(R.string.ui_flash_off));torchButton.setEnabled(!hasResult&&!session.importing&&!session.pickerOpen);
        if(hasResult)showResults();
        if(hasResult||session.importing||session.pickerOpen)stopCamera();else if(hasPermission())bindCamera();
    }
    private boolean hasPermission(){return ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED;}
    @androidx.annotation.OptIn(markerClass=androidx.camera.core.ExperimentalGetImage.class)
    private void bindCamera() {
        if(destroyed||binding||camera!=null||session.paused||!session.results.isEmpty()||!hasPermission())return;
        binding=true;ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);
        future.addListener(()->{
            binding=false;if(destroyed||session.paused||!session.results.isEmpty())return;
            try {
                provider=future.get();cameraPreview=new Preview.Builder().build();cameraPreview.setSurfaceProvider(preview.getSurfaceProvider());
                ResolutionSelector resolution=new ResolutionSelector.Builder().setResolutionStrategy(new ResolutionStrategy(new android.util.Size(1280,720),ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)).build();
                imageAnalysis=new ImageAnalysis.Builder().setResolutionSelector(resolution).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                final ScanSession owner=session;imageAnalysis.setAnalyzer(analysisExecutor,owner::analyze);
                camera=provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,cameraPreview,imageAnalysis);
                if(session.torchRequested&&camera.getCameraInfo().hasFlashUnit())camera.getCameraControl().enableTorch(true);
            }catch(Exception e){camera=null;session.message=UiText.get(R.string.ui_camera_could_not_start_import_an_image_to_scan_instead);status.setText(session.message);}
        },ContextCompat.getMainExecutor(this));
    }
    private void stopCamera() {
        if(camera!=null)camera.getCameraControl().enableTorch(false);
        if(imageAnalysis!=null)imageAnalysis.clearAnalyzer();
        if(provider!=null&&cameraPreview!=null&&imageAnalysis!=null)provider.unbind(cameraPreview,imageAnalysis);
        camera=null;cameraPreview=null;imageAnalysis=null;
    }
    private void toggleTorch() {
        if(camera==null||!camera.getCameraInfo().hasFlashUnit()){Toast.makeText(this,UiText.get(R.string.ui_this_camera_has_no_flash),Toast.LENGTH_SHORT).show();return;}
        session.torchRequested=!session.torchRequested;camera.getCameraControl().enableTorch(session.torchRequested);session.publish();
    }
    private void showResults() {
        resultCards.removeAllViews();TextView heading=text(session.results.size()==1 ? UiText.get(R.string.ui_code_found) : UiText.format(R.string.ui_value_codes_found, session.results.size()),23,INK);heading.setTypeface(null,android.graphics.Typeface.BOLD);resultCards.addView(heading);
        for(CodeResult result:session.results) {
            LinearLayout card=new LinearLayout(this);card.setOrientation(android.widget.LinearLayout.VERTICAL);card.setPadding(dp(16),dp(16),dp(16),dp(16));card.setBackground(round(AppTheme.surface(),10));LinearLayout.LayoutParams cardLp=new LinearLayout.LayoutParams(-1,-2);cardLp.topMargin=dp(14);resultCards.addView(card,cardLp);
            TextView type=text(UiText.format(R.string.ui_value_value_c5cbdde4, result.format, result.type),13,BRAND);type.setTypeface(null,android.graphics.Typeface.BOLD);card.addView(type);
            TextView value=text(result.value,16,INK);value.setTextIsSelectable(true);value.setPadding(0,dp(14),0,dp(14));card.addView(value);
            LinearLayout tools=new LinearLayout(this);card.addView(tools);TextView copy=button(UiText.get(R.string.ui_copy),"All Documents",false),share=button(UiText.get(R.string.ui_share),"Share",false);tools.addView(copy,new LinearLayout.LayoutParams(0,dp(46),1));tools.addView(share,new LinearLayout.LayoutParams(0,dp(46),1));
            copy.setOnClickListener(v->{ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("Scanned code",result.value));Toast.makeText(this,UiText.get(R.string.ui_copied),Toast.LENGTH_SHORT).show();});
            share.setOnClickListener(v->{Intent send=new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,result.value);startActivity(Intent.createChooser(send,UiText.get(R.string.ui_share_scanned_code)));});
            if(result.openUri!=null) {
                TextView open=button(UiText.get(R.string.ui_open_link),"View",true);LinearLayout.LayoutParams openLp=new LinearLayout.LayoutParams(-1,dp(46));openLp.topMargin=dp(10);card.addView(open,openLp);
                open.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(result.openUri)).addCategory(Intent.CATEGORY_BROWSABLE));}catch(Exception e){Toast.makeText(this,UiText.get(R.string.ui_no_app_can_open_this_link),Toast.LENGTH_LONG).show();}});
            }
        }
    }
    private TextView text(String value,int size,int color){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setGravity(Gravity.CENTER_VERTICAL);return view;}
    private TextView button(String title,String icon,boolean filled){TextView view=text(title,14,filled ? AppTheme.onAccent() :BRAND);view.setGravity(Gravity.CENTER);view.setContentDescription(title);view.setBackground(round(filled?BRAND:AppTheme.soft(),7));AppTheme.polishAction(view);AppIcon drawable=new AppIcon(icon,filled ? AppTheme.onAccent() :BRAND);drawable.setBounds(0,0,dp(20),dp(20));view.setCompoundDrawables(drawable,null,null,null);view.setCompoundDrawablePadding(dp(7));view.setPadding(dp(12),0,dp(12),0);return view;}
    private GradientDrawable round(int color,int radius){GradientDrawable drawable=new GradientDrawable();drawable.setColor(color);drawable.setCornerRadius(dp(radius));return drawable;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override protected void onStart(){super.onStart();if(preview!=null)render();}
    @Override protected void onStop(){stopCamera();super.onStop();}
    @Override protected void onSaveInstanceState(Bundle state){session.save(state);super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){destroyed=true;stopCamera();analysisExecutor.shutdown();super.onDestroy();}

    private class ScanGuide extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        ScanGuide(){super(ScanCodeActivity.this);setContentDescription(UiText.get(R.string.ui_qr_and_barcode_scan_frame));}
        @Override protected void onDraw(Canvas canvas){float w=getWidth(),h=getHeight(),side=Math.min(w*.76f,h*.58f);RectF r=new RectF((w-side)/2,(h-side)/2,(w+side)/2,(h+side)/2);paint.setColor(0x66000000);paint.setStyle(Paint.Style.FILL);canvas.drawRect(0,0,w,r.top,paint);canvas.drawRect(0,r.bottom,w,h,paint);canvas.drawRect(0,r.top,r.left,r.bottom,paint);canvas.drawRect(r.right,r.top,w,r.bottom,paint);paint.setColor(BRAND);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(3));float length=dp(25);canvas.drawLine(r.left,r.top,r.left+length,r.top,paint);canvas.drawLine(r.left,r.top,r.left,r.top+length,paint);canvas.drawLine(r.right,r.top,r.right-length,r.top,paint);canvas.drawLine(r.right,r.top,r.right,r.top+length,paint);canvas.drawLine(r.left,r.bottom,r.left+length,r.bottom,paint);canvas.drawLine(r.left,r.bottom,r.left,r.bottom-length,paint);canvas.drawLine(r.right,r.bottom,r.right-length,r.bottom,paint);canvas.drawLine(r.right,r.bottom,r.right,r.bottom-length,paint);}
    }

    static class CodeResult {
        final String value,format,type,openUri;
        CodeResult(String value,String format,String type,String uri){this.value=value;this.format=format;this.type=type;openUri=uri;}
        static CodeResult from(com.google.zxing.Result barcode){String value=barcode.getText();if(value==null||value.isEmpty())return null;String uri=null;com.google.zxing.client.result.ParsedResult parsed=com.google.zxing.client.result.ResultParser.parseResult(barcode);if(parsed instanceof com.google.zxing.client.result.URIParsedResult){String link=((com.google.zxing.client.result.URIParsedResult)parsed).getURI();Uri target=Uri.parse(link);if(("https".equalsIgnoreCase(target.getScheme())||"http".equalsIgnoreCase(target.getScheme()))&&target.getHost()!=null)uri=link;}String format=barcode.getBarcodeFormat()==com.google.zxing.BarcodeFormat.QR_CODE?"QR Code":barcode.getBarcodeFormat().name().replace('_',' ');return new CodeResult(value,format,parsed.getType().name().replace('_',' '),uri);}

    }

    public static class ScanSession extends AndroidViewModel {
        final MutableLiveData<Integer> changes=new MutableLiveData<>(0);
        final ArrayList<CodeResult> results=new ArrayList<>();

        final ExecutorService worker=Executors.newSingleThreadExecutor();
        final Handler main=new Handler(Looper.getMainLooper());
        final AtomicBoolean processing=new AtomicBoolean();
        volatile boolean paused,cleared;
        volatile int generation;
        boolean initialized,permissionAsked,torchRequested,pickerOpen,importing;
        String message=UiText.get(R.string.ui_align_a_qr_code_or_barcode_inside_the_frame),candidate;
        int candidateCount;
        Uri pendingImport;
        public ScanSession(Application app){super(app);}
        void publish(){if(!cleared)changes.setValue(changes.getValue()==null?1:changes.getValue()+1);}
        void restart(){generation++;results.clear();candidate=null;candidateCount=0;paused=false;message=UiText.get(R.string.ui_align_a_qr_code_or_barcode_inside_the_frame);publish();}
        void resumeAfterPicker(){paused=!results.isEmpty();message=results.isEmpty() ? UiText.get(R.string.ui_align_a_qr_code_or_barcode_inside_the_frame) : UiText.get(R.string.ui_code_decoded_on_this_device);publish();}
        @androidx.camera.core.ExperimentalGetImage
        void analyze(ImageProxy proxy){
            if(cleared||paused||!processing.compareAndSet(false,true)){proxy.close();return;}final int version=generation;Bitmap bitmap=null;ArrayList<CodeResult> found=new ArrayList<>();
            try{bitmap=proxy.toBitmap();found=decodeCodes(bitmap);}catch(Exception ignored){}finally{if(bitmap!=null)bitmap.recycle();proxy.close();processing.set(false);}
            final ArrayList<CodeResult> decoded=found;main.post(()->{if(cleared)return;if(version==generation&&!paused&&!decoded.isEmpty()){String value=decoded.get(0).value;if(value.equals(candidate))candidateCount++;else{candidate=value;candidateCount=1;}if(candidateCount>=2){results.clear();results.add(decoded.get(0));paused=true;message=UiText.get(R.string.ui_code_decoded_on_this_device);publish();}}else if(version==generation&&decoded.isEmpty()){candidate=null;candidateCount=0;}flushImport();});
        }
        void importImage(Uri uri){generation++;paused=true;importing=true;pendingImport=uri;message=UiText.get(R.string.ui_reading_codes_from_image);publish();flushImport();}
        private void flushImport(){
            if(cleared||pendingImport==null||!processing.compareAndSet(false,true))return;
            final Uri uri=pendingImport;pendingImport=null;final int version=generation;
            worker.execute(()->{
                Bitmap bitmap=null;ArrayList<CodeResult> found=new ArrayList<>();String error=null;
                try{bitmap=decode(uri);found=decodeCodes(bitmap);if(found.isEmpty())error="No readable code found. Try a sharper image or scan with the camera.";}
                catch(Exception|OutOfMemoryError e){error="Could not read this image. Choose another image.";}
                finally{if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();processing.set(false);}
                final ArrayList<CodeResult> decoded=found;final String failure=error;
                main.post(()->{if(cleared){return;}if(version==generation){importing=false;if(!decoded.isEmpty()){results.clear();results.addAll(decoded);paused=true;message=UiText.get(R.string.ui_code_decoded_on_this_device);}else{paused=!results.isEmpty();message=failure;}publish();}flushImport();});
            });
        }
        private Bitmap decode(Uri uri)throws Exception{
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;try(InputStream in=getApplication().getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,options);}if(options.outWidth<=0||options.outHeight<=0)throw new java.io.IOException();
            options.inJustDecodeBounds=false;options.inSampleSize=1;while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>2600)options.inSampleSize*=2;
            Bitmap image;try(InputStream in=getApplication().getContentResolver().openInputStream(uri)){image=BitmapFactory.decodeStream(in,null,options);}if(image==null)throw new java.io.IOException();
            try(InputStream in=getApplication().getContentResolver().openInputStream(uri)){ExifInterface exif=new ExifInterface(in);Matrix matrix=new Matrix();matrix.postRotate(exif.getRotationDegrees());if(exif.isFlipped())matrix.postScale(-1,1);if(!matrix.isIdentity()){Bitmap rotated=Bitmap.createBitmap(image,0,0,image.getWidth(),image.getHeight(),matrix,true);if(rotated!=image){image.recycle();image=rotated;}}}catch(Exception ignored){}return image;
        }
        private static ArrayList<CodeResult> decodeCodes(Bitmap bitmap){ArrayList<CodeResult> results=new ArrayList<>();int[] pixels=new int[bitmap.getWidth()*bitmap.getHeight()];bitmap.getPixels(pixels,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight());com.google.zxing.LuminanceSource source=new com.google.zxing.RGBLuminanceSource(bitmap.getWidth(),bitmap.getHeight(),pixels);java.util.Map<com.google.zxing.DecodeHintType,Object> hints=new java.util.EnumMap<>(com.google.zxing.DecodeHintType.class);hints.put(com.google.zxing.DecodeHintType.TRY_HARDER,Boolean.TRUE);hints.put(com.google.zxing.DecodeHintType.ALSO_INVERTED,Boolean.TRUE);for(int angle=0;angle<2&&results.isEmpty();angle++){try{com.google.zxing.BinaryBitmap input=new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(source));com.google.zxing.Result[] codes=new com.google.zxing.multi.GenericMultipleBarcodeReader(new com.google.zxing.MultiFormatReader()).decodeMultiple(input,hints);for(com.google.zxing.Result code:codes){CodeResult result=CodeResult.from(code);if(result!=null)results.add(result);if(results.size()>=20)break;}}catch(com.google.zxing.NotFoundException ignored){}if(angle==0){int w=bitmap.getWidth(),h=bitmap.getHeight();int[] rotated=new int[pixels.length];for(int y=0;y<h;y++)for(int x=0;x<w;x++)rotated[x*h+h-1-y]=pixels[y*w+x];source=new com.google.zxing.RGBLuminanceSource(h,w,rotated);}}return results;}
        void save(Bundle state){state.putBoolean("code_permission_asked",permissionAsked);state.putBoolean("code_torch",torchRequested);state.putBoolean("code_picker",pickerOpen);ArrayList<String> values=new ArrayList<>(),formats=new ArrayList<>(),types=new ArrayList<>(),uris=new ArrayList<>();for(CodeResult r:results){values.add(r.value);formats.add(r.format);types.add(r.type);uris.add(r.openUri);}state.putStringArrayList("code_values",values);state.putStringArrayList("code_formats",formats);state.putStringArrayList("code_types",types);state.putStringArrayList("code_uris",uris);}
        void restore(Bundle state){permissionAsked=state.getBoolean("code_permission_asked");torchRequested=state.getBoolean("code_torch");pickerOpen=state.getBoolean("code_picker");ArrayList<String> values=state.getStringArrayList("code_values"),formats=state.getStringArrayList("code_formats"),types=state.getStringArrayList("code_types"),uris=state.getStringArrayList("code_uris");if(values!=null&&formats!=null&&types!=null&&uris!=null)for(int i=0;i<Math.min(Math.min(values.size(),formats.size()),Math.min(types.size(),uris.size()));i++)results.add(new CodeResult(values.get(i),formats.get(i),types.get(i),uris.get(i)));paused=pickerOpen||!results.isEmpty();if(!results.isEmpty())message=UiText.get(R.string.ui_code_decoded_on_this_device);}
        @Override protected void onCleared(){cleared=true;paused=true;worker.shutdown();}
    }
}

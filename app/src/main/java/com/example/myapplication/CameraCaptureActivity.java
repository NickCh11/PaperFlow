package com.example.myapplication;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.graphics.drawable.*;
import android.net.Uri;
import android.os.*;
import android.util.Size;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.*;
import androidx.camera.core.*;
import androidx.camera.core.Camera;
import androidx.camera.core.resolutionselector.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.view.*;
import androidx.lifecycle.ViewModelProvider;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

public class CameraCaptureActivity extends BackHandlingActivity {
    private static final int TEAL = 0xffefad87;
    private static final String[] MODES={"To Word","Sign","Single","Batch","ID Cards","Book","Whiteboard","Slides","Timestamp"};
    private CameraCaptureSession session;
    private PreviewView preview; private GuideView guide; private ImageCapture imageCapture; private Camera camera; private boolean ready,binding;
    private ImageView flash,thumbnail; private TextView quality,hint,count,undo,bookSingle,bookDouble; private LinearLayout bookChoices; private View close,more,shutter,features,images,files;
    private FrameLayout review; private HorizontalScrollView modeScroll; private LinearLayout modeRow;
    private ActivityResultLauncher<String> permissionLauncher; private ActivityResultLauncher<String[]> galleryLauncher;
    private ActivityResultLauncher<Intent> bookReviewLauncher;
    private final java.util.concurrent.ExecutorService liveWorker=java.util.concurrent.Executors.newSingleThreadExecutor();
    private ImageAnalysis liveAnalysis;private volatile int liveGeneration;private float[] previousLive;private int stableLive;private long lastLive;
    private boolean autoCapture,autoArmed=true,resumed;private long stableSince,missingSince,lastAuto;private float[] stableAnchor;private int[] capturedSignature,latestSignature;private boolean bookRearmedByChange;private final BookPageTracker bookTracker=new BookPageTracker();
    private final Handler handler=new Handler(Looper.getMainLooper()); private int countdown; private boolean timerPending;
    private final Runnable timerTick=new Runnable(){@Override public void run(){if(isFinishing()||isDestroyed())return;if(countdown>0){hint.setText(String.valueOf(countdown--));handler.postDelayed(this,1000);}else{timerPending=false;render();takePhoto();}}};
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);
        WindowCompat.getInsetsController(getWindow(),getWindow().getDecorView()).setAppearanceLightStatusBars(false);
        WindowCompat.getInsetsController(getWindow(),getWindow().getDecorView()).setAppearanceLightNavigationBars(false);
        session=new ViewModelProvider(this).get(CameraCaptureSession.class);
        if(!session.initialized){session.initialized=true;session.mode=canonical(state==null?getIntent().getStringExtra("capture_mode"):state.getString("mode"));
            if(state!=null){ArrayList<String> saved=state.getStringArrayList("pages");if(saved!=null)for(String path:saved)if(new File(path).isFile())session.pages.add(path);
                session.hd=state.getBoolean("hd",true);session.grid=state.getBoolean("grid");session.flash=state.getInt("flash",ImageCapture.FLASH_MODE_OFF);session.timer=state.getInt("timer");session.bookDoublePage=state.getBoolean("book_double_page",true);}}
        permissionLauncher=registerForActivityResult(new ActivityResultContracts.RequestPermission(),granted->{if(granted)bindCamera();else permissionDenied();});
        galleryLauncher=registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(),uris->{if(uris!=null&&!uris.isEmpty()&&!session.busy)ScanImportOptions.show(this,(crop,filter)->{session.importOptionsChosen=true;session.importAutoCrop=crop;session.importFilter=filter;session.importImages(uris);},()->{});});
        bookReviewLauncher=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{
            if(result.getResultCode()==RESULT_OK&&result.getData()!=null){String path=result.getData().getStringExtra("path");if(path!=null&&new File(path).isFile()){for(String source:session.pages)new File(source).delete();session.completeBookDraft();session.returned=true;startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("document_action","Register").putExtra("path",path).putExtra("name",result.getData().getStringExtra("name")));setResult(RESULT_CANCELED);finish();return;}}
            render();
        });
        if("Book".equals(session.mode)&&session.pages.isEmpty())session.restoreBookDraft();
        autoCapture=getPreferences(0).getBoolean("auto_capture",false);autoArmed=session.pages.isEmpty();buildUi();session.changes.observe(this,revision->render());render();
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)bindCamera();else permissionLauncher.launch(Manifest.permission.CAMERA);
    }
    private String canonical(String input){if("ID Card".equals(input))return "ID Cards";for(String mode:MODES)if(mode.equals(input))return mode;return "Single";}
    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(android.widget.LinearLayout.VERTICAL);root.setBackgroundColor(Color.BLACK);setContentView(root);
        UiInsets.apply(root);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.BOTTOM);header.setPadding(dp(6),0,dp(4),0);root.addView(header,new LinearLayout.LayoutParams(-1,dp(72)));
        close=headerIcon(header,new AppIcon("Close",Color.WHITE),UiText.get(R.string.ui_close_camera),v->cancel());header.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));
        flash=headerIcon(header,new HeaderIcon(false,false),UiText.get(R.string.ui_flash_off),v->chooseFlash());
        FrameLayout qualityHolder=new FrameLayout(this);quality=text(UiText.get(R.string.ui_hd),13,Color.BLACK);quality.setGravity(Gravity.CENTER);quality.setTypeface(null,android.graphics.Typeface.BOLD);quality.setBackground(round(AppTheme.surface(),4));
        qualityHolder.addView(quality,new FrameLayout.LayoutParams(dp(23),dp(20),Gravity.CENTER));header.addView(qualityHolder,new LinearLayout.LayoutParams(dp(48),dp(48)));
        qualityHolder.setOnClickListener(v->{if(!session.busy){session.hd=!session.hd;render();bindCamera();}});
        more=headerIcon(header,new HeaderIcon(true,false),UiText.get(R.string.ui_camera_settings),v->settings());
        FrameLayout cameraFrame=new FrameLayout(this);root.addView(cameraFrame,new LinearLayout.LayoutParams(-1,0,1));
        preview=new PreviewView(this);preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);cameraFrame.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        guide=new GuideView(this);cameraFrame.addView(guide,new FrameLayout.LayoutParams(-1,-1));
        bookChoices=new LinearLayout(this);bookChoices.setPadding(dp(2),dp(2),dp(2),dp(2));bookChoices.setBackground(round(0xcc252525,20));bookSingle=text(UiText.get(R.string.ui_single),12,Color.WHITE);bookDouble=text(UiText.get(R.string.ui_double),12,Color.WHITE);for(TextView option:new TextView[]{bookSingle,bookDouble}){option.setGravity(Gravity.CENTER);bookChoices.addView(option,new LinearLayout.LayoutParams(0,-1,1));}FrameLayout.LayoutParams bookLp=new FrameLayout.LayoutParams(dp(132),dp(34),Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);bookLp.bottomMargin=dp(10);cameraFrame.addView(bookChoices,bookLp);bookSingle.setOnClickListener(v->{if(!session.busy){session.bookDoublePage=false;render();}});bookDouble.setOnClickListener(v->{if(!session.busy){session.bookDoublePage=true;render();}});
        guide.setOnTouchListener((v,event)->{if(event.getAction()==MotionEvent.ACTION_UP&&camera!=null&&!session.busy){focus(event.getX(),event.getY(),true);v.performClick();}return true;});
        hint=text("",13,Color.WHITE);hint.setGravity(Gravity.CENTER);hint.setShadowLayer(3,0,1,Color.BLACK);FrameLayout.LayoutParams hintLp=new FrameLayout.LayoutParams(-1,dp(56),Gravity.TOP);hintLp.topMargin=dp(10);cameraFrame.addView(hint,hintLp);
        review=new FrameLayout(this);review.setPadding(dp(3),dp(3),dp(3),dp(3));review.setBackground(round(0xCC10191A,8));thumbnail=new ImageView(this);thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);review.addView(thumbnail,new FrameLayout.LayoutParams(-1,-1));
        count=text("",12,Color.WHITE);count.setGravity(Gravity.CENTER);count.setTypeface(null,android.graphics.Typeface.BOLD);count.setBackground(round(TEAL,4));review.addView(count,new FrameLayout.LayoutParams(-1,dp(24),Gravity.BOTTOM));
        FrameLayout.LayoutParams reviewLp=new FrameLayout.LayoutParams(dp(58),dp(72),Gravity.BOTTOM|Gravity.RIGHT);reviewLp.setMargins(0,0,dp(16),dp(15));cameraFrame.addView(review,reviewLp);review.setOnClickListener(v->finishCapture(false));
        undo=text(UiText.get(R.string.ui_undo),12,Color.WHITE);undo.setGravity(Gravity.CENTER);icon(undo,UiText.get(R.string.ui_undo),false);FrameLayout.LayoutParams undoLp=new FrameLayout.LayoutParams(dp(76),dp(44),Gravity.BOTTOM|Gravity.LEFT);undoLp.setMargins(dp(12),0,0,dp(15));cameraFrame.addView(undo,undoLp);undo.setOnClickListener(v->session.undo());
        modeScroll=new HorizontalScrollView(this);modeScroll.setHorizontalScrollBarEnabled(false);modeRow=new LinearLayout(this);int modePadding=Math.max(0,getResources().getDisplayMetrics().widthPixels/2-dp(32));modeRow.setPadding(modePadding,0,modePadding,0);modeScroll.addView(modeRow);root.addView(modeScroll,new LinearLayout.LayoutParams(-1,dp(46)));
        for(String mode:MODES){TextView label=new ModeLabel();label.setText(UiText.label(mode));label.setTextSize(14);label.setTextColor(Color.WHITE);label.setSingleLine();label.setGravity(Gravity.CENTER);label.setTag(mode);modeRow.addView(label,new LinearLayout.LayoutParams(dp(mode.length()>8?104:64),-1));label.setOnClickListener(v->selectMode(mode));}
        LinearLayout controls=new LinearLayout(this);controls.setGravity(Gravity.CENTER_VERTICAL);controls.setPadding(dp(10),dp(12),dp(10),dp(20));root.addView(controls,new LinearLayout.LayoutParams(-1,dp(200)));
        features=action(controls,UiText.get(R.string.ui_all_features),"All Features",v->new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_capture_mode)).setItems(UiText.labels(MODES),(d,w)->selectMode(MODES[w])).show());
        ((LinearLayout.LayoutParams)features.getLayoutParams()).weight=2f;
        FrameLayout holder=new FrameLayout(this);controls.addView(holder,new LinearLayout.LayoutParams(0,-1,1.2f));FrameLayout ring=new FrameLayout(this);GradientDrawable outline=round(Color.BLACK,100);outline.setStroke(dp(3),TEAL);ring.setBackground(outline);holder.addView(ring,new FrameLayout.LayoutParams(dp(64),dp(64),Gravity.CENTER));
        shutter=new View(this);shutter.setBackground(round(0xFFF6F7F7,100));ring.addView(shutter,new FrameLayout.LayoutParams(dp(54),dp(54),Gravity.CENTER));shutter.setContentDescription(UiText.get(R.string.ui_capture_page));shutter.setOnClickListener(v->capture());
        images=action(controls,UiText.get(R.string.ui_import_images_ac61abc3),"Import Images",v->galleryLauncher.launch(new String[]{"image/*"}));files=action(controls,UiText.get(R.string.ui_import_files),"Import Files",v->finishCapture(true));
    }
    private ImageView headerIcon(LinearLayout parent,Drawable drawable,String desc,View.OnClickListener listener){ImageView view=new ImageView(this);view.setImageDrawable(drawable);view.setPadding(dp(13),dp(13),dp(13),dp(13));view.setContentDescription(desc);parent.addView(view,new LinearLayout.LayoutParams(dp(48),dp(48)));view.setOnClickListener(listener);return view;}
    private TextView action(LinearLayout parent,String label,String name,View.OnClickListener listener){TextView view=text(label,11,Color.WHITE);view.setGravity(Gravity.CENTER);icon(view,name,true);view.setContentDescription(UiText.label(label).replace('\n',' '));parent.addView(view,new LinearLayout.LayoutParams(0,dp(88),1));view.setOnClickListener(listener);return view;}
    private void icon(TextView view,String name,boolean top){AppIcon icon=new AppIcon(name,Color.WHITE);icon.setBounds(0,0,dp(top?27:20),dp(top?27:20));view.setCompoundDrawablePadding(dp(7));view.setCompoundDrawables(top?null:icon,top?icon:null,null,null);}
    private void render(){
        bookChoices.setVisibility("Book".equals(session.mode)?View.VISIBLE:View.GONE);bookSingle.setEnabled(!session.busy);bookDouble.setEnabled(!session.busy);bookSingle.setBackground(round(session.bookDoublePage?Color.TRANSPARENT:0xff737373,18));bookDouble.setBackground(round(session.bookDoublePage?0xff737373:Color.TRANSPARENT,18));bookSingle.setSelected(!session.bookDoublePage);bookDouble.setSelected(session.bookDoublePage);
        for(View view:new View[]{close,flash,more,shutter,features,images,files,review,undo,(View)quality.getParent()})view.setEnabled(!session.busy);
        shutter.setEnabled(!session.busy&&ready);shutter.setAlpha(!session.busy&&ready?1:.4f);
        for(int i=0;i<modeRow.getChildCount();i++){TextView label=(TextView)modeRow.getChildAt(i);label.setEnabled(!session.busy);boolean active=session.mode.equals(label.getTag());label.setSelected(active);label.setTextColor(active?TEAL:Color.WHITE);}
        quality.setText(session.hd ? UiText.get(R.string.ui_hd) : UiText.get(R.string.ui_sd));((View)quality.getParent()).setContentDescription(session.hd ? UiText.get(R.string.ui_high_resolution_on) : UiText.get(R.string.ui_standard_resolution_on));
        flash.setImageDrawable(new HeaderIcon(false,session.flash!=ImageCapture.FLASH_MODE_OFF));flash.setContentDescription(UiText.format(R.string.ui_flash_value, (session.flash==ImageCapture.FLASH_MODE_AUTO?"auto":session.flash==ImageCapture.FLASH_MODE_ON?"on":"off")));
        review.setVisibility(session.pages.isEmpty()?View.GONE:View.VISIBLE);undo.setVisibility(session.pages.isEmpty()?View.GONE:View.VISIBLE);count.setText(UiText.format(R.string.ui_value, ("Book".equals(session.mode)?Math.min(99,session.pages.size()):session.pages.size())));review.setContentDescription(UiText.format(R.string.ui_review_value_pages, session.pages.size()));
        if(session.thumbnail!=null)thumbnail.setImageBitmap(session.thumbnail);guide.mode=session.mode;guide.grid=session.grid;guide.bookDouble=session.bookDoublePage;guide.invalidate();
        if(!timerPending)hint.setText(session.busy ? UiText.get(R.string.ui_processing) : guideText());if(session.message!=null){Toast.makeText(this,session.message,Toast.LENGTH_LONG).show();session.message=null;}
        if(session.returnRequested&&!session.busy&&!isFinishing()){finishCapture(false);return;}
        modeScroll.post(()->{for(int i=0;i<modeRow.getChildCount();i++){View child=modeRow.getChildAt(i);if(session.mode.equals(child.getTag())){modeScroll.smoothScrollTo(Math.max(0,child.getLeft()-(modeScroll.getWidth()-child.getWidth())/2),0);break;}}});
    }
    private String guideText(){switch(session.mode){case "ID Cards":return session.pages.isEmpty() ? UiText.get(R.string.ui_align_the_front_of_the_card_with_the_live_outline) : UiText.get(R.string.ui_turn_the_card_over_and_capture_the_back);case "Book":return session.bookDoublePage ? UiText.get(R.string.ui_align_the_spine_with_the_center_line_saves_two_pages) : UiText.get(R.string.ui_align_the_page_with_the_live_outline_saves_one_page);case "Whiteboard":return UiText.get(R.string.ui_align_the_board_with_the_live_outline);case "Slides":return UiText.get(R.string.ui_align_the_slide_with_the_live_outline);case "Timestamp":return UiText.get(R.string.ui_date_and_time_will_be_printed_on_the_image);case "Batch":return UiText.get(R.string.ui_scan_more_pages_then_tap_the_thumbnail_to_review);default:return "";}}
    private void selectMode(String mode){if(session.busy||session.mode.equals(mode))return;if(!session.pages.isEmpty()){Toast.makeText(this,UiText.get(R.string.ui_review_or_undo_your_pages_before_changing_mode),Toast.LENGTH_SHORT).show();return;}session.mode=mode;if("Book".equals(mode))session.restoreBookDraft();render();}
    private void bindCamera(){
        if(isFinishing()||isDestroyed()||binding)return;
        if(preview.getViewPort()==null){preview.postDelayed(this::bindCamera,100);return;}
        binding=true;ready=false;render();ListenableFuture<ProcessCameraProvider> future=ProcessCameraProvider.getInstance(this);
        future.addListener(()->{if(isFinishing()||isDestroyed())return;try{ProcessCameraProvider provider=future.get();
            ResolutionSelector resolution=new ResolutionSelector.Builder().setResolutionStrategy(session.hd?ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY:new ResolutionStrategy(new Size(1920,1440),ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build();
            Preview live=new Preview.Builder().build();live.setSurfaceProvider(preview.getSurfaceProvider());ImageCapture.Builder builder=new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).setResolutionSelector(resolution).setFlashMode(session.flash);
            if(preview.getDisplay()!=null)builder.setTargetRotation(preview.getDisplay().getRotation());imageCapture=builder.build();
            liveGeneration++;previousLive=null;stableLive=0;guide.documentQuad=null;
            if(liveAnalysis!=null)liveAnalysis.clearAnalyzer();
            liveAnalysis=new ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setTargetResolution(new Size(640,480)).build();
            final int generation=liveGeneration;liveAnalysis.setAnalyzer(liveWorker,image->analyzeLive(image,generation));
            UseCaseGroup group=new UseCaseGroup.Builder().setViewPort(preview.getViewPort()).addUseCase(live).addUseCase(imageCapture).addUseCase(liveAnalysis).build();
            provider.unbindAll();camera=provider.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,group);ready=true;render();
        }catch(Exception error){hint.setText(UiText.get(R.string.ui_camera_unavailable_import_images_or_files_below));Toast.makeText(this,UiText.get(R.string.ui_could_not_start_camera),Toast.LENGTH_LONG).show();}finally{binding=false;}},ContextCompat.getMainExecutor(this));
    }
    private void analyzeLive(ImageProxy image,int generation){
        Bitmap bitmap=null;
        try{
            if(session.busy||generation!=liveGeneration||SystemClock.elapsedRealtime()-lastLive<120)return;
            lastLive=SystemClock.elapsedRealtime();bitmap=image.toBitmap();Rect crop=image.getCropRect();
            if(crop.width()!=bitmap.getWidth()||crop.height()!=bitmap.getHeight()){Bitmap clipped=Bitmap.createBitmap(bitmap,crop.left,crop.top,crop.width(),crop.height());if(clipped!=bitmap)bitmap.recycle();bitmap=clipped;}
            int rotation=image.getImageInfo().getRotationDegrees();if(rotation!=0){Matrix matrix=new Matrix();matrix.postRotate(rotation);Bitmap turned=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);if(turned!=bitmap)bitmap.recycle();bitmap=turned;}
            float ratio=bitmap.getWidth()/(float)bitmap.getHeight();float[] quad=com.paperflow.scanengine.ScanEngine.analyze(bitmap,true).corners;
            float difference=0;if(quad!=null&&previousLive!=null)for(int i=0;i<8;i+=2)difference=Math.max(difference,(float)Math.hypot(quad[i]-previousLive[i],quad[i+1]-previousLive[i+1]));
            stableLive=quad==null?0:previousLive!=null&&difference<20f/640?stableLive+1:1;previousLive=quad;
            final float[] visible=stableLive>=3?quad:null;
            int[] signature="Book".equals(session.mode)?BookPageTracker.frame(bitmap,quad):signature(bitmap);
            runOnUiThread(()->{if(generation==liveGeneration&&!isFinishing()){guide.documentQuad=visible;guide.imageRatio=ratio;guide.invalidate();considerAutoCapture(quad,signature);}});
        }catch(Exception|LinkageError error){runOnUiThread(()->{if(generation==liveGeneration){guide.documentQuad=null;guide.invalidate();}});}
        finally{if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();image.close();}
    }
    private int[] signature(Bitmap image){int[] values=new int[64];for(int y=0;y<8;y++)for(int x=0;x<8;x++){int pixel=image.getPixel((x*2+1)*image.getWidth()/16,(y*2+1)*image.getHeight()/16);values[y*8+x]=(Color.red(pixel)*3+Color.green(pixel)*6+Color.blue(pixel))/10;}return values;}
    private void considerAutoCapture(float[] quad,int[] signature){
        latestSignature=signature;if("Book".equals(session.mode)&&signature!=null&&!session.pages.isEmpty()&&!bookTracker.hasBaseline())bookTracker.accept(signature);boolean bookReady="Book".equals(session.mode)&&bookTracker.observe(signature);long now=SystemClock.elapsedRealtime();if(!autoCapture||!resumed||session.busy||!ready){stableSince=0;return;}
        if(quad==null){stableSince=0;stableAnchor=null;if(missingSince==0)missingSince=now;if(now-missingSince>600){autoArmed=true;bookRearmedByChange=false;}return;}missingSince=0;
        float area=0;for(int i=0;i<4;i++){int next=(i+1)%4;area+=quad[2*i]*quad[2*next+1]-quad[2*next]*quad[2*i+1];}if(Math.abs(area)*.5f<.15f){stableSince=0;return;}
        for(float value:quad)if(value<.025f||value>.975f){stableSince=0;return;}
        if(!autoArmed&&bookReady&&now-lastAuto>2200){autoArmed=true;bookRearmedByChange=true;}
        if(!autoArmed)return;
        float movement=0;if(stableAnchor!=null)for(int i=0;i<8;i++)movement=Math.max(movement,Math.abs(quad[i]-stableAnchor[i]));
        if(stableAnchor==null||movement>.018f){stableAnchor=quad.clone();stableSince=now;return;}if(stableSince==0)stableSince=now;
        hint.setText(UiText.get(R.string.ui_hold_steady));if(now-stableSince>=1200&&now-lastAuto>2200){if("Book".equals(session.mode)&&bookTracker.hasBaseline()&&!bookTracker.changed(signature)){autoArmed=false;stableSince=0;hint.setText(UiText.get(R.string.ui_page_unchanged_tap_shutter_to_capture_again));return;}capturedSignature=signature;bookRearmedByChange=false;capture();}
    }
    private void chooseFlash(){if(camera==null||!camera.getCameraInfo().hasFlashUnit()){Toast.makeText(this,UiText.get(R.string.ui_this_camera_has_no_flash),Toast.LENGTH_SHORT).show();return;}new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_flash)).setSingleChoiceItems(new String[]{UiText.get(R.string.ui_off), UiText.get(R.string.ui_auto), UiText.get(R.string.ui_on)},session.flash==ImageCapture.FLASH_MODE_OFF?0:session.flash==ImageCapture.FLASH_MODE_AUTO?1:2,(d,w)->{session.flash=w==0?ImageCapture.FLASH_MODE_OFF:w==1?ImageCapture.FLASH_MODE_AUTO:ImageCapture.FLASH_MODE_ON;imageCapture.setFlashMode(session.flash);render();d.dismiss();}).show();}
    private void settings(){new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_camera_settings)).setItems(new String[]{session.grid ? UiText.get(R.string.ui_hide_alignment_grid) : UiText.get(R.string.ui_show_alignment_grid), UiText.get(R.string.ui_timer_off), UiText.get(R.string.ui_timer_3_seconds), UiText.get(R.string.ui_timer_5_seconds), autoCapture ? UiText.get(R.string.ui_automatic_capture_on) : UiText.get(R.string.ui_automatic_capture_off)},(d,w)->{if(w==0)session.grid=!session.grid;else if(w==4){autoCapture=!autoCapture;autoArmed=true;stableSince=0;getPreferences(0).edit().putBoolean("auto_capture",autoCapture).apply();}else session.timer=w==1?0:w==2?3:5;render();}).show();}
    private void permissionDenied(){hint.setText(UiText.get(R.string.ui_allow_camera_access_to_scan_or_import_images_below));new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_camera_access)).setMessage(UiText.get(R.string.ui_allow_camera_access_in_app_settings_to_scan_documents_you_can_also_import_i)).setPositiveButton(UiText.get(R.string.ui_settings),(d,w)->startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))).setNegativeButton(UiText.get(R.string.ui_continue),null).show();}
    @Override protected void onResume(){super.onResume();resumed=true;if(preview!=null){render();if(!ready&&ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)bindCamera();}}
    private void capture(){if(session.busy||!ready||imageCapture==null)return;autoArmed=false;stableSince=0;if(latestSignature!=null){capturedSignature=latestSignature.clone();if("Book".equals(session.mode))bookTracker.accept(latestSignature);}lastAuto=SystemClock.elapsedRealtime();timerPending=session.timer>0;session.busy=true;session.notifyChange();countdown=session.timer;timerTick.run();}
    private void takePhoto(){countdown=0;if(!ready||imageCapture==null){session.fail("Camera is not ready");return;}session.previewRatio=preview.getHeight()>0?(float)preview.getWidth()/preview.getHeight():0;final CameraCaptureSession owner=session;final String mode=session.mode;final boolean doublePage=session.bookDoublePage;final File file=session.newFile("capture");imageCapture.takePicture(new ImageCapture.OutputFileOptions.Builder(file).build(),session.worker,new ImageCapture.OnImageSavedCallback(){@Override public void onImageSaved(ImageCapture.OutputFileResults output){owner.processCaptured(file,mode,doublePage);}@Override public void onError(ImageCaptureException error){file.delete();owner.fail("Could not capture page. Please try again.");}});}
    private void finishCapture(boolean importPdf){if(session.busy||(!importPdf&&session.pages.isEmpty()))return;if("Book".equals(session.mode)&&!importPdf){reviewBook();return;}Intent result=new Intent().putStringArrayListExtra("pages",new ArrayList<>(session.pages)).putExtra("capture_mode","ID Cards".equals(session.mode)?"ID Card":session.mode).putExtra("import_pdf",importPdf);if(session.importOptionsChosen)result.putExtra("initial_filter",session.importFilter).putExtra("auto_detect",session.importAutoCrop);session.returned=true;setResult(RESULT_OK,result);finish();}
    private void reviewBook(){
        if(session.busy||session.pages.isEmpty())return;session.busy=true;session.notifyChange();ArrayList<String> originals=new ArrayList<>(session.pages);
        session.worker.execute(()->{ArrayList<String> copies=new ArrayList<>();try{for(String original:originals){File copy=session.newFile("book-review");copyBookFile(new File(original),copy);copies.add(Uri.fromFile(copy).toString());}runOnUiThread(()->{session.busy=false;session.notifyChange();if(isFinishing()||isDestroyed()){for(String copy:copies)new File(Uri.parse(copy).getPath()).delete();return;}bookReviewLauncher.launch(new Intent(this,ScanEditorActivity.class).putStringArrayListExtra("pages",copies).putExtra("capture_mode","Book").putExtra("book_review",true));});}catch(Exception error){for(String copy:copies)new File(Uri.parse(copy).getPath()).delete();session.fail("Could not prepare book review. Your captured pages are saved.");}});
    }
    private void copyBookFile(File source,File target)throws java.io.IOException{try(java.io.InputStream in=new java.io.FileInputStream(source);java.io.OutputStream out=new java.io.FileOutputStream(target)){byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}}
    private void cancel(){if(session.busy)return;if("Book".equals(session.mode)&&!session.pages.isEmpty()){new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_book_saved_as_a_draft)).setMessage(UiText.get(R.string.ui_continue_capturing_keep_the_draft_for_later_or_discard_this_book)).setNegativeButton(UiText.get(R.string.ui_keep_capturing),null).setNeutralButton(UiText.get(R.string.ui_discard),(d,w)->{session.discardBookDraft();setResult(RESULT_CANCELED);finish();}).setPositiveButton(UiText.get(R.string.ui_save_for_later),(d,w)->{setResult(RESULT_CANCELED);finish();}).show();return;}setResult(RESULT_CANCELED);finish();}
    @Override public void handleBackPressed(){if(session.busy)Toast.makeText(this,UiText.get(R.string.ui_please_wait_for_the_page_to_finish),Toast.LENGTH_SHORT).show();else cancel();}
    @Override protected void onSaveInstanceState(Bundle state){state.putStringArrayList("pages",new ArrayList<>(session.pages));state.putString("mode",session.mode);state.putBoolean("hd",session.hd);state.putBoolean("grid",session.grid);state.putInt("flash",session.flash);state.putInt("timer",session.timer);state.putBoolean("book_double_page",session.bookDoublePage);super.onSaveInstanceState(state);}
    @Override protected void onStop(){resumed=false;stableSince=0;if(timerPending){handler.removeCallbacks(timerTick);timerPending=false;countdown=0;session.busy=false;session.notifyChange();}super.onStop();}
    @Override protected void onDestroy(){handler.removeCallbacks(timerTick);if(timerPending){timerPending=false;session.busy=false;session.notifyChange();}liveGeneration++;if(liveAnalysis!=null)liveAnalysis.clearAnalyzer();liveWorker.shutdown();super.onDestroy();}
    private void focus(float x,float y,boolean manual){
        if(camera==null)return;long focusTime=SystemClock.elapsedRealtime();
        try{
            FocusMeteringAction action=new FocusMeteringAction.Builder(preview.getMeteringPointFactory().createPoint(x,y),FocusMeteringAction.FLAG_AF|FocusMeteringAction.FLAG_AE).setAutoCancelDuration(3,TimeUnit.SECONDS).build();
            if(camera.getCameraInfo().isFocusMeteringSupported(action))camera.getCameraControl().startFocusAndMetering(action);
            if(manual){guide.focusX=x;guide.focusY=y;guide.focusUntil=focusTime+900;guide.invalidate();guide.postDelayed(guide::invalidate,950);}
        }catch(Exception ignored){}
    }
    private TextView text(String value,int size,int color){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(color);view.setGravity(Gravity.CENTER_VERTICAL);return view;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private int dp(int value){return(int)(value*getResources().getDisplayMetrics().density+.5f);}
    private final class ModeLabel extends androidx.appcompat.widget.AppCompatTextView {
        final Paint marker=new Paint(Paint.ANTI_ALIAS_FLAG);
        ModeLabel(){super(CameraCaptureActivity.this);marker.setColor(TEAL);}
        @Override protected void onDraw(Canvas canvas){if(isSelected())canvas.drawRoundRect(getWidth()/2f-dp(13),-dp(3),getWidth()/2f+dp(13),dp(3),dp(4),dp(4),marker);super.onDraw(canvas);}
    }
    private static class HeaderIcon extends Drawable {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);final boolean more,on;HeaderIcon(boolean more,boolean on){this.more=more;this.on=on;p.setColor(Color.WHITE);p.setStrokeWidth(1.8f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);}
        @Override public void draw(Canvas c){int s=c.save();c.translate(getBounds().left,getBounds().top);c.scale(getBounds().width()/24f,getBounds().height()/24f);if(more){p.setStyle(Paint.Style.FILL);for(int y:new int[]{5,12,19})c.drawCircle(12,y,1.7f,p);}else{p.setStyle(Paint.Style.STROKE);Path a=new Path();a.moveTo(14,2);a.lineTo(5,13);a.lineTo(11,13);a.lineTo(10,22);a.lineTo(20,10);a.lineTo(14,10);a.close();c.drawPath(a,p);if(!on)c.drawLine(3,3,21,21,p);}c.restoreToCount(s);}
        @Override public void setAlpha(int a){p.setAlpha(a);}@Override public void setColorFilter(ColorFilter f){p.setColorFilter(f);}@Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
    private static class GuideView extends View {
        final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);volatile float[] documentQuad;float imageRatio=1;String mode="Single";boolean grid,bookDouble=true;float focusX,focusY;long focusUntil;GuideView(android.content.Context c){super(c);setContentDescription(UiText.get(R.string.ui_tap_preview_to_focus));}
        @Override protected void onDraw(Canvas c){float w=getWidth(),h=getHeight();p.setStyle(Paint.Style.STROKE);p.setColor(0x77FFFFFF);p.setStrokeWidth(1.5f);if(grid)for(int i=1;i<3;i++){c.drawLine(w*i/3,0,w*i/3,h,p);c.drawLine(0,h*i/3,w,h*i/3,p);}
            if("Book".equals(mode)){float margin=w*.05f,arm=w*.055f,top=h*.03f,bottom=h*.96f;p.setColor(Color.WHITE);p.setStrokeWidth(3);for(float x:new float[]{margin,w-margin})for(float y:new float[]{top,bottom}){c.drawLine(x,y,x+(x<w/2?arm:-arm),y,p);c.drawLine(x,y,x,y+(y<h/2?arm:-arm),p);}if(bookDouble){p.setPathEffect(new DashPathEffect(new float[]{12,8},0));c.drawLine(0,h/2,w,h/2,p);p.setPathEffect(null);p.setStyle(Paint.Style.FILL);p.setTextSize(w*.23f);p.setColor(0x33000000);p.setTypeface(Typeface.DEFAULT_BOLD);for(int i=0;i<2;i++){c.save();c.translate(w/2,h*(i==0?.25f:.75f));c.rotate(90);String label=i==0?"A":"B";c.drawText(label,-p.measureText(label)/2,p.getTextSize()/3,p);c.restore();}p.setTypeface(Typeface.DEFAULT);}}
            if(documentQuad!=null){float iw=w,ih=w/imageRatio;if(ih<h){ih=h;iw=h*imageRatio;}float ox=(w-iw)/2,oy=(h-ih)/2;Path path=new Path();for(int i=0;i<4;i++){float x=ox+documentQuad[2*i]*iw,y=oy+documentQuad[2*i+1]*ih;if(i==0)path.moveTo(x,y);else path.lineTo(x,y);}path.close();p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(4);p.setColor(0xff14d7b3);c.drawPath(path,p);}
            if(SystemClock.elapsedRealtime()<focusUntil){p.setStyle(Paint.Style.STROKE);p.setColor(Color.WHITE);p.setStrokeWidth(2);c.drawCircle(focusX,focusY,26,p);c.drawLine(focusX-34,focusY,focusX-22,focusY,p);c.drawLine(focusX+22,focusY,focusX+34,focusY,p);}
        }
    }
}

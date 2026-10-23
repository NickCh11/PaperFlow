package com.example.myapplication;

import android.app.Application;
import android.app.Dialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.ViewModelProvider;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.rendering.PDFRenderer;
import com.tom_roush.pdfbox.util.Matrix;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Full document signing session. Original PDF and reusable private marks are never overwritten. */
public class SignatureEditorActivity extends BackHandlingActivity {
    private final int INK = AppTheme.ink(), BRAND = AppTheme.accent(), MUTED = AppTheme.muted();
    private Session session;
    private PlacementView preview;
    private TextView counter, status;
    private LinearLayout root, footer, pageNavigation;
    private boolean disposed, passwordDialog, navigating;
    private Dialog library;
    private ActivityResultLauncher<String> imagePicker;
    private ActivityResultLauncher<Intent> camera;

    public static final class Mark {
        String file; int page; float x=.3f,y=.65f,width=.35f; int angle;
        Bitmap bitmap;
        Mark(String f,int p,Bitmap b){file=f;page=p;bitmap=b;}
    }
    public static final class Stroke {
        final ArrayList<Float> points=new ArrayList<>(); final int color;
        Stroke(int c){color=c;}
    }
    public static final class Session extends AndroidViewModel {
        final ExecutorService worker=Executors.newSingleThreadExecutor();
        final Handler main=new Handler(Looper.getMainLooper());
        final ArrayList<Mark> marks=new ArrayList<>();
        final ArrayList<Stroke> strokes=new ArrayList<>();
        final ArrayList<String> temporaryDates=new ArrayList<>();
        String path,name,password="",error,result;
        int page,count,selected=-1,generation,inkColor=0xff18314c;
        boolean busy,needsPassword,draw,stamp,disposed;
        Bitmap pageBitmap;
        Runnable changed;
        public Session(Application application){super(application);}
        void notifyChange(){main.post(()->{if(changed!=null)changed.run();});}
        void render(){
            if(busy)return;busy=true;int token=++generation;int index=page;notifyChange();
            worker.execute(()->{
                Bitmap rendered=null;
                PDFBoxResourceLoader.init(getApplication());
                try(PDDocument doc=PDDocument.load(new File(path),password)){
                    count=doc.getNumberOfPages();
                    if(count==0)throw new IllegalArgumentException(UiText.get(R.string.ui_this_pdf_has_no_pages));
                    indexCheck(index,count);PDPage p=doc.getPage(index);PDRectangle b=p.getCropBox();boolean turn=Math.abs(p.getRotation())%180==90;
                    float w=turn?b.getHeight():b.getWidth(),h=turn?b.getWidth():b.getHeight();
                    rendered=new PDFRenderer(doc).renderImage(index,Math.min(1800f/w,2400f/h));needsPassword=false;
                }catch(InvalidPasswordException e){needsPassword=true;}
                catch(Exception e){error="Could not open this PDF.";}
                Bitmap ready=rendered;
                main.post(()->{if(disposed||token!=generation){if(ready!=null)ready.recycle();return;}if(pageBitmap!=null)pageBitmap.recycle();pageBitmap=ready;busy=false;notifyChange();});
            });
        }
        static void indexCheck(int i,int count){if(i<0||i>=count)throw new IllegalArgumentException(UiText.get(R.string.ui_page_unavailable));}
        @Override protected void onCleared(){disposed=true;changed=null;worker.execute(()->{if(pageBitmap!=null)pageBitmap.recycle();for(Mark m:marks)if(m.bitmap!=null&&!m.bitmap.isRecycled())m.bitmap.recycle();SignatureStore.removeTemporaryDates(getApplication(),temporaryDates);});worker.shutdown();}
    }
    @Override protected void onCreate(Bundle saved){
        super.onCreate(saved);session=new ViewModelProvider(this).get(Session.class);
        if(session.path==null){session.path=getIntent().getStringExtra("path");session.name=getIntent().getStringExtra("name");if(session.name==null)session.name="Document";String pw=getIntent().getStringExtra("pdf_password");if(pw!=null)session.password=pw;session.page=Math.max(0,getIntent().getIntExtra("page",0));if(saved!=null)restore(saved);}
        imagePicker=registerForActivityResult(new ActivityResultContracts.GetContent(),uri->{if(uri!=null)importImage(uri);});
        camera=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{if(result.getResultCode()==RESULT_OK&&result.getData()!=null){ArrayList<String> pages=result.getData().getStringArrayListExtra("pages");if(pages!=null&&!pages.isEmpty()){String first=pages.get(0);importImage(first.contains(":")?Uri.parse(first):Uri.fromFile(new File(first)),new ArrayList<>(pages));}else if(result.getData().getBooleanExtra("import_pdf",false))toast(UiText.get(R.string.ui_choose_an_image_to_scan_a_signature));}});
        getWindow().setStatusBarColor(Color.WHITE);getWindow().setNavigationBarColor(Color.WHITE);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        session.changed=this::refresh;build();if(session.pageBitmap==null&&!session.busy&&!session.draw)session.render();
    }
    private void build(){
        setRequestedOrientation(session.draw?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(session.draw?Color.WHITE:AppTheme.background());
        LinearLayout top=row();top.setBackgroundColor(AppTheme.surface());
        if(session.draw)button(top,UiText.get(R.string.ui_cancel),this::leaveDraw,72);
        else{ImageView back=new ImageView(this);back.setImageDrawable(new AppIcon("Back",INK));back.setPadding(dp(14),dp(14),dp(14),dp(14));back.setContentDescription(UiText.get(R.string.ui_back));top.addView(back,new LinearLayout.LayoutParams(dp(56),dp(56)));back.setOnClickListener(v->handleBackPressed());}
        TextView title=label(session.draw ? UiText.get(R.string.ui_create_a_signature) : session.name,17,INK);title.setGravity(session.draw?Gravity.CENTER:Gravity.CENTER_VERTICAL|Gravity.LEFT);title.setSingleLine();title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setPadding(0,0,dp(16),0);top.addView(title,new LinearLayout.LayoutParams(0,dp(56),1));
        if(session.draw)button(top,UiText.get(R.string.ui_done),this::finishDrawing,68);root.addView(top,new LinearLayout.LayoutParams(-1,dp(56)));
        if(session.draw){
            DrawPad pad=new DrawPad();root.addView(pad,new LinearLayout.LayoutParams(-1,0,1));
            LinearLayout bottom=row();button(bottom,UiText.get(R.string.ui_undo),()->{if(!session.strokes.isEmpty())session.strokes.remove(session.strokes.size()-1);pad.invalidate();},72);button(bottom,UiText.get(R.string.ui_clear),()->{session.strokes.clear();pad.invalidate();},72);
            TextView hint=label(UiText.get(R.string.ui_please_sign_your_name_clearly),12,MUTED);hint.setGravity(Gravity.CENTER);bottom.addView(hint,new LinearLayout.LayoutParams(0,dp(40),1));
            button(bottom,UiText.get(R.string.ui_ink),()->new ProtectedDialogs.Builder(this).setTitle(UiText.get(R.string.ui_ink_color)).setItems(new String[]{UiText.get(R.string.ui_black), UiText.get(R.string.ui_blue), UiText.get(R.string.ui_red)},(d,w)->session.inkColor=new int[]{Color.BLACK,0xff183e9b,0xffc63232}[w]).show(),64);root.addView(bottom,new LinearLayout.LayoutParams(-1,dp(40)));
        }else{
            status=label("",13,MUTED);status.setGravity(Gravity.CENTER);root.addView(status,new LinearLayout.LayoutParams(-1,dp(26)));
            preview=new PlacementView();root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
            pageNavigation=row();button(pageNavigation,UiText.get(R.string.ui_previous_page),()->changePage(-1),100);counter=label("",12,MUTED);counter.setGravity(Gravity.CENTER);pageNavigation.addView(counter,new LinearLayout.LayoutParams(0,dp(38),1));button(pageNavigation,UiText.get(R.string.ui_next_page),()->changePage(1),100);root.addView(pageNavigation,new LinearLayout.LayoutParams(-1,dp(38)));
            footer=new LinearLayout(this);footer.setOrientation(LinearLayout.VERTICAL);footer.setBackgroundColor(AppTheme.surface());root.addView(footer);buildFooter();
        }
        UiInsets.apply(root);setContentView(root);refresh();
    }
    private void buildFooter(){
        if(footer==null)return;footer.removeAllViews();
        android.graphics.drawable.GradientDrawable panel=new android.graphics.drawable.GradientDrawable();panel.setColor(AppTheme.surface());panel.setCornerRadii(new float[]{dp(12),dp(12),dp(12),dp(12),0,0,0,0});footer.setBackground(panel);
        String signer=UiText.get(R.string.ui_me);String signLabel=UiText.format(R.string.ui_sign_by_value,signer);android.text.SpannableString signBy=new android.text.SpannableString(signLabel);int signerStart=signLabel.lastIndexOf(signer);if(signerStart>=0)signBy.setSpan(new android.text.style.ForegroundColorSpan(INK),signerStart,signerStart+signer.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView by=label("",13,MUTED);by.setText(signBy);by.setGravity(Gravity.CENTER_VERTICAL);by.setPadding(dp(24),0,0,0);footer.addView(by,new LinearLayout.LayoutParams(-1,dp(42)));
        View separator=new View(this);separator.setBackgroundColor(AppTheme.border());footer.addView(separator,new LinearLayout.LayoutParams(-1,dp(1)));
        LinearLayout actions=row();footerAction(actions,UiText.get(R.string.ui_sign_stamp),"Sign",()->showLibrary(false));footerAction(actions,UiText.get(R.string.ui_date),"Date",()->addDate(new SimpleDateFormat("MM/dd/yyyy",Locale.getDefault()).format(new Date())));footerAction(actions,UiText.get(R.string.ui_more),"More",this::more);
        FrameLayout saveBox=new FrameLayout(this);actions.addView(saveBox,new LinearLayout.LayoutParams(0,-1,1.24f));ImageView done=new ImageView(this);done.setImageDrawable(new AppIcon("Done",AppTheme.onAccent()));done.setPadding(dp(12),dp(4),dp(12),dp(4));done.setContentDescription(UiText.get(R.string.ui_save_signed_copy));android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();background.setColor(BRAND);background.setCornerRadius(dp(4));done.setBackground(background);saveBox.addView(done,new FrameLayout.LayoutParams(dp(56),dp(32),Gravity.CENTER));done.setOnClickListener(v->save());footer.addView(actions,new LinearLayout.LayoutParams(-1,dp(50)));setControlsEnabled(footer,!session.busy);
    }
    private void footerAction(LinearLayout parent,String title,String glyph,Runnable action){
        TextView view=label(title,11,INK);view.setGravity(Gravity.CENTER);view.setContentDescription(title);
        android.graphics.drawable.Drawable icon;
        if(glyph.equals("Sign"))icon=new AppIcon("Signature",INK);
        else icon=new android.graphics.drawable.Drawable(){final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);@Override public void draw(Canvas canvas){int saved=canvas.save();canvas.translate(getBounds().left,getBounds().top);canvas.scale(getBounds().width()/24f,getBounds().height()/24f);p.setColor(INK);p.setStrokeWidth(1.7f);if(glyph.equals("More")){p.setStyle(Paint.Style.FILL);for(int y:new int[]{5,12,19})canvas.drawCircle(12,y,1.6f,p);}else{p.setStyle(Paint.Style.STROKE);canvas.drawRoundRect(4,5,20,21,1,1,p);canvas.drawLine(4,10,20,10,p);canvas.drawLine(8,2,8,7,p);canvas.drawLine(16,2,16,7,p);p.setStrokeWidth(2);for(int y:new int[]{14,17})for(int x:new int[]{8,12,16})canvas.drawPoint(x,y,p);}canvas.restoreToCount(saved);}@Override public void setAlpha(int a){p.setAlpha(a);}@Override public void setColorFilter(android.graphics.ColorFilter f){p.setColorFilter(f);}@Override public int getOpacity(){return android.graphics.PixelFormat.TRANSLUCENT;}};
        icon.setBounds(0,0,dp(25),dp(25));view.setCompoundDrawables(null,icon,null,null);view.setCompoundDrawablePadding(dp(4));parent.addView(view,new LinearLayout.LayoutParams(0,-1,1));view.setOnClickListener(v->{if(!session.busy)action.run();});
    }
    private void refresh(){
        if(disposed)return;
        if(session.result!=null&&!navigating){navigating=true;Intent intent=new Intent(this,MainActivity.class);intent.putExtra("document_action","Register");intent.putExtra("path",session.result);intent.putExtra("name",session.name+" · signed");intent.putExtra("pages",session.count);intent.putExtra("origin_path",session.path);intent.putExtra("signed_revision",true);intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);startActivity(intent);finish();return;}
        if(session.error!=null){String message=session.error;session.error=null;toast(message);}
        if(session.needsPassword&&!passwordDialog){passwordDialog=true;EditText input=new EditText(this);input.setSingleLine();input.setInputType(129);input.setHint(UiText.get(R.string.ui_pdf_password));AlertDialog prompt=new ProtectedDialogs.Builder(this).setTitle(UiText.get(R.string.ui_password_protected_pdf)).setView(input).setNegativeButton(UiText.get(R.string.ui_cancel),(d,w)->finish()).setPositiveButton(UiText.get(R.string.ui_open),null).create();prompt.setOnShowListener(d->prompt.getButton(-1).setOnClickListener(v->{if(input.getText().length()==0){input.setError(UiText.get(R.string.ui_enter_password));return;}session.password=input.getText().toString();session.needsPassword=false;prompt.dismiss();session.render();}));prompt.setOnDismissListener(d->passwordDialog=false);prompt.show();}
        if(status!=null){status.setText(session.busy ? UiText.get(R.string.ui_processing) : "");status.setVisibility(session.busy?View.VISIBLE:View.GONE);pageNavigation.setVisibility(session.count>1?View.VISIBLE:View.GONE);counter.setText(session.count==0 ? "" : UiText.format(R.string.ui_value_value_65c5dbb0, (session.page+1), session.count));setControlsEnabled(footer,!session.busy);setControlsEnabled(pageNavigation,!session.busy);preview.invalidate();}
    }
    private void setControlsEnabled(View view,boolean enabled){if(view==null)return;if(view.isClickable()){view.setEnabled(enabled);view.setAlpha(enabled?1f:.4f);}if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)setControlsEnabled(group.getChildAt(i),enabled);}}
    private void changePage(int delta){if(session.busy)return;int next=session.page+delta;if(next<0||next>=session.count)return;session.page=next;session.selected=-1;buildFooter();session.render();}
    private void showLibrary(boolean stamp){
        if(session.busy)return;session.stamp=stamp;
        library=new Dialog(this);library.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);LinearLayout sheet=new LinearLayout(this);sheet.setOrientation(LinearLayout.VERTICAL);sheet.setBackgroundColor(AppTheme.surface());
        LinearLayout tabs=row();libraryTab(tabs,UiText.get(R.string.ui_signature),84,!stamp,()->{library.dismiss();showLibrary(false);});libraryTab(tabs,UiText.get(R.string.ui_stamp),64,stamp,()->{library.dismiss();showLibrary(true);});tabs.addView(new View(this),new LinearLayout.LayoutParams(0,1,1));ImageView close=new ImageView(this);close.setImageDrawable(new AppIcon("Close",INK));close.setPadding(dp(12),dp(12),dp(12),dp(12));close.setContentDescription(UiText.get(R.string.ui_close_signature_library));tabs.addView(close,new LinearLayout.LayoutParams(dp(44),-1));close.setOnClickListener(v->library.dismiss());sheet.addView(tabs,new LinearLayout.LayoutParams(-1,dp(44)));
        ArrayList<File> stored=SignatureStore.list(this,stamp);
        if(stored.isEmpty()){TextView empty=label(stamp ? UiText.get(R.string.ui_no_stamps_yet_add_one) : UiText.get(R.string.ui_no_signatures_yet_add_one),14,MUTED);empty.setGravity(Gravity.CENTER);sheet.addView(empty,new LinearLayout.LayoutParams(-1,dp(30)));}
        else{ScrollView scroll=new ScrollView(this);LinearLayout entries=new LinearLayout(this);entries.setOrientation(LinearLayout.VERTICAL);for(File file:stored){LinearLayout item=row();ImageView image=new ImageView(this);image.setImageBitmap(BitmapFactory.decodeFile(file.getAbsolutePath()));image.setScaleType(ImageView.ScaleType.FIT_CENTER);image.setPadding(dp(18),dp(8),dp(18),dp(8));image.setContentDescription(stamp ? UiText.get(R.string.ui_use_saved_stamp) : UiText.get(R.string.ui_use_saved_signature));item.addView(image,new LinearLayout.LayoutParams(0,dp(72),1));image.setOnClickListener(v->{library.dismiss();addFile(file);});button(item,UiText.get(R.string.ui_delete),()->new ProtectedDialogs.Builder(this).setTitle(UiText.get(R.string.ui_delete_saved_mark)).setNegativeButton(UiText.get(R.string.ui_cancel),null).setPositiveButton(UiText.get(R.string.ui_delete),(d,w)->{file.delete();library.dismiss();showLibrary(stamp);}).show(),68);entries.addView(item);}scroll.addView(entries);sheet.addView(scroll,new LinearLayout.LayoutParams(-1,dp(190)));}
        FrameLayout addArea=new FrameLayout(this);LinearLayout add=row();libraryAddAction(add,UiText.get(R.string.ui_draw),"Sign",()->{library.dismiss();session.draw=true;session.strokes.clear();build();});libraryAddAction(add,UiText.get(R.string.ui_scan),"Camera",()->{library.dismiss();Intent intent=new Intent(this,CameraCaptureActivity.class);intent.putExtra("capture_mode","Single");camera.launch(intent);});libraryAddAction(add,UiText.get(R.string.ui_import),"Import Images",()->{library.dismiss();imagePicker.launch("image/*");});addArea.addView(add,new FrameLayout.LayoutParams(Math.min(dp(300),getResources().getDisplayMetrics().widthPixels),-1,Gravity.CENTER));sheet.addView(addArea,new LinearLayout.LayoutParams(-1,dp(62)));
        library.setContentView(sheet);library.getWindow().setBackgroundDrawableResource(android.R.color.transparent);library.getWindow().setLayout(-1,-2);library.getWindow().setGravity(Gravity.BOTTOM);library.show();UiInsets.applyBottomSheet(library.getWindow(),sheet);library.getWindow().setLayout(-1,-2);
    }
    private void libraryTab(LinearLayout parent,String title,int width,boolean selected,Runnable action){
        FrameLayout tab=new FrameLayout(this);TextView text=label(title,16,selected?INK:MUTED);text.setTypeface(null,selected?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL);text.setGravity(Gravity.CENTER_VERTICAL);text.setPadding(dp(12),0,0,0);text.setContentDescription(title);tab.addView(text,new FrameLayout.LayoutParams(-1,-1));if(selected){View underline=new View(this);underline.setBackgroundColor(BRAND);FrameLayout.LayoutParams line=new FrameLayout.LayoutParams(dp(24),dp(2),Gravity.BOTTOM|Gravity.START);line.leftMargin=dp(12);tab.addView(underline,line);}parent.addView(tab,new LinearLayout.LayoutParams(dp(width),-1));tab.setContentDescription(title);tab.setOnClickListener(v->action.run());
    }
    private void libraryAddAction(LinearLayout parent,String title,String iconName,Runnable action){
        TextView text=label(title,14,INK);text.setGravity(Gravity.CENTER);text.setContentDescription(title);AppIcon icon=new AppIcon(iconName,INK);icon.setBounds(0,0,dp(25),dp(25));text.setCompoundDrawables(null,icon,null,null);text.setCompoundDrawablePadding(dp(4));parent.addView(text,new LinearLayout.LayoutParams(0,-1,1));text.setOnClickListener(v->action.run());
    }
    private void importImage(Uri uri){
        importImage(uri,null);
    }
    private void importImage(Uri uri,ArrayList<String> ownedCaptures){
        session.busy=true;refresh();Session active=session;boolean stamp=session.stamp;
        session.worker.execute(()->{try{Bitmap bitmap=SignatureStore.importMark(getApplicationContext(),uri);File file=SignatureStore.save(getApplicationContext(),bitmap,stamp);SignatureStore.removeOwnedCaptures(getApplicationContext(),ownedCaptures);active.main.post(()->{if(active.disposed){bitmap.recycle();return;}addBitmap(active,file,bitmap);active.busy=false;active.notifyChange();});}catch(Exception error){active.error=error.getMessage()==null?"Could not import signature":error.getMessage();active.busy=false;active.notifyChange();}});
    }
    private void addFile(File file){Bitmap bitmap=BitmapFactory.decodeFile(file.getAbsolutePath());if(bitmap==null){toast(UiText.get(R.string.ui_this_saved_mark_is_unavailable));return;}addBitmap(session,file,bitmap);buildFooter();preview.invalidate();}
    private static void addBitmap(Session active,File file,Bitmap bitmap){Mark mark=new Mark(file.getAbsolutePath(),active.page,bitmap);active.marks.add(mark);active.selected=active.marks.size()-1;}
    private void finishDrawing(){
        if(session.strokes.isEmpty()){toast(UiText.get(R.string.ui_draw_a_signature_first));return;}
        Bitmap bitmap=Bitmap.createBitmap(1600,600,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);drawStrokes(canvas,1600,600);Bitmap trimmed;
        try{trimmed=SignatureStore.trimBackground(bitmap);File file=SignatureStore.save(this,trimmed,session.stamp);addBitmap(session,file,trimmed);bitmap.recycle();leaveDraw();}catch(Exception error){bitmap.recycle();toast(UiText.get(R.string.ui_could_not_save_signature));}
    }
    private void leaveDraw(){session.draw=false;session.strokes.clear();build();if(session.pageBitmap==null&&!session.busy)session.render();}
    private void chooseDate(){
        String[] formats={"yyyy-MM-dd","dd/MM/yyyy","MM/dd/yyyy","dd MMM yyyy","EEEE, dd MMMM yyyy"};String[] values=new String[formats.length+1];Date now=new Date();for(int i=0;i<formats.length;i++)values[i]=new SimpleDateFormat(formats[i],Locale.getDefault()).format(now);values[formats.length]=UiText.get(R.string.ui_custom_date);
        new ProtectedDialogs.Builder(this).setTitle(UiText.get(R.string.ui_date)).setItems(values,(d,w)->{if(w<formats.length)addDate(values[w]);else{EditText input=new EditText(this);input.setSingleLine();input.setText(values[0]);new ProtectedDialogs.Builder(this).setTitle(UiText.get(R.string.ui_date_text)).setView(input).setNegativeButton(UiText.get(R.string.ui_cancel),null).setPositiveButton(UiText.get(R.string.ui_add),(dialog,which)->{if(!input.getText().toString().trim().isEmpty())addDate(input.getText().toString().trim());}).show();}}).show();
    }
    private void addDate(String text){
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.BLACK);paint.setTextSize(64);Bitmap bitmap=Bitmap.createBitmap(Math.max(1,(int)Math.ceil(paint.measureText(text))+16),92,Bitmap.Config.ARGB_8888);new Canvas(bitmap).drawText(text,8,72,paint);
        try{File file=SignatureStore.saveTemporaryDate(this,bitmap);session.temporaryDates.add(file.getAbsolutePath());addBitmap(session,file,bitmap);buildFooter();preview.invalidate();}catch(Exception error){bitmap.recycle();toast(UiText.get(R.string.ui_could_not_add_date));}
    }
    private void editMark(){
        if(session.selected<0)return;new ProtectedDialogs.Builder(this).setTitle(UiText.get(R.string.ui_edit_signature)).setItems(new String[]{UiText.get(R.string.ui_rotate_left), UiText.get(R.string.ui_rotate_right), UiText.get(R.string.ui_smaller), UiText.get(R.string.ui_larger), UiText.get(R.string.ui_delete), UiText.get(R.string.ui_replace), UiText.get(R.string.ui_apply_to_all_pages), UiText.get(R.string.ui_apply_to_selected_pages)},(d,w)->{if(session.selected<0)return;Mark mark=session.marks.get(session.selected);switch(w){case 0:mark.angle=(mark.angle+270)%360;break;case 1:mark.angle=(mark.angle+90)%360;break;case 2:mark.width=Math.max(.06f,mark.width*.85f);break;case 3:mark.width=Math.min(.85f,mark.width*1.15f);break;case 4:removeSelected();return;case 5:removeSelected();showLibrary(false);return;case 6:bulkMark(mark,true);return;case 7:bulkMark(mark,false);return;}preview.constrain(mark);preview.invalidate();}).show();
    }
    private void bulkMark(Mark original,boolean all){String[] labels=new String[session.count];boolean[] chosen=new boolean[session.count];for(int i=0;i<labels.length;i++){labels[i]=UiText.format(R.string.ui_page_value_67f70d13, (i+1));chosen[i]=all||i==original.page;}new ProtectedDialogs.Builder(this).setTitle(UiText.get(R.string.ui_apply_signature_stamp)).setMultiChoiceItems(labels,chosen,(d,w,value)->chosen[w]=value).setMessage(UiText.get(R.string.ui_copies_keep_the_same_relative_position_and_width_existing_marks_are_kept_re)).setNegativeButton(UiText.get(R.string.ui_cancel),null).setPositiveButton(UiText.get(R.string.ui_apply),(d,w)->{int count=0;for(boolean value:chosen)if(value)count++;if(session.marks.size()+count>1000){toast(UiText.get(R.string.ui_use_at_most_1000_marks_per_document));return;}for(int i=0;i<chosen.length;i++)if(chosen[i]&&i!=original.page){Mark copy=new Mark(original.file,i,original.bitmap);copy.x=original.x;copy.y=original.y;copy.width=original.width;copy.angle=original.angle;session.marks.add(copy);}preview.invalidate();toast(UiText.get(R.string.ui_signature_stamp_applied_to_selected_pages));}).show();}
    private void removeSelected(){if(session.selected>=0){Mark removed=session.marks.remove(session.selected);if(removed.bitmap!=null&&session.marks.stream().noneMatch(mark->mark.bitmap==removed.bitmap))removed.bitmap.recycle();session.selected=-1;buildFooter();preview.invalidate();}}
    private void more(){
        new ProtectedDialogs.Builder(this).setTitle(UiText.get(R.string.ui_more)).setItems(new String[]{UiText.get(R.string.ui_manage_signatures), UiText.get(R.string.ui_delete), UiText.get(R.string.ui_choose_page), UiText.get(R.string.ui_date_format), UiText.get(R.string.ui_save_signed_copy), UiText.get(R.string.ui_apply_selected_mark_to_all_pages), UiText.get(R.string.ui_apply_selected_mark_to_chosen_pages)},(dialog,which)->{
            if(which==0){showLibrary(false);return;}
            if(which==1){if(session.marks.isEmpty()){toast(UiText.get(R.string.ui_there_are_no_marks_to_delete));return;}new ProtectedDialogs.Builder(this).setTitle(UiText.get(R.string.ui_delete_all_marks_on_this_page)).setNegativeButton(UiText.get(R.string.ui_cancel),null).setPositiveButton(UiText.get(R.string.ui_delete),(a,b)->{for(int i=session.marks.size()-1;i>=0;i--){Mark mark=session.marks.get(i);if(mark.page==session.page){session.marks.remove(i);if(session.marks.stream().noneMatch(other->other.bitmap==mark.bitmap))mark.bitmap.recycle();}}session.selected=-1;buildFooter();preview.invalidate();}).show();return;}
            if(which==2){String[] pages=new String[session.count];for(int i=0;i<pages.length;i++)pages[i]="Page "+(i+1);new ProtectedDialogs.Builder(this).setTitle(UiText.get(R.string.ui_choose_page)).setSingleChoiceItems(pages,session.page,(picker,index)->{picker.dismiss();if(!session.busy){session.page=index;session.selected=-1;buildFooter();session.render();}}).show();return;}
            if(which>=5){if(session.selected<0){toast(UiText.get(R.string.ui_select_a_signature_or_stamp_first));return;}bulkMark(session.marks.get(session.selected),which==5);}else if(which==3)chooseDate();else save();
        }).show();
    }
    private void save(){
        if(session.busy)return;if(session.marks.isEmpty()){toast(UiText.get(R.string.ui_add_a_signature_stamp_or_date_first));return;}session.selected=-1;session.busy=true;buildFooter();refresh();Session active=session;
        session.worker.execute(()->{File output=null;try(PDDocument doc=PDDocument.load(new File(active.path),active.password)){PdfPermissions.modify(doc);
            for(Mark mark:active.marks){PDPage page=doc.getPage(mark.page);PDRectangle box=page.getCropBox();int rotation=((page.getRotation()%360)+360)%360;float W=box.getWidth(),H=box.getHeight();boolean turn=rotation==90||rotation==270;float wd=turn?H:W,hd=turn?W:H;
                Bitmap image=mark.bitmap;Bitmap rotated=null;if(mark.angle!=0){android.graphics.Matrix transform=new android.graphics.Matrix();transform.postRotate(mark.angle);rotated=Bitmap.createBitmap(image,0,0,image.getWidth(),image.getHeight(),transform,true);image=rotated;}
                float aspect=image.getHeight()/(float)image.getWidth(),wf=Math.min(mark.width,.94f*hd/(wd*aspect)),hf=wf*wd*aspect/hd;float placedX=Math.max(0,Math.min(1-wf,mark.x)),placedY=Math.max(0,Math.min(1-hf,mark.y));float X=placedX*wd,Y=(1-placedY-hf)*hd,w=wf*wd,h=hf*hd,llx=box.getLowerLeftX(),lly=box.getLowerLeftY();Matrix transform;
                switch(rotation){case 90:transform=new Matrix(0,w,-h,0,llx+W-Y,lly+X);break;case 180:transform=new Matrix(-w,0,0,-h,llx+W-X,lly+H-Y);break;case 270:transform=new Matrix(0,-w,h,0,llx+Y,lly+H-X);break;default:transform=new Matrix(w,0,0,h,llx+X,lly+Y);}
                try(PDPageContentStream stream=new PDPageContentStream(doc,page,PDPageContentStream.AppendMode.APPEND,true,true)){stream.drawImage(LosslessFactory.createFromImage(doc,image),transform);}finally{if(rotated!=null)rotated.recycle();}
            }
            if(doc.isEncrypted()){
                AccessPermission permissions=new AccessPermission(doc.getCurrentAccessPermission().getPermissionBytes());
                StandardProtectionPolicy protection=new StandardProtectionPolicy(UUID.randomUUID().toString(),active.password,permissions);
                protection.setEncryptionKeyLength(128);protection.setPreferAES(true);doc.setAllSecurityToBeRemoved(false);doc.protect(protection);
            }
            File folder=new File(getFilesDir(),"documents");if(!folder.isDirectory()&&!folder.mkdirs())throw new IllegalStateException();output=new File(folder,"paperflow-signed-"+System.currentTimeMillis()+".pdf");doc.save(output);active.result=output.getAbsolutePath();
        }catch(Exception error){logSaveFailure(error,active.password);if(output!=null)output.delete();active.error=UiText.get(R.string.ui_could_not_save_signed_pdf_your_marks_are_still_available);}finally{active.busy=false;active.notifyChange();}});
    }
    private void logSaveFailure(Throwable failure,String password){
        if((getApplicationInfo().flags&ApplicationInfo.FLAG_DEBUGGABLE)==0)return;
        int depth=0;for(Throwable error=failure;error!=null&&depth<4;error=error.getCause(),depth++){
            String message=error.getMessage();if(message==null)message="";if(password!=null&&!password.isEmpty())message=message.replace(password,"[redacted]");
            android.util.Log.e("PaperFlow.Sign",error.getClass().getName()+": "+message);
            StackTraceElement[] frames=error.getStackTrace();for(int i=0;i<Math.min(8,frames.length);i++)android.util.Log.e("PaperFlow.Sign","  at "+frames[i]);
        }
    }
    private class DrawPad extends View {
        Stroke current;boolean limitNotified;final Paint hint=new Paint(Paint.ANTI_ALIAS_FLAG);
        DrawPad(){super(SignatureEditorActivity.this);setContentDescription(UiText.get(R.string.ui_signature_drawing_area));}
        @Override protected void onDraw(Canvas canvas){hint.setColor(0xffeceeef);hint.setTextSize(dp(54));hint.setTextAlign(Paint.Align.CENTER);if(session.strokes.isEmpty())canvas.drawText(UiText.get(R.string.ui_sign_here),getWidth()/2f,getHeight()/2f,hint);drawStrokes(canvas,getWidth(),getHeight());}
        @Override public boolean onTouchEvent(MotionEvent event){
            float x=Math.max(0,Math.min(1,event.getX()/Math.max(1,getWidth()))),y=Math.max(0,Math.min(1,event.getY()/Math.max(1,getHeight())));
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){limitNotified=false;if(session.strokes.size()<256){current=new Stroke(session.inkColor);session.strokes.add(current);}else current=null;}
            int total=0;for(Stroke stroke:session.strokes)total+=stroke.points.size()/2;
            if(current!=null&&current.points.size()<8192&&total<32768){current.points.add(x);current.points.add(y);}
            else if(!limitNotified){limitNotified=true;toast(UiText.get(R.string.ui_signature_limit_reached_use_undo_or_clear_to_continue));}
            if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL)current=null;
            invalidate();return true;
        }
    }
    private void drawStrokes(Canvas canvas,int width,int height){Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeWidth(Math.max(2,width*.0027f));for(Stroke stroke:session.strokes){if(stroke.points.size()<2)continue;paint.setColor(stroke.color);Path path=new Path();path.moveTo(stroke.points.get(0)*width,stroke.points.get(1)*height);if(stroke.points.size()==2)path.lineTo(stroke.points.get(0)*width+.1f,stroke.points.get(1)*height+.1f);for(int i=2;i+1<stroke.points.size();i+=2)path.lineTo(stroke.points.get(i)*width,stroke.points.get(i+1)*height);canvas.drawPath(path,paint);}}
    private class PlacementView extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);float lastX,lastY;int gesture; // 1 drag, 2 resize
        PlacementView(){super(SignatureEditorActivity.this);setContentDescription(UiText.get(R.string.ui_signature_page_preview_drag_to_position_use_corner_handles_to_resize_rotate));}
        RectF pageRect(){Bitmap bitmap=session.pageBitmap;if(bitmap==null)return new RectF();float fit=Math.min(Math.max(1,getWidth()-dp(16))/(float)bitmap.getWidth(),Math.max(1,getHeight()-dp(12))/(float)bitmap.getHeight());float w=bitmap.getWidth()*fit,h=bitmap.getHeight()*fit;return new RectF((getWidth()-w)/2,dp(6),(getWidth()+w)/2,dp(6)+h);}
        float height(Mark mark){RectF page=pageRect();if(page.height()==0)return .1f;boolean turn=mark.angle%180!=0;float ratio=turn?mark.bitmap.getWidth()/(float)mark.bitmap.getHeight():mark.bitmap.getHeight()/(float)mark.bitmap.getWidth();return mark.width*page.width()*ratio/page.height();}
        RectF rect(Mark mark){RectF page=pageRect();return new RectF(page.left+mark.x*page.width(),page.top+mark.y*page.height(),page.left+(mark.x+mark.width)*page.width(),page.top+(mark.y+height(mark))*page.height());}
        void constrain(Mark mark){float h=height(mark);if(h>.94f){mark.width*=.94f/h;h=height(mark);}mark.x=Math.max(0,Math.min(1-mark.width,mark.x));mark.y=Math.max(0,Math.min(1-h,mark.y));}
        @Override protected void onDraw(Canvas canvas){Bitmap page=session.pageBitmap;if(page==null)return;RectF paper=pageRect();canvas.drawBitmap(page,null,paper,paint);paint.setStyle(Paint.Style.FILL);paint.setColor(0xbb444747);float left=paper.left+dp(8),top=paper.top+dp(8);String pageLabel=(session.page+1)+"/"+session.count;paint.setTextSize(dp(12));paint.setTextAlign(Paint.Align.LEFT);float labelWidth=paint.measureText(pageLabel)+dp(12);canvas.drawRoundRect(left,top,left+labelWidth,top+dp(24),dp(2),dp(2),paint);paint.setColor(Color.WHITE);canvas.drawText(pageLabel,left+dp(6),top+dp(17),paint);for(int i=0;i<session.marks.size();i++){Mark mark=session.marks.get(i);if(mark.page!=session.page)continue;constrain(mark);RectF target=rect(mark);canvas.save();canvas.translate(target.centerX(),target.centerY());canvas.rotate(mark.angle);boolean turn=mark.angle%180!=0;float w=turn?target.height():target.width(),h=turn?target.width():target.height();canvas.drawBitmap(mark.bitmap,null,new RectF(-w/2,-h/2,w/2,h/2),paint);canvas.restore();if(i==session.selected){paint.setColor(BRAND);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));canvas.drawRect(target,paint);paint.setStyle(Paint.Style.FILL);handle(canvas,target.left,target.top,"×");handle(canvas,target.right,target.top,"⋯");handle(canvas,target.right,target.bottom,"↘");}}}
        void handle(Canvas canvas,float x,float y,String text){paint.setColor(AppTheme.accent());canvas.drawCircle(x,y,dp(12),paint);paint.setColor(AppTheme.onAccent());paint.setStyle(Paint.Style.STROKE);canvas.drawCircle(x,y,dp(12),paint);paint.setStyle(Paint.Style.FILL);paint.setTextSize(dp(18));paint.setTextAlign(Paint.Align.CENTER);canvas.drawText(text,x,y+dp(6),paint);}
        boolean near(float x,float y,float a,float b){return Math.hypot(x-a,y-b)<dp(24);}
        @Override public boolean onTouchEvent(MotionEvent event){if(session.busy||session.pageBitmap==null)return true;float x=event.getX(),y=event.getY();RectF page=pageRect();if(event.getActionMasked()==MotionEvent.ACTION_DOWN){gesture=0;if(session.selected>=0){Mark mark=session.marks.get(session.selected);RectF r=rect(mark);if(near(x,y,r.left,r.top)){removeSelected();return true;}if(near(x,y,r.right,r.top)){editMark();return true;}if(near(x,y,r.right,r.bottom))gesture=2;}
            if(gesture==0){session.selected=-1;for(int i=session.marks.size()-1;i>=0;i--){Mark m=session.marks.get(i);if(m.page==session.page&&rect(m).contains(x,y)){session.selected=i;gesture=1;break;}}buildFooter();}lastX=x;lastY=y;invalidate();}
            else if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&session.selected>=0){Mark mark=session.marks.get(session.selected);if(gesture==1){mark.x+=(x-lastX)/page.width();mark.y+=(y-lastY)/page.height();}else if(gesture==2)mark.width=Math.max(.05f,Math.min(.95f,mark.width+(x-lastX)/page.width()));constrain(mark);lastX=x;lastY=y;invalidate();}return true;}
    }
    private LinearLayout row(){LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);return row;}
    private TextView label(String text,int size,int color){TextView view=new TextView(this);view.setText(text);view.setTextSize(size);view.setTextColor(color);return view;}
    private void button(LinearLayout parent,String title,Runnable action,int width){TextView view=label(title,14,title.equals("Done")||title.equals("OK")?BRAND:INK);view.setGravity(Gravity.CENTER);view.setContentDescription(title);parent.addView(view,width==0?new LinearLayout.LayoutParams(0,-1,1):new LinearLayout.LayoutParams(dp(width),-1));view.setOnClickListener(v->{if(!session.busy)action.run();});}
    private void toast(String message){if(!disposed)Toast.makeText(this,message,Toast.LENGTH_LONG).show();}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    @Override public void handleBackPressed(){if(session.busy){toast(UiText.get(R.string.ui_please_wait_for_processing));return;}if(session.draw){leaveDraw();return;}if(session.marks.isEmpty()){super.handleBackPressed();return;}new ProtectedDialogs.Builder(this).setMessage(UiText.get(R.string.ui_going_back_will_discard_the_signature)).setNegativeButton(UiText.get(R.string.ui_cancel),null).setPositiveButton(UiText.get(R.string.ui_discard),(d,w)->finish()).show();}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("page",session.page);ArrayList<String> files=new ArrayList<>();float[] positions=new float[session.marks.size()*5];for(int i=0;i<session.marks.size();i++){Mark m=session.marks.get(i);files.add(m.file);positions[i*5]=m.page;positions[i*5+1]=m.x;positions[i*5+2]=m.y;positions[i*5+3]=m.width;positions[i*5+4]=m.angle;}out.putStringArrayList("marks",files);out.putFloatArray("positions",positions);out.putStringArrayList("temporary_dates",new ArrayList<>(session.temporaryDates));super.onSaveInstanceState(out);}
    private void restore(Bundle saved){session.page=saved.getInt("page",session.page);ArrayList<String> temporary=saved.getStringArrayList("temporary_dates");if(temporary!=null)for(String path:temporary)if(SignatureStore.isOwnedTemporaryDate(this,path)&&!session.temporaryDates.contains(path))session.temporaryDates.add(path);ArrayList<String> files=saved.getStringArrayList("marks");float[] positions=saved.getFloatArray("positions");java.util.HashMap<String,Bitmap> decoded=new java.util.HashMap<>();if(files!=null&&positions!=null&&positions.length==files.size()*5)for(int i=0;i<files.size();i++){Bitmap bitmap=decoded.get(files.get(i));if(bitmap==null){bitmap=BitmapFactory.decodeFile(files.get(i));if(bitmap!=null)decoded.put(files.get(i),bitmap);}if(bitmap!=null){Mark mark=new Mark(files.get(i),(int)positions[i*5],bitmap);mark.x=positions[i*5+1];mark.y=positions[i*5+2];mark.width=positions[i*5+3];mark.angle=(int)positions[i*5+4];session.marks.add(mark);if(SignatureStore.isOwnedTemporaryDate(this,mark.file)&&!session.temporaryDates.contains(mark.file))session.temporaryDates.add(mark.file);}}}
    @Override protected void onResume(){super.onResume();FolderLock.guard(this,session.path);session.changed=()->{refresh();if(!session.draw)buildFooter();};refresh();}
    @Override protected void onPause(){session.changed=null;super.onPause();}
    @Override protected void onDestroy(){disposed=true;if(library!=null)library.dismiss();super.onDestroy();}
}

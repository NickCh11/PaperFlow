package com.example.myapplication;

import android.app.Application;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.lifecycle.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.tom_roush.pdfbox.rendering.PDFRenderer;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Scrollable long-image preview, quality, Save as New and real cancellable export. */
public class LongImageActivity extends BackHandlingActivity {
    private final int BRAND = AppTheme.accent(),INK = AppTheme.ink(),MUTED = AppTheme.muted();
    private LongSession session;
    private ListView pages;
    private PageAdapter adapter;
    private TextView title,standard,high,summary,share,save,empty;
    private AlertDialog passwordDialog,progressDialog,pageDialog;
    private EditText passwordInput;
    private TextView progressLabel;
    private ProgressBar progress;
    private int cacheVersion=-1,selectionVersion=-1;
    private int restorePosition=-1,restoreTop;
    private boolean destroyed;
    private ActivityResultLauncher<Intent> saveLauncher;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);PDFBoxResourceLoader.init(getApplicationContext());
        session=new ViewModelProvider(this).get(LongSession.class);
        saveLauncher=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->{
            Uri uri=result.getResultCode()==RESULT_OK&&result.getData()!=null?result.getData().getData():null;
            if(uri==null){session.deviceSaveSource=null;session.publish();}else saveAuthorized(uri);
        });
        if(state!=null){restorePosition=state.getInt("list_position");restoreTop=state.getInt("list_top");}
        buildUi();session.changes.observe(this,unused->update());session.initialize(getIntent(),state);
    }
    private void saveAuthorized(Uri uri){if(FolderLock.ensureDocument(this,session.path,()->saveAuthorized(uri)))session.saveToDevice(uri);}
    private void buildUi(){
        getWindow().setStatusBarColor(Color.WHITE);getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(AppTheme.background());
        LinearLayout toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setBackgroundColor(AppTheme.surface());
        ImageView back=new ImageView(this);back.setImageDrawable(new AppIcon("Back",INK));back.setPadding(dp(15),dp(15),dp(15),dp(15));back.setContentDescription(UiText.get(R.string.ui_back));toolbar.addView(back,new LinearLayout.LayoutParams(dp(52),dp(56)));back.setOnClickListener(v->back());
        title=text(UiText.get(R.string.ui_pdf_to_long_image),16,INK);title.setSingleLine();title.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);toolbar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        save=text(UiText.get(R.string.ui_save_as_new),12,BRAND);save.setTypeface(null,android.graphics.Typeface.BOLD);save.setGravity(Gravity.CENTER);save.setPadding(dp(12),dp(6),dp(12),dp(6));save.setContentDescription(UiText.get(R.string.ui_save_as_new));toolbar.addView(save,new LinearLayout.LayoutParams(dp(100),dp(44)));save.setOnClickListener(v->session.export(LongSession.REGISTER));
        ImageView more=new ImageView(this);more.setImageDrawable(new AppIcon("More",INK));more.setContentDescription(UiText.get(R.string.ui_more_options));more.setPadding(dp(12),dp(16),dp(12),dp(16));toolbar.addView(more,new LinearLayout.LayoutParams(dp(44),dp(56)));more.setOnClickListener(v->{PopupMenu menu=new PopupMenu(this,more);UiText.add(menu.getMenu(), "Select pages");UiText.add(menu.getMenu(), "Save to device");menu.setOnMenuItemClickListener(item->{if(session.busy||session.deviceSaveSource!=null)return true;if(UiText.canonical(item).equals("Select pages"))showPages();else session.export(LongSession.SAVE);return true;});menu.show();});root.addView(toolbar);
        FrameLayout preview=new FrameLayout(this);pages=new ListView(this);pages.setDivider(null);pages.setPadding(dp(5),dp(5),dp(5),dp(5));pages.setClipToPadding(false);pages.setContentDescription(UiText.get(R.string.ui_long_image_preview));adapter=new PageAdapter();pages.setAdapter(adapter);preview.addView(pages,new FrameLayout.LayoutParams(-1,-1));
        empty=text(UiText.get(R.string.ui_opening_pdf),15,MUTED);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(24),dp(24),dp(24),dp(24));preview.addView(empty,new FrameLayout.LayoutParams(-1,-1));empty.setOnClickListener(v->{if(!session.busy&&session.dimensions.isEmpty())session.load();});root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=new LinearLayout(this);footer.setOrientation(LinearLayout.VERTICAL);footer.setPadding(dp(16),dp(8),dp(16),dp(8));footer.setBackgroundColor(AppTheme.surface());
        LinearLayout quality=new LinearLayout(this);quality.setPadding(dp(2),dp(2),dp(2),dp(2));quality.setBackground(round(0xFFF1F2F3,20));
        standard=text(UiText.get(R.string.ui_standard_up_to_1000_px),12,INK);high=text(UiText.get(R.string.ui_high_clarity_up_to_1600_px),12,INK);
        for(TextView item:new TextView[]{standard,high}){item.setGravity(Gravity.CENTER);item.setIncludeFontPadding(false);quality.addView(item,new LinearLayout.LayoutParams(0,-1,1));}
        standard.setContentDescription(UiText.get(R.string.ui_standard_clarity));high.setContentDescription(UiText.get(R.string.ui_high_clarity));standard.setOnClickListener(v->session.setQuality(0));high.setOnClickListener(v->session.setQuality(1));footer.addView(quality,new LinearLayout.LayoutParams(-1,dp(42)));
        summary=text("",12,MUTED);summary.setGravity(Gravity.CENTER);summary.setPadding(0,dp(5),0,dp(5));footer.addView(summary,new LinearLayout.LayoutParams(-1,-2));
        share=text(UiText.get(R.string.ui_share),16,AppTheme.onAccent());share.setGravity(Gravity.CENTER);share.setTypeface(null,android.graphics.Typeface.BOLD);share.setBackground(round(BRAND,4));footer.addView(share,new LinearLayout.LayoutParams(-1,dp(48)));share.setOnClickListener(v->session.export(LongSession.SHARE));root.addView(footer);UiInsets.apply(root);setContentView(root);
    }
    private void update(){
        if(destroyed)return;title.setText(session.name==null ? UiText.get(R.string.ui_pdf_to_long_image) : session.name);
        if(cacheVersion!=session.previewVersion||selectionVersion!=session.selectionVersion){cacheVersion=session.previewVersion;selectionVersion=session.selectionVersion;adapter.notifyDataSetChanged();}
        boolean usable=!session.dimensions.isEmpty(),enabled=usable&&!session.busy&&session.deviceSaveSource==null;
        if(usable&&restorePosition>=0){int position=Math.min(restorePosition,Math.max(0,session.selected.size()-1)),top=restoreTop;restorePosition=-1;pages.post(()->pages.setSelectionFromTop(position,top));}
        empty.setVisibility(usable?View.GONE:View.VISIBLE);empty.setText(session.status);
        standard.setSelected(session.quality==0);high.setSelected(session.quality==1);
        standard.setBackground(session.quality==0?round(AppTheme.surface(),20):null);high.setBackground(session.quality==1?round(AppTheme.surface(),20):null);
        standard.setTypeface(null,session.quality==0?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL);high.setTypeface(null,session.quality==1?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL);standard.setEnabled(enabled);high.setEnabled(enabled);
        boolean valid=false;
        if(usable)try{LongImageExporter.Plan plan=session.plan();summary.setText(UiText.format(R.string.ui_value_pages_value_value_px, session.selected.size(), plan.width, plan.height));valid=true;}catch(IllegalArgumentException e){summary.setText(e.getMessage());}
        share.setEnabled(enabled&&valid);save.setEnabled(enabled&&valid);share.setAlpha(share.isEnabled()?1:.45f);save.setAlpha(save.isEnabled()?1:.45f);
        updateProgressDialog();
        if(session.needsPassword&&passwordDialog==null)showPassword();
        if(session.pageDialogOpen&&pageDialog==null&&!session.busy)showPages();
        if(session.message!=null){Toast.makeText(this,session.message,Toast.LENGTH_LONG).show();session.message=null;}
        deliver();
    }
    private void updateProgressDialog(){
        if(!session.busy){if(progressDialog!=null){progressDialog.dismiss();progressDialog=null;}return;}
        if(progressDialog==null){LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);content.setPadding(dp(24),dp(16),dp(24),dp(12));progressLabel=text("",15,INK);content.addView(progressLabel);progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(8));p.topMargin=dp(16);content.addView(progress,p);progressDialog=new ProtectedDialogs.Builder(this).setTitle(UiText.get(R.string.ui_pdf_to_long_image)).setView(content).setNegativeButton(UiText.get(R.string.ui_cancel),(d,w)->session.cancel()).create();progressDialog.setCanceledOnTouchOutside(false);progressDialog.setOnCancelListener(d->session.cancel());progressDialog.show();}
        progressLabel.setText(session.status);progress.setIndeterminate(session.total<=0);if(session.total>0){progress.setMax(session.total);progress.setProgress(session.done);}progressDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(session.job!=null&&!session.job.cancelled);
    }
    private void showPassword(){
        passwordInput=new EditText(this);passwordInput.setSingleLine();passwordInput.setInputType(129);passwordInput.setHint(UiText.get(R.string.ui_pdf_password));passwordInput.setText(session.passwordDraft);
        passwordDialog=new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_password_protected_pdf)).setMessage(session.passwordWrong ? UiText.get(R.string.ui_incorrect_password_try_again) : UiText.get(R.string.ui_enter_the_password_to_open_this_document)).setView(passwordInput).setNegativeButton(UiText.get(R.string.ui_cancel),(d,w)->finish()).setPositiveButton(UiText.get(R.string.ui_open),null).create();
        passwordDialog.setOnCancelListener(d->finish());passwordDialog.show();passwordDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String value=passwordInput.getText().toString();if(value.isEmpty()){passwordInput.setError(UiText.get(R.string.ui_enter_password));return;}session.password=value;session.passwordDraft=value;session.needsPassword=false;passwordDialog.dismiss();passwordDialog=null;session.load();});
    }
    private void showPages(){
        if(session.dimensions.isEmpty()||session.busy)return;session.pageDialogOpen=true;
        if(session.pageDraft==null)session.pageDraft=new HashSet<>(session.selected);
        String[] labels=new String[session.dimensions.size()];boolean[] selected=new boolean[labels.length];for(int i=0;i<labels.length;i++){labels[i]=UiText.format(R.string.ui_page_value_67f70d13, (i+1));selected[i]=session.pageDraft.contains(i);}
        pageDialog=new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_select_pages)).setMultiChoiceItems(labels,selected,(d,which,value)->{if(value)session.pageDraft.add(which);else session.pageDraft.remove(which);}).setNeutralButton(UiText.get(R.string.ui_select_all_clear),null).setNegativeButton(UiText.get(R.string.ui_cancel),(d,w)->{session.pageDialogOpen=false;session.pageDraft=null;}).setPositiveButton(UiText.get(R.string.ui_apply),null).create();
        pageDialog.setOnCancelListener(d->{session.pageDialogOpen=false;session.pageDraft=null;});pageDialog.setOnDismissListener(d->pageDialog=null);pageDialog.show();
        pageDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{boolean all=session.pageDraft.size()!=labels.length;session.pageDraft.clear();ListView list=pageDialog.getListView();for(int i=0;i<labels.length;i++){if(all)session.pageDraft.add(i);list.setItemChecked(i,all);}});
        pageDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(session.pageDraft.isEmpty()){Toast.makeText(this,UiText.get(R.string.ui_select_at_least_one_page),Toast.LENGTH_SHORT).show();return;}session.setPages(session.pageDraft);session.pageDialogOpen=false;session.pageDraft=null;pageDialog.dismiss();});
    }
    private void deliver(){
        if(!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)||session.busy||session.pendingAction==0||session.output==null)return;
        int action=session.pendingAction;session.pendingAction=0;
        try{
            if(action==LongSession.SHARE){Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",session.output);session.shared.add(session.output.getAbsolutePath());Intent send=new Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);send.setClipData(ClipData.newRawUri("Long image",uri));startActivity(Intent.createChooser(send,UiText.get(R.string.ui_share_long_image)));}
            else if(action==LongSession.SAVE){session.deviceSaveSource=session.output;Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/jpeg").putExtra(Intent.EXTRA_TITLE,session.name+" · Long Image.jpg");saveLauncher.launch(intent);}
            else if(session.registerPdf!=null){Intent intent=new Intent(this,MainActivity.class).putExtra("document_action","Register").putExtra("path",session.registerPdf.getAbsolutePath()).putExtra("name",session.name+" · Long Image").putExtra("pages",1).putExtra("origin_path",session.path).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);session.registerPdf=null;startActivity(intent);finish();}
        }catch(Exception e){session.deviceSaveSource=null;Toast.makeText(this,UiText.get(R.string.ui_could_not_open_export_destination),Toast.LENGTH_LONG).show();}
    }
    @Override protected void onResume(){super.onResume();String origin=getIntent().getStringExtra("path");if(origin!=null)FolderLock.guard(this,origin);if(session!=null)deliver();}
    private void back(){if(session.busy){session.cancel();return;}finish();}
    @Override public void handleBackPressed(){back();}
    @Override protected void onSaveInstanceState(Bundle state){if(passwordInput!=null&&passwordDialog!=null)session.passwordDraft=passwordInput.getText().toString();session.saveState(state);state.putInt("list_position",pages.getFirstVisiblePosition());View first=pages.getChildAt(0);state.putInt("list_top",first==null?0:first.getTop());super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){destroyed=true;if(passwordDialog!=null)passwordDialog.dismiss();if(progressDialog!=null)progressDialog.dismiss();if(pageDialog!=null)pageDialog.dismiss();pages.setAdapter(null);super.onDestroy();}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setGravity(Gravity.CENTER_VERTICAL);return t;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private class PageAdapter extends BaseAdapter {
        public int getCount(){return session==null||session.dimensions.isEmpty()?0:session.selected.size();}public Integer getItem(int position){return session.selected.get(position);}public long getItemId(int position){return getItem(position);}public boolean hasStableIds(){return true;}
        public View getView(int position,View convert,android.view.ViewGroup parent){PageView view=convert instanceof PageView?(PageView)convert:new PageView();view.page=getItem(position);view.setContentDescription(UiText.format(R.string.ui_preview_page_value, (view.page+1)));session.requestPreview(view.page);return view;}
    }
    private class PageView extends View {
        int page;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        PageView(){super(LongImageActivity.this);}
        @Override protected void onMeasure(int widthSpec,int heightSpec){int w=MeasureSpec.getSize(widthSpec);float[] d=session.dimensions.get(page);setMeasuredDimension(w,Math.max(1,Math.round(w*d[1]/d[0]))+dp(5));}
        @Override protected void onDraw(Canvas c){c.drawColor(0xFFF6F6F6);paint.setColor(Color.WHITE);c.drawRect(0,0,getWidth(),getHeight()-dp(5),paint);Bitmap image=session.previews.get(page);if(image!=null&&!image.isRecycled())c.drawBitmap(image,null,new RectF(0,0,getWidth(),getHeight()-dp(5)),paint);else{paint.setColor(MUTED);paint.setTextSize(dp(14));c.drawText(UiText.format(R.string.ui_page_value_2, (page+1)),dp(16),dp(32),paint);}}
    }

    public static class LongSession extends AndroidViewModel {
        static final int SHARE=1,SAVE=2,REGISTER=3;
        final MutableLiveData<Integer> changes=new MutableLiveData<>(0);final Handler main=new Handler(Looper.getMainLooper());final ExecutorService worker=Executors.newSingleThreadExecutor();
        final ArrayList<float[]> dimensions=new ArrayList<>();final ArrayList<Integer> selected=new ArrayList<>();
        final LinkedHashMap<Integer,Bitmap> previews=new LinkedHashMap<>(16,.75f,true);final HashSet<String> pendingPreviews=new HashSet<>(),shared=new HashSet<>();
        String path,name,password="",passwordDraft="",status=UiText.get(R.string.ui_opening_pdf),message,outputKey;File output,deviceSaveSource,registerPdf;Uri queuedSaveUri;int quality,done,total,pendingAction,previewVersion,selectionVersion,previewGeneration;
        boolean initialized,busy,needsPassword,passwordWrong,pageDialogOpen;HashSet<Integer> pageDraft;volatile boolean cleared;LongImageExporter.Job job;
        public LongSession(Application application){super(application);}
        void publish(){if(!cleared)changes.setValue(changes.getValue()+1);}
        void initialize(Intent intent,Bundle state){if(initialized)return;initialized=true;path=intent.getStringExtra("path");name=intent.getStringExtra("name");if(name==null)name="Document";password=intent.getStringExtra("password");if(password==null)password="";
            if(state!=null){password=state.getString("long_password",password);passwordDraft=state.getString("long_password_draft","");quality=state.getInt("long_quality");ArrayList<Integer> saved=state.getIntegerArrayList("long_selected");if(saved!=null)selected.addAll(saved);output=owned(state.getString("long_output"),"paperflow-long-",".jpg");outputKey=state.getString("long_output_key");deviceSaveSource=owned(state.getString("long_device_source"),"paperflow-long-",".jpg");shared.addAll(state.getStringArrayList("long_shared")==null?Collections.emptyList():state.getStringArrayList("long_shared"));pageDialogOpen=state.getBoolean("long_page_dialog");ArrayList<Integer> draft=state.getIntegerArrayList("long_page_draft");if(draft!=null)pageDraft=new HashSet<>(draft);}
            if(state!=null&&state.getString("long_queued_uri")!=null)queuedSaveUri=Uri.parse(state.getString("long_queued_uri"));
            load();
        }
        private File directory(){File dir=new File(getApplication().getFilesDir(),"documents");if(!dir.isDirectory()&&!dir.mkdirs())throw new IllegalStateException(UiText.get(R.string.ui_storage_unavailable));return dir;}
        private File owned(String value,String prefix,String suffix){if(value==null)return null;try{File file=new File(value).getCanonicalFile();return file.isFile()&&file.getParentFile().equals(directory().getCanonicalFile())&&file.getName().startsWith(prefix)&&file.getName().endsWith(suffix)?file:null;}catch(Exception e){return null;}}
        private PDDocument open()throws Exception{PDDocument doc=PDDocument.load(new File(path),password,MemoryUsageSetting.setupMixed(16*1024*1024));try{PdfPermissions.extract(doc);return doc;}catch(Exception e){doc.close();throw e;}}
        void load(){
            if(busy)return;busy=true;needsPassword=false;done=total=0;status="Opening PDF…";
            LongImageExporter.Job active=new LongImageExporter.Job();job=active;publish();
            worker.execute(()->{
                try(PDDocument pdf=open()){
                    ArrayList<float[]> sizes=new ArrayList<>();
                    if(pdf.getNumberOfPages()==0)throw new IllegalArgumentException(UiText.get(R.string.ui_the_pdf_has_no_pages));
                    for(PDPage page:pdf.getPages()){active.check();sizes.add(LongImageExporter.dimensions(page));}
                    active.check();
                    main.post(()->{
                        if(cleared)return;if(active.cancelled){completeCancel();return;}
                        dimensions.clear();dimensions.addAll(sizes);selected.removeIf(i->i<0||i>=sizes.size());
                        if(selected.isEmpty())for(int i=0;i<sizes.size();i++)selected.add(i);
                        Collections.sort(selected);busy=false;job=null;passwordWrong=false;status="";selectionVersion++;
                        if(queuedSaveUri!=null)saveToDevice(queuedSaveUri);else publish();
                    });
                }catch(InvalidPasswordException e){main.post(()->{if(cleared)return;if(active.cancelled){completeCancel();return;}busy=false;job=null;needsPassword=true;passwordWrong=!password.isEmpty();if(queuedSaveUri!=null)saveToDevice(queuedSaveUri);else publish();});}
                catch(Exception|OutOfMemoryError e){failed(active,e);}
            });
        }
        LongImageExporter.Plan plan(){return LongImageExporter.plan(dimensions,selected,quality);}
        void setQuality(int value){if(busy||deviceSaveSource!=null||quality==value)return;quality=value;previewGeneration++;pendingPreviews.clear();clearPreviews();previewVersion++;publish();}
        void setPages(Set<Integer> values){selected.clear();selected.addAll(values);Collections.sort(selected);selectionVersion++;publish();}
        private String key(){return quality+":"+selected.toString();}
        void requestPreview(int page){String token=previewGeneration+":"+page;if(cleared||busy||previews.containsKey(page)||pendingPreviews.contains(token)||pendingPreviews.size()>=12)return;pendingPreviews.add(token);int generation=previewGeneration,width=quality==0?360:720;worker.execute(()->{Bitmap bitmap=null;try{if(cleared||generation!=previewGeneration)return;try(PDDocument pdf=open()){float[] d=LongImageExporter.dimensions(pdf.getPage(page));PDFRenderer renderer=new PDFRenderer(pdf);renderer.setSubsamplingAllowed(true);bitmap=renderer.renderImage(page,Math.min(width/d[0],2400/d[1]));}Bitmap image=bitmap;bitmap=null;main.post(()->{pendingPreviews.remove(token);if(cleared||generation!=previewGeneration){image.recycle();return;}Bitmap old=previews.put(page,image);if(old!=null)old.recycle();int bytes=0;for(Bitmap b:previews.values())bytes+=b.getAllocationByteCount();Iterator<Map.Entry<Integer,Bitmap>> iterator=previews.entrySet().iterator();while(bytes>12*1024*1024&&previews.size()>1&&iterator.hasNext()){Bitmap retired=iterator.next().getValue();bytes-=retired.getAllocationByteCount();iterator.remove();retired.recycle();}previewVersion++;publish();});}catch(Exception|OutOfMemoryError ignored){}finally{if(bitmap!=null)bitmap.recycle();main.post(()->pendingPreviews.remove(token));}});}
        void export(int action){if(busy||deviceSaveSource!=null)return;try{plan();}catch(IllegalArgumentException e){message=e.getMessage();publish();return;}String snapshotKey=key();if(action!=REGISTER&&output!=null&&output.isFile()&&snapshotKey.equals(outputKey)){pendingAction=action;publish();return;}busy=true;done=0;total=selected.size();status="Preparing long image…";LongImageExporter.Job active=new LongImageExporter.Job();job=active;ArrayList<Integer> selection=new ArrayList<>(selected);ArrayList<float[]> sizes=new ArrayList<>(dimensions);int clarity=quality;publish();worker.execute(()->{File next=null,pdfFile=null;boolean produced=false;try{
                File cached=output;
                if(cached!=null&&cached.isFile()&&snapshotKey.equals(outputKey))next=cached;
                else{next=new File(directory(),"paperflow-long-"+UUID.randomUUID()+".jpg");produced=true;try(PDDocument pdf=open()){LongImageExporter.write(pdf,sizes,selection,clarity,next,active,(completed,count)->main.post(()->{if(!cleared&&job==active){done=completed;total=count;status="Preparing long image…\n"+completed+" / "+count;publish();}}));}}
                active.check();
                if(action==REGISTER){pdfFile=new File(directory(),"paperflow-long-doc-"+UUID.randomUUID()+".pdf");LongImageExporter.Plan plan=LongImageExporter.plan(sizes,selection,clarity);float h=Math.min(14400f,595f*plan.height/plan.width),w=h*plan.width/plan.height;try(PDDocument pdf=new PDDocument();InputStream input=new FileInputStream(next)){PDPage page=new PDPage(new PDRectangle(w,h));pdf.addPage(page);try(PDPageContentStream stream=new PDPageContentStream(pdf,page)){stream.drawImage(JPEGFactory.createFromStream(pdf,input),0,0,w,h);}pdf.save(pdfFile);}active.check();}
                File ready=next,registered=pdfFile;boolean created=produced;main.post(()->{if(cleared||active.cancelled){if(created)ready.delete();if(registered!=null)registered.delete();if(!cleared)completeCancel();return;}if(output!=null&&!output.equals(ready)&&!shared.contains(output.getAbsolutePath()))output.delete();output=ready;outputKey=snapshotKey;registerPdf=registered;pendingAction=action;busy=false;job=null;status="";publish();});
            }catch(Exception|OutOfMemoryError e){if(produced&&next!=null)next.delete();if(pdfFile!=null)pdfFile.delete();failed(active,e);}});}
        void cancel(){if(job!=null){job.cancelled=true;status="Cancelling…";publish();}}
        private void completeCancel(){busy=false;job=null;done=total=0;pendingAction=0;queuedSaveUri=null;deviceSaveSource=null;status=dimensions.isEmpty()?"Cancelled. Tap to retry.":"";message=UiText.get(R.string.ui_cancelled);publish();}
        private void failed(LongImageExporter.Job active,Throwable error){main.post(()->{if(cleared)return;if(error instanceof CancellationException||active.cancelled){completeCancel();return;}busy=false;job=null;pendingAction=0;if(queuedSaveUri!=null&&deviceSaveSource!=null){saveToDevice(queuedSaveUri);return;}message=error instanceof OutOfMemoryError ? UiText.get(R.string.ui_not_enough_memory_select_fewer_pages_or_standard_clarity) : UiText.format(R.string.ui_could_not_prepare_long_image_value, (error.getMessage()==null?"Unreadable PDF":error.getMessage()));status=dimensions.isEmpty()?message+"\nTap to retry.":"";publish();});}
        void saveToDevice(Uri uri){
            if(deviceSaveSource==null)return;if(busy){queuedSaveUri=uri;return;}
            File file=deviceSaveSource;queuedSaveUri=null;busy=true;done=total=0;
            LongImageExporter.Job active=new LongImageExporter.Job();job=active;status="Saving image…";publish();
            worker.execute(()->{
                try{
                    active.check();try(InputStream input=new FileInputStream(file);OutputStream target=getApplication().getContentResolver().openOutputStream(uri,"wt")){
                        if(target==null)throw new IOException(UiText.get(R.string.ui_destination_unavailable));
                        byte[] buffer=new byte[16384];int n;while((n=input.read(buffer))!=-1){active.check();target.write(buffer,0,n);}active.check();
                    }
                    // Closed output is the commit point; a later Cancel cannot undo a completed save.
                    main.post(()->{if(cleared)return;deviceSaveSource=null;busy=false;job=null;message=UiText.get(R.string.ui_image_saved);publish();});
                }catch(Exception e){
                    try{android.provider.DocumentsContract.deleteDocument(getApplication().getContentResolver(),uri);}catch(Exception ignored){}
                    main.post(()->{if(cleared)return;deviceSaveSource=null;if(active.cancelled){completeCancel();return;}busy=false;job=null;message=UiText.get(R.string.ui_could_not_save_image);publish();});
                }
            });
        }
        void saveState(Bundle state){state.putString("long_password",password);state.putString("long_password_draft",passwordDraft);state.putInt("long_quality",quality);state.putIntegerArrayList("long_selected",new ArrayList<>(selected));state.putString("long_output",output==null?null:output.getAbsolutePath());state.putString("long_output_key",outputKey);state.putString("long_device_source",deviceSaveSource==null?null:deviceSaveSource.getAbsolutePath());if(queuedSaveUri!=null)state.putString("long_queued_uri",queuedSaveUri.toString());state.putStringArrayList("long_shared",new ArrayList<>(shared));state.putBoolean("long_page_dialog",pageDialogOpen);if(pageDraft!=null)state.putIntegerArrayList("long_page_draft",new ArrayList<>(pageDraft));}
        private void clearPreviews(){for(Bitmap bitmap:previews.values())bitmap.recycle();previews.clear();}
        @Override protected void onCleared(){cleared=true;if(job!=null)job.cancelled=true;clearPreviews();worker.execute(()->{if(output!=null&&!shared.contains(output.getAbsolutePath()))output.delete();if(registerPdf!=null)registerPdf.delete();});worker.shutdown();}
    }
}

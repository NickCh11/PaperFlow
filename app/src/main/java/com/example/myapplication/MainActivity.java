package com.example.myapplication;

import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {
    private final int INK = AppTheme.ink();
    private final int MUTED = AppTheme.muted();
    private final int BRAND = AppTheme.accent();
    private final int BG = AppTheme.background();
    private LinearLayout content;
    private ScrollView mainScroll;
    private final java.util.List<TextView> navigationItems = new ArrayList<>();
    private DocumentStore store;
    private ActivityResultLauncher<Intent> captureLauncher,googleUnlockLauncher;
    private ActivityResultLauncher<String[]> pdfPicker;
    private ActivityResultLauncher<String[]> imagePicker,filePicker;
    private ActivityResultLauncher<String[]> wordPdfPicker;
    private ActivityResultLauncher<String> wordSaveLauncher;
    private File wordExport;
    private WordState wordState;
    private android.app.ProgressDialog wordProgressDialog;
    private AlertDialog wordResultDialog,wordErrorDialog;
    public static final class WordProgress {
        final int completed,total; final String message;
        WordProgress(int completed,int total,String message){this.completed=completed;this.total=total;this.message=message;}
    }
    static final class CloudRequest {
        final String path,name,password,key;
        CloudRequest(String path,String name,String password,String key){this.path=path;this.name=name;this.password=password;this.key=key;}
        Bundle saved(){Bundle state=new Bundle();state.putString("path",path);state.putString("name",name);state.putString("password",password);return state;}
        static CloudRequest restore(Bundle state){return state==null?null:new CloudRequest(state.getString("path"),state.getString("name"),state.getString("password"),null);}
    }
    public static final class WordState extends androidx.lifecycle.ViewModel {
        CloudRequest pendingCloud;
        final ExecutorService worker=Executors.newSingleThreadExecutor();
        final androidx.lifecycle.MutableLiveData<WordProgress> progress=new androidx.lifecycle.MutableLiveData<>();
        final androidx.lifecycle.MutableLiveData<WordConversion.Result> result=new androidx.lifecycle.MutableLiveData<>();
        final androidx.lifecycle.MutableLiveData<Throwable> error=new androidx.lifecycle.MutableLiveData<>();
        volatile boolean running,disposed; WordConversion.Job job; String path,name,password;int route,orientation;
        @Override protected void onCleared(){pendingCloud=null;disposed=true;if(job!=null)job.cancel();worker.shutdownNow();}
    }
    private String searchQuery = "";
    private boolean favoritesOnly = false;
    private long folderId=0; private boolean trashView; private String sortOrder="last_opened DESC, created DESC"; private boolean libraryVisible;private String section="Home";
    private final ExecutorService pdfWorker = Executors.newSingleThreadExecutor();
    private final ExecutorService libraryWorker=Executors.newSingleThreadExecutor();
    private final java.util.concurrent.ThreadPoolExecutor thumbnailWorker=new java.util.concurrent.ThreadPoolExecutor(2,2,0,java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.ArrayBlockingQueue<>(64),new java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy());
    private final android.util.LruCache<String,Bitmap> libraryThumbnails=new android.util.LruCache<String,Bitmap>(4*1024*1024){@Override protected int sizeOf(String key,Bitmap b){return b.getAllocationByteCount();}};
    private final java.util.concurrent.atomic.AtomicInteger libraryGeneration = new java.util.concurrent.atomic.AtomicInteger();
    private int libraryOffset;private String libraryQueryKey="";

    private String pendingAppendPath, pendingAppendName, pendingAppendPassword="";
    private LinearLayout cameraRow, selectionBar;
    private TextView selectionCount;
    private final java.util.LinkedHashMap<Long,String> selectedDocuments = new java.util.LinkedHashMap<>();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        store = new DocumentStore(this);
        sortOrder=getPreferences(0).getString("library_sort","last_opened DESC, created DESC");
        PDFBoxResourceLoader.init(getApplicationContext());
        wordState=new androidx.lifecycle.ViewModelProvider(this).get(WordState.class);
        if(wordState.pendingCloud==null&&state!=null)wordState.pendingCloud=CloudRequest.restore(state.getBundle("cloud_unlock"));
        googleUnlockLauncher=registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),result->finishGoogleUnlock(result.getResultCode()));
        captureLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if(result.getResultCode()!=RESULT_OK||result.getData()==null){clearPendingAppend();return;}
            Intent data=result.getData(); ArrayList<String> paths=data.getStringArrayListExtra("pages");
            if(paths!=null&&!paths.isEmpty()){ArrayList<String> uris=new ArrayList<>();for(String path:paths)uris.add(Uri.fromFile(new File(path)).toString());Intent editor=editorIntent(uris,data.getStringExtra("capture_mode"),data.getBooleanExtra("start_crop",true),data.getBooleanExtra("import_pdf",false));if(data.hasExtra("initial_filter"))editor.putExtra("initial_filter",data.getIntExtra("initial_filter",1)).putExtra("auto_detect",data.getBooleanExtra("auto_detect",true));startActivity(editor);}
            else if(data.getBooleanExtra("import_pdf",false))pdfPicker.launch(new String[]{"application/pdf"});
        });
        pdfPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if(uri!=null){if(pendingAppendPath!=null)appendImportedPdf(uri);else importPdf(uri);}else clearPendingAppend();
        });
        imagePicker = registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
            if(uris!=null&&!uris.isEmpty()){ArrayList<String> pages=new ArrayList<>();for(Uri uri:uris)pages.add(uri.toString());chooseImageImport(pages);}else clearPendingAppend();
        });
        filePicker=registerForActivityResult(new ActivityResultContracts.OpenDocument(),uri->{if(uri!=null)importDocument(uri);});
        wordPdfPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> { if (uri != null) preparePdfToWord(uri); });
        wordSaveLauncher=registerForActivityResult(new ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),uri->{if(uri==null||wordExport==null)return;File source=wordExport;pdfWorker.execute(()->{try(InputStream in=new java.io.FileInputStream(source);OutputStream out=getContentResolver().openOutputStream(uri)){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);runOnUiThread(()->Toast.makeText(this,UiText.get(R.string.ui_word_document_saved),Toast.LENGTH_SHORT).show());}catch(Exception e){runOnUiThread(()->Toast.makeText(this,UiText.get(R.string.ui_could_not_save_word_document),Toast.LENGTH_LONG).show());}});});

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        UiInsets.apply(root); setContentView(root);
        ScrollView scroll = new ScrollView(this);
        mainScroll=scroll;
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(22), dp(22), dp(28));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        renderHome();
        cameraRow = new LinearLayout(this);
        cameraRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        cameraRow.setPadding(dp(16), 0, dp(24), dp(10));
        ImageView camera = iconView("Camera", AppTheme.onAccent(), 16); camera.setElevation(dp(4));
        android.graphics.drawable.GradientDrawable cameraShape = new android.graphics.drawable.GradientDrawable();
        cameraShape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        cameraShape.setColor(BRAND);
        camera.setBackground(cameraShape);
        cameraRow.addView(camera, new LinearLayout.LayoutParams(dp(60), dp(60)));
        root.addView(cameraRow);
        camera.setOnClickListener(v -> startScan());
        LinearLayout nav = new LinearLayout(this);
        nav.setGravity(Gravity.CENTER);
        nav.setPadding(dp(8), dp(8), dp(8), dp(8));
        nav.setBackgroundColor(AppTheme.surface());
        TextView home = navItem("Home", true);
        TextView files = navItem("All Documents", false);
        TextView tools = navItem("Tools", false);
        TextView profile = navItem("Settings", false);
        nav.addView(home, new LinearLayout.LayoutParams(0, -2, 1));
        nav.addView(files, new LinearLayout.LayoutParams(0, -2, 1));
        nav.addView(tools, new LinearLayout.LayoutParams(0, -2, 1));
        nav.addView(profile, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(nav);
        home.setOnClickListener(v -> renderHome());
        files.setOnClickListener(v -> renderAllDocuments());
        tools.setOnClickListener(v -> renderTools());
        profile.setOnClickListener(v -> renderSettings());
        if(state!=null){pendingAppendPath=state.getString("append_path");pendingAppendName=state.getString("append_name");pendingAppendPassword=state.getString("append_password","");String word=state.getString("word_export");if(word!=null)wordExport=new File(word);}
        if(state!=null){folderId=state.getLong("library_folder");trashView=state.getBoolean("library_trash");favoritesOnly=state.getBoolean("library_favorites");searchQuery=state.getString("library_query","");if(state.getBoolean("library_visible"))renderAllDocuments();else if("Settings".equals(state.getString("section")))renderSettings();else if("Tools".equals(state.getString("section")))renderTools();else renderHome();}
        observeWordConversion();handleDocumentIntent(getIntent());
        indexLibrary();if(state==null&&Intent.ACTION_MAIN.equals(getIntent().getAction())&&getSharedPreferences("scan_defaults",0).getBoolean("launch_camera",false))startScan();
    }

    private void renderHome() {
        libraryVisible=false;prepareLibrarySelection(true);
        selectNavigation("Home");
        content.removeAllViews();
        content.setPadding(dp(16), dp(10), dp(16), dp(24));
        LinearLayout searchRow = new LinearLayout(this); searchRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout searchField = new LinearLayout(this); searchField.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.GradientDrawable searchShape = new android.graphics.drawable.GradientDrawable();
        searchShape.setColor(AppTheme.soft()); searchShape.setCornerRadius(dp(9)); searchField.setBackground(searchShape);
        ImageView magnifier = iconView("Search", MUTED, 11);
        searchField.addView(magnifier, new LinearLayout.LayoutParams(dp(42), dp(36)));
        EditText searchBox = new EditText(this); searchBox.setSingleLine(true); searchBox.setTextSize(16); searchBox.setText(searchQuery); searchBox.setHint(UiText.get(R.string.ui_search));
        searchBox.setBackgroundColor(Color.TRANSPARENT); searchBox.setPadding(0, 0, dp(8), 0);
        searchField.addView(searchBox, new LinearLayout.LayoutParams(0, dp(36), 1));
        searchRow.addView(searchField, new LinearLayout.LayoutParams(0, dp(36), 1));
        ImageView cloud = iconView("Cloud", BRAND, 9);
        searchRow.addView(cloud, new LinearLayout.LayoutParams(dp(42), dp(36))); content.addView(searchRow);
        cloud.setContentDescription(UiText.get(R.string.ui_save_to_cloud));cloud.setOnClickListener(v -> chooseCloudDocuments());
        searchBox.setOnEditorActionListener((v, action, event) -> { searchQuery = searchBox.getText().toString().trim(); android.app.ProgressDialog searching=android.app.ProgressDialog.show(this,"",UiText.get(R.string.ui_searching_library),true,false);pdfWorker.execute(()->{store.indexText();runOnUiThread(()->{searching.dismiss();if(!isDestroyed())renderHome();});}); return true; });

        String[][] shortcuts = {{"\u25a3", "Smart Scan"}, {"\u25a0", "PDF Tools"}, {"\u25c8", "Import Images"}, {"\u21a9", "Import Files"}, {"\u25ad", "ID Cards"}, {"\u27f3", "Rotate PDF"}, {"\u229e", "Merge PDFs"}, {"\u22ef", "All Tools"}};
        int[] shortcutColors = {BRAND, Color.rgb(255, 105, 83), BRAND, BRAND, BRAND, BRAND, BRAND, BRAND};
        for (int row = 0; row < 2; row++) {
            LinearLayout shortcutRow = new LinearLayout(this); shortcutRow.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams shortcutParams=new LinearLayout.LayoutParams(-1,-2);if(row==0)shortcutParams.topMargin=dp(7);content.addView(shortcutRow,shortcutParams);
            for (int col = 0; col < 4; col++) {
                final int index = row * 4 + col;
                LinearLayout tile = new LinearLayout(this); tile.setGravity(Gravity.CENTER); tile.setOrientation(LinearLayout.VERTICAL);
                shortcutRow.addView(tile, new LinearLayout.LayoutParams(0, -2, 1));tile.setMinimumHeight(dp(88));tile.setPadding(dp(2),dp(8),dp(2),dp(8));
                ImageView icon = iconView(shortcuts[index][1], shortcutColors[index], 8);
                android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable(); circle.setShape(android.graphics.drawable.GradientDrawable.OVAL); circle.setColor(AppTheme.surface()); circle.setStroke(dp(1), AppTheme.border());
                icon.setBackground(circle); tile.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
                TextView caption = new TextView(this); caption.setText(UiText.label(shortcuts[index][1])); caption.setTextColor(INK); caption.setTextSize(12); caption.setGravity(Gravity.CENTER); caption.setPadding(0, dp(4), 0, 0); tile.addView(caption);
                tile.setOnClickListener(v -> { if (index == 0) startScan(); else if (index == 4) startScan("ID Card"); else if (index == 5) launchTool("Rotate PDF"); else if (index == 6) launchTool("Merge Files"); else if (index == 1 || index == 7) renderTools(); else if (index == 2) imagePicker.launch(new String[]{"image/*"}); else pdfPicker.launch(new String[]{"application/pdf"}); });
            }
        }
        LinearLayout sectionHeader = new LinearLayout(this); sectionHeader.setGravity(Gravity.CENTER_VERTICAL); sectionHeader.setPadding(0, dp(16), 0, dp(8));
        android.graphics.drawable.GradientDrawable sectionShape = new android.graphics.drawable.GradientDrawable(); sectionShape.setColor(AppTheme.surface()); sectionShape.setCornerRadii(new float[]{dp(16),dp(16),dp(16),dp(16),0,0,0,0}); sectionHeader.setBackground(sectionShape);
        TextView recent = new TextView(this); recent.setText(UiText.get(R.string.ui_recents)); recent.setTextSize(18); recent.setTypeface(null, Typeface.BOLD); recent.setTextColor(INK); sectionHeader.addView(recent, new LinearLayout.LayoutParams(0, -2, 1));
        TextView viewAll = new TextView(this); viewAll.setText(UiText.get(R.string.ui_view_all)); viewAll.setTextSize(12); viewAll.setTextColor(MUTED);AppIcon nextIcon=new AppIcon("Next",MUTED);nextIcon.setBounds(0,0,dp(12),dp(12));viewAll.setCompoundDrawables(null,null,nextIcon,null);viewAll.setCompoundDrawablePadding(dp(2));sectionHeader.addView(viewAll); viewAll.setOnClickListener(v -> renderAllDocuments());LinearLayout.LayoutParams sectionParams=new LinearLayout.LayoutParams(-1,-2);sectionParams.topMargin=dp(6);content.addView(sectionHeader,sectionParams);
        addSelectionBar();
        loadLibraryRows(true);
    }

    private class EmptyDocumentIllustration extends View {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.DashPathEffect dash = new android.graphics.DashPathEffect(new float[]{3, 3}, 0);
        EmptyDocumentIllustration() { super(MainActivity.this); }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float scale = getResources().getDisplayMetrics().density;
            canvas.save(); canvas.scale(scale, scale);
            paint.setStyle(android.graphics.Paint.Style.FILL); paint.setColor(AppTheme.soft());
            canvas.drawRect(24, 9, 88, 116, paint);
            paint.setStyle(android.graphics.Paint.Style.STROKE); paint.setStrokeWidth(1); paint.setColor(AppTheme.border());
            canvas.drawRect(24, 9, 88, 116, paint);
            paint.setStrokeWidth(1.5f); paint.setColor(AppTheme.accent()); paint.setPathEffect(dash);
            canvas.drawRect(20, 13, 92, 121, paint); paint.setPathEffect(null);
            paint.setStrokeWidth(1.5f); paint.setColor(AppTheme.accent());
            canvas.drawLine(39, 29, 77, 29, paint); canvas.drawLine(32, 43, 82, 43, paint);
            canvas.drawLine(32, 57, 82, 57, paint); canvas.drawLine(32, 71, 82, 71, paint);
            canvas.drawLine(32, 85, 63, 85, paint);
            canvas.restore();
        }
    }

    private void renderAllDocuments() {
        libraryVisible=true;prepareLibrarySelection(false);selectNavigation("All Documents");
        content.removeAllViews();title(content,trashView ? UiText.get(R.string.ui_trash) : favoritesOnly ? UiText.get(R.string.ui_favorites) : folderTitle(),24);
        LinearLayout controls=new LinearLayout(this);content.addView(controls);
        String[] labels={"Folders","Sort",trashView?"Library":"Trash"};
        for(int i=0;i<labels.length;i++){final int action=i;TextView button=recentAction(UiText.label(labels[i]));controls.addView(button,new LinearLayout.LayoutParams(0,dp(44),1));button.setOnClickListener(v->{if(action==0)folderOptions();else if(action==1)chooseSort();else{trashView=!trashView;renderAllDocuments();}});}
        if(!trashView){
            TextView favorites=recentAction(favoritesOnly ? UiText.get(R.string.ui_show_all) : UiText.get(R.string.ui_favorites));content.addView(favorites,new LinearLayout.LayoutParams(-1,dp(40)));favorites.setOnClickListener(v->{favoritesOnly=!favoritesOnly;renderAllDocuments();});
            if(folderId!=0){TextView up=recentAction(UiText.get(R.string.ui_parent_folder));content.addView(up,new LinearLayout.LayoutParams(-1,dp(44)));up.setOnClickListener(v->{try(Cursor c=store.db().rawQuery("SELECT parent FROM folders WHERE id=?",new String[]{Long.toString(folderId)})){folderId=c.moveToFirst()?c.getLong(0):0;}renderAllDocuments();});}
            try(Cursor folders=store.db().rawQuery("SELECT id,name FROM folders WHERE parent=? ORDER BY name COLLATE NOCASE",new String[]{Long.toString(folderId)})){while(folders.moveToNext()){long id=folders.getLong(0);String name=folders.getString(1);TextView row=recentAction((FolderLock.protectedFolder(this,id)?"🔒 ":"▣ ")+name);content.addView(row,new LinearLayout.LayoutParams(-1,dp(50)));row.setOnClickListener(v->{if(FolderLock.ensureFolder(this,store,id,()->{folderId=id;renderAllDocuments();})){folderId=id;renderAllDocuments();}});row.setOnLongClickListener(v->{editFolder(id,name);return true;});}}
            addSelectionBar();
        }
        loadLibraryRows(false);
    }
    private static final class LibraryRow {
        final long id,created;final String name,path;final int pages;final boolean favorite;
        LibraryRow(Cursor c,boolean home){id=c.getLong(0);name=c.getString(1);path=c.getString(2);pages=c.getInt(3);created=c.getLong(home?4:5);favorite=c.getInt(6)!=0;}
    }
    private void prepareLibrarySelection(boolean home){
        String order=home?"last_opened DESC, created DESC":trashView?"deleted_at DESC":sortOrder;
        String key=home+"|"+searchQuery+"|"+order+"|"+trashView+"|"+favoritesOnly+"|"+folderId;
        if(!key.equals(libraryQueryKey)){libraryOffset=0;libraryQueryKey=key;selectedDocuments.clear();}
    }
    private void loadLibraryRows(boolean home){
        final String query=searchQuery,order=home?"last_opened DESC, created DESC":trashView?"deleted_at DESC":sortOrder;
        final boolean trash=trashView,favorites=favoritesOnly;final long folder=folderId;
        String key=home+"|"+query+"|"+order+"|"+trash+"|"+favorites+"|"+folder;
        if(!key.equals(libraryQueryKey)){libraryOffset=0;libraryQueryKey=key;}
        final int offset=libraryOffset,version=libraryGeneration.get();
        libraryWorker.execute(()->{ArrayList<LibraryRow> rows=new ArrayList<>();boolean more;
            try(DocumentStore reader=new DocumentStore(getApplicationContext())){
                String where=home?"deleted_at=0 AND "+DocumentStore.recentRevisionSql():(trash?"deleted_at>0":"deleted_at=0"+(favorites?" AND favorite=1":" AND folder_id="+folder));
                where+=" AND "+FolderLock.visibleSql(this,reader);String[] arguments=null;
                if(home&&!query.isEmpty()){where+=" AND (instr(search_text,?)>0 OR instr(search_meta,?)>0)";arguments=new String[]{DocumentStore.normalize(query),DocumentStore.normalize(query)};}
                try(Cursor c=reader.db().query("docs",new String[]{"id","name","path","pages","last_opened","created","favorite"},where,arguments,null,null,order,offset+","+(PageWindow.SIZE+1))){while(c.moveToNext()&&rows.size()<PageWindow.SIZE)rows.add(new LibraryRow(c,home));more=c.getPosition()<c.getCount();}
                final boolean hasMore=more;
                runOnUiThread(()->{if(isDestroyed()||version!=libraryGeneration.get())return;
                    if(rows.isEmpty()&&offset>0){libraryOffset=Math.max(0,offset-PageWindow.SIZE);if(home)renderHome();else renderAllDocuments();return;}
                    if(rows.isEmpty()){if(home)showEmptyHome();else label(content,trash?UiText.get(R.string.ui_trash_is_empty_deleted_files_stay_here_until_you_remove_them_permanently):UiText.get(R.string.ui_no_documents_in_this_folder),14,MUTED,false);}
                    for(LibraryRow row:rows){if(!home&&trash){TextView view=recentAction(UiText.format(R.string.ui_value_restore_remove,row.name));content.addView(view,new LinearLayout.LayoutParams(-1,dp(56)));view.setOnClickListener(v->trashActions(row.id,row.name,row.path));}else addDocumentCard(row.id,row.name,row.path,row.pages,row.created,row.favorite);}
                    if(offset>0||hasMore){LinearLayout controls=new LinearLayout(this);Button previous=new Button(this),next=new Button(this);previous.setText(R.string.ui_previous);next.setText(R.string.ui_next);previous.setEnabled(offset>0);next.setEnabled(hasMore);controls.addView(previous,new LinearLayout.LayoutParams(0,-2,1));controls.addView(next,new LinearLayout.LayoutParams(0,-2,1));content.addView(controls);previous.setOnClickListener(v->{libraryOffset=Math.max(0,offset-PageWindow.SIZE);if(home)renderHome();else renderAllDocuments();});next.setOnClickListener(v->{libraryOffset=offset+PageWindow.SIZE;if(home)renderHome();else renderAllDocuments();});}
                });
            }catch(Exception error){runOnUiThread(()->{if(!isDestroyed()&&version==libraryGeneration.get())Toast.makeText(this,UiText.get(R.string.ui_could_not_open_pdf),Toast.LENGTH_LONG).show();});}
        });
    }
    private void showEmptyHome(){
            LinearLayout empty = new LinearLayout(this); empty.setGravity(Gravity.CENTER); empty.setOrientation(LinearLayout.VERTICAL); empty.setBackgroundColor(AppTheme.surface()); empty.setMinimumHeight(dp(440));
            View illustration = new EmptyDocumentIllustration(); empty.addView(illustration, new LinearLayout.LayoutParams(dp(112), dp(128)));
            TextView emptyText = new TextView(this); emptyText.setText(searchQuery.isEmpty() ? UiText.get(R.string.ui_no_recent_file) : UiText.get(R.string.ui_no_files_found)); emptyText.setGravity(Gravity.CENTER);emptyText.setTextSize(16); emptyText.setTextColor(MUTED); emptyText.setPadding(0, dp(12), 0, dp(20)); empty.addView(emptyText);
            Button scan = new Button(this); scan.setText(UiText.get(R.string.ui_scan_new_docs)); scan.setTextColor(BRAND); scan.setTextSize(14); scan.setAllCaps(false);
            android.graphics.drawable.GradientDrawable outline = new android.graphics.drawable.GradientDrawable(); outline.setColor(AppTheme.surface()); outline.setCornerRadius(dp(6)); outline.setStroke(dp(1), BRAND); scan.setBackground(outline);
            LinearLayout.LayoutParams scanParams = new LinearLayout.LayoutParams(dp(168), dp(44)); empty.addView(scan, scanParams); scan.setOnClickListener(v -> startScan()); content.addView(empty);
    }

    private String folderTitle(){if(folderId==0)return "All Documents";try(Cursor c=store.db().rawQuery("SELECT name FROM folders WHERE id=?",new String[]{Long.toString(folderId)})){return c.moveToFirst()?c.getString(0):"Documents";}}
    private void chooseSort(){String[] labels={"Recently opened","Newest first","Oldest first","Name A–Z","Name Z–A"};String[] orders={"last_opened DESC, created DESC","created DESC","created ASC","name COLLATE NOCASE ASC","name COLLATE NOCASE DESC"};new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_sort_documents)).setItems(UiText.labels(labels),(d,w)->{sortOrder=orders[w];getPreferences(0).edit().putString("library_sort",sortOrder).apply();renderAllDocuments();}).show();}
    private void folderOptions(){if(!FolderLock.ensureFolder(this,store,folderId,this::folderOptions))return;new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_folders)).setItems(new String[]{UiText.get(R.string.ui_create_folder_here), UiText.get(R.string.ui_all_documents)},(d,w)->{if(w==1){folderId=0;trashView=false;renderAllDocuments();return;}EditText input=new EditText(this);input.setSingleLine();new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_new_folder)).setView(input).setNegativeButton(UiText.get(R.string.ui_cancel),null).setPositiveButton(UiText.get(R.string.ui_create),(x,y)->{String name=input.getText().toString().trim();if(!name.isEmpty()){ContentValues v=new ContentValues();v.put("name",name);v.put("parent",folderId);store.db().insertOrThrow("folders",null,v);trashView=false;renderAllDocuments();}}).show();}).show();}
    private void editFolder(long id,String name){new AlertDialog.Builder(this).setTitle(name).setItems(new String[]{UiText.get(R.string.ui_rename), UiText.get(R.string.ui_remove_empty_folder), UiText.get(R.string.ui_folder_protection), UiText.get(R.string.ui_move_folder)},(d,w)->{if(w==3){chooseFolderDestination(id);return;}if(w==2){d.dismiss();getWindow().getDecorView().post(()->{if(!isFinishing()&&!isDestroyed())FolderLock.configure(this,id,this::renderAllDocuments);});return;}if(!FolderLock.ensureFolder(this,store,id,()->editFolder(id,name)))return;if(w==0){EditText input=new EditText(this);input.setText(name);new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_rename_folder)).setView(input).setNegativeButton(UiText.get(R.string.ui_cancel),null).setPositiveButton(UiText.get(R.string.ui_save),(x,y)->{if(!input.getText().toString().trim().isEmpty()){ContentValues v=new ContentValues();v.put("name",input.getText().toString().trim());store.db().update("folders",v,"id=?",new String[]{Long.toString(id)});renderAllDocuments();}}).show();}else{try(Cursor c=store.db().rawQuery("SELECT (SELECT COUNT(*) FROM docs WHERE folder_id=?)+(SELECT COUNT(*) FROM folders WHERE parent=?)+(SELECT COUNT(*) FROM document_guards WHERE folder_id=?)",new String[]{Long.toString(id),Long.toString(id),Long.toString(id)})){if(c.moveToFirst()&&c.getLong(0)==0){store.db().delete("folders","id=?",new String[]{Long.toString(id)});renderAllDocuments();}else Toast.makeText(this,UiText.get(R.string.ui_move_the_folder_s_documents_and_subfolders_first),Toast.LENGTH_LONG).show();}}}).show();}
    private void chooseFolderDestination(long id){
        if(!FolderLock.ensureFolder(this,store,id,()->chooseFolderDestination(id)))return;
        java.util.Set<Long> excluded=FolderMoves.descendants(store,id);ArrayList<Long> ids=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();ids.add(0L);labels.add(UiText.get(R.string.ui_all_documents));
        try(Cursor c=store.db().rawQuery("SELECT id FROM folders ORDER BY name COLLATE NOCASE",null)){while(c.moveToNext())if(!excluded.contains(Long.valueOf(c.getLong(0)))){ids.add(c.getLong(0));labels.add(folderPath(c.getLong(0)));}}
        new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_move_folder_to)).setItems(labels.toArray(new String[0]),(d,w)->moveFolderTo(id,ids.get(w))).setNegativeButton(UiText.get(R.string.ui_cancel),null).show();
    }
    private void moveFolderTo(long id,long target){
        if(!FolderLock.ensureFolder(this,store,id,()->moveFolderTo(id,target))||!FolderLock.ensureFolder(this,store,target,()->moveFolderTo(id,target)))return;
        try{FolderMoves.move(this,store,id,target);selectedDocuments.clear();renderAllDocuments();indexLibrary();}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private void moveDocumentsTo(java.util.List<Long> ids,long target){
        for(Long id:ids)try(Cursor row=store.db().rawQuery("SELECT path FROM docs WHERE id=?",new String[]{Long.toString(id)})){if(row.moveToFirst()&&!FolderLock.ensureDocument(this,row.getString(0),()->moveDocumentsTo(ids,target)))return;}
        if(!FolderLock.ensureFolder(this,store,target,()->moveDocumentsTo(ids,target)))return;
        ContentValues values=new ContentValues();values.put("folder_id",target);
        if(!FolderLock.ancestors(this,store,target).isEmpty()){values.put("search_text","");values.put("search_meta","");values.put("indexed_mtime",0);}
        store.db().beginTransaction();try{for(Long id:ids)store.db().update("docs",values,"id=? AND deleted_at=0",new String[]{Long.toString(id)});store.db().setTransactionSuccessful();}finally{store.db().endTransaction();}selectedDocuments.clear();renderAllDocuments();
    }
    private void moveDocuments(java.util.List<Long> ids){ArrayList<String> names=new ArrayList<>();ArrayList<Long> folders=new ArrayList<>();names.add(UiText.get(R.string.ui_all_documents));folders.add(0L);try(Cursor c=store.db().rawQuery("SELECT id,name FROM folders ORDER BY name COLLATE NOCASE",null)){while(c.moveToNext()){folders.add(c.getLong(0));names.add(folderPath(c.getLong(0)));}}new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_move_to_folder)).setItems(names.toArray(new String[0]),(d,w)->{moveDocumentsTo(ids,folders.get(w));}).show();}
    private String folderPath(long id){ArrayList<String> parts=new ArrayList<>();for(int depth=0;id!=0&&depth<64;depth++){try(Cursor c=store.db().rawQuery("SELECT name,parent FROM folders WHERE id=?",new String[]{Long.toString(id)})){if(!c.moveToFirst())break;parts.add(0,c.getString(0));id=c.getLong(1);}}return android.text.TextUtils.join(" / ",parts);}
    private void trashActions(long id,String name,String path){new AlertDialog.Builder(this).setTitle(name).setItems(new String[]{UiText.get(R.string.ui_restore), UiText.get(R.string.ui_delete_permanently)},(d,w)->{if(w==0){ContentValues v=new ContentValues();v.put("deleted_at",0);store.db().update("docs",v,"id=?",new String[]{Long.toString(id)});renderAllDocuments();}else new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_delete_permanently_afd1aa16)).setMessage(UiText.get(R.string.ui_this_cannot_be_undone)).setNegativeButton(UiText.get(R.string.ui_cancel),null).setPositiveButton(UiText.get(R.string.ui_delete),(x,y)->{File file=new File(path);if(file.exists()&&!file.delete()){Toast.makeText(this,UiText.get(R.string.ui_could_not_remove_the_file),Toast.LENGTH_LONG).show();return;}ScanArchive.remove(this,path);store.db().delete("docs","id=?",new String[]{Long.toString(id)});store.db().delete("document_guards","path=?",new String[]{path});renderAllDocuments();}).show();}).show();}
    private void chooseCloudDocuments(){ArrayList<String> names=new ArrayList<>(),paths=new ArrayList<>();try(Cursor c=store.db().rawQuery("SELECT name,path FROM docs WHERE deleted_at=0 AND "+FolderLock.visibleSql(this,store)+" ORDER BY last_opened DESC",null)){while(c.moveToNext()){names.add(c.getString(0));paths.add(c.getString(1));}}if(paths.isEmpty()){Toast.makeText(this,UiText.get(R.string.ui_scan_or_import_a_document_first),Toast.LENGTH_LONG).show();return;}boolean[] checked=new boolean[paths.size()];new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_save_to_cloud)).setMultiChoiceItems(names.toArray(new String[0]),checked,(d,w,value)->checked[w]=value).setNegativeButton(UiText.get(R.string.ui_cancel),null).setPositiveButton(UiText.get(R.string.ui_choose_destination),(d,w)->{ArrayList<String> chosen=new ArrayList<>(),titles=new ArrayList<>();for(int i=0;i<checked.length;i++)if(checked[i]){chosen.add(paths.get(i));titles.add(names.get(i));}if(!chosen.isEmpty())startCloudExport(chosen,titles);else Toast.makeText(this,UiText.get(R.string.ui_select_at_least_one_document),Toast.LENGTH_SHORT).show();}).show();}
    private void startCloudExport(ArrayList<String> paths,ArrayList<String> names){startActivity(new Intent(this,CloudExportActivity.class).putStringArrayListExtra("paths",paths).putStringArrayListExtra("names",names));}
    private void addDocumentCard(long id, String name, String path, int pages, long created, boolean favorite) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(0, dp(8), 0, dp(10)); card.setBackgroundColor(AppTheme.surface());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(8); content.addView(card, lp);

        LinearLayout details = new LinearLayout(this); details.setGravity(Gravity.TOP); card.addView(details, new LinearLayout.LayoutParams(-1, dp(64)));
        ImageView preview = new ImageView(this); preview.setScaleType(ImageView.ScaleType.CENTER_CROP); preview.setBackgroundColor(Color.rgb(247, 248, 248)); preview.setTag(path);
        details.addView(preview, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView title = new TextView(this); title.setText(name); title.setTextSize(15); title.setTextColor(INK); title.setTypeface(null, Typeface.BOLD); title.setMaxLines(2); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout metadata=new LinearLayout(this);metadata.setGravity(Gravity.CENTER_VERTICAL);metadata.setPadding(0,dp(4),0,0);TextView date=new TextView(this);date.setText(new java.text.SimpleDateFormat("MM/dd/yyyy HH:mm",java.util.Locale.US).format(new Date(created)));date.setTextSize(12);date.setTextColor(MUTED);metadata.addView(date);View dateDivider=new View(this);dateDivider.setBackgroundColor(AppTheme.border());LinearLayout.LayoutParams dividerLp=new LinearLayout.LayoutParams(dp(1),dp(10));dividerLp.setMargins(dp(7),0,dp(7),0);metadata.addView(dateDivider,dividerLp);ImageView pageIcon=iconView(pages==0?"Lock":"Page Count",MUTED,0);metadata.addView(pageIcon,new LinearLayout.LayoutParams(dp(12),dp(12)));TextView count=new TextView(this);count.setText(pages==0 ? UiText.get(R.string.ui_locked_pdf) : String.valueOf(pages));count.setTextSize(12);count.setTextColor(MUTED);metadata.addView(count);
        LinearLayout textColumn = new LinearLayout(this); textColumn.setOrientation(LinearLayout.VERTICAL); textColumn.setGravity(Gravity.TOP); textColumn.setPadding(dp(12), 0, dp(4), 0);
        textColumn.addView(title); textColumn.addView(metadata); details.addView(textColumn, new LinearLayout.LayoutParams(0, -1, 1));
        CheckBox selection = new CheckBox(this); selection.setButtonTintList(android.content.res.ColorStateList.valueOf(Color.rgb(220, 222, 222))); selection.setPadding(0, 0, 0, 0); details.addView(selection, new LinearLayout.LayoutParams(dp(24), dp(40)));
        selection.setContentDescription(UiText.format(R.string.ui_select_value, name));selection.setChecked(selectedDocuments.containsKey(Long.valueOf(id)));selection.setOnCheckedChangeListener((v,checked)->{if(checked)selectedDocuments.put(id,path);else selectedDocuments.remove(Long.valueOf(id));updateSelectionBar();});
        preview.setOnClickListener(v->openPdf(path));textColumn.setOnClickListener(v->openPdf(path));

        final int version=libraryGeneration.get();
        thumbnailWorker.execute(()->{
            if(version!=libraryGeneration.get()||isDestroyed())return;
            String key=path+"|"+new File(path).lastModified()+"|"+dp(144);
            Bitmap cached=libraryThumbnails.get(key);Bitmap bitmap=cached==null?renderPdfThumbnail(path,dp(144),dp(144)):cached;
            if(bitmap==null)return;
            runOnUiThread(()->{if(version==libraryGeneration.get()&&!isDestroyed()&&preview.isAttachedToWindow()){libraryThumbnails.put(key,bitmap);preview.setImageBitmap(bitmap);}else if(cached==null)bitmap.recycle();});
        });

        LinearLayout actions = new LinearLayout(this); actions.setGravity(Gravity.CENTER); LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, dp(48)); actionParams.topMargin = dp(16); card.addView(actions, actionParams);
        TextView share = recentAction(UiText.get(R.string.ui_share)); TextView toWord = recentAction(UiText.get(R.string.ui_to_word)); TextView open = recentAction(UiText.get(R.string.ui_view));share.setCompoundDrawables(null,null,null,null);open.setCompoundDrawables(null,null,null,null);setIcon(toWord,"Conversion",BRAND,18,false);for(TextView action:new TextView[]{share,toWord,open}){action.setTextSize(13);action.setTextColor(INK);}
        actions.addView(share, new LinearLayout.LayoutParams(0, -1, 1));
        LinearLayout.LayoutParams middle = new LinearLayout.LayoutParams(0, -1, 1); middle.leftMargin = dp(16); middle.rightMargin = dp(16); actions.addView(toWord, middle);
        actions.addView(open, new LinearLayout.LayoutParams(0, -1, 1));
        share.setOnClickListener(v -> sharePdf(path)); open.setOnClickListener(v -> openPdf(path)); toWord.setOnClickListener(v -> beginWordConversion(path, name));

        View divider = new View(this); divider.setBackgroundColor(Color.rgb(238, 239, 239)); LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1)); dividerParams.topMargin = dp(10); card.addView(divider, dividerParams);
        card.setOnLongClickListener(v -> { showDocumentMenu(id, name, path, favorite); return true; });
    }

    private Bitmap renderPdfThumbnail(String path, int targetWidth, int targetHeight) {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(fd);
             PdfRenderer.Page page = renderer.openPage(0)) {
            float scale = Math.min((float) targetWidth / page.getWidth(), (float) targetHeight / page.getHeight());
            int width = Math.max(1, Math.round(page.getWidth() * scale));
            int height = Math.max(1, Math.round(page.getHeight() * scale));
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        } catch (Exception ignored) { return null; }
    }

    private TextView recentAction(String text) {
        TextView action = new TextView(this); action.setText(text); setIcon(action, text, text.equals("To Word") ? BRAND : INK, 18, false); action.setPadding(dp(10),0,dp(10),0); action.setTextSize(14); action.setTextColor(INK); action.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable(); shape.setColor(AppTheme.soft()); shape.setCornerRadius(dp(6)); action.setBackground(shape);AppTheme.polishAction(action);
        return action;
    }

    private void showDocumentMenu(long id, String name, String path, boolean favorite) {
        String[] actions = {favorite ? "Remove from favorites" : "Add to favorites", "Rename", "Move to trash", "Move to folder", "Save to cloud"};
        new AlertDialog.Builder(this).setTitle(name).setItems(UiText.labels(actions), (dialog, which) -> {
            if (which == 0) { ContentValues values = new ContentValues(); values.put("favorite", favorite ? 0 : 1); store.db().update("docs", values, "id=?", new String[]{String.valueOf(id)}); renderHome(); }
            else if (which == 1) renameDocument(id, name);
            else if (which == 2) {store.trash(id);selectedDocuments.remove(Long.valueOf(id));if(libraryVisible)renderAllDocuments();else renderHome();}
            else if(which==3)moveDocuments(java.util.Collections.singletonList(id));
            else if(which==4)startCloudExport(new ArrayList<>(java.util.Collections.singletonList(path)),new ArrayList<>(java.util.Collections.singletonList(name)));
        }).show();
    }
    private void showSearchDialog() {
        EditText input = new EditText(this); input.setSingleLine(true); input.setHint(UiText.get(R.string.ui_text)); input.setText(searchQuery);
        new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_text_904bb000)).setView(input)
                .setNegativeButton(UiText.get(R.string.ui_text_65ff4130), null).setPositiveButton(UiText.get(R.string.ui_text_bd46c2b2), (dialog, which) -> { searchQuery = input.getText().toString().trim(); renderHome(); }).show();
    }

    private void renameDocument(long id, String currentName) {
        EditText input = new EditText(this); input.setSingleLine(true); input.setText(currentName); input.selectAll();
        new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_text_519f5578)).setView(input)
                .setNegativeButton(UiText.get(R.string.ui_text_65ff4130), null).setPositiveButton(UiText.get(R.string.ui_text_886b95e5), (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) { ContentValues values = new ContentValues(); values.put("name", name); store.db().update("docs", values, "id=?", new String[]{String.valueOf(id)}); renderHome(); }
                }).show();
    }

    private void importDocument(Uri uri){String type=getContentResolver().getType(uri);if(type!=null&&type.startsWith("application/vnd.openxmlformats-officedocument.")){startActivity(new Intent(this,ExtendedToolsActivity.class).putExtra("operation","Office to PDF").setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));return;}String filename="";try(Cursor c=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())filename=c.getString(0);}if(filename.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(docx|xlsx|pptx)$")){startActivity(new Intent(this,ExtendedToolsActivity.class).putExtra("operation","Office to PDF").setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));return;}importPdf(uri);}
    private void chooseImageImport(ArrayList<String> pages){ScanImportOptions.show(this,(crop,filter)->{Intent editor=editorIntent(pages,"Batch",true,false);editor.putExtra("initial_filter",filter).putExtra("auto_detect",crop);startActivity(editor);},this::clearPendingAppend);}
    private void importPdf(Uri uri) {
        android.app.ProgressDialog dialog=android.app.ProgressDialog.show(this,"",UiText.get(R.string.ui_importing_pdf),true,false);
        pdfWorker.execute(()->{File dest=null;try{File dir=new File(getFilesDir(),"documents");if(!dir.exists()&&!dir.mkdirs())throw new java.io.IOException(UiText.get(R.string.ui_storage_unavailable));String filename="Imported document";try(Cursor cursor=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())filename=cursor.getString(0).replaceFirst("(?i)\\.pdf$","");}dest=new File(dir,"paperflow-import-"+System.currentTimeMillis()+".pdf");try(InputStream in=getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(dest)){if(in==null)throw new java.io.IOException(UiText.get(R.string.ui_no_pdf_data));byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}int count;try(PDDocument document=PDDocument.load(dest)){count=document.getNumberOfPages();if(count<1)throw new java.io.IOException(UiText.get(R.string.ui_pdf_has_no_pages));}catch(com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException encrypted){count=0;}File result=dest;String title=filename;int total=count;runOnUiThread(()->{dialog.dismiss();if(isDestroyed())return;registerDocument(result.getAbsolutePath(),title,total);renderHome();openPdf(result.getAbsolutePath());});}catch(Exception e){if(dest!=null)dest.delete();runOnUiThread(()->{dialog.dismiss();Toast.makeText(this,UiText.format(R.string.ui_could_not_import_pdf_value, e.getMessage()),Toast.LENGTH_LONG).show();});}});
    }
    private int pageCount(File pdf) {
        try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(fd)) { return renderer.getPageCount(); }
        catch (Exception ignored) { return 1; }
    }

    private void renderTools() {
        libraryVisible=false;selectNavigation("Tools");
        content.removeAllViews(); content.setPadding(dp(16), dp(18), dp(16), dp(28));content.setClipToPadding(false);
        String[] categoryNames={"Scan","Import","Convert","Edit","Utilities"};
        String[][] categoryTools={{"ID Cards","ID Photo Maker","Photo Translation","Book","Slides","Whiteboard","Timestamp"},{"Import Images","Import Files"},{"To Word","To Excel","To PowerPoint","Office to PDF","OCR PDF","Batch OCR","PDF to Images","PDF to Long Image","Collage"},{"Sign","Add Watermark","Erase Marks","Merge Files","Extract PDF Pages","Reorder Pages","Lock","Remove PDF password","Compress","Rotate PDF"},{"Print","Scan Code"}};
        ArrayList<String> allTools=new ArrayList<>();for(String[] category:categoryTools)java.util.Collections.addAll(allTools,category);
        LinearLayout intro=new LinearLayout(this);intro.setOrientation(LinearLayout.VERTICAL);intro.setPadding(dp(16),dp(18),dp(16),dp(16));intro.setBackgroundColor(AppTheme.background());LinearLayout.LayoutParams introLp=new LinearLayout.LayoutParams(-1,-2);introLp.setMargins(0,0,0,0);content.addView(intro,introLp);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.TOP);TextView heading=new TextView(this);heading.setText(UiText.get(R.string.ui_tools));heading.setTextSize(28);heading.setIncludeFontPadding(false);heading.setTypeface(null,Typeface.BOLD);heading.setTextColor(AppTheme.ink());header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        FrameLayout search=new FrameLayout(this);search.setContentDescription(UiText.get(R.string.ui_search_tools));search.setClickable(true);search.setFocusable(true);ImageView searchIcon=iconView("Search",AppTheme.ink(),0);search.addView(searchIcon,new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.END|Gravity.CENTER_VERTICAL));header.addView(search,new LinearLayout.LayoutParams(dp(40),dp(32)));search.setOnClickListener(v->showToolSearch(allTools));intro.addView(header,new LinearLayout.LayoutParams(-1,dp(49)));
        FrameLayout banner=new FrameLayout(this);android.graphics.drawable.GradientDrawable bannerBg=new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,new int[]{AppTheme.soft(),AppTheme.surface()});bannerBg.setCornerRadius(dp(8));banner.setBackground(bannerBg);
        ImageView illustration=iconView("Smart Scan",AppTheme.accent(),0);illustration.setAlpha(.12f);FrameLayout.LayoutParams artLp=new FrameLayout.LayoutParams(dp(78),dp(78),Gravity.END|Gravity.CENTER_VERTICAL);artLp.rightMargin=dp(14);banner.addView(illustration,artLp);
        LinearLayout bannerText=new LinearLayout(this);bannerText.setOrientation(LinearLayout.VERTICAL);bannerText.setGravity(Gravity.CENTER_VERTICAL);bannerText.setPadding(dp(18),dp(20),dp(16),dp(20));TextView bannerTitle=new TextView(this);bannerTitle.setText(UiText.get(R.string.app_name));bannerTitle.setTextSize(25);bannerTitle.setTypeface(null,Typeface.BOLD);bannerTitle.setTextColor(AppTheme.ink());bannerTitle.setIncludeFontPadding(false);bannerText.addView(bannerTitle);TextView bannerSubtitle=new TextView(this);bannerSubtitle.setText(UiText.get(R.string.ui_smart_document_workflows_for_work_and_life));bannerSubtitle.setTextSize(14);bannerSubtitle.setTextColor(AppTheme.ink());bannerSubtitle.setIncludeFontPadding(false);LinearLayout.LayoutParams subtitleLp=new LinearLayout.LayoutParams(-1,-2);subtitleLp.topMargin=dp(6);bannerText.addView(bannerSubtitle,subtitleLp);banner.addView(bannerText,new FrameLayout.LayoutParams(-1,-2));banner.setMinimumHeight(dp(106));intro.addView(banner,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout sections=new LinearLayout(this);sections.setOrientation(LinearLayout.VERTICAL);sections.setPadding(dp(16),dp(20),dp(16),dp(24));android.graphics.drawable.GradientDrawable panel=new android.graphics.drawable.GradientDrawable();panel.setColor(AppTheme.surface());panel.setCornerRadii(new float[]{dp(8),dp(8),dp(8),dp(8),0,0,0,0});sections.setBackground(panel);LinearLayout.LayoutParams panelLp=new LinearLayout.LayoutParams(-1,-2);panelLp.setMargins(0,0,0,0);content.addView(sections,panelLp);
        for(int i=0;i<categoryNames.length;i++)addToolSection(sections,categoryNames[i],categoryTools[i]);
        if(mainScroll!=null)mainScroll.post(()->mainScroll.scrollTo(0,0));
    }
    private void addToolSection(LinearLayout parent,String section,String[] tools) {
        TextView heading=new TextView(this);heading.setText(UiText.label(section));heading.setTextSize(17);heading.setTextColor(AppTheme.ink());heading.setTypeface(null,Typeface.BOLD);heading.setIncludeFontPadding(false);heading.setPadding(dp(4),0,0,0);LinearLayout.LayoutParams headingLp=new LinearLayout.LayoutParams(-1,-2);if(parent.getChildCount()>0)headingLp.topMargin=dp(20);parent.addView(heading,headingLp);
        android.graphics.Paint captionMeasure=new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);captionMeasure.setTextSize(12*getResources().getDisplayMetrics().scaledDensity);int columns=getResources().getConfiguration().screenWidthDp<360||getResources().getConfiguration().fontScale>1.15f?3:4;
        for (int offset=0; offset<tools.length; offset+=columns) {
            LinearLayout row = new LinearLayout(this);
            parent.addView(row,new LinearLayout.LayoutParams(-1,-2));
            for (int col=0; col<columns; col++) {
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL); cell.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);
                cell.setMinimumHeight(dp(96));cell.setPadding(dp(2),0,dp(2),dp(12));row.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
                if (offset+col >= tools.length) continue;
                String name = tools[offset+col]; int color = iconColor(name);
                cell.setContentDescription(UiText.label(name));ImageView icon = iconView(name, color, 10);
                android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
                shape.setShape(android.graphics.drawable.GradientDrawable.OVAL); shape.setColor(toolIconBackground(name));
                icon.setBackground(shape);LinearLayout.LayoutParams iconLp=new LinearLayout.LayoutParams(dp(44),dp(44));iconLp.topMargin=dp(16);cell.addView(icon,iconLp);
                TextView caption = new TextView(this); caption.setText(UiText.label(name)); caption.setTextSize(12); caption.setTextColor(AppTheme.ink());caption.setIncludeFontPadding(false);caption.setMaxLines(2);caption.setEllipsize(android.text.TextUtils.TruncateAt.END);
                caption.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL); caption.setPadding(dp(2),0,dp(2),0);
                LinearLayout.LayoutParams captionLp=new LinearLayout.LayoutParams(-1,-2);captionLp.topMargin=dp(4);cell.addView(caption,captionLp);cell.setOnClickListener(v -> handleTool(name));
            }
        }
    }
    private int iconColor(String name) { return BRAND; }
    private int toolIconBackground(String name){return AppTheme.soft();}
    static final class ToolEntry{final String id;ToolEntry(String id){this.id=id;}@Override public String toString(){return UiText.label(id);}}
    private void showToolSearch(ArrayList<String> tools){EditText query=new EditText(this);query.setSingleLine();query.setHint(UiText.get(R.string.ui_search_tools));android.widget.ListView matches=new android.widget.ListView(this);ArrayList<ToolEntry> entries=new ArrayList<>();for(String id:tools)entries.add(new ToolEntry(id));android.widget.ArrayAdapter<ToolEntry> adapter=new android.widget.ArrayAdapter<>(this,android.R.layout.simple_list_item_1,entries);matches.setAdapter(adapter);LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(dp(16),dp(8),dp(16),0);fields.addView(query,new LinearLayout.LayoutParams(-1,dp(48)));fields.addView(matches,new LinearLayout.LayoutParams(-1,dp(320)));AlertDialog dialog=new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_search_tools)).setView(fields).setNegativeButton(UiText.get(R.string.ui_close),null).create();query.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){adapter.getFilter().filter(s);}public void afterTextChanged(android.text.Editable e){}});matches.setOnItemClickListener((parent,view,position,id)->{ToolEntry tool=adapter.getItem(position);if(tool!=null){dialog.dismiss();handleTool(tool.id);}});dialog.show();}
    private void handleTool(String name){
        switch(name){
            case "To Excel":case "To PowerPoint":case "Office to PDF":case "Batch OCR":case "Collage":startActivity(new Intent(this,ExtendedToolsActivity.class).putExtra("operation",name));break;
            case "ID Cards": startScan("ID Card"); break;
            case "Book": case "Slides": case "Whiteboard": case "Timestamp": startScan(name); break;
            case "OCR Languages": startActivity(new Intent(this,LanguageManagerActivity.class).putExtra("kind","ocr")); break;
            case "Translation Languages": startActivity(new Intent(this,LanguageManagerActivity.class).putExtra("kind","translation")); break;
            case "Photo Translation": startActivity(new Intent(this,LocalTranslationActivity.class)); break;
            case "ID Photo Maker": startActivity(new Intent(this,IdPhotoMakerActivity.class)); break;
            case "Erase Marks": startActivity(new Intent(this,EraseMarksActivity.class)); break;
            case "Import Images": imagePicker.launch(new String[]{"image/*"}); break;
            case "Import Files": filePicker.launch(new String[]{"application/pdf","application/vnd.openxmlformats-officedocument.wordprocessingml.document","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet","application/vnd.openxmlformats-officedocument.presentationml.presentation"}); break;
            case "OCR PDF": case "To Word": launchTool(name); break;
            case "PDF to Images": launchTool(name); break;
            case "PDF to Long Image": launchTool(name); break;
            case "Print": launchTool(name); break;
            case "Scan Code": startActivity(new Intent(this,ScanCodeActivity.class)); break;
            case "Remove PDF password": case "Sign": case "Rotate PDF": case "Add Watermark": case "Merge Files": case "Extract PDF Pages": case "Reorder Pages": case "Lock": case "Compress": launchTool(name); break;
        }
    }
    private void preparePdfToWord(Uri uri){
        try{File directory=new File(getFilesDir(),"documents");if(!directory.exists()&&!directory.mkdirs())throw new IllegalStateException();File copy=new File(directory,"paperflow-word-source-"+System.currentTimeMillis()+".pdf");try(InputStream in=getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(copy)){if(in==null)throw new IllegalStateException();byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}beginWordConversion(copy.getAbsolutePath(),"Document");}
        catch(Exception e){Toast.makeText(this,UiText.get(R.string.ui_could_not_open_pdf),Toast.LENGTH_LONG).show();}
    }
    private void authenticateGoogleSettings(CloudRequest request) {
        android.app.KeyguardManager keyguard=getSystemService(android.app.KeyguardManager.class);
        if(keyguard==null||!keyguard.isDeviceSecure()){
            Toast.makeText(this,UiText.get(R.string.ui_cloud_requires_device_lock),Toast.LENGTH_LONG).show();return;
        }
        Intent confirm=keyguard.createConfirmDeviceCredentialIntent(UiText.get(R.string.ui_google_api_settings),UiText.get(R.string.ui_cloud_unlock_reason));
        if(confirm==null)return;
        wordState.pendingCloud=request;
        try{googleUnlockLauncher.launch(confirm);}catch(RuntimeException unavailable){wordState.pendingCloud=null;Toast.makeText(this,UiText.get(R.string.ui_could_not_read_google_api_settings),Toast.LENGTH_LONG).show();}
    }
    private void finishGoogleUnlock(int resultCode){
        CloudRequest request=wordState.pendingCloud;wordState.pendingCloud=null;
        if(resultCode!=RESULT_OK||request==null||isFinishing()||isDestroyed())return;
        if(request.key!=null)promptGoogleApiKey(request);else chooseCloudWordRoute(request);
    }
    private void promptGoogleApiKey(CloudRequest request) {
        EditText keyInput = new EditText(this); keyInput.setSingleLine(true); keyInput.setHint(UiText.get(R.string.ui_google_cloud_api_key)); keyInput.setInputType(129);if(request.key!=null)keyInput.setText(request.key);
        LinearLayout fields = new LinearLayout(this); fields.setOrientation(LinearLayout.VERTICAL); fields.setPadding(dp(20), dp(8), dp(20), dp(4)); fields.addView(keyInput);
        AlertDialog settingsDialog=new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_google_api_settings)).setMessage(UiText.get(R.string.ui_google_cloud_vision_is_available_for_the_optional_cloud_word_ocr_route_enab)).setView(fields).setNegativeButton(UiText.get(R.string.ui_cancel), null).setPositiveButton(UiText.get(R.string.ui_save),null).create();settingsDialog.setOnShowListener(shown->settingsDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->{
            String key = keyInput.getText().toString().trim();
            if(key.isEmpty()){keyInput.setError(UiText.get(R.string.ui_enter_your_google_cloud_api_key));return;}
            saveGoogleSettings(key,settingsDialog,request);
        }));settingsDialog.show();if(settingsDialog.getWindow()!=null)settingsDialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
    }
    private void saveGoogleSettings(String key,AlertDialog dialog,CloudRequest request){
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        pdfWorker.execute(()->{
            try{writeGoogleSettings(key,"en");runOnUiThread(()->{if(isDestroyed())return;dialog.dismiss();chooseCloudWordRoute(new CloudRequest(request.path,request.name,request.password,null));});}
            catch(Exception error){runOnUiThread(()->{
                if(isDestroyed())return;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                if(error instanceof android.security.keystore.UserNotAuthenticatedException){dialog.dismiss();authenticateGoogleSettings(new CloudRequest(request.path,request.name,request.password,key));}
                else Toast.makeText(this,UiText.get(R.string.ui_could_not_save_google_cloud_settings),Toast.LENGTH_LONG).show();
            });}
        });
    }
    static KeyGenParameterSpec googleKeySpec(String alias) {
        KeyGenParameterSpec.Builder builder=new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true);
        if(android.os.Build.VERSION.SDK_INT>=30)builder.setUserAuthenticationParameters(300,KeyProperties.AUTH_DEVICE_CREDENTIAL|KeyProperties.AUTH_BIOMETRIC_STRONG);
        else builder.setUserAuthenticationValidityDurationSeconds(300);
        return builder.build();
    }
    private SecretKey googleSettingsKey() throws Exception {
        java.security.KeyStore keyStore=java.security.KeyStore.getInstance("AndroidKeyStore");keyStore.load(null);
        java.security.Key existing=keyStore.getKey("paperflow_google_translation_auth_v2",null);
        if(existing instanceof SecretKey)return (SecretKey)existing;
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(googleKeySpec("paperflow_google_translation_auth_v2"));return generator.generateKey();
    }
    private void writeGoogleSettings(String apiKey,String target)throws Exception {
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        try{cipher.init(Cipher.ENCRYPT_MODE,googleSettingsKey());}
        catch(android.security.keystore.KeyPermanentlyInvalidatedException invalidated){
            java.security.KeyStore keyStore=java.security.KeyStore.getInstance("AndroidKeyStore");keyStore.load(null);
            keyStore.deleteEntry("paperflow_google_translation_auth_v2");cipher.init(Cipher.ENCRYPT_MODE,googleSettingsKey());
        }
        byte[] encrypted=cipher.doFinal((apiKey+"\n"+target).getBytes(StandardCharsets.UTF_8));
        if(!getSharedPreferences("google_translation",MODE_PRIVATE).edit()
                .putString("iv",android.util.Base64.encodeToString(cipher.getIV(),android.util.Base64.NO_WRAP))
                .putString("value",android.util.Base64.encodeToString(encrypted,android.util.Base64.NO_WRAP)).putInt("key_version",2).commit())throw new java.io.IOException("Could not persist Cloud settings");
        java.security.KeyStore keyStore=java.security.KeyStore.getInstance("AndroidKeyStore");keyStore.load(null);
        keyStore.deleteEntry("paperflow_google_translation");
    }
    private String[] readGoogleSettings()throws Exception {
        android.content.SharedPreferences prefs=getSharedPreferences("google_translation",MODE_PRIVATE);
        String iv=prefs.getString("iv",null),value=prefs.getString("value",null);
        if(iv==null||value==null)return new String[]{"","en"};
        boolean legacy=prefs.getInt("key_version",1)<2;
        java.security.Key key;
        if(legacy){java.security.KeyStore keyStore=java.security.KeyStore.getInstance("AndroidKeyStore");keyStore.load(null);key=keyStore.getKey("paperflow_google_translation",null);}
        else key=googleSettingsKey();
        if(key==null)throw new java.security.KeyStoreException("Cloud settings key unavailable");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,android.util.Base64.decode(iv,android.util.Base64.NO_WRAP)));
        String[] parts=new String(cipher.doFinal(android.util.Base64.decode(value,android.util.Base64.NO_WRAP)),StandardCharsets.UTF_8).split("\\n",2);
        String[] settings={parts[0],parts.length>1?parts[1]:"en"};
        if(legacy)writeGoogleSettings(settings[0],settings[1]);
        return settings;
    }
    private String readGoogleApiKey() throws Exception { return readGoogleSettings()[0]; }
    private void beginWordConversion(String pdfPath, String name) {
        beginWordConversion(pdfPath,name,"");
    }
    private void beginWordConversion(String pdfPath,String name,String password) {
        if(!FolderLock.ensureDocument(this,pdfPath,()->beginWordConversion(pdfPath,name,password)))return;
        if(wordState.running){showWordProgress();return;}
        LinearLayout choices=new LinearLayout(this);choices.setOrientation(LinearLayout.VERTICAL);choices.setPadding(dp(18),dp(8),dp(18),dp(4));TextView explanation=new TextView(this);explanation.setText(UiText.get(R.string.ui_automatic_uses_embedded_pdf_text_including_greek_and_on_device_ocr_for_scan));explanation.setTextSize(13);choices.addView(explanation);android.widget.Button ocrLanguages=new android.widget.Button(this);ocrLanguages.setText(UiText.get(R.string.ui_ocr_languages_84fba977));ocrLanguages.setOnClickListener(v->startActivity(new Intent(this,LanguageManagerActivity.class).putExtra("kind","ocr")));choices.addView(ocrLanguages);
        android.widget.RadioGroup routes=new android.widget.RadioGroup(this);String[] labels={"Automatic · PDF text, then local OCR","PDF text only · includes Greek","On-device OCR · Tesseract","Google Cloud OCR · Greek","Editable page layout · local","Keep page appearance · images"};for(int i=0;i<labels.length;i++){android.widget.RadioButton option=new android.widget.RadioButton(this);option.setId(View.generateViewId());option.setText(UiText.label(labels[i]));option.setTag(i);routes.addView(option);if(i==4)option.setChecked(true);}choices.addView(routes);
        new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_convert_to_word)).setView(choices).setNegativeButton(UiText.get(R.string.ui_cancel),null).setPositiveButton(UiText.get(R.string.ui_convert),(d,w)->{View selected=routes.findViewById(routes.getCheckedRadioButtonId());int route=selected==null?WordConversion.AUTO:(Integer)selected.getTag();chooseWordRoute(pdfPath,name==null?"Document":name,password,route);}).show();
    }
    private void chooseWordRoute(String path,String name,String password,int route){
        if(route!=WordConversion.CLOUD){convertPdfToWord(path,name,password,route,"");return;}
        authenticateGoogleSettings(new CloudRequest(path,name,password,null));
    }
    private void chooseCloudWordRoute(CloudRequest request){
        String path=request.path,name=request.name,password=request.password;
        pdfWorker.execute(()->{
            try{String key=readGoogleApiKey();runOnUiThread(()->{
                if(isDestroyed()||isFinishing())return;
                if(key.isEmpty()){promptGoogleApiKey(request);return;}
                new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_upload_pages_to_google_cloud)).setMessage(UiText.get(R.string.ui_each_pdf_page_will_be_rendered_and_sent_to_google_cloud_vision_for_ocr_incl)).setNegativeButton(UiText.get(R.string.ui_cancel),null).setPositiveButton(UiText.get(R.string.ui_upload_and_convert),(d,w)->convertPdfToWord(path,name,password,WordConversion.CLOUD,key)).show();
            });}catch(Exception error){runOnUiThread(()->{
                if(isDestroyed()||isFinishing())return;
                if(error instanceof android.security.keystore.UserNotAuthenticatedException)authenticateGoogleSettings(new CloudRequest(path,name,password,null));
                else{Toast.makeText(this,UiText.get(R.string.ui_could_not_read_google_api_settings),Toast.LENGTH_LONG).show();promptGoogleApiKey(request);}
            });}
        });
    }
    private void convertPdfToWord(String path,String name,String password,int route,String key){
        if(wordState.running){showWordProgress();return;}wordState.error.setValue(null);wordState.result.setValue(null);wordState.path=path;wordState.name=name;wordState.password=password;wordState.route=route;wordState.orientation=getRequestedOrientation();wordState.running=true;WordConversion.Job job=new WordConversion.Job();wordState.job=job;wordState.progress.setValue(new WordProgress(0,0,"Opening PDF…"));setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED);showWordProgress();
        android.content.Context app=getApplicationContext();WordState task=wordState;task.worker.execute(()->{WordConversion.Result result=null;try{result=WordConversion.convert(app,path,password,route,key,job,(completed,total,message)->task.progress.postValue(new WordProgress(completed,total,message)));job.check();if(task.disposed){result.file.delete();return;}ConversionHistory.add(app,result.file,name+".docx","application/vnd.openxmlformats-officedocument.wordprocessingml.document",java.util.Collections.singletonList(path));task.running=false;task.result.postValue(result);}catch(Exception e){if(result!=null)result.file.delete();task.running=false;if(!task.disposed)task.error.postValue(job.cancelled?new java.util.concurrent.CancellationException("Conversion cancelled"):e);}});
    }
    private void observeWordConversion(){
        wordState.progress.observe(this,progress->{if(wordState.running){showWordProgress();if(progress!=null&&wordProgressDialog!=null){wordProgressDialog.setIndeterminate(progress.total==0);if(progress.total>0){wordProgressDialog.setMax(progress.total);wordProgressDialog.setProgress(progress.completed);}wordProgressDialog.setMessage(wordState.job!=null&&wordState.job.cancelled ? UiText.get(R.string.ui_cancelling) : progress.message);}}});
        wordState.result.observe(this,result->{if(result==null)return;dismissWordProgress();wordExport=result.file;showWordResult(result);setRequestedOrientation(wordState.orientation);});
        wordState.error.observe(this,error->{if(error==null)return;dismissWordProgress();if(error instanceof com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException){showWordPassword();}else{wordState.error.setValue(null);Toast.makeText(this,error instanceof java.util.concurrent.CancellationException ? UiText.get(R.string.ui_conversion_cancelled) : UiText.format(R.string.ui_to_word_failed_value, error.getMessage()),Toast.LENGTH_LONG).show();}setRequestedOrientation(wordState.orientation);});
        if(wordState.running)showWordProgress();
    }
    private void showWordProgress(){
        if(wordProgressDialog!=null&&wordProgressDialog.isShowing())return;if(isFinishing()||isDestroyed()||!wordState.running||wordState.job==null)return;
        final WordConversion.Job job=wordState.job;final android.app.ProgressDialog dialog=new android.app.ProgressDialog(this);wordProgressDialog=dialog;
        dialog.setTitle(UiText.get(R.string.ui_to_word));dialog.setMessage(UiText.get(R.string.ui_opening_pdf));dialog.setProgressStyle(android.app.ProgressDialog.STYLE_HORIZONTAL);dialog.setIndeterminate(true);dialog.setCancelable(false);dialog.setButton(android.content.DialogInterface.BUTTON_NEGATIVE,"Cancel",(d,w)->{});
        dialog.setOnShowListener(shown->{if(wordProgressDialog!=dialog||!dialog.isShowing()||wordState.job!=job||!wordState.running||isFinishing()||isDestroyed())return;Button cancel=dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE);if(cancel==null)return;cancel.setOnClickListener(v->{if(wordProgressDialog!=dialog||!dialog.isShowing()||wordState.job!=job||!wordState.running||isFinishing()||isDestroyed())return;job.cancel();dialog.setMessage(UiText.get(R.string.ui_cancelling));cancel.setEnabled(false);});});dialog.show();
    }
    private void dismissWordProgress(){android.app.ProgressDialog dialog=wordProgressDialog;wordProgressDialog=null;if(dialog!=null)dialog.dismiss();}
    private void showWordResult(WordConversion.Result result){
        if(wordResultDialog!=null&&wordResultDialog.isShowing())return;String name=wordState.name;String detail=UiText.format(R.string.ui_value_docx_value_pages_value_value, name, result.pages, (wordState.route>=4?(wordState.route==4?"editable layout":"page images"):result.nativePages+" from PDF text · "+result.ocrPages+" from OCR"), android.text.format.Formatter.formatFileSize(this,result.file.length()));wordResultDialog=new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_conversion_complete)).setMessage(detail).setNegativeButton(UiText.get(R.string.ui_done),(d,w)->wordState.result.setValue(null)).setNeutralButton(UiText.get(R.string.ui_save_to_device),(d,w)->{wordState.result.setValue(null);wordSaveLauncher.launch(name.replaceAll("[\\\\/:*?\"<>|]","_")+".docx");}).setPositiveButton(UiText.get(R.string.ui_share),(d,w)->{wordState.result.setValue(null);shareWordFile(result.file,name);}).create();wordResultDialog.setOnCancelListener(d->wordState.result.setValue(null));wordResultDialog.show();
    }
    private void showWordPassword(){
        if(wordErrorDialog!=null&&wordErrorDialog.isShowing())return;if(isFinishing()||isDestroyed())return;
        final Throwable failedError=wordState.error.getValue();final WordConversion.Job failedJob=wordState.job;final String path=wordState.path,name=wordState.name,password=wordState.password;final int route=wordState.route;if(failedError==null)return;
        EditText field=new EditText(this);field.setSingleLine();field.setInputType(129);field.setHint(UiText.get(R.string.ui_pdf_password));
        final AlertDialog prompt=new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_password_protected_pdf)).setMessage(password==null||password.isEmpty() ? UiText.get(R.string.ui_enter_the_password_to_convert_this_document) : UiText.get(R.string.ui_incorrect_password_try_again)).setView(field).setNegativeButton(UiText.get(R.string.ui_cancel),null).setPositiveButton(UiText.get(R.string.ui_continue),null).create();wordErrorDialog=prompt;
        prompt.setOnCancelListener(d->{if(wordErrorDialog!=prompt||wordState.job!=failedJob||wordState.error.getValue()!=failedError)return;wordErrorDialog=null;wordState.error.setValue(null);});
        prompt.setOnShowListener(shown->{if(wordErrorDialog!=prompt||!prompt.isShowing()||wordState.job!=failedJob||wordState.error.getValue()!=failedError||isFinishing()||isDestroyed())return;Button confirm=prompt.getButton(AlertDialog.BUTTON_POSITIVE),cancel=prompt.getButton(AlertDialog.BUTTON_NEGATIVE);if(confirm==null||cancel==null)return;
            cancel.setOnClickListener(v->{if(wordErrorDialog!=prompt||!prompt.isShowing()||wordState.job!=failedJob||wordState.error.getValue()!=failedError)return;wordErrorDialog=null;prompt.dismiss();wordState.error.setValue(null);});
            confirm.setOnClickListener(v->{if(wordErrorDialog!=prompt||!prompt.isShowing()||wordState.job!=failedJob||wordState.error.getValue()!=failedError||isFinishing()||isDestroyed())return;if(field.getText().length()==0){field.setError(UiText.get(R.string.ui_enter_the_password));return;}String entered=field.getText().toString();wordErrorDialog=null;prompt.dismiss();wordState.error.setValue(null);chooseWordRoute(path,name,entered,route);});});prompt.show();
    }
    @Override protected void onDestroy(){libraryGeneration.incrementAndGet();libraryWorker.shutdownNow();thumbnailWorker.shutdownNow();libraryThumbnails.evictAll();pdfWorker.execute(store::close);pdfWorker.shutdown();dismissWordProgress();if(wordResultDialog!=null)wordResultDialog.dismiss();if(wordErrorDialog!=null)wordErrorDialog.dismiss();super.onDestroy();}
    private void shareWordFile(File file,String title){Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",file);Intent intent=new Intent(Intent.ACTION_SEND);intent.setType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");intent.putExtra(Intent.EXTRA_STREAM,uri);intent.putExtra(Intent.EXTRA_SUBJECT,title+".docx");intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(intent,UiText.get(R.string.ui_share_word_document)));}
    private void showOpenSourceLicenses(){try{java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();try(java.io.InputStream in=getAssets().open("licenses/THIRD_PARTY_NOTICES.md")){byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);}android.widget.ScrollView scroll=new android.widget.ScrollView(this);TextView text=new TextView(this);text.setText(out.toString("UTF-8"));text.setTextIsSelectable(true);text.setPadding(dp(20),dp(12),dp(20),dp(12));scroll.addView(text);new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_open_source_licenses)).setView(scroll).setPositiveButton(UiText.get(R.string.ui_close),null).show();}catch(Exception e){Toast.makeText(this,UiText.get(R.string.ui_could_not_load_licenses),Toast.LENGTH_LONG).show();}}
    private void renderSettings() {
        libraryVisible=false;selectNavigation("Settings");
        content.removeAllViews();content.setPadding(dp(20),dp(20),dp(20),dp(28));title(content,UiText.get(R.string.ui_settings),28);
        setting(AppTheme.summary(),"Settings",()->AppTheme.show(this));
        settingHelp(UiText.get(R.string.ui_appearance_help));
        setting(AppLanguage.summary(this),"Text",()->AppLanguage.show(this));
        settingHelp(UiText.get(R.string.app_language_help));
        label(content,UiText.get(R.string.ui_paperflow_local_storage),15,MUTED,false).setPadding(0,dp(16),0,0);
        settingHelp(UiText.get(R.string.ui_documents_are_saved_locally_use_save_to_cloud_to_export_copies_to_your_own));
        setting(UiText.get(R.string.ui_offline_languages),"OCR PDF",()->startActivity(new Intent(this,LanguageManagerActivity.class)));
        setting(UiText.get(R.string.ui_lock_folders_now),"Lock",()->{FolderLock.lockAll();renderSettings();});
        setting(UiText.get(R.string.ui_scan_and_export_preferences),"Smart Scan",this::scanPreferences);
        setting(UiText.get(R.string.ui_save_to_cloud),"Cloud",this::chooseCloudDocuments);
        setting(UiText.get(R.string.ui_trash),"Delete",()->{trashView=true;renderAllDocuments();});
        setting(UiText.get(R.string.ui_open_source_licenses),"Page Count",this::showOpenSourceLicenses);
        setting(UiText.get(R.string.ui_conversion_history),"History",()->startActivity(new Intent(this,ConversionHistoryActivity.class)));
        setting(UiText.get(R.string.ui_favorites),"Star",()->{favoritesOnly=true;folderId=0;trashView=false;renderAllDocuments();});
    }
    private void setting(String label,String icon,Runnable action){
        TextView row=new TextView(this);row.setText(label);row.setTextColor(INK);row.setTextSize(15);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(16),dp(12),dp(16),dp(12));row.setMinimumHeight(dp(56));setIcon(row,icon,BRAND,22,false);
        android.graphics.drawable.GradientDrawable shape=new android.graphics.drawable.GradientDrawable();shape.setColor(AppTheme.surface());shape.setCornerRadius(dp(12));shape.setStroke(dp(1),AppTheme.border());row.setBackground(shape);AppTheme.polishAction(row);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(8);content.addView(row,lp);row.setOnClickListener(v->action.run());
    }
    private void settingHelp(String value){TextView help=label(content,value,12,MUTED,false);help.setPadding(dp(4),dp(6),dp(4),dp(12));}
    private void startScan() { startScan(getSharedPreferences("scan_defaults",0).getString("mode","Single")); }
    private void scanPreferences(){android.content.SharedPreferences prefs=getSharedPreferences("scan_defaults",0);new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_scan_and_export_preferences)).setItems(new String[]{UiText.get(R.string.ui_default_capture_mode), UiText.get(R.string.ui_default_filter), UiText.get(R.string.ui_pdf_page_size), UiText.get(R.string.ui_new_document_naming), UiText.get(R.string.ui_default_library_folder), UiText.get(R.string.ui_curved_paper), UiText.get(R.string.ui_automatic_cleanup), UiText.get(R.string.ui_gallery_copies), UiText.get(R.string.ui_open_camera_on_launch)},(d,w)->{
        if(w==7){galleryPreferences();return;}
        if(w==8){new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_open_camera_on_launch)).setSingleChoiceItems(new String[]{UiText.get(R.string.ui_show_home), UiText.get(R.string.ui_open_camera)},prefs.getBoolean("launch_camera",false)?1:0,(x,y)->{prefs.edit().putBoolean("launch_camera",y==1).apply();x.dismiss();}).show();return;}
        if(w==5){new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_curved_paper_paperscan_pro)).setSingleChoiceItems(new String[]{UiText.get(R.string.ui_off), UiText.get(R.string.ui_book_pages), UiText.get(R.string.ui_all_paperscan_pro_pages)},prefs.getInt("dewarp",1),(x,y)->{prefs.edit().putInt("dewarp",y).apply();x.dismiss();}).show();return;}
        if(w==6){boolean[] checked={prefs.getBoolean("edge_fingers",false),prefs.getBoolean("demoire",false)};new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_automatic_cleanup_paperscan_pro)).setMultiChoiceItems(new String[]{UiText.get(R.string.ui_detect_fingers_at_paper_edges), UiText.get(R.string.ui_reduce_color_moire)},checked,(x,y,b)->checked[y]=b).setMessage(UiText.get(R.string.ui_review_the_preview_edge_detection_can_confuse_colored_illustrations_with_fi)).setNegativeButton(UiText.get(R.string.ui_cancel),null).setPositiveButton(UiText.get(R.string.ui_apply),(x,y)->prefs.edit().putBoolean("edge_fingers",checked[0]).putBoolean("demoire",checked[1]).apply()).show();return;}
        if(w==0){String[] choices={"Photo","Single","Batch","Book"};String[] labels=new String[choices.length];for(int i=0;i<choices.length;i++)labels[i]=CaptureModes.label(choices[i]);new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_capture_mode)).setItems(labels,(x,y)->prefs.edit().putString("mode",choices[y]).apply()).show();}
        else if(w==1){String[] choices={"Default","PaperScan Pro"};new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_default_filter)).setSingleChoiceItems(UiText.labels(choices),prefs.getInt("filter",1),(x,y)->{prefs.edit().putInt("filter",y).apply();x.dismiss();}).show();}
        else if(w==2){String[] choices={"Original proportions","A4","Letter"};new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_pdf_page_size)).setSingleChoiceItems(UiText.labels(choices),prefs.getInt("paper",0),(x,y)->{prefs.edit().putInt("paper",y).apply();x.dismiss();}).show();}
        else if(w==3)ScanNaming.configure(this);
        else{ArrayList<String> names=new ArrayList<>();ArrayList<Long> ids=new ArrayList<>();names.add(UiText.get(R.string.ui_all_documents));ids.add(0L);try(Cursor c=store.db().rawQuery("SELECT id FROM folders ORDER BY name COLLATE NOCASE",null)){while(c.moveToNext()){ids.add(c.getLong(0));names.add(folderPath(c.getLong(0)));}}new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_default_library_folder)).setItems(names.toArray(new String[0]),(x,y)->prefs.edit().putLong("folder",ids.get(y)).apply()).show();}
    }).show();}
    private void galleryPreferences(){
        android.content.SharedPreferences prefs=getSharedPreferences("scan_defaults",0);boolean[] values={prefs.getBoolean("gallery_originals",false),prefs.getBoolean("gallery_processed",false)};
        new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_save_photos_to_gallery)).setMultiChoiceItems(new String[]{UiText.get(R.string.ui_keep_original_photos), UiText.get(R.string.ui_save_processed_scan_images)},values,(d,w,b)->values[w]=b).setMessage(UiText.get(R.string.ui_photos_are_saved_in_pictures_paperflow_after_a_successful_scan_export_galle)).setNegativeButton(UiText.get(R.string.ui_cancel),null).setPositiveButton(UiText.get(R.string.ui_save),(d,w)->{prefs.edit().putBoolean("gallery_originals",values[0]).putBoolean("gallery_processed",values[1]).apply();if((values[0]||values[1])&&android.os.Build.VERSION.SDK_INT<29&&androidx.core.content.ContextCompat.checkSelfPermission(this,android.Manifest.permission.WRITE_EXTERNAL_STORAGE)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},731);}).show();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);if(request==731&&(grants.length==0||grants[0]!=android.content.pm.PackageManager.PERMISSION_GRANTED)){getSharedPreferences("scan_defaults",0).edit().putBoolean("gallery_originals",false).putBoolean("gallery_processed",false).apply();Toast.makeText(this,UiText.get(R.string.ui_gallery_copies_remain_off_because_storage_access_was_not_allowed),Toast.LENGTH_LONG).show();}}
    private void startScan(String mode) {
        Intent intent = new Intent(this, CameraCaptureActivity.class); intent.putExtra("capture_mode", mode); captureLauncher.launch(intent);
    }
    private boolean resumedOnce;
    @Override protected void onResume(){super.onResume();FolderLock.watch(this,()->{selectedDocuments.clear();if(content!=null){if("All Documents".equals(section))renderAllDocuments();else if("Home".equals(section))renderHome();}});if(resumedOnce&&content!=null){if("All Documents".equals(section))renderAllDocuments();else if("Home".equals(section))renderHome();}resumedOnce=true;}
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleDocumentIntent(intent); }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);if(wordState.pendingCloud!=null)state.putBundle("cloud_unlock",wordState.pendingCloud.saved());state.putString("section",section);state.putLong("library_folder",folderId);state.putBoolean("library_trash",trashView);state.putBoolean("library_favorites",favoritesOnly);state.putString("library_query",searchQuery);state.putBoolean("library_visible",libraryVisible);state.putString("append_path",pendingAppendPath);state.putString("append_name",pendingAppendName);state.putString("append_password",pendingAppendPassword);if(wordExport!=null)state.putString("word_export",wordExport.getAbsolutePath());}
    private void clearPendingAppend(){pendingAppendPath=null;pendingAppendName=null;pendingAppendPassword="";}
    private void indexLibrary(){if(isDestroyed()||pdfWorker.isShutdown())return;pdfWorker.execute(()->{store.indexText();});}
    private void handleDocumentIntent(Intent intent) {
        handleIncomingFiles(intent);
        String action=intent.getStringExtra("document_action"),path=intent.getStringExtra("path"),name=intent.getStringExtra("name");
        if(action==null||path==null)return;
        if(!FolderLock.ensureDocument(this,path,()->handleDocumentIntent(intent)))return;
        try{if(!new File(path).getCanonicalPath().startsWith(new File(getFilesDir(),"documents").getCanonicalPath()+File.separator))return;}catch(java.io.IOException invalid){return;}
        String origin=intent.getStringExtra("origin_path");ArrayList<String> origins=intent.getStringArrayListExtra("origin_paths");if(origins==null)origins=new ArrayList<>();if(origin!=null)origins.add(origin);for(String item:origins)if(!FolderLock.ensureDocument(this,item,()->handleDocumentIntent(intent)))return;
        intent.removeExtra("document_action");
        if(action.equals("Register")){registerDerivedDocument(path,name==null?"Document":name,intent.getIntExtra("pages",-1),origins);if(intent.getBooleanExtra("signed_revision",false))store.signedRevision(origin,path);renderHome();String next=intent.getStringExtra("after_save_action");if("To Word".equals(next))beginWordConversion(path,name);else if("Sign".equals(next)){Intent viewer=new Intent(this,PdfViewerActivity.class).putExtra("path",path).putExtra("name",name).putExtra("start_sign",true);startActivity(viewer);}else openPdf(path);}
        else if(action.equals("Rename")){ContentValues values=new ContentValues();values.put("name",name);store.db().update("docs",values,"path=?",new String[]{path});renderHome();openPdf(path);}
        else if(action.equals("Tags"))editTags(path);
        else if(action.equals("To Word")){String password=intent.getStringExtra("pdf_password");intent.removeExtra("pdf_password");beginWordConversion(path,name==null?"Document":name,password==null?"":password);}
        else if(action.equals("Edit")){String password=intent.getStringExtra("pdf_password");intent.removeExtra("pdf_password");editExistingPdf(path,name,password==null?"":password);}
        else if(action.equals("Add")){pendingAppendPath=path;pendingAppendName=name;pendingAppendPassword=intent.getStringExtra("pdf_password");if(pendingAppendPassword==null)pendingAppendPassword="";intent.removeExtra("pdf_password");new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_add_pages)).setItems(new String[]{UiText.get(R.string.ui_camera), UiText.get(R.string.ui_import_images), UiText.get(R.string.ui_import_pdf)},(d,w)->{if(w==0)startScan("Batch");else if(w==1)imagePicker.launch(new String[]{"image/*"});else pdfPicker.launch(new String[]{"application/pdf"});}).setOnCancelListener(d->clearPendingAppend()).show();}
    }
    private void handleIncomingFiles(Intent intent){
        String action=intent.getAction();if(!Intent.ACTION_VIEW.equals(action)&&!Intent.ACTION_SEND.equals(action)&&!Intent.ACTION_SEND_MULTIPLE.equals(action))return;
        ArrayList<Uri> uris=new ArrayList<>();if(Intent.ACTION_VIEW.equals(action)&&intent.getData()!=null)uris.add(intent.getData());else if(Intent.ACTION_SEND_MULTIPLE.equals(action)){ArrayList<Uri> streams=intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);if(streams!=null)uris.addAll(streams);}else{Uri stream=intent.getParcelableExtra(Intent.EXTRA_STREAM);if(stream!=null)uris.add(stream);}
        intent.setAction(null);ArrayList<String> images=new ArrayList<>();ArrayList<Uri> office=new ArrayList<>();for(Uri uri:uris){if(!"content".equals(uri.getScheme()))continue;String type=getContentResolver().getType(uri);if(type==null)type=intent.getType();if("application/pdf".equals(type))importPdf(uri);else if(type!=null&&type.startsWith("image/"))images.add(uri.toString());else if(type!=null&&type.startsWith("application/vnd.openxmlformats-officedocument."))office.add(uri);}
        if(!images.isEmpty())chooseImageImport(images);if(office.size()==1)startActivity(new Intent(this,ExtendedToolsActivity.class).putExtra("operation","Office to PDF").setData(office.get(0)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));else if(office.size()>1)Toast.makeText(this,UiText.get(R.string.ui_open_one_office_file_at_a_time),Toast.LENGTH_LONG).show();
    }
    private void registerDerivedDocument(String path,String name,int knownPages,java.util.Collection<String> origins){if(isDestroyed())return;store.registerDerived(path,name,knownPages>=0?knownPages:pageCount(new File(path)),origins);indexLibrary();}
    private void registerDocument(String path,String name){
        registerDocument(path,name,-1);
    }
    private void registerDocument(String path,String name,int knownPages){
        store.registerDocument(path,name,knownPages>=0?knownPages:pageCount(new File(path)));indexLibrary();
    }
    private void launchTool(String operation){
        Cursor cursor=store.db().query("docs",new String[]{"name","path"},"deleted_at=0 AND "+FolderLock.visibleSql(this,store),null,null,null,"last_opened DESC");ArrayList<String> names=new ArrayList<>(),paths=new ArrayList<>();while(cursor.moveToNext()){names.add(cursor.getString(0));paths.add(cursor.getString(1));}cursor.close();
        Intent intent=new Intent(this,ToolWorkflowActivity.class);intent.putExtra("operation",operation);intent.putStringArrayListExtra("paths",paths);intent.putStringArrayListExtra("names",names);startActivity(intent);
    }
    private void launchScanEditor(ArrayList<String> pages,String mode,boolean crop){
        launchScanEditor(pages,mode,crop,false);
    }
    private void launchScanEditor(ArrayList<String> pages,String mode,boolean crop,boolean importPdf){startActivity(editorIntent(pages,mode,crop,importPdf));}
    private Intent editorIntent(ArrayList<String> pages,String mode,boolean crop,boolean importPdf){
        Intent intent=new Intent(this,ScanEditorActivity.class);intent.putStringArrayListExtra("pages",pages);intent.putExtra("capture_mode",mode==null?"Batch":mode);intent.putExtra("start_crop",crop);intent.putExtra("import_pdf",importPdf);
        if(pendingAppendPath!=null){intent.putExtra("append_path",pendingAppendPath);intent.putExtra("append_password",pendingAppendPassword);intent.putExtra("name",pendingAppendName);clearPendingAppend();}
        return intent;
    }
    private Bitmap renderDocumentPage(PDDocument document,int index,int maxWidth,int maxHeight)throws java.io.IOException{
        com.tom_roush.pdfbox.pdmodel.PDPage page=document.getPage(index);com.tom_roush.pdfbox.pdmodel.common.PDRectangle bounds=page.getCropBox();boolean turn=Math.abs(page.getRotation())%180==90;float width=turn?bounds.getHeight():bounds.getWidth(),height=turn?bounds.getWidth():bounds.getHeight();
        return new com.tom_roush.pdfbox.rendering.PDFRenderer(document).renderImage(index,Math.min(maxWidth/width,maxHeight/height));
    }
    private void editExistingPdf(String path,String name,String password){
        startActivity(new Intent(this,PdfEditActivity.class).putExtra("path",path).putExtra("name",name).putExtra("pdf_password",password).putExtra("page",getIntent().getIntExtra("page",0)));
    }
    private void appendImportedPdf(Uri uri){if(pendingAppendPath==null)return;if(!FolderLock.ensureDocument(this,pendingAppendPath,()->appendImportedPdf(uri)))return;
        String source=pendingAppendPath,name=pendingAppendName,password=pendingAppendPassword;clearPendingAppend();
        android.app.ProgressDialog progress=android.app.ProgressDialog.show(this,"",UiText.get(R.string.ui_adding_pages),true,false);
        pdfWorker.execute(()->{File incoming=null,output=null;try{File dir=new File(getFilesDir(),"documents");incoming=new File(dir,"append-source-"+System.nanoTime()+".pdf");try(InputStream in=getContentResolver().openInputStream(uri);FileOutputStream out=new FileOutputStream(incoming)){if(in==null)throw new java.io.IOException(UiText.get(R.string.ui_no_pdf_data));byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);}output=new File(dir,"appended-"+System.nanoTime()+".pdf");try(PDDocument result=new PDDocument();PDDocument original=PDDocument.load(new File(source),password);PDDocument extra=PDDocument.load(incoming)){PdfPermissions.assemble(original);PdfPermissions.assemble(extra);PDFMergerUtility merge=new PDFMergerUtility();merge.appendDocument(result,original);merge.appendDocument(result,extra);PdfProtection.inheritAll(java.util.Arrays.asList(original,extra),result,password);result.save(output);ScanArchive.combine(this,java.util.Arrays.asList(new File(source),incoming),java.util.Arrays.asList(password,""),output);}incoming.delete();File saved=output;runOnUiThread(()->{progress.dismiss();if(isDestroyed())return;registerDerivedDocument(saved.getAbsolutePath(),name+" · pages added",-1,java.util.Collections.singletonList(source));renderHome();openPdf(saved.getAbsolutePath());});}catch(Exception e){if(incoming!=null)incoming.delete();if(output!=null)output.delete();runOnUiThread(()->{progress.dismiss();Toast.makeText(this,UiText.format(R.string.ui_could_not_add_pdf_value, e.getMessage()),Toast.LENGTH_LONG).show();});}});
    }
    private void editTags(String path) {
        String current="";try(Cursor cursor=store.db().query("docs",new String[]{"tags"},"path=?",new String[]{path},null,null,null)){if(cursor.moveToFirst())current=cursor.getString(0);}
        EditText input=new EditText(this);input.setHint(UiText.get(R.string.ui_separate_tags_with_commas));input.setText(current);input.setSingleLine();input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle(UiText.get(R.string.ui_tags)).setView(input).setNegativeButton(UiText.get(R.string.ui_cancel),(d,w)->openPdf(path)).setOnCancelListener(d->openPdf(path)).setPositiveButton(UiText.get(R.string.ui_save),(d,w)->{
            java.util.LinkedHashSet<String> tags=new java.util.LinkedHashSet<>();for(String tag:input.getText().toString().split(",")){String value=tag.trim();if(!value.isEmpty()&&tags.size()<20)tags.add(value.substring(0,Math.min(value.length(),40)));}
            ContentValues values=new ContentValues();values.put("tags",android.text.TextUtils.join(", ",tags));store.db().update("docs",values,"path=?",new String[]{path});renderHome();openPdf(path);
        }).show();
    }
    private void openPdf(String path) { if(!FolderLock.ensureDocument(this,path,()->openPdf(path)))return; ContentValues values = new ContentValues(); values.put("last_opened", System.currentTimeMillis()); store.db().update("docs", values, "path=?", new String[]{path}); Intent i = new Intent(this, PdfViewerActivity.class); i.putExtra("path", path);Cursor cursor=store.db().query("docs",new String[]{"name","tags"},"path=?",new String[]{path},null,null,null);if(cursor.moveToFirst()){i.putExtra("name",cursor.getString(0));i.putExtra("tags",cursor.getString(1));}cursor.close();startActivity(i); }
    private void sharePdf(String path) { if(!FolderLock.ensureDocument(this,path,()->sharePdf(path)))return; Uri uri=FileProvider.getUriForFile(this,getPackageName()+".files",new File(path)); Intent i=new Intent(Intent.ACTION_SEND);i.setType("application/pdf");i.putExtra(Intent.EXTRA_STREAM,uri);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(i,UiText.get(R.string.ui_pdf_7439090a))); }
    private ImageView iconView(String name, int color, int padding) {
        ImageView view = new ImageView(this); view.setImageDrawable(new AppIcon(name, color));
        view.setPadding(dp(padding),dp(padding),dp(padding),dp(padding)); view.setContentDescription(UiText.label(name));
        view.setScaleType(ImageView.ScaleType.FIT_CENTER); return view;
    }
    private void setIcon(TextView view, String name, int color, int size, boolean top) {
        AppIcon icon = new AppIcon(name, color); icon.setBounds(0,0,dp(size),dp(size));
        view.setCompoundDrawablePadding(dp(6));
        view.setCompoundDrawables(top ? null : icon, top ? icon : null, null, null);
    }
    private TextView navItem(String text, boolean selected) {
        TextView t = new TextView(this); t.setTag(text);t.setText(UiText.label(text)); t.setGravity(Gravity.CENTER); t.setTextSize(11);
        t.setMinHeight(dp(64));t.setMaxLines(2);t.setTextColor(selected ? BRAND : MUTED); setIcon(t, text, selected ? BRAND : MUTED, 25, true);
        navigationItems.add(t); return t;
    }
    private void selectNavigation(String name) {
        libraryGeneration.incrementAndGet();
        if(!name.equals(section))selectedDocuments.clear();
        section=name;
        if(mainScroll!=null)mainScroll.post(()->mainScroll.scrollTo(0,0));
        if(cameraRow!=null)cameraRow.setVisibility(name.equals("Home")||name.equals("All Documents")?View.VISIBLE:View.GONE);
        for (TextView item : navigationItems) {
            boolean selected = name.equals(item.getTag());
            item.setTextColor(selected ? BRAND : MUTED); item.setSelected(selected);
            setIcon(item, String.valueOf(item.getTag()), selected ? BRAND : MUTED, 25, true);
        }
    }
    private void addSelectionBar(){
        selectionBar=new LinearLayout(this);selectionBar.setGravity(Gravity.CENTER_VERTICAL);selectionCount=new TextView(this);selectionCount.setTextColor(BRAND);selectionCount.setTextSize(13);selectionBar.addView(selectionCount,new LinearLayout.LayoutParams(0,dp(40),1));
        TextView move=recentAction(UiText.get(R.string.ui_move));selectionBar.addView(move,new LinearLayout.LayoutParams(dp(65),dp(36)));move.setOnClickListener(v->moveDocuments(new ArrayList<>(selectedDocuments.keySet())));TextView share=recentAction(UiText.get(R.string.ui_share)),delete=recentAction(UiText.get(R.string.ui_trash));selectionBar.addView(share,new LinearLayout.LayoutParams(dp(84),dp(36)));selectionBar.addView(delete,new LinearLayout.LayoutParams(dp(84),dp(36)));content.addView(selectionBar);
        share.setOnClickListener(v->{ArrayList<Uri> uris=new ArrayList<>();for(String path:selectedDocuments.values())uris.add(FileProvider.getUriForFile(this,getPackageName()+".files",new File(path)));Intent intent=new Intent(Intent.ACTION_SEND_MULTIPLE).setType("application/pdf");intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivity(Intent.createChooser(intent,UiText.get(R.string.ui_share_documents)));});
        delete.setOnClickListener(v->new AlertDialog.Builder(this).setMessage(UiText.format(R.string.ui_move_value_selected_document_s_to_trash, selectedDocuments.size())).setNegativeButton(UiText.get(R.string.ui_cancel),null).setPositiveButton(UiText.get(R.string.ui_delete),(d,w)->{for(Long id:selectedDocuments.keySet())store.trash(id);selectedDocuments.clear();renderHome();}).show());updateSelectionBar();
    }
    private void updateSelectionBar(){if(selectionBar!=null){selectionBar.setVisibility(selectedDocuments.isEmpty()?View.GONE:View.VISIBLE);selectionCount.setText(UiText.format(R.string.ui_value_selected, selectedDocuments.size()));}}
    private Button button(String text) { Button b=new Button(this);b.setText(text);b.setTextColor(AppTheme.onAccent());b.setTextSize(16);b.setTypeface(null,Typeface.BOLD);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(BRAND));return b; }
    private Button smallButton(String text) { Button b=new Button(this);b.setText(text);b.setTextSize(11);b.setMinHeight(dp(38));b.setMinimumHeight(dp(38));return b; }
    private TextView label(LinearLayout parent,String text,int size,int color,boolean bold) { TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,Typeface.BOLD);parent.addView(t);return t; }
    private void title(LinearLayout parent,String text,int size) { TextView t=label(parent,text,size,INK,true);t.setPadding(0,dp(16),0,dp(5)); }
    private int dp(int v) { return (int)(v*getResources().getDisplayMetrics().density+0.5f); }

}

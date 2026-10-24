package com.example.myapplication;

import android.content.Intent;
import android.content.ClipData;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.DragEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.rendering.PDFRenderer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Shared, staged local PDF tools: choose documents, preview/options, process, inspect result. */
public class ToolWorkflowActivity extends BackHandlingActivity {
    private final int INK = AppTheme.ink(), MUTED = AppTheme.muted(), BRAND = AppTheme.accent(), BG = AppTheme.background();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<Source> sources = new ArrayList<>(), selectedSources = new ArrayList<>();
    private final ArrayList<Bitmap> ownedBitmaps = new ArrayList<>();
    private int pageWindow;
    // Only this executor owns the open preview documents. Exports use worker independently.
    private final java.util.concurrent.ThreadPoolExecutor previewWorker=new java.util.concurrent.ThreadPoolExecutor(1,1,0,java.util.concurrent.TimeUnit.MILLISECONDS,new java.util.concurrent.LinkedBlockingQueue<>(),r->new Thread(r,"paperflow-preview"));
    private PDDocument previewDocument,originalPreviewDocument;
    private int previewDocumentEpoch=-1;
    private void closePreviewDocuments(){
        for(PDDocument doc:new PDDocument[]{previewDocument,originalPreviewDocument})if(doc!=null)try{doc.close();}catch(Exception ignored){}
        previewDocument=null;originalPreviewDocument=null;previewDocumentEpoch=-1;
    }
    private void invalidatePreviewRequests(){
        thumbnailEpoch++;thumbnailWaiters.clear();previewWorker.getQueue().clear();
        previewWorker.execute(this::closePreviewDocuments);
    }
    private volatile int thumbnailEpoch;
    private final android.util.LruCache<Integer,Bitmap> thumbnailCache=new android.util.LruCache<Integer,Bitmap>(4*1024*1024){@Override protected int sizeOf(Integer key,Bitmap value){return value.getAllocationByteCount();}};
    private final java.util.Map<Integer,ArrayList<java.util.function.Consumer<Bitmap>>> thumbnailWaiters=new java.util.HashMap<>();
    private final ArrayList<float[]> pageSizes = new ArrayList<>();
    private final ArrayList<Integer> pageOrder = new ArrayList<>();
    private final HashSet<Integer> selectedPages = new HashSet<>();
    private final ArrayList<CheckBox> pageChecks = new ArrayList<>();
    private LinearLayout body, root, bottomBar;
    private TextView heading, subtitle, primary, progressText;
    private ProgressBar progressBar;
    private String operation, stage = "source", resultMime, watermark = "Confidential";
    private Source source;
    private File resultFile;
    private volatile boolean ocrCancelled;
    private String resultName, resultPassword = "";
    private final java.util.HashMap<Integer,Integer> pageRotations=new java.util.HashMap<>();
    private ImageView rotationPreview;private TextView rotationLabel;private int rotationPage;private Bitmap largeRotationPreview;
    private int rotation = 90, opacity = 30, angle = -45, quality = 90, preset, longImageQuality;
    private String imageFormat = "JPEG", newPassword = "", restoredConfirmation = "";
    private EditText passwordInput, confirmationInput;
    private WatermarkPreview watermarkPreview;
    private int watermarkColor = Color.BLACK, watermarkSize = 17, watermarkSpacing = 30, watermarkPage;
    private boolean watermarkTile;
    private TextView watermarkTextAction, watermarkPageLabel;
    private AlertDialog watermarkDialog;
    private EditText watermarkDraftInput;
    private String watermarkDraft;
    private boolean reopenWatermarkDialog;
    private boolean watermarkRemove,watermarkSettingsLoaded;
    private volatile boolean destroyed;
    private int generation, resultPage, resultPageCount;
    private Bitmap resultBitmap;
    private ImageView resultImage;
    private TextView resultPageLabel;
    private TextView compressionSaveAction;
    private boolean compressionSaveCopy=true, compressionRegistered;
    private ActivityResultLauncher<String[]> importLauncher;
    private ActivityResultLauncher<Intent> saveLauncher;
    private Bundle restoredOptions;
    private boolean orientationLocked;
    private int originalOrientation;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);restoredRotationState=state;rotationPage=state==null?0:state.getInt("rotation_preview_page",0); PDFBoxResourceLoader.init(getApplicationContext());
        compressionSaveCopy=getSharedPreferences("paperflow_tools",MODE_PRIVATE).getBoolean("compress_save_copy",true);
        operation = getIntent().getStringExtra("operation"); if (operation == null) operation = "Rotate PDF";
        ArrayList<String> paths = getIntent().getStringArrayListExtra("paths"), names = getIntent().getStringArrayListExtra("names");
        if (paths != null) for (int i = 0; i < paths.size(); i++) if (new File(paths.get(i)).isFile()) sources.add(new Source(paths.get(i), names != null && i < names.size() ? names.get(i) : new File(paths.get(i)).getName()));
        importLauncher = registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> { if (uris != null && !uris.isEmpty()) importSources(uris); });
        saveLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> { if (result.getResultCode() == RESULT_OK && result.getData() != null && result.getData().getData() != null) saveToDevice(result.getData().getData()); });
        getWindow().setStatusBarColor(Color.WHITE); getWindow().setNavigationBarColor(Color.WHITE); getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (state != null) restoreWorkflowState(state);
        buildUi();
        if (state != null && "result".equals(state.getString("workflow_stage")) && resultFile != null && resultFile.isFile() && source != null) { restoreResult(state.getInt("result_count"), state.getInt("result_page")); return; }
        if (state != null && ("options".equals(state.getString("workflow_stage")) || "progress".equals(state.getString("workflow_stage"))) && !selectedSources.isEmpty()) { restoredOptions = state; continueFromSources(); return; }
        showSources();
        if (state != null) return;
        String initialPath = getIntent().getStringExtra("path");
        if (initialPath != null && new File(initialPath).isFile()) { Source initial = null; for (Source s : sources) if (s.path.equals(initialPath)) initial = s; if (initial == null) { initial = new Source(initialPath, getIntent().getStringExtra("name")); sources.add(initial); } initial.password=getIntent().getStringExtra("pdf_password");if(initial.password==null)initial.password="";selectedSources.add(initial); if (!operation.equals("Merge Files")) continueFromSources(); else showSources(); }
    }

    private void buildUi() {
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        LinearLayout toolbar = new LinearLayout(this); toolbar.setBackgroundColor(AppTheme.surface()); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        ImageView back = new ImageView(this); back.setImageDrawable(new AppIcon("Back", INK)); back.setPadding(dp(15), dp(15), dp(15), dp(15)); back.setContentDescription(UiText.get(R.string.ui_back)); toolbar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(56))); back.setOnClickListener(v -> goBack());
        heading = label(UiText.label(operation), operation.equals("Compress")?16:19, INK); heading.setTypeface(null,android.graphics.Typeface.BOLD); toolbar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));compressionSaveAction=label(UiText.get(R.string.ui_save_a_copy),14,BRAND);compressionSaveAction.setGravity(Gravity.CENTER);compressionSaveAction.setPadding(dp(12),0,dp(16),0);compressionSaveAction.setVisibility(View.GONE);toolbar.addView(compressionSaveAction,new LinearLayout.LayoutParams(-2,dp(56)));compressionSaveAction.setOnClickListener(v->{registerCompressionCopy();updateCompressionSaveAction();}); root.addView(toolbar);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); body = new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL); body.setPadding(dp(16), dp(18), dp(16), dp(24)); scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        bottomBar = new LinearLayout(this);bottomBar.setOrientation(LinearLayout.VERTICAL);bottomBar.setBackgroundColor(AppTheme.surface());primary = action(UiText.get(R.string.ui_next), true);primary.setOnClickListener(v -> { if (stage.equals("source")) continueFromSources(); else if (stage.equals("options")) process(); else if (stage.equals("result"))requestSaveToDevice(); });root.addView(bottomBar);configureBottom(false);UiInsets.applyKeyboardAware(root); setContentView(root);
    }

    private void showSources() {
        stage = "source"; invalidatePreviewRequests(); generation++; int version = generation; body.removeAllViews(); if(largeRotationPreview!=null&&!largeRotationPreview.isRecycled())largeRotationPreview.recycle();largeRotationPreview=null;releaseResultPreview();configureBottom(false);compressionSaveAction.setVisibility(View.GONE); primary.setEnabled(true); primary.setText(UiText.get(R.string.ui_next)); unlockOrientation();
        if(operation.equals("OCR PDF"))note(UiText.get(R.string.ui_ocr_pdf_intro));
        title(UiText.format(R.string.ui_select_value, (operation.equals("Merge Files") ? "PDF files" : "a PDF"))); note(operation.equals("Merge Files") ? UiText.get(R.string.ui_choose_two_or_more_files_then_arrange_them_in_the_next_step) : UiText.get(R.string.ui_choose_a_document_from_your_library_or_import_a_pdf));
        TextView importButton = action(UiText.get(R.string.ui_import_pdf_files), false); body.addView(importButton, spaced(-1, dp(46), 14)); importButton.setOnClickListener(v -> importLauncher.launch(new String[]{"application/pdf"}));
        if (sources.isEmpty()) note(UiText.get(R.string.ui_your_document_library_is_empty_import_a_pdf_to_get_started));
        for (Source item : sources) {
            LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(10), dp(10), dp(10), dp(10)); row.setBackground(round(AppTheme.surface(), 8)); body.addView(row, spaced(-1, dp(100), 10));
            ImageView image = new ImageView(this); image.setBackgroundColor(AppTheme.border()); image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setImageDrawable(new AppIcon("PDF Tools", MUTED)); item.preview = image; row.addView(image, new LinearLayout.LayoutParams(dp(56), dp(76)));
            LinearLayout names = new LinearLayout(this); names.setPadding(dp(12), 0, dp(8), 0); names.setOrientation(LinearLayout.VERTICAL); TextView name = label(item.name, 15, INK); name.setMaxLines(2); names.addView(name); names.addView(label(android.text.format.Formatter.formatFileSize(this, new File(item.path).length()), 12, MUTED)); row.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
            CheckBox check = new CheckBox(this); check.setChecked(selectedSources.contains(item)); row.addView(check); check.setClickable(false);
            row.setOnClickListener(v -> { if (operation.equals("Merge Files")) { if (selectedSources.contains(item)) selectedSources.remove(item); else selectedSources.add(item); } else { selectedSources.clear(); selectedSources.add(item); } showSources(); });
            if (item.thumbnail != null) image.setImageBitmap(item.thumbnail);
            else if (!item.thumbnailPending) { item.thumbnailPending = true; worker.execute(() -> { try (PDDocument document = load(item)) { Bitmap thumb = render(document, 0, 180, 220); runOnUiThread(() -> { item.thumbnailPending = false; if (destroyed) { thumb.recycle(); return; } item.thumbnail = thumb; ownedBitmaps.add(thumb); if (stage.equals("source") && item.preview != null) item.preview.setImageBitmap(thumb); }); } catch (Exception ignored) { runOnUiThread(() -> item.thumbnailPending = false); } }); }
        }
    }

    private void importSources(java.util.List<Uri> uris) {
        showProgress("Importing PDF files…"); worker.execute(() -> { ArrayList<Source> imported = new ArrayList<>(); int failures = 0;
            for (Uri uri : uris) { File copy = null; try { String name = displayName(uri); File directory = documentsDir(); copy = new File(directory, "paperflow-source-" + UUID.randomUUID() + ".pdf"); try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(copy)) { if (in == null) throw new IllegalArgumentException(); copy(in, out); } imported.add(new Source(copy.getAbsolutePath(), name)); } catch (Exception e) { if (copy != null) copy.delete(); failures++; } }
            int errors = failures; runOnUiThread(() -> { if (destroyed) return; sources.addAll(imported); if (!operation.equals("Merge Files")) selectedSources.clear(); if (operation.equals("Merge Files")) selectedSources.addAll(imported); else if (!imported.isEmpty()) selectedSources.add(imported.get(0)); showSources(); if (errors > 0) toast(UiText.format(R.string.ui_value_file_s_could_not_be_imported, errors)); });
        });
    }

    private String displayName(Uri uri) { try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) { if (c != null && c.moveToFirst()) return c.getString(0).replaceFirst("(?i)\\.pdf$", ""); } catch (Exception ignored) { } return "Imported document"; }

    private void continueFromSources() {
        for(Source selected:selectedSources)if(!FolderLock.ensureDocument(this,selected.path,this::continueFromSources))return;
        if (selectedSources.isEmpty()) { toast(UiText.get(R.string.ui_select_a_pdf_first)); return; }
        if (operation.equals("Merge Files") && selectedSources.size() < 2) { toast(UiText.get(R.string.ui_select_at_least_two_pdfs)); return; }
        if(source!=null&&!source.path.equals(selectedSources.get(0).path)){pageRotations.clear();rotationPage=0;}source = selectedSources.get(0);
        if (operation.equals("Sign")) { Intent intent = new Intent(this, PdfViewerActivity.class); intent.putExtra("path", source.path); intent.putExtra("name", source.name); intent.putExtra("start_sign", true); startActivity(intent); finish(); return; }
        if (operation.equals("To Word")) { Intent intent = new Intent(this, MainActivity.class); intent.putExtra("document_action", "To Word"); intent.putExtra("path", source.path); intent.putExtra("name", source.name); intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP); startActivity(intent); finish(); return; }
        if (operation.equals("PDF to Long Image")) { startActivity(new Intent(this, LongImageActivity.class).putExtra("path", source.path).putExtra("name", source.name).putExtra("password", source.password)); return; }
        clearThumbnails();pageWindow=restoredOptions==null?0:restoredOptions.getInt("page_window",0);watermarkSettingsLoaded=false;showProgress("Opening PDF…");
        final ArrayList<Source> selection=new ArrayList<>(selectedSources);
        final Source chosen=source;final int epoch=thumbnailEpoch;
        worker.execute(()->{Source locked=null;try{
            ArrayList<float[]> sizes=new ArrayList<>();org.json.JSONObject saved=null;
            for(Source item:selection){
                locked=item;
                try(PDDocument doc=load(item)){
                    int total=doc.getNumberOfPages();if(total==0)throw new IllegalStateException(UiText.get(R.string.ui_the_pdf_has_no_pages));
                    boolean encrypted=doc.isEncrypted();
                    if(item==chosen)for(int i=0;i<total;i++){
                        if(destroyed||epoch!=thumbnailEpoch)return;
                        PDPage p=doc.getPage(i);PDRectangle b=p.getCropBox();boolean rotated=Math.abs(p.getRotation())%180==90;
                        sizes.add(new float[]{rotated?b.getHeight():b.getWidth(),rotated?b.getWidth():b.getHeight()});
                        if(saved==null&&operation.equals("Add Watermark"))saved=PdfWatermark.settings(p);
                    }
                    runOnUiThread(()->{if(!destroyed&&epoch==thumbnailEpoch){item.encrypted=encrypted;item.pages=total;}});
                }
            }
            final org.json.JSONObject settings=saved;
            runOnUiThread(()->{if(destroyed||epoch!=thumbnailEpoch)return;
                pageSizes.clear();pageSizes.addAll(sizes);selectedPages.clear();pageOrder.clear();
                for(int i=0;i<sizes.size();i++){if(!operation.equals("OCR PDF"))selectedPages.add(i);pageOrder.add(i);}
                if(settings!=null&&restoredOptions==null){watermarkSettingsLoaded=true;watermark=settings.optString("text",watermark);watermarkColor=settings.optInt("color",watermarkColor);opacity=settings.optInt("opacity",opacity);watermarkSize=settings.optInt("size",watermarkSize);angle=settings.optInt("angle",angle);watermarkTile=settings.optBoolean("tile",watermarkTile);watermarkSpacing=settings.optInt("spacing",watermarkSpacing);}
                if(restoredOptions!=null){ArrayList<Integer> selected=restoredOptions.getIntegerArrayList("chosen_pages"),order=restoredOptions.getIntegerArrayList("page_order");if(selected!=null){selectedPages.clear();for(int page:selected)if(page>=0&&page<sizes.size())selectedPages.add(page);}if(order!=null&&order.size()==sizes.size()){pageOrder.clear();pageOrder.addAll(order);}restoredOptions=null;}
                if(restoredRotationState!=null){for(int page=0;page<sizes.size();page++)if(restoredRotationState.containsKey("page_rotation_"+page))pageRotations.put(page,restoredRotationState.getInt("page_rotation_"+page));restoredRotationState=null;}
                showOptions();
            });
        }catch(InvalidPasswordException e){Source item=locked;runOnUiThread(()->{if(!destroyed&&epoch==thumbnailEpoch)askPassword(item);});}
        catch(Exception|OutOfMemoryError e){runOnUiThread(()->{if(!destroyed&&epoch==thumbnailEpoch){showSources();toast(UiText.format(R.string.ui_could_not_open_pdf_value,e.getMessage()));}});}});
    }

    /** Small cache and windowed UI: no bitmap is allocated for unopened page windows. */
    private void requestThumbnail(int page,boolean original,java.util.function.Consumer<Bitmap> ready){
        if(destroyed||!"options".equals(stage))return;
        int key=page*2+(original?1:0);Bitmap cached=thumbnailCache.get(key);
        if(cached!=null){ready.accept(cached);return;}
        ArrayList<java.util.function.Consumer<Bitmap>> waiting=thumbnailWaiters.get(key);
        if(waiting!=null){waiting.add(ready);return;}
        waiting=new ArrayList<>();waiting.add(ready);thumbnailWaiters.put(key,waiting);
        int epoch=thumbnailEpoch;String path=source.path,password=source.password;boolean clean=operation.equals("Add Watermark")&&!original;
        previewWorker.execute(()->{Bitmap bitmap=null;
            try{if(destroyed||epoch!=thumbnailEpoch)return;
                if(previewDocumentEpoch!=epoch){closePreviewDocuments();previewDocumentEpoch=epoch;}
                PDDocument doc=original?originalPreviewDocument:previewDocument;
                if(doc==null){doc=PDDocument.load(new File(path),password,MemoryUsageSetting.setupMixed(24*1024*1024));if(original)originalPreviewDocument=doc;else previewDocument=doc;}
                if(destroyed||epoch!=thumbnailEpoch)return;
                if(clean)PdfWatermark.remove(doc.getPage(page));bitmap=render(doc,page,160,200);
            }
            catch(Exception|OutOfMemoryError ignored){}
            Bitmap result=bitmap;runOnUiThread(()->{if(destroyed||epoch!=thumbnailEpoch){if(result!=null)result.recycle();return;}
                ArrayList<java.util.function.Consumer<Bitmap>> listeners=thumbnailWaiters.remove(key);
                if(result!=null)thumbnailCache.put(key,result);
                if(listeners!=null)for(java.util.function.Consumer<Bitmap> listener:listeners)listener.accept(result);
            });
        });
    }
    private void bindThumbnail(ImageView image,int page){int epoch=thumbnailEpoch;requestThumbnail(page,false,b->{if(!destroyed&&epoch==thumbnailEpoch&&image.isAttachedToWindow())image.setImageBitmap(b);});}
    private void pageWindowControls(){
        pageWindow=PageWindow.start(pageWindow,pageSizes.size());
        if(pageSizes.size()<=PageWindow.SIZE)return;
        LinearLayout row=new LinearLayout(this);Button previous=new Button(this),next=new Button(this);previous.setText(R.string.ui_previous);next.setText(R.string.ui_next);previous.setEnabled(pageWindow>0);next.setEnabled(PageWindow.end(pageWindow,pageSizes.size())<pageSizes.size());
        TextView range=label((pageWindow+1)+"–"+PageWindow.end(pageWindow,pageSizes.size())+" / "+pageSizes.size(),13,MUTED);range.setGravity(Gravity.CENTER);
        row.addView(previous);row.addView(range,new LinearLayout.LayoutParams(0,-2,1));row.addView(next);body.addView(row);
        previous.setOnClickListener(v->{pageWindow-=PageWindow.SIZE;showOptions();});next.setOnClickListener(v->{pageWindow+=PageWindow.SIZE;showOptions();});
    }

    private void askPassword(Source item) {
        EditText field = new EditText(this); field.setSingleLine(); field.setInputType(129); field.setHint(UiText.get(R.string.ui_pdf_password)); AlertDialog dialog = new ProtectedDialogs.Builder(this).setTitle(item.name).setMessage(UiText.get(R.string.ui_enter_the_password_for_this_pdf)).setView(field).setNegativeButton(UiText.get(R.string.ui_cancel), (d, w) -> showSources()).setPositiveButton(UiText.get(R.string.ui_open), null).create(); dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { if (field.getText().length() == 0) { field.setError(UiText.get(R.string.ui_enter_password)); return; } item.password = field.getText().toString(); dialog.dismiss(); continueFromSources(); })); dialog.show();
    }

    private void showOptions() {
        ArrayList<String> guarded=new ArrayList<>();for(Source item:selectedSources)guarded.add(item.path);FolderLock.guardAll(this,guarded);
        stage = "options"; invalidatePreviewRequests(); generation++; body.removeAllViews(); if(largeRotationPreview!=null&&!largeRotationPreview.isRecycled())largeRotationPreview.recycle();largeRotationPreview=null;releaseResultPreview();configureBottom(operation.equals("Compress"));compressionSaveAction.setVisibility(View.GONE); pageChecks.clear(); primary.setEnabled(true); primary.setText(operation.equals("Print") ? UiText.get(R.string.ui_print) : operation.equals("Compress") ? UiText.get(R.string.ui_compress) : UiText.get(R.string.ui_continue)); if(!operation.equals("Compress")){title(source.name); note(UiText.get(R.string.ui_choose_your_settings_and_review_the_selected_pages));} unlockOrientation();
        switch (operation) {
            case "Merge Files": showMergeOrder(); if(mergeNeedsPassword()){note(UiText.get(R.string.ui_a_source_is_protected_set_a_password_for_the_merged_pdf));showPasswordOptions();} return;
            case "Reorder Pages": note(UiText.get(R.string.ui_press_and_hold_to_drag_a_page_or_use_the_arrows_to_reorder)); showPageOrder(); return;
            case "Remove PDF password": note(UiText.get(R.string.ui_save_an_unlocked_copy_your_original_pdf_stays_unchanged_the_new_copy_will_h)); return;
            case "Lock": showPasswordOptions(); return;
            case "Compress": showCompressionOptions(); return;
            case "Rotate PDF": showRotationOptions(); break;
            case "OCR PDF": primary.setText(UiText.get(R.string.ui_create_searchable_pdf));note(UiText.get(R.string.ui_ocr_pdf_intro));TextView languages=label(UiText.get(R.string.ui_ocr_languages_84fba977),16,BRAND);languages.setPadding(12,20,12,20);languages.setOnClickListener(v->startActivity(new Intent(this,LanguageManagerActivity.class).putExtra("kind","ocr")));body.addView(languages);break;
            case "Add Watermark": showWatermarkOptions(); break;
            case "PDF to Images": showImageOptions(); break;
            case "PDF to Long Image": radioOptions(UiText.get(R.string.ui_clarity),new String[]{UiText.get(R.string.ui_clarity_standard),UiText.get(R.string.ui_clarity_high)},longImageQuality,value->longImageQuality=value);note(UiText.get(R.string.ui_selected_pages_are_stacked_in_order_long_documents_are_scaled_to_fit_a_16_0)); break;
            case "Extract PDF Pages": note(UiText.get(R.string.ui_only_the_selected_pages_will_be_included_in_the_new_pdf)); break;
            case "Print": note(UiText.get(R.string.ui_select_pages_here_then_choose_printer_and_paper_settings_in_the_print_previ)); break;
            default: note(UiText.get(R.string.ui_this_tool_is_unavailable)); primary.setEnabled(false); return;
        }
        showPageSelection();
    }

    private void showPageSelection() {
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); TextView count = label("", 17, INK); header.addView(count, new LinearLayout.LayoutParams(0, -2, 1)); TextView all = label("", 13, BRAND); header.addView(all); body.addView(header, spaced(-1, dp(48), 8));updatePageSelectionSummary(count,all);
        all.setOnClickListener(v -> { boolean select = selectedPages.size() != pageSizes.size(); selectedPages.clear(); if (select) for (int i = 0; i < pageSizes.size(); i++) selectedPages.add(i); for (int i = 0; i < pageChecks.size(); i++) pageChecks.get(i).setChecked(selectedPages.contains(i)); updatePageSelectionSummary(count,all);refreshWatermarkPreview(); });
        pageWindowControls();
        for (int i = pageWindow; i < PageWindow.end(pageWindow,pageSizes.size()); i += 3) { LinearLayout row = new LinearLayout(this); body.addView(row, spaced(-1, dp(155), 8)); for (int j = 0; j < 3; j++) {
            int page = i + j; if (page >= pageSizes.size()) { row.addView(new View(this), new LinearLayout.LayoutParams(0, -1, 1)); continue; }
            LinearLayout cell = new LinearLayout(this); cell.setOrientation(LinearLayout.VERTICAL); cell.setBackground(round(AppTheme.surface(), 6)); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1); lp.setMargins(dp(3), 0, dp(3), 0); row.addView(cell, lp);
            ImageView preview = new ImageView(this); preview.setScaleType(ImageView.ScaleType.FIT_CENTER); preview.post(()->bindThumbnail(preview,page)); cell.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1));
            CheckBox check = new CheckBox(this); check.setText(UiText.format(R.string.ui_page_value_67f70d13, (page + 1))); check.setTextSize(11); check.setChecked(selectedPages.contains(page)); cell.addView(check, new LinearLayout.LayoutParams(-1, dp(38))); pageChecks.add(check); check.setOnCheckedChangeListener((button, value) -> { if (value) selectedPages.add(page); else selectedPages.remove(page); updatePageSelectionSummary(count,all);refreshWatermarkPreview(); }); preview.setOnClickListener(v -> check.setChecked(!check.isChecked()));
        } }
    }

    private void updatePageSelectionSummary(TextView count,TextView all){
        count.setText(UiText.format(R.string.ui_value_value_selected, selectedPages.size(), pageSizes.size()));
        all.setText(selectedPages.size()==pageSizes.size() ? UiText.get(R.string.ui_clear_selection) : UiText.get(R.string.ui_select_all));
        if(operation.equals("OCR PDF")&&stage.equals("options"))primary.setText(UiText.format(R.string.ui_ocr_value_selected_pagevalue, selectedPages.size(), (selectedPages.size()==1?"":"s")));
    }

    private void showMergeOrder() { note(UiText.get(R.string.ui_files_merge_from_top_to_bottom_hold_a_file_to_drag_it_into_order)); for (int i = 0; i < selectedSources.size(); i++) { int position = i; Source item = selectedSources.get(i); LinearLayout row = orderRow(item.thumbnail, (i + 1) + ". " + item.name + "\n" + item.pages + " pages", () -> { if (position > 0) { Collections.swap(selectedSources, position, position - 1); showOptions(); } }, () -> { if (position + 1 < selectedSources.size()) { Collections.swap(selectedSources, position, position + 1); showOptions(); } }); enableOrderDrag(row,position,true); body.addView(row, spaced(-1, dp(90), 8)); } }
    private void showPageOrder() { pageWindowControls(); for (int i = pageWindow; i < PageWindow.end(pageWindow,pageOrder.size()); i++) { int position = i, page = pageOrder.get(i); LinearLayout row = orderRow(null, "Page " + (page + 1), () -> { if (position > 0) { Collections.swap(pageOrder, position, position - 1); showOptions(); } }, () -> { if (position + 1 < pageOrder.size()) { Collections.swap(pageOrder, position, position + 1); showOptions(); } }); enableOrderDrag(row,position,false); body.addView(row, spaced(-1, dp(100), 8)); ImageView image=(ImageView)row.getChildAt(0);image.post(()->bindThumbnail(image,page)); } }
    private LinearLayout orderRow(Bitmap thumb, String name, Runnable up, Runnable down) { LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(dp(8), dp(8), dp(8), dp(8)); row.setBackground(round(AppTheme.surface(), 7)); ImageView image = new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER); if (thumb != null) image.setImageBitmap(thumb); row.addView(image, new LinearLayout.LayoutParams(dp(58), -1)); TextView title = label(name, 14, INK); title.setPadding(dp(10), 0, dp(4), 0); row.addView(title, new LinearLayout.LayoutParams(0, -2, 1)); TextView previous = label("↑", 24, INK), next = label("↓", 24, INK); previous.setGravity(Gravity.CENTER); next.setGravity(Gravity.CENTER); previous.setContentDescription(UiText.get(R.string.ui_move_up)); next.setContentDescription(UiText.get(R.string.ui_move_down)); row.addView(previous, new LinearLayout.LayoutParams(dp(40), -1)); row.addView(next, new LinearLayout.LayoutParams(dp(40), -1)); previous.setOnClickListener(v -> up.run()); next.setOnClickListener(v -> down.run()); return row; }

    private static class OrderDrag {final int from;final boolean files;OrderDrag(int from,boolean files){this.from=from;this.files=files;}}
    private void enableOrderDrag(View row,int position,boolean files){
        row.setOnLongClickListener(v->{if(!stage.equals("options"))return false;return v.startDragAndDrop(ClipData.newPlainText("Document order",String.valueOf(position)),new View.DragShadowBuilder(v),new OrderDrag(position,files),0);});
        row.setOnDragListener((view,event)->{
            if(!(event.getLocalState() instanceof OrderDrag)||((OrderDrag)event.getLocalState()).files!=files)return false;
            switch(event.getAction()){
                case DragEvent.ACTION_DRAG_STARTED:return stage.equals("options");
                case DragEvent.ACTION_DRAG_ENTERED:view.setBackground(round(0xFFE0F6EF,7));return true;
                case DragEvent.ACTION_DRAG_EXITED:case DragEvent.ACTION_DRAG_ENDED:view.setBackground(round(AppTheme.surface(),7));return true;
                case DragEvent.ACTION_DROP:
                    int from=((OrderDrag)event.getLocalState()).from,count=files?selectedSources.size():pageOrder.size();if(from<0||from>=count||position>=count)return false;
                    if(from!=position){if(files){Source moved=selectedSources.remove(from);selectedSources.add(position,moved);}else{Integer moved=pageOrder.remove(from);pageOrder.add(position,moved);}body.post(()->{if(!destroyed&&stage.equals("options"))showOptions();});}return true;
                default:return true;
            }
        });
    }
    private void showPasswordOptions() { note(UiText.get(R.string.ui_create_a_password_protected_copy_keep_the_password_somewhere_safe)); passwordInput = new EditText(this); passwordInput.setSingleLine(); passwordInput.setInputType(129); passwordInput.setHint(UiText.get(R.string.ui_password_at_least_6_characters)); passwordInput.setText(newPassword); confirmationInput = new EditText(this); confirmationInput.setSingleLine(); confirmationInput.setInputType(129); confirmationInput.setHint(UiText.get(R.string.ui_confirm_password)); confirmationInput.setText(restoredConfirmation); body.addView(passwordInput, spaced(-1, dp(56), 12)); body.addView(confirmationInput, spaced(-1, dp(56), 8)); CheckBox show = new CheckBox(this); show.setText(UiText.get(R.string.ui_show_password)); show.setOnCheckedChangeListener((b, checked) -> { passwordInput.setInputType(checked ? 145 : 129); confirmationInput.setInputType(checked ? 145 : 129); }); body.addView(show); note(UiText.format(R.string.ui_value_pages_will_be_protected, source.pages)); }
    private void showCompressionOptions() {
        ImageView illustration=new ImageView(this);illustration.setImageDrawable(new AppIcon("Compress",AppTheme.accent()));illustration.setPadding(dp(12),dp(12),dp(12),dp(12));LinearLayout.LayoutParams picture=new LinearLayout.LayoutParams(dp(96),dp(96));picture.gravity=Gravity.CENTER_HORIZONTAL;picture.topMargin=dp(2);body.addView(illustration,picture);
        TextView document=label(source.name,16,AppTheme.ink());document.setGravity(Gravity.CENTER);document.setSingleLine();document.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);body.addView(document,spaced(-1,-2,12));
        TextView prompt=label(UiText.get(R.string.ui_select_compression_level),14,AppTheme.ink());LinearLayout.LayoutParams hint=spaced(-1,-2,32);hint.setMargins(dp(8),dp(32),dp(8),0);body.addView(prompt,hint);
        compressionCard("Medium","Medium size, better quality",0,16);
        compressionCard("High","Smaller size, standard quality",1,12);
        TextView boundary=label(UiText.get(R.string.ui_reduces_photograph_size_while_keeping_searchable_text_vector_detail_links_a),12,MUTED);boundary.setLineSpacing(dp(2),1);LinearLayout.LayoutParams warning=spaced(-1,-2,20);warning.setMargins(dp(8),dp(20),dp(8),0);body.addView(boundary,warning);
    }
    private void compressionCard(String name,String description,int choice,int topMargin){
        boolean selected=preset==choice;LinearLayout card=new LinearLayout(this);card.setGravity(Gravity.CENTER_VERTICAL);card.setPadding(dp(16),dp(12),dp(16),dp(12));GradientDrawable background=round(selected?AppTheme.soft():AppTheme.surface(),4);background.setStroke(dp(1),selected?AppTheme.accent():0xffdcdcdc);card.setBackground(background);card.setContentDescription(UiText.format(R.string.ui_value_compression, name));card.setSelected(selected);
        LinearLayout text=new LinearLayout(this);text.setOrientation(LinearLayout.VERTICAL);TextView title=label(name,16,selected?AppTheme.accent():0xff5a5a5a);title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL));text.addView(title);TextView detail=label(description,12,AppTheme.muted());text.addView(detail,spaced(-1,-2,3));card.addView(text,new LinearLayout.LayoutParams(0,-2,1));ImageView check=new ImageView(this);check.setImageDrawable(new AppIcon("Done",AppTheme.accent()));check.setVisibility(selected?View.VISIBLE:View.INVISIBLE);card.addView(check,new LinearLayout.LayoutParams(dp(24),dp(24)));LinearLayout.LayoutParams layout=spaced(-1,-2,topMargin);layout.setMargins(dp(8),dp(topMargin),dp(8),0);body.addView(card,layout);card.setOnClickListener(v->{preset=choice;showOptions();});
    }
    private void configureBottom(boolean compressionOptions){
        bottomBar.removeAllViews();bottomBar.setVisibility(View.VISIBLE);bottomBar.setPadding(dp(16),dp(8),dp(16),dp(8));primary.setBackground(round(compressionOptions?AppTheme.accent():BRAND,4));
        if(compressionOptions){CheckBox copy=new CheckBox(this);copy.setText(UiText.get(R.string.ui_save_a_copy));copy.setTextSize(12);copy.setTextColor(AppTheme.muted());copy.setButtonTintList(android.content.res.ColorStateList.valueOf(AppTheme.accent()));copy.setChecked(compressionSaveCopy);copy.setOnCheckedChangeListener((button,checked)->{compressionSaveCopy=checked;getSharedPreferences("paperflow_tools",MODE_PRIVATE).edit().putBoolean("compress_save_copy",checked).apply();});bottomBar.addView(copy,new LinearLayout.LayoutParams(-2,dp(32)));}
        if(stage.equals("result")){
            TextView secondary=action(operation.equals("Compress") ? UiText.get(R.string.ui_share) : resultMime.equals("application/pdf") ? UiText.get(R.string.ui_save_to_documents) : UiText.get(R.string.ui_done),false);
            secondary.setOnClickListener(v->{if(operation.equals("Compress"))shareResult();else finishResult();});
            bottomBar.addView(secondary,new LinearLayout.LayoutParams(-1,dp(44)));
        }
        bottomBar.addView(primary,new LinearLayout.LayoutParams(-1,dp(compressionOptions?40:48)));
    }
    private boolean usefulCompression(){return resultFile!=null&&source!=null&&resultFile.length()*100<=new File(source.path).length()*99;}
    private void registerCompressionCopy(){
        if(compressionRegistered||!usefulCompression())return;DocumentStore library=new DocumentStore(this);try{library.registerDerived(resultFile.getAbsolutePath(),resultName,resultPageCount,originPaths());compressionRegistered=true;toast(UiText.get(R.string.ui_copy_saved));}catch(Exception error){toast(UiText.get(R.string.ui_could_not_save_the_copy_you_can_still_share_or_export_it));}finally{library.close();}
    }
    private void updateCompressionSaveAction(){compressionSaveAction.setVisibility(operation.equals("Compress")&&stage.equals("result")&&usefulCompression()&&!compressionRegistered?View.VISIBLE:View.GONE);}
    private void showImageOptions() { title(UiText.get(R.string.ui_image_format)); Spinner format = new Spinner(this); format.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"JPEG", "PNG"})); format.setSelection(imageFormat.equals("PNG") ? 1 : 0); format.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() { public void onItemSelected(android.widget.AdapterView<?> p, View v, int index, long id) { imageFormat = index == 0 ? "JPEG" : "PNG"; } public void onNothingSelected(android.widget.AdapterView<?> p) {} }); body.addView(format); slider(UiText.get(R.string.ui_jpeg_quality), quality, 100, value -> quality = Math.max(10, value)); note(UiText.get(R.string.ui_page_images_will_be_saved_together_in_a_zip_file_png_uses_lossless_compress)); }
    private void showWatermarkOptions() {
        CheckBox remove=new CheckBox(this);remove.setText(UiText.get(R.string.ui_remove_paperflow_watermark_from_selected_pages));remove.setTextColor(INK);remove.setChecked(watermarkRemove);body.addView(remove);remove.setOnCheckedChangeListener((button,value)->{watermarkRemove=value;refreshWatermarkPreview();});note(UiText.get(R.string.ui_edits_replace_our_editable_watermark_on_selected_pages_watermarks_already_b));
        watermarkPage = Math.max(0, Math.min(watermarkPage, pageSizes.size() - 1));
        watermarkPreview = new WatermarkPreview();
        watermarkPreview.setContentDescription(UiText.get(R.string.ui_watermark_preview));
        body.addView(watermarkPreview, spaced(-1, dp(210), 10));
        LinearLayout navigation = new LinearLayout(this); navigation.setGravity(Gravity.CENTER_VERTICAL);
        TextView previous = action("‹", false), next = action("›", false);
        previous.setContentDescription(UiText.get(R.string.ui_previous_preview_page)); next.setContentDescription(UiText.get(R.string.ui_next_preview_page));
        watermarkPageLabel = label("", 12, MUTED); watermarkPageLabel.setGravity(Gravity.CENTER);
        navigation.addView(previous, new LinearLayout.LayoutParams(dp(48), dp(40)));
        navigation.addView(watermarkPageLabel, new LinearLayout.LayoutParams(0, -2, 1));
        navigation.addView(next, new LinearLayout.LayoutParams(dp(48), dp(40)));
        body.addView(navigation);
        previous.setOnClickListener(v -> { if (watermarkPage > 0) { watermarkPage--; refreshWatermarkPreview(); } });
        next.setOnClickListener(v -> { if (watermarkPage + 1 < pageSizes.size()) { watermarkPage++; refreshWatermarkPreview(); } });
        watermarkTextAction = action(watermark, false);
        AppIcon editIcon = new AppIcon("Edit", BRAND); editIcon.setBounds(0, 0, dp(20), dp(20));
        watermarkTextAction.setCompoundDrawables(null, null, editIcon, null);
        watermarkTextAction.setContentDescription(UiText.get(R.string.ui_edit_watermark_text));
        watermarkTextAction.setSingleLine(); watermarkTextAction.setEllipsize(android.text.TextUtils.TruncateAt.END);
        body.addView(watermarkTextAction, spaced(-1, dp(44), 8));
        watermarkTextAction.setOnClickListener(v -> editWatermarkText());
        LinearLayout modes = new LinearLayout(this);
        TextView single = action(UiText.get(R.string.ui_single), !watermarkTile), tile = action(UiText.get(R.string.ui_tile), watermarkTile);
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, dp(40), 1); first.rightMargin = dp(8);
        modes.addView(single, first); modes.addView(tile, new LinearLayout.LayoutParams(0, dp(40), 1));
        body.addView(modes, spaced(-1, -2, 16));
        Runnable styleModes = () -> {
            single.setBackground(round(watermarkTile ? AppTheme.surface() : AppTheme.accent(), 4));
            tile.setBackground(round(watermarkTile ? AppTheme.accent() : AppTheme.surface(), 4));
            single.setTextColor(watermarkTile ? INK : AppTheme.onAccent()); tile.setTextColor(watermarkTile ? AppTheme.onAccent() : INK);
            single.setSelected(!watermarkTile); tile.setSelected(watermarkTile);
            refreshWatermarkPreview();
        };
        single.setOnClickListener(v -> { if (watermarkTile) { watermarkTile = false; showOptions(); } });
        tile.setOnClickListener(v -> { if (!watermarkTile) { watermarkTile = true; showOptions(); } });
        LinearLayout colors = new LinearLayout(this); colors.setGravity(Gravity.CENTER_VERTICAL);
        int[] palette = {0xFF5A5A5A, Color.BLACK, 0xFFE55050, 0xFF397FE4, 0xFF00A889, Color.WHITE};
        String[] names = {"Gray", "Black", "Red", "Blue", "Green", "White"};
        ArrayList<TextView> swatches = new ArrayList<>();
        for (int i = 0; i < palette.length; i++) {
            int color = palette[i]; TextView swatch = label(color == watermarkColor ? "✓" : "", 15,
                    color == Color.WHITE ? INK : Color.WHITE);
            swatch.setGravity(Gravity.CENTER); swatch.setContentDescription(UiText.format(R.string.ui_value_watermark, UiText.label(names[i])));
            GradientDrawable fill = round(color, 16); fill.setStroke(dp(1), 0xFFDCDCDC); swatch.setBackground(fill);
            swatch.setSelected(color == watermarkColor);
            LinearLayout.LayoutParams circle = new LinearLayout.LayoutParams(dp(28), dp(28)); circle.setMargins(dp(10), dp(8), dp(10), dp(8));
            colors.addView(swatch, circle); swatches.add(swatch);
            swatch.setOnClickListener(v -> { watermarkColor = color; for (int j = 0; j < swatches.size(); j++) {
                swatches.get(j).setText(palette[j] == color ? "✓" : ""); swatches.get(j).setSelected(palette[j] == color);
            } refreshWatermarkPreview(); });
        }
        body.addView(colors, spaced(-1, -2, 8));
        slider(UiText.get(R.string.ui_opacity), opacity, 100, value -> { opacity = value; refreshWatermarkPreview(); }, value -> value + "%");
        slider(UiText.get(R.string.ui_size), watermarkSize, 100, value -> { watermarkSize = value; refreshWatermarkPreview(); }, value -> String.format(Locale.getDefault(), "%.1f×", .2f + 4.8f * value / 100));
        slider(UiText.get(R.string.ui_rotate), Math.round((angle + 180) * 100f / 360), 100, value -> { angle = Math.round(360f * value / 100 - 180); refreshWatermarkPreview(); }, value -> (value == Math.round((angle + 180) * 100f / 360) ? angle : Math.round(360f * value / 100 - 180)) + "°");
        if (watermarkTile) slider(UiText.get(R.string.ui_spacing), watermarkSpacing, 100, value -> { watermarkSpacing = value; refreshWatermarkPreview(); });
        styleModes.run();
        if (reopenWatermarkDialog) { reopenWatermarkDialog = false; body.post(() -> { if (!destroyed && stage.equals("options")) editWatermarkText(); }); }
    }

    private void editWatermarkText() {
        EditText input = new EditText(this); input.setSingleLine(); input.setText(watermarkDraft == null ? watermark : watermarkDraft);
        watermarkDraftInput = input;
        input.setHint(UiText.get(R.string.ui_enter_text_for_your_watermark)); input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(50)});
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(24), dp(12), dp(24), 0);
        content.addView(input, new LinearLayout.LayoutParams(-1, dp(52)));
        TextView count = label(UiText.format(R.string.ui_value_50, input.length()), 12, MUTED); count.setGravity(Gravity.END); content.addView(count);
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int after) { count.setText(UiText.format(R.string.ui_value_50, s.length())); }
            public void afterTextChanged(Editable e) {}
        });
        AlertDialog dialog = new ProtectedDialogs.Builder(this).setTitle(UiText.get(R.string.ui_add_watermark)).setView(content)
                .setNegativeButton(UiText.get(R.string.ui_cancel), null).setPositiveButton(UiText.get(R.string.ui_confirm), null).create();
        watermarkDialog = dialog;
        dialog.setOnDismissListener(ignored -> { if (watermarkDialog == dialog) { watermarkDialog = null; watermarkDraftInput = null; watermarkDraft = null; } });
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (input.getText().toString().trim().isEmpty()) { input.setError(UiText.get(R.string.ui_enter_text_to_insert)); return; }
            watermark = input.getText().toString().trim(); watermarkTextAction.setText(watermark);
            refreshWatermarkPreview(); dialog.dismiss();
        }));
        dialog.show(); input.setSelection(input.length());
    }

    private void refreshWatermarkPreview() {
        if (watermarkPreview == null || !operation.equals("Add Watermark")) return;
        final int page=watermarkPage;final boolean original=!selectedPages.contains(page);final WatermarkPreview target=watermarkPreview;target.bitmap=null;requestThumbnail(page,original,b->{if(watermarkPreview==target&&watermarkPage==page&&original==!selectedPages.contains(page)){target.bitmap=b;target.invalidate();}});
        if (watermarkPageLabel != null) watermarkPageLabel.setText(UiText.format(R.string.ui_preview_value_of_valuevalue, (watermarkPage + 1), pageSizes.size(), (selectedPages.contains(watermarkPage) ? "" : " · not selected")));
        watermarkPreview.invalidate();
    }
    private interface Choice { void set(int value); }
    private void radioOptions(String title, String[] options, int selected, Choice change) { title(title); RadioGroup group = new RadioGroup(this); for (int i = 0; i < options.length; i++) { RadioButton radio = new RadioButton(this); radio.setId(View.generateViewId()); radio.setText(options[i]); group.addView(radio); if (i == selected) radio.setChecked(true); int index = i; radio.setOnClickListener(v -> change.set(index)); } body.addView(group); }
    private interface ValueLabel { String format(int value); }
    private void slider(String label, int value, int max, Choice change) { slider(label, value, max, change, number -> String.valueOf(number)); }
    private void slider(String label, int value, int max, Choice change, ValueLabel format) { TextView name = this.label(UiText.format(R.string.ui_value_value_edd9f856, label, format.format(value)), 13, MUTED); body.addView(name, spaced(-1, -2, 12)); SeekBar seek = new SeekBar(this); seek.setContentDescription(label); seek.setProgressTintList(android.content.res.ColorStateList.valueOf(AppTheme.accent())); seek.setThumbTintList(android.content.res.ColorStateList.valueOf(AppTheme.accent())); seek.setMax(max); seek.setProgress(value); body.addView(seek); seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() { public void onProgressChanged(SeekBar s, int p, boolean user) { name.setText(UiText.format(R.string.ui_value_value_edd9f856, label, format.format(p))); change.set(p); } public void onStartTrackingTouch(SeekBar s) {} public void onStopTrackingTouch(SeekBar s) {} }); }

    private void saveProtectedCopy(PDDocument document,File output)throws Exception{
        if(document.isEncrypted()){com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy policy=new com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy(java.util.UUID.randomUUID().toString(),source.password,document.getCurrentAccessPermission());policy.setEncryptionKeyLength(128);policy.setPreferAES(true);document.protect(policy);}document.save(output);
    }
    private Bundle restoredRotationState;
    private void showRotationOptions(){
        rotationPreview=new ImageView(this);rotationPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);rotationPreview.setContentDescription(UiText.get(R.string.ui_live_rotation_preview));body.addView(rotationPreview,new LinearLayout.LayoutParams(-1,dp(360)));
        rotationLabel=label("",14,INK);body.addView(rotationLabel);LinearLayout controls=new LinearLayout(this);body.addView(controls);
        for(String title:new String[]{"Previous","Rotate left","Rotate right","Next"}){TextView button=label(title,13,BRAND);button.setGravity(Gravity.CENTER);controls.addView(button,new LinearLayout.LayoutParams(0,dp(52),1));button.setOnClickListener(v->{if(title.equals("Previous"))rotationPage=Math.max(0,rotationPage-1);else if(title.equals("Next"))rotationPage=Math.min(pageSizes.size()-1,rotationPage+1);else pageRotations.put(rotationPage,(pageRotations.getOrDefault(rotationPage,0)+(title.equals("Rotate left")?270:90))%360);refreshRotation();});}
        TextView apply=label(UiText.get(R.string.ui_apply_this_rotation_to_selected_pages),14,BRAND);apply.setPadding(8,16,8,16);body.addView(apply);apply.setOnClickListener(v->{int delta=pageRotations.getOrDefault(rotationPage,0);for(int page:selectedPages)pageRotations.put(page,delta);refreshRotation();});refreshRotation();
    }
    private void refreshRotation(){
        if(rotationPreview==null||pageSizes.isEmpty())return;rotationPage=Math.max(0,Math.min(pageSizes.size()-1,rotationPage));int page=rotationPage,delta=pageRotations.getOrDefault(page,0);rotationLabel.setText(UiText.format(R.string.ui_page_value_value_value_fff4ffc6, (page+1), pageSizes.size(), delta));
        rotationPreview.setRotation(0);final ImageView target=rotationPreview;requestThumbnail(page,false,b->{if(rotationPreview==target&&rotationPage==page&&largeRotationPreview==null)target.setImageBitmap(b);});final int token=++generation;
        worker.execute(()->{Bitmap image=null;try(PDDocument document=load(source)){image=render(document,page,1000,1400);if(delta!=0){android.graphics.Matrix matrix=new android.graphics.Matrix();matrix.postRotate(delta);Bitmap rotated=Bitmap.createBitmap(image,0,0,image.getWidth(),image.getHeight(),matrix,true);if(rotated!=image)image.recycle();image=rotated;}Bitmap ready=image;runOnUiThread(()->{if(destroyed||token!=generation||rotationPreview==null){ready.recycle();return;}Bitmap old=largeRotationPreview;largeRotationPreview=ready;rotationPreview.setImageBitmap(ready);if(old!=null&&!old.isRecycled())old.recycle();});}catch(Exception ignored){if(image!=null)image.recycle();}});
    }
    private void process() {
        if(operation.equals("OCR PDF")&&com.paperflow.scanengine.LocalOcr.activeLanguages(this).isEmpty()){startActivity(new Intent(this,LanguageManagerActivity.class).putExtra("kind","ocr"));toast(UiText.get(R.string.ui_download_and_enable_a_language_in_settings_ocr_languages_first));return;}

        if (operation.equals("Lock") || operation.equals("Merge Files")&&mergeNeedsPassword()) { newPassword = passwordInput.getText().toString(); if (newPassword.length() < 6) { passwordInput.setError(UiText.get(R.string.ui_use_at_least_6_characters)); return; } if (!newPassword.equals(confirmationInput.getText().toString())) { confirmationInput.setError(UiText.get(R.string.ui_passwords_do_not_match)); return; } }
        else if (!operation.equals("Merge Files") && !operation.equals("Reorder Pages") && !operation.equals("Compress") && selectedPages.isEmpty()) { toast(UiText.get(R.string.ui_select_at_least_one_page)); return; }
        if (operation.equals("Add Watermark") && !watermarkRemove && watermark.trim().isEmpty()) { toast(UiText.get(R.string.ui_enter_watermark_text)); return; }
        android.view.inputmethod.InputMethodManager keyboard=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(root.getWindowToken(),0);View focused=getCurrentFocus();if(focused!=null)focused.clearFocus();
        ArrayList<Integer> selected = new ArrayList<>(selectedPages); Collections.sort(selected); ArrayList<Integer> order = new ArrayList<>(pageOrder); ArrayList<Source> merging = new ArrayList<>(selectedSources);
        if (operation.equals("Print")) { print(selected); return; }
        if(operation.equals("Compress"))compressionRegistered=false;
        ocrCancelled=false;showProgress(operation.equals("Compress")?"Compressing...":"Processing " + operation.toLowerCase(Locale.ROOT) + "…");
        worker.execute(() -> { File output = null; try {
            boolean imageOutput = operation.equals("PDF to Images") || operation.equals("PDF to Long Image"); String ext = operation.equals("PDF to Images") ? ".zip" : operation.equals("PDF to Long Image") ? ".jpg" : ".pdf"; output = new File(documentsDir(), "paperflow-tool-" + UUID.randomUUID() + ext); resultMime = operation.equals("PDF to Images") ? "application/zip" : operation.equals("PDF to Long Image") ? "image/jpeg" : "application/pdf";
            if (operation.equals("Merge Files")) merge(merging, output);
            else try (PDDocument document = load(source)) {
                switch(operation){case "Rotate PDF":case "OCR PDF":case "Lock":case "Compress":PdfPermissions.modify(document);break;case "Extract PDF Pages":case "Reorder Pages":PdfPermissions.assemble(document);break;case "PDF to Images":case "PDF to Long Image":PdfPermissions.extract(document);break;}
                switch (operation) {
                    case "Rotate PDF": for (int page : selected) document.getPage(page).setRotation((document.getPage(page).getRotation() + pageRotations.getOrDefault(page,0)) % 360); saveProtectedCopy(document, output); break;
                    case "OCR PDF":
                        PdfOcr.apply(this,document,selected,this::updateProgress,()->ocrCancelled||destroyed);
                        if(ocrCancelled||destroyed)throw new java.io.InterruptedIOException(UiText.get(R.string.ui_ocr_cancelled));
                        // Remove unselected pages from this output copy after OCR has used source indices.
                        if(selected.size()!=document.getNumberOfPages())PdfPermissions.assemble(document);HashSet<Integer> included=new HashSet<>(selected);
                        for(int page=document.getNumberOfPages()-1;page>=0;page--)if(!included.contains(page))document.removePage(page);
                        saveProtectedCopy(document,output);break;
                    case "Add Watermark": if(!document.getCurrentAccessPermission().canModifyAnnotations())throw new java.io.IOException(UiText.get(R.string.ui_this_pdf_does_not_permit_watermark_annotations));for (int i = 0; i < selected.size(); i++) { PdfWatermark.remove(document.getPage(selected.get(i)));if(!watermarkRemove)PdfWatermark.append(document, selected.get(i), watermark, watermarkColor, opacity, watermarkSize, angle, watermarkTile, watermarkSpacing); updateProgress(i + 1, selected.size()); } saveWatermarkedCopy(document, output); break;
                    case "Extract PDF Pages": case "Reorder Pages": try (PDDocument extracted = new PDDocument()) { for (int page : operation.equals("Reorder Pages") ? order : selected) importPage(extracted, document.getPage(page)); PdfProtection.inherit(document,extracted,source.password);extracted.save(output); } break;
                    case "Remove PDF password": if(!document.getCurrentAccessPermission().canModify())throw new java.io.IOException(UiText.get(R.string.ui_this_pdf_does_not_allow_changes));document.setAllSecurityToBeRemoved(true);document.save(output);break;
                    case "Lock": document.setAllSecurityToBeRemoved(false); AccessPermission permission = new AccessPermission(); permission.setCanPrint(true); StandardProtectionPolicy protection = new StandardProtectionPolicy(UUID.randomUUID().toString(), newPassword, permission); protection.setEncryptionKeyLength(128);protection.setPreferAES(true); document.protect(protection); document.save(output); break;
                    case "Compress": compress(document, output); break;
                    case "PDF to Images": exportImages(document, selected, output); break;
                    case "PDF to Long Image": exportLongImage(document, selected, output); break;
                    default: throw new IllegalArgumentException(UiText.get(R.string.ui_unsupported_tool));
                }
            }
            resultPassword = operation.equals("Merge Files") ? (mergeNeedsPassword()?newPassword:"") : operation.equals("Lock") ? newPassword : operation.equals("Compress") || operation.equals("Add Watermark") || operation.equals("OCR PDF") || operation.equals("Rotate PDF") || operation.equals("Extract PDF Pages") || operation.equals("Reorder Pages") ? source.password : "";
            if (operation.equals("Compress") && output.length()*100 > new File(source.path).length()*99) { try (InputStream in = new FileInputStream(source.path); OutputStream out = new FileOutputStream(output)) { copy(in, out); } resultPassword = source.password; }
            File saved = output; resultName = source.name + " · " + operation; Bitmap preview = null; int resultPages = 0;
            if (!imageOutput) try (PDDocument resultDoc = PDDocument.load(saved, resultPassword)) { resultPages = resultDoc.getNumberOfPages(); preview = render(resultDoc, 0, 1000, 1400); }
            else if (operation.equals("PDF to Long Image")) { android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options(); options.inSampleSize = 4; preview = android.graphics.BitmapFactory.decodeFile(saved.getAbsolutePath(), options); }
            DocumentStore.inheritProtection(this,saved.getAbsolutePath(),originPaths());Bitmap resultPreview = preview; int count = resultPages; runOnUiThread(() -> { if (destroyed) { if (resultPreview != null) resultPreview.recycle(); return; } resultFile = saved; showResult(resultPreview, count); });
        } catch (Exception e) { if (output != null) output.delete(); runOnUiThread(() -> { if (!destroyed) { showOptions(); toast(UiText.format(R.string.ui_could_not_complete_value_value, UiText.label(operation), e.getMessage())); } }); }
        });
    }

    private void saveUnlockedCopy(PDDocument document, File output) throws Exception { document.setAllSecurityToBeRemoved(true); document.save(output); }
    private void saveWatermarkedCopy(PDDocument document, File output) throws Exception {
        if (document.isEncrypted()) {
            document.setAllSecurityToBeRemoved(false);
            StandardProtectionPolicy protection = new StandardProtectionPolicy(UUID.randomUUID().toString(), source.password, document.getCurrentAccessPermission());
            protection.setEncryptionKeyLength(128); protection.setPreferAES(true); document.protect(protection);
            document.save(output);
        } else saveUnlockedCopy(document, output);
    }
    private boolean mergeNeedsPassword(){for(Source input:selectedSources)if(input.encrypted)return true;return false;}
    private void merge(ArrayList<Source> inputs, File output) throws Exception { try (PDDocument merged = new PDDocument()) { PDFMergerUtility merger = new PDFMergerUtility(); for (int i = 0; i < inputs.size(); i++) { try (PDDocument doc = load(inputs.get(i))) { PdfPermissions.assemble(doc);merger.appendDocument(merged, doc); } updateProgress(i + 1, inputs.size()); } if(mergeNeedsPassword()){AccessPermission permission=new AccessPermission();for(Source input:inputs)try(PDDocument doc=load(input)){AccessPermission p=doc.getCurrentAccessPermission();permission.setCanPrint(permission.canPrint()&&p.canPrint());permission.setCanModify(permission.canModify()&&p.canModify());permission.setCanExtractContent(permission.canExtractContent()&&p.canExtractContent());permission.setCanExtractForAccessibility(permission.canExtractForAccessibility()&&p.canExtractForAccessibility());permission.setCanModifyAnnotations(permission.canModifyAnnotations()&&p.canModifyAnnotations());permission.setCanFillInForm(permission.canFillInForm()&&p.canFillInForm());permission.setCanAssembleDocument(permission.canAssembleDocument()&&p.canAssembleDocument());permission.setCanPrintDegraded(permission.canPrintDegraded()&&p.canPrintDegraded());}StandardProtectionPolicy policy=new StandardProtectionPolicy(UUID.randomUUID().toString(),newPassword,permission);policy.setEncryptionKeyLength(128);policy.setPreferAES(true);merged.protect(policy);} merged.save(output);ArrayList<File> files=new ArrayList<>();ArrayList<String> passwords=new ArrayList<>();for(Source input:inputs){files.add(new File(input.path));passwords.add(input.password);}ScanArchive.combine(this,files,passwords,output); } }
    private void importPage(PDDocument target, PDPage sourcePage) throws Exception { PDPage imported = target.importPage(sourcePage); if (sourcePage.getResources() != null) imported.setResources(sourcePage.getResources()); imported.setRotation(sourcePage.getRotation()); }
    private void compress(PDDocument document, File output) throws Exception { PdfCompression.images(document,preset);updateProgress(document.getNumberOfPages(),document.getNumberOfPages());saveWatermarkedCopy(document,output); }
    private void exportImages(PDDocument document, ArrayList<Integer> selected, File output) throws Exception { try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(output))) { for (int i = 0; i < selected.size(); i++) { int page = selected.get(i); Bitmap bitmap = render(document, page, 1800, 2400); try { boolean png = imageFormat.equals("PNG"); zip.putNextEntry(new ZipEntry("page-" + (page + 1) + (png ? ".png" : ".jpg"))); bitmap.compress(png ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG, quality, zip); zip.closeEntry(); } finally { bitmap.recycle(); } updateProgress(i + 1, selected.size()); } } }
    private void exportLongImage(PDDocument document, ArrayList<Integer> selected, File output) throws Exception {
        try { LongImageExporter.write(document, pageSizes, selected, longImageQuality, output,
                new LongImageExporter.Job(), this::updateProgress); }
        catch (OutOfMemoryError error) { throw new java.io.IOException(UiText.get(R.string.ui_not_enough_memory_select_fewer_pages), error); }
    }

    private void showResult(Bitmap preview, int pageCount) {
        stage = "result"; generation++; body.removeAllViews(); if(largeRotationPreview!=null&&!largeRotationPreview.isRecycled())largeRotationPreview.recycle();largeRotationPreview=null;releaseResultPreview();configureBottom(false); resultPage = 0; resultPageCount = pageCount; primary.setEnabled(true); primary.setText(UiText.get(R.string.ui_save_to_device)); if(!operation.equals("Compress")){title(operation.equals("OCR PDF") ? UiText.get(R.string.ui_searchable_pdf_ready) : UiText.get(R.string.ui_ready));note(resultName);if(operation.equals("OCR PDF"))note(UiText.get(R.string.ui_save_or_share_this_pdf_to_select_copy_and_search_its_recognized_text_in_pap));} unlockOrientation();
        if(operation.equals("Compress")&&compressionSaveCopy)registerCompressionCopy();updateCompressionSaveAction();
        if (preview != null) { resultBitmap = preview; resultImage = new ImageView(this); resultImage.setImageBitmap(preview); resultImage.setScaleType(ImageView.ScaleType.FIT_CENTER); resultImage.setBackgroundColor(AppTheme.surface());int previewHeight=operation.equals("Compress")?Math.max(220,Math.min(570,(int)(getResources().getDisplayMetrics().heightPixels/getResources().getDisplayMetrics().density)-270)):350; body.addView(resultImage, spaced(-1, dp(previewHeight), 12)); }
        if (pageCount > 1) { LinearLayout navigation = new LinearLayout(this); navigation.setGravity(Gravity.CENTER_VERTICAL); TextView previous = action(UiText.get(R.string.ui_previous), false), next = action(UiText.get(R.string.ui_next_page), false); resultPageLabel = label(UiText.format(R.string.ui_page_1_of_value, pageCount), 13, MUTED); resultPageLabel.setGravity(Gravity.CENTER); navigation.addView(previous, new LinearLayout.LayoutParams(0, dp(42), 1)); navigation.addView(resultPageLabel, new LinearLayout.LayoutParams(0, dp(42), 1)); navigation.addView(next, new LinearLayout.LayoutParams(0, dp(42), 1)); body.addView(navigation, spaced(-1, dp(42), 8)); previous.setOnClickListener(v -> changeResultPage(-1)); next.setOnClickListener(v -> changeResultPage(1)); }
        if (pageCount > 0&&!operation.equals("Compress")) note(UiText.format(R.string.ui_value_pages, pageCount)); String before = android.text.format.Formatter.formatFileSize(this, new File(source.path).length()), after = android.text.format.Formatter.formatFileSize(this, resultFile.length()); if(operation.equals("Compress")){if(usefulCompression()){double saved=100.0*(new File(source.path).length()-resultFile.length())/new File(source.path).length();note(UiText.format(R.string.ui_value_value_saving_value, before, after, String.format(Locale.getDefault(),"%.1f%%",saved)));}else note(UiText.get(R.string.ui_this_document_is_already_at_an_ideal_size));}else note(UiText.format(R.string.ui_file_size_value, after));
        if(!operation.equals("Compress")){TextView share = action(UiText.get(R.string.ui_share), false); body.addView(share, spaced(-1, dp(46), 14)); share.setOnClickListener(v -> shareResult());}
    }

    private void requestSaveToDevice(){
        if(resultFile==null||!resultFile.isFile())return;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType(resultMime);
        intent.putExtra(Intent.EXTRA_TITLE,resultName+(resultMime.equals("application/pdf")?".pdf":resultMime.equals("application/zip")?".zip":".jpg"));saveLauncher.launch(intent);
    }

    private void changeResultPage(int direction) { int page = resultPage + direction; if (page < 0 || page >= resultPageCount || resultImage == null) return; resultPage = page; int version = generation; resultPageLabel.setText(UiText.format(R.string.ui_page_value_of_value, (page + 1), resultPageCount)); worker.execute(() -> { try (PDDocument doc = PDDocument.load(resultFile, resultPassword)) { Bitmap rendered = render(doc, page, 1000, 1400); runOnUiThread(() -> { if (destroyed || !stage.equals("result") || version != generation || page != resultPage) { rendered.recycle(); return; } Bitmap old = resultBitmap; resultBitmap = rendered; resultImage.setImageBitmap(rendered); if (old != null) old.recycle(); }); } catch (Exception e) { runOnUiThread(() -> toast(UiText.get(R.string.ui_could_not_display_result_page))); } }); }
    private void releaseResultPreview() { if (resultImage != null) { resultImage.setImageDrawable(null); resultImage = null; } if (resultBitmap != null && !resultBitmap.isRecycled()) resultBitmap.recycle(); resultBitmap = null; resultPageLabel = null; }

    private ArrayList<String> originPaths(){ArrayList<String> paths=new ArrayList<>();for(Source item:selectedSources)paths.add(item.path);return paths;}
    private void finishResult() { if (resultFile == null) return; if (resultMime.equals("application/pdf")) { Intent intent = new Intent(this, MainActivity.class); intent.putExtra("document_action", "Register"); intent.putExtra("path", resultFile.getAbsolutePath()); intent.putExtra("name", resultName); intent.putExtra("pages", resultPageCount); intent.putStringArrayListExtra("origin_paths",originPaths()); intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP); startActivity(intent); } finish(); }
    private int mergedPageCount() { int count = 0; for (Source s : selectedSources) count += s.pages; return count; }
    private void shareResult() { try { Intent share = new Intent(Intent.ACTION_SEND); share.setType(resultMime); share.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(this, getPackageName() + ".files", resultFile)); share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(Intent.createChooser(share, UiText.format(R.string.ui_share_value, UiText.label(operation)))); } catch (Exception e) { toast(UiText.get(R.string.ui_could_not_share_result)); } }
    private void saveToDevice(Uri uri) { for(String origin:originPaths())if(!FolderLock.ensureDocument(this,origin,()->saveToDevice(uri)))return;File file = resultFile;if(file==null)return; worker.execute(() -> { try (InputStream in = new FileInputStream(file); OutputStream out = getContentResolver().openOutputStream(uri)) { if (out == null) throw new IllegalArgumentException(); copy(in, out); runOnUiThread(() -> toast(UiText.get(R.string.ui_file_saved))); } catch (Exception e) { runOnUiThread(() -> toast(UiText.get(R.string.ui_could_not_save_file))); } }); }

    private void print(ArrayList<Integer> selected) { PrintManager manager = (PrintManager) getSystemService(PRINT_SERVICE); Source printing = source; ExecutorService printWorker = Executors.newSingleThreadExecutor(); manager.print(source.name, new PrintDocumentAdapter() {
        @Override public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes, CancellationSignal signal, LayoutResultCallback callback, Bundle extras) { if (signal.isCanceled()) callback.onLayoutCancelled(); else callback.onLayoutFinished(new PrintDocumentInfo.Builder(printing.name + ".pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).setPageCount(selected.size()).build(), true); }
        @Override public void onWrite(PageRange[] ranges, ParcelFileDescriptor destination, CancellationSignal signal, WriteResultCallback callback) { printWorker.execute(() -> { try (PDDocument original = load(printing); PDDocument out = new PDDocument(); OutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(destination)) { PdfPermissions.print(original);for (int i = 0; i < selected.size(); i++) { if (signal.isCanceled()) { callback.onWriteCancelled(); return; } boolean requested = false; for (PageRange range : ranges) if (i >= range.getStart() && i <= range.getEnd()) requested = true; if (requested) importPage(out, original.getPage(selected.get(i))); } if (out.getNumberOfPages() == 0) { callback.onWriteFailed("No pages selected"); return; } out.save(stream); callback.onWriteFinished(ranges); } catch (Exception e) { callback.onWriteFailed("Could not prepare PDF for printing"); } }); }
        @Override public void onFinish() { printWorker.shutdown(); super.onFinish(); }
    }, new PrintAttributes.Builder().build()); }

    private class WatermarkPreview extends View {
        Bitmap bitmap; final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        WatermarkPreview() { super(ToolWorkflowActivity.this); setBackgroundColor(Color.WHITE); }
        @Override protected void onDraw(Canvas canvas) {
            if (bitmap == null || bitmap.isRecycled()) return;
            float[] dimensions = pageSizes.get(watermarkPage);
            float scale = Math.min(getWidth() / dimensions[0], getHeight() / dimensions[1]);
            float w = dimensions[0] * scale, h = dimensions[1] * scale;
            float x = (getWidth() - w) / 2, y = (getHeight() - h) / 2;
            paint.setColor(Color.WHITE); canvas.drawBitmap(bitmap, null, new RectF(x, y, x + w, y + h), paint);
            if (!watermarkRemove && selectedPages.contains(watermarkPage)) {
                canvas.save(); canvas.translate(x, y); canvas.scale(scale, scale);
                PdfWatermark.draw(canvas, dimensions[0], dimensions[1], watermark, watermarkColor,
                        opacity, watermarkSize, angle, watermarkTile, watermarkSpacing);
                canvas.restore();
            }
        }
    }

    private void showProgress(String message) { stage = "progress"; invalidatePreviewRequests();generation++; body.removeAllViews(); if(largeRotationPreview!=null&&!largeRotationPreview.isRecycled())largeRotationPreview.recycle();largeRotationPreview=null;releaseResultPreview();configureBottom(false);compressionSaveAction.setVisibility(View.GONE);if(operation.equals("Compress"))bottomBar.setVisibility(View.GONE); primary.setEnabled(false); primary.setText(UiText.get(R.string.ui_processing)); progressText = label(message, 16, INK); progressText.setGravity(Gravity.CENTER); body.addView(progressText, spaced(-1, dp(120), 20)); progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progressBar.setIndeterminate(true); body.addView(progressBar, spaced(-1, dp(8), 10)); if(operation.equals("OCR PDF")){TextView cancel=action(UiText.get(R.string.ui_cancel_ocr),false);body.addView(cancel,spaced(-1,dp(48),18));cancel.setOnClickListener(v->{ocrCancelled=true;cancel.setEnabled(false);progressText.setText(UiText.get(R.string.ui_cancelling_after_the_current_page));});} lockOrientation(); }
    private void updateProgress(int done, int total) { runOnUiThread(() -> { if (destroyed || !stage.equals("progress")) return; progressBar.setIndeterminate(false); progressBar.setMax(total); progressBar.setProgress(done); progressText.setText(operation.equals("Compress") ? UiText.format(R.string.ui_compressing_value_value, done, total) : UiText.format(R.string.ui_processing_value_of_value, done, total)); }); }
    private void lockOrientation() { if (!orientationLocked) { originalOrientation = getRequestedOrientation(); orientationLocked = true; setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED); } }
    private void unlockOrientation() { if (orientationLocked) { orientationLocked = false; setRequestedOrientation(originalOrientation); } }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("page_window",pageWindow);state.putString("workflow_stage", stage); ArrayList<String> paths = new ArrayList<>(), names = new ArrayList<>(), passwords = new ArrayList<>(), chosen = new ArrayList<>(); ArrayList<Integer> counts = new ArrayList<>();
        for (Source item : sources) { paths.add(item.path); names.add(item.name); passwords.add(item.password); counts.add(item.pages); }
        for (Source item : selectedSources) chosen.add(item.path);
        state.putStringArrayList("source_paths", paths); state.putStringArrayList("source_names", names); state.putStringArrayList("source_passwords", passwords); state.putIntegerArrayList("source_counts", counts); state.putStringArrayList("chosen_sources", chosen); state.putString("active_source", source == null ? null : source.path);
        state.putIntegerArrayList("chosen_pages", new ArrayList<>(selectedPages)); state.putIntegerArrayList("page_order", new ArrayList<>(pageOrder)); state.putInt("rotation", rotation); state.putInt("opacity", opacity); state.putInt("angle", angle); state.putInt("quality", quality); state.putInt("preset", preset); state.putString("watermark", watermark); state.putString("image_format", imageFormat);
        state.putInt("watermark_color", watermarkColor); state.putInt("watermark_size", watermarkSize); state.putInt("watermark_spacing", watermarkSpacing); state.putInt("watermark_page", watermarkPage); state.putBoolean("watermark_tile", watermarkTile);
        state.putBoolean("watermark_dialog", watermarkDialog != null && watermarkDialog.isShowing());
        if (watermarkDraftInput != null) state.putString("watermark_draft", watermarkDraftInput.getText().toString());
        state.putBoolean("watermark_remove",watermarkRemove);state.putBoolean("compression_save_copy",compressionSaveCopy);state.putBoolean("compression_registered",compressionRegistered);state.putInt("long_image_quality",longImageQuality);state.putString("new_password", passwordInput != null ? passwordInput.getText().toString() : newPassword); state.putString("confirmation", confirmationInput != null ? confirmationInput.getText().toString() : restoredConfirmation);
        state.putString("result_path", resultFile == null ? null : resultFile.getAbsolutePath()); state.putString("result_name", resultName); state.putString("result_mime", resultMime); state.putString("result_password", resultPassword); state.putInt("result_count", resultPageCount); state.putInt("result_page", resultPage); state.putBoolean("orientation_locked", orientationLocked); state.putInt("original_orientation", originalOrientation);
        float[] dimensions = new float[pageSizes.size() * 2]; for (int i = 0; i < pageSizes.size(); i++) { dimensions[i * 2] = pageSizes.get(i)[0]; dimensions[i * 2 + 1] = pageSizes.get(i)[1]; } state.putFloatArray("page_sizes", dimensions);
        super.onSaveInstanceState(state);state.putInt("rotation_preview_page",rotationPage);for(java.util.Map.Entry<Integer,Integer> entry:pageRotations.entrySet())state.putInt("page_rotation_"+entry.getKey(),entry.getValue());
    }

    private void restoreWorkflowState(Bundle state) {
        ArrayList<String> paths = state.getStringArrayList("source_paths"), names = state.getStringArrayList("source_names"), passwords = state.getStringArrayList("source_passwords"), chosen = state.getStringArrayList("chosen_sources"); ArrayList<Integer> counts = state.getIntegerArrayList("source_counts");
        if (paths != null) { sources.clear(); selectedSources.clear(); for (int i = 0; i < paths.size(); i++) { if (!new File(paths.get(i)).isFile()) continue; Source item = new Source(paths.get(i), names != null && i < names.size() ? names.get(i) : null); if (passwords != null && i < passwords.size()) item.password = passwords.get(i); if (counts != null && i < counts.size()) item.pages = counts.get(i); sources.add(item); }
            if (chosen != null) for (String path : chosen) for (Source item : sources) if (item.path.equals(path)) selectedSources.add(item);
        }
        String active = state.getString("active_source"); for (Source item : sources) if (item.path.equals(active)) source = item; if (source == null && !selectedSources.isEmpty()) source = selectedSources.get(0);
        rotation = state.getInt("rotation", 90); opacity = state.getInt("opacity", 30); angle = state.getInt("angle", -45); quality = state.getInt("quality", 90); preset = state.getInt("preset"); watermark = state.getString("watermark", "Confidential"); imageFormat = state.getString("image_format", "JPEG"); newPassword = state.getString("new_password", ""); restoredConfirmation = state.getString("confirmation", "");
        watermarkColor = state.getInt("watermark_color", Color.BLACK); watermarkSize = state.getInt("watermark_size", 17); watermarkSpacing = state.getInt("watermark_spacing", 30); watermarkPage = state.getInt("watermark_page"); watermarkTile = state.getBoolean("watermark_tile");
        reopenWatermarkDialog = state.getBoolean("watermark_dialog"); watermarkDraft = state.getString("watermark_draft");
        watermarkRemove=state.getBoolean("watermark_remove");compressionSaveCopy=state.getBoolean("compression_save_copy",compressionSaveCopy);compressionRegistered=state.getBoolean("compression_registered");longImageQuality=state.getInt("long_image_quality");String output = state.getString("result_path"); if (output != null) resultFile = new File(output); resultName = state.getString("result_name"); resultMime = state.getString("result_mime"); resultPassword = state.getString("result_password", ""); orientationLocked = state.getBoolean("orientation_locked"); originalOrientation = state.getInt("original_orientation", android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        ArrayList<Integer> selected = state.getIntegerArrayList("chosen_pages"), order = state.getIntegerArrayList("page_order"); if (selected != null) selectedPages.addAll(selected); if (order != null) pageOrder.addAll(order); float[] dimensions = state.getFloatArray("page_sizes"); if (dimensions != null) for (int i = 0; i + 1 < dimensions.length; i += 2) pageSizes.add(new float[]{dimensions[i], dimensions[i + 1]});
    }

    private void restoreResult(int count, int page) {
        showProgress("Restoring result…"); worker.execute(() -> { Bitmap preview = null; try { if ("application/pdf".equals(resultMime)) try (PDDocument document = PDDocument.load(resultFile, resultPassword)) { preview = render(document, 0, 1000, 1400); } else if ("image/jpeg".equals(resultMime)) { android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options(); options.inSampleSize = 4; preview = android.graphics.BitmapFactory.decodeFile(resultFile.getAbsolutePath(), options); } Bitmap rendered = preview; runOnUiThread(() -> { if (destroyed) { if (rendered != null) rendered.recycle(); return; } showResult(rendered, count); if (page > 0 && page < count) changeResultPage(page); }); }
            catch (Exception e) { if (preview != null) preview.recycle(); runOnUiThread(() -> { if (!destroyed) { showSources(); toast(UiText.get(R.string.ui_could_not_restore_the_result_preview)); } }); }
        });
    }
    private PDDocument load(Source item) throws Exception { return PDDocument.load(new File(item.path), item.password, MemoryUsageSetting.setupMixed(24 * 1024 * 1024)); }
    private Bitmap render(PDDocument document, int page, int maxWidth, int maxHeight) throws Exception { PDPage p = document.getPage(page); PDRectangle b = p.getCropBox(); boolean turn = Math.abs(p.getRotation()) % 180 == 90; float width = turn ? b.getHeight() : b.getWidth(), height = turn ? b.getWidth() : b.getHeight(); float scale = Math.min(maxWidth / width, maxHeight / height); return new PDFRenderer(document).renderImage(page, scale); }
    private void copy(InputStream in, OutputStream out) throws Exception { byte[] buffer = new byte[16384]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n); }
    private File documentsDir() { File dir = new File(getFilesDir(), "documents"); if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException(UiText.get(R.string.ui_storage_unavailable)); return dir; }
    private void clearThumbnails() { invalidatePreviewRequests();thumbnailCache.evictAll(); }
    private void goBack() { if (stage.equals("progress")) { if(operation.equals("OCR PDF")){ocrCancelled=true;progressText.setText(UiText.get(R.string.ui_cancelling_after_the_current_page));}else toast(UiText.get(R.string.ui_please_wait_for_processing_to_finish)); return; } if (stage.equals("options")) showSources(); else if (stage.equals("result")) { if (pageSizes.isEmpty() && !operation.equals("Merge Files") && !operation.equals("Lock") && !operation.equals("Compress")) { restoredOptions = new Bundle(); restoredOptions.putIntegerArrayList("chosen_pages", new ArrayList<>(selectedPages)); restoredOptions.putIntegerArrayList("page_order", new ArrayList<>(pageOrder)); continueFromSources(); } else showOptions(); } else finish(); }
    @Override public void handleBackPressed() { goBack(); }
    private void title(String value) { TextView t = label(value, 20, INK); t.setTypeface(null,android.graphics.Typeface.BOLD); body.addView(t, spaced(-1, -2, 12)); }
    private void note(String value) { TextView t = label(value, 14, MUTED); t.setLineSpacing(dp(3), 1); body.addView(t, spaced(-1, -2, 10)); }
    private TextView label(String value, int size, int color) { TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(color); return t; }
    private TextView action(String value, boolean filled) { TextView t = label(value, 16, filled ? AppTheme.onAccent() : BRAND); t.setGravity(Gravity.CENTER); t.setTypeface(null,android.graphics.Typeface.BOLD); t.setBackground(round(filled ? BRAND : AppTheme.surface(), 8));AppTheme.polishAction(t); t.setPadding(dp(12), 0, dp(12), 0); return t; }
    private GradientDrawable round(int color, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable; }
    private LinearLayout.LayoutParams spaced(int width, int height, int marginTop) { LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height); p.topMargin = dp(marginTop); return p; }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String value) { if (!destroyed) Toast.makeText(this, value, Toast.LENGTH_LONG).show(); }
    private static class Source { final String path, name; String password = ""; Bitmap thumbnail; ImageView preview; boolean thumbnailPending,encrypted; int pages; Source(String path, String name) { this.path = path; this.name = name == null ? new File(path).getName() : name; } }
    @Override protected void onResume(){super.onResume();ArrayList<String> paths=new ArrayList<>();for(Source selected:selectedSources)paths.add(selected.path);FolderLock.guardAll(this,paths);}
    @Override protected void onDestroy() { destroyed = true; generation++; if (watermarkDialog != null) watermarkDialog.dismiss(); body.removeAllViews(); if(largeRotationPreview!=null&&!largeRotationPreview.isRecycled())largeRotationPreview.recycle();largeRotationPreview=null;releaseResultPreview(); clearThumbnails(); for (Bitmap b : ownedBitmaps) if (!b.isRecycled()) b.recycle(); ownedBitmaps.clear(); worker.shutdown();previewWorker.shutdown(); super.onDestroy(); }
}

package com.example.myapplication;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.rendering.PDFRenderer;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Document detail view. PDF handles and rendering are confined to one worker. */
public class PdfViewerActivity extends AppCompatActivity {
    private final int INK = AppTheme.ink(), MUTED = AppTheme.muted(), BG = AppTheme.background(), BRAND = AppTheme.accent();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<PageSlot> slots = new ArrayList<>();
    private final ArrayList<float[]> sizes = new ArrayList<>();
    private PdfRenderer renderer;
    private ParcelFileDescriptor descriptor;
    private PDDocument boxDocument;
    private ScrollView scroll;
    private LinearLayout pages;
    private TextView titleLabel;
    private String path, documentName, password = "";
    private volatile boolean destroyed;
    private boolean grid;
    private int currentPage, viewVersion;
    private ActivityResultLauncher<String> saveLauncher,textSaveLauncher;
    private File textExport;private PdfPageSelection selection;private int selectionRequest;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        path = getIntent().getStringExtra("path");
        documentName = getIntent().getStringExtra("name");
        if (documentName == null || documentName.trim().isEmpty()) documentName = path == null ? "Document" : new File(path).getName().replaceFirst("(?i)\\.pdf$", "");
        if (state != null) { grid = state.getBoolean("grid"); currentPage = state.getInt("page"); }
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        saveLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("application/pdf"), uri -> { if (uri != null) saveToDevice(uri); });
        textSaveLauncher=registerForActivityResult(new ActivityResultContracts.CreateDocument("text/plain"),uri->{if(uri!=null&&textExport!=null)saveText(uri);});
        if(state!=null&&state.getString("text_export")!=null)textExport=new File(state.getString("text_export"));
        buildUi();
        if (path == null || !new File(path).isFile()) { showInvalid("This document is no longer available."); return; }
        openDocument("");
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        LinearLayout toolbar = new LinearLayout(this); toolbar.setGravity(Gravity.CENTER_VERTICAL); toolbar.setBackgroundColor(AppTheme.surface()); toolbar.setPadding(dp(4), 0, dp(4), 0);
        ImageView back = icon("Back", UiText.get(R.string.ui_back), 12); toolbar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(56))); back.setOnClickListener(v -> finish());
        titleLabel = new TextView(this); titleLabel.setText(documentName); titleLabel.setTextColor(INK); titleLabel.setTextSize(17); titleLabel.setSingleLine(); titleLabel.setEllipsize(TextUtils.TruncateAt.MIDDLE); titleLabel.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.addView(titleLabel, new LinearLayout.LayoutParams(0, -1, 1)); titleLabel.setOnClickListener(v -> rename());
        ImageView editTitle = icon("Edit", UiText.get(R.string.ui_rename_document), 13); toolbar.addView(editTitle, new LinearLayout.LayoutParams(dp(38), dp(56))); editTitle.setOnClickListener(v -> rename());
        String tags=getIntent().getStringExtra("tags");TextView tagButton=text(tags==null||tags.isEmpty() ? UiText.get(R.string.ui_tags_47f5e443) : UiText.get(R.string.ui_tags),12,MUTED);tagButton.setGravity(Gravity.CENTER);tagButton.setContentDescription(tags==null||tags.isEmpty() ? UiText.get(R.string.ui_tags) : UiText.format(R.string.ui_tags_value, tags));toolbar.addView(tagButton,new LinearLayout.LayoutParams(dp(52),dp(56)));tagButton.setOnClickListener(v->dispatch("Tags"));
        ImageView tiles = icon("Tools", UiText.get(R.string.ui_page_grid), 13); toolbar.addView(tiles, new LinearLayout.LayoutParams(dp(42), dp(56))); tiles.setOnClickListener(v -> toggleGrid());
        TextView overflow = new TextView(this); overflow.setText("\u22ee"); overflow.setTextSize(29); overflow.setTextColor(INK); overflow.setGravity(Gravity.CENTER); overflow.setContentDescription(UiText.get(R.string.ui_more_options)); toolbar.addView(overflow, new LinearLayout.LayoutParams(dp(36), dp(56))); overflow.setOnClickListener(v -> showMenu(overflow));
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(56)));
        scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false); scroll.setPadding(dp(12), dp(12), dp(12), dp(18));
        pages = new LinearLayout(this); pages.setOrientation(LinearLayout.VERTICAL); scroll.addView(pages, new ScrollView.LayoutParams(-1, -2));
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> refreshVisiblePages());
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        View separator = new View(this); separator.setBackgroundColor(AppTheme.border()); root.addView(separator, new LinearLayout.LayoutParams(-1, dp(1)));
        LinearLayout actions = new LinearLayout(this); actions.setBackgroundColor(AppTheme.surface()); actions.setGravity(Gravity.CENTER); actions.setPadding(dp(5), dp(3), dp(5), dp(3));
        addAction(actions, "Add", "Camera", () -> dispatch("Add"));
        addAction(actions, "Edit", "Edit", () -> dispatch("Edit"));
        addAction(actions, "Share", "Share", this::shareDocument);
        addAction(actions, "To Word", "To Word", () -> dispatch("To Word"));
        addAction(actions, "Sign", "Sign", this::chooseSignaturePage);
        root.addView(actions, new LinearLayout.LayoutParams(-1, dp(64))); UiInsets.apply(root); setContentView(root);
        TextView loading = text(UiText.get(R.string.ui_opening_document), 14, MUTED); loading.setGravity(Gravity.CENTER); pages.addView(loading, new LinearLayout.LayoutParams(-1, dp(200)));
    }

    private void addAction(LinearLayout parent, String label, String iconName, Runnable action) {
        TextView button = text(UiText.label(label), 11, INK); button.setGravity(Gravity.CENTER); button.setContentDescription(UiText.label(label));
        AppIcon drawable = new AppIcon(iconName, INK); drawable.setBounds(0, 0, dp(26), dp(26)); button.setCompoundDrawables(null, drawable, null, null); button.setCompoundDrawablePadding(dp(4));
        parent.addView(button, new LinearLayout.LayoutParams(0, -1, 1)); button.setOnClickListener(v -> { if (!sizes.isEmpty()) action.run(); });
    }

    private void openDocument(String enteredPassword) {
        worker.execute(() -> {
            try {
                closeDocument(); PDFBoxResourceLoader.init(getApplicationContext());
                if (enteredPassword.isEmpty()) {
                    try { descriptor = ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY); renderer = new PdfRenderer(descriptor); }
                    catch (Exception androidError) { if (descriptor != null) descriptor.close(); descriptor = null; renderer = null; boxDocument = PDDocument.load(new File(path)); }
                } else boxDocument = PDDocument.load(new File(path), enteredPassword);
                password = enteredPassword;
                int count = renderer != null ? renderer.getPageCount() : boxDocument.getNumberOfPages();
                if (count == 0) throw new IllegalArgumentException(UiText.get(R.string.ui_the_pdf_has_no_pages_ce04a739));
                ArrayList<float[]> found = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    if (renderer != null) { try (PdfRenderer.Page page = renderer.openPage(i)) { found.add(new float[]{page.getWidth(), page.getHeight()}); } }
                    else { PDPage page = boxDocument.getPage(i); PDRectangle r = page.getCropBox(); boolean turn = Math.abs(page.getRotation()) % 180 == 90; found.add(new float[]{turn ? r.getHeight() : r.getWidth(), turn ? r.getWidth() : r.getHeight()}); }
                }
                runOnUiThread(() -> { if (destroyed) return; sizes.clear(); sizes.addAll(found); currentPage = Math.min(currentPage, sizes.size() - 1); rebuildPages(currentPage); if (getIntent().getBooleanExtra("start_sign", false)) { getIntent().removeExtra("start_sign"); chooseSignaturePage(); } });
            } catch (InvalidPasswordException e) { runOnUiThread(() -> { if (!destroyed) requestPassword(!enteredPassword.isEmpty()); }); }
            catch (Exception e) { runOnUiThread(() -> { if (!destroyed) showInvalid("This file could not be opened as a PDF. It may be damaged or unsupported."); }); }
        });
    }

    private void requestPassword(boolean retry) {
        EditText input = new EditText(this); input.setSingleLine(); input.setInputType(129); input.setHint(UiText.get(R.string.ui_pdf_password));
        AlertDialog dialog = new ProtectedDialogs.Builder(this).setTitle(UiText.get(R.string.ui_password_protected_pdf)).setMessage(retry ? UiText.get(R.string.ui_incorrect_password_try_again) : UiText.get(R.string.ui_enter_the_password_to_open_this_document)).setView(input).setCancelable(false).setNegativeButton(UiText.get(R.string.ui_close), (d, w) -> finish()).setPositiveButton(UiText.get(R.string.ui_open), null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { String value = input.getText().toString(); if (value.isEmpty()) { input.setError(UiText.get(R.string.ui_enter_the_password)); return; } dialog.dismiss(); openDocument(value); })); dialog.show();
    }

    private void showInvalid(String message) { new ProtectedDialogs.Builder(this).setTitle(UiText.get(R.string.ui_unable_to_open_document)).setMessage(message).setCancelable(false).setPositiveButton(UiText.get(R.string.ui_close), (d, w) -> finish()).show(); }

    private void rebuildPages(int selectedPage) {
        viewVersion++; recycleSlots(); slots.clear(); pages.removeAllViews();
        int available = Math.max(dp(100), getResources().getDisplayMetrics().widthPixels - dp(24));
        if (grid) {
            for (int i = 0; i < sizes.size(); i += 3) {
                LinearLayout row = new LinearLayout(this); pages.addView(row, new LinearLayout.LayoutParams(-1, -2));
                for (int j = 0; j < 3; j++) {
                    if (i + j < sizes.size()) { PageSlot slot = pageSlot(i + j, (available - dp(16)) / 3, true); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(166), 1); lp.setMargins(dp(2), dp(2), dp(2), dp(8)); row.addView(slot.frame, lp); }
                    else row.addView(new View(this), new LinearLayout.LayoutParams(0, dp(166), 1));
                }
            }
        } else {
            for (int i = 0; i < sizes.size(); i++) { float[] size = sizes.get(i); PageSlot slot = pageSlot(i, available, false); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(dp(80), Math.round(available * size[1] / size[0]))); lp.bottomMargin = dp(16); pages.addView(slot.frame, lp); }
        }
        TextView add = text(UiText.get(R.string.ui_add), 16, MUTED); add.setGravity(Gravity.CENTER); AppIcon camera = new AppIcon("Camera", MUTED); camera.setBounds(0, 0, dp(24), dp(24)); add.setCompoundDrawables(camera, null, null, null); add.setCompoundDrawablePadding(dp(8)); add.setPadding(dp(110), 0, dp(110), 0);
        GradientDrawable dashed = new GradientDrawable(); dashed.setColor(AppTheme.surface()); dashed.setCornerRadius(dp(5)); dashed.setStroke(dp(1), 0xFFDDDFDF, dp(5), dp(4)); add.setBackground(dashed); pages.addView(add, new LinearLayout.LayoutParams(-1, dp(54))); add.setOnClickListener(v -> dispatch("Add"));
        scroll.post(() -> { if (destroyed || slots.isEmpty()) return; PageSlot target = slots.get(Math.max(0, Math.min(selectedPage, slots.size() - 1))); int y = grid ? ((View) target.frame.getParent()).getTop() : target.frame.getTop(); scroll.scrollTo(0, y); refreshVisiblePages(); });
    }

    private PageSlot pageSlot(int index, int width, boolean thumbnail) {
        PageSlot slot = new PageSlot(index); slots.add(slot); slot.frame = new FrameLayout(this); slot.frame.setBackgroundColor(AppTheme.surface());
        slot.image = new ImageView(this); slot.image.setScaleType(ImageView.ScaleType.FIT_CENTER); slot.frame.addView(slot.image, new FrameLayout.LayoutParams(-1, -1));
        TextView number = text(UiText.format(R.string.ui_value_value_41ed6226, (index + 1), sizes.size()), thumbnail ? 10 : 12, Color.WHITE); number.setGravity(Gravity.CENTER); number.setBackgroundColor(0xBB444747); FrameLayout.LayoutParams nlp = new FrameLayout.LayoutParams(-2, dp(thumbnail ? 20 : 24), Gravity.TOP | Gravity.START); nlp.setMargins(dp(1), dp(4), 0, 0); number.setPadding(dp(6), 0, dp(6), 0); slot.frame.addView(number, nlp);
        slot.frame.setContentDescription(UiText.format(R.string.ui_page_value_67f70d13, (index + 1))); slot.frame.setOnClickListener(v -> { currentPage = index; if (grid) { grid = false; rebuildPages(index); } else zoomPage(index); });
        slot.frame.setOnLongClickListener(v -> { selectOnPage(index,null); return true; });
        return slot;
    }

    private void refreshVisiblePages() {
        if (slots.isEmpty() || destroyed) return;
        int top = scroll.getScrollY(), bottom = top + scroll.getHeight(), pad = scroll.getHeight() / 2;
        for (PageSlot slot : slots) {
            int y = grid ? ((View) slot.frame.getParent()).getTop() : slot.frame.getTop();
            boolean near = y + slot.frame.getHeight() >= top - pad && y <= bottom + pad;
            if (near && slot.bitmap == null && !slot.pending) requestPage(slot);
            if (!near && slot.bitmap != null) { slot.image.setImageDrawable(null); slot.bitmap.recycle(); slot.bitmap = null; }
            if (y <= top + dp(40) && y + slot.frame.getHeight() > top + dp(40)) currentPage = slot.index;
        }
    }

    private void requestPage(PageSlot slot) {
        slot.pending = true; int version = viewVersion; int targetWidth = Math.max(dp(100), slot.frame.getWidth());
        worker.execute(() -> { Bitmap bitmap = null; try { bitmap = renderPage(slot.index, targetWidth, grid ? dp(180) : 2400); } catch (Exception ignored) { }
            Bitmap result = bitmap; runOnUiThread(() -> { slot.pending = false; if (destroyed || version != viewVersion) { if (result != null) result.recycle(); return; } int y = grid ? ((View) slot.frame.getParent()).getTop() : slot.frame.getTop(); int top = scroll.getScrollY(), pad = scroll.getHeight() / 2; if (y + slot.frame.getHeight() < top - pad || y > top + scroll.getHeight() + pad) { if (result != null) result.recycle(); return; } if (result != null) { slot.bitmap = result; slot.image.setImageBitmap(result); } else slot.image.setContentDescription(UiText.get(R.string.ui_page_could_not_be_rendered)); });
        });
    }

    private Bitmap renderPage(int index, int maxWidth, int maxHeight) throws Exception {
        float[] size = sizes.get(index); float scale = Math.min(maxWidth / size[0], maxHeight / size[1]);
        if (boxDocument != null) return new PDFRenderer(boxDocument).renderImage(index, scale);
        try (PdfRenderer.Page page = renderer.openPage(index)) { Bitmap bitmap = Bitmap.createBitmap(Math.max(1, (int) (page.getWidth() * scale)), Math.max(1, (int) (page.getHeight() * scale)), Bitmap.Config.ARGB_8888); bitmap.eraseColor(Color.WHITE); page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); return bitmap; }
    }

    private void toggleGrid() { if (sizes.isEmpty()) return; grid = !grid; rebuildPages(currentPage); }

    private void zoomPage(int index) {
        ZoomImage zoom = new ZoomImage(); zoom.setBackgroundColor(0xFF222222);
        AlertDialog dialog = new ProtectedDialogs.Builder(this).setTitle(UiText.format(R.string.ui_page_value_pinch_to_zoom, (index + 1))).setView(zoom).setPositiveButton(UiText.get(R.string.ui_done), null).create();
        dialog.setOnDismissListener(d -> zoom.release()); dialog.show(); zoom.setMinimumHeight(dp(420));
        worker.execute(() -> { try { Bitmap bitmap = renderPage(index, 2200, 3200); runOnUiThread(() -> { if (destroyed || !dialog.isShowing()) bitmap.recycle(); else zoom.setBitmap(bitmap); }); } catch (Exception e) { runOnUiThread(() -> toast(UiText.get(R.string.ui_could_not_open_page_preview))); } });
    }

    private void rename() {
        EditText name = new EditText(this); name.setSingleLine(); name.setText(documentName); name.selectAll();
        AlertDialog dialog = new ProtectedDialogs.Builder(this).setTitle(UiText.get(R.string.ui_rename_document)).setView(name).setNegativeButton(UiText.get(R.string.ui_cancel), null).setPositiveButton(UiText.get(R.string.ui_save), null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> { String value = name.getText().toString().trim(); if (value.isEmpty()) { name.setError(UiText.get(R.string.ui_enter_a_name)); return; } documentName = value; titleLabel.setText(value); dialog.dismiss(); dispatch("Rename"); })); dialog.show();
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor); UiText.add(menu.getMenu(), "Document tools");UiText.add(menu.getMenu(), "Rename"); UiText.add(menu.getMenu(), grid ? "Page view" : "Page grid"); UiText.add(menu.getMenu(), "Select text on page"); UiText.add(menu.getMenu(), "Select text"); UiText.add(menu.getMenu(), "Find in document"); UiText.add(menu.getMenu(), "Extract text / TXT"); UiText.add(menu.getMenu(), "Save to cloud"); UiText.add(menu.getMenu(), "Share PDF"); UiText.add(menu.getMenu(), "Save to device"); UiText.add(menu.getMenu(), "Document info");
        menu.setOnMenuItemClickListener(item -> { switch (UiText.canonical(item)) { case "Document tools":documentTools();break;case "Rename": rename(); break; case "Select text on page": selectOnPage(currentPage,null);break;case "Select text": showPageText(currentPage); break;case "Find in document": findText();break;case "Extract text / TXT": chooseTextPages();break;case "Save to cloud": startActivity(new Intent(this,CloudExportActivity.class).putStringArrayListExtra("paths",new ArrayList<>(java.util.Collections.singletonList(path))).putStringArrayListExtra("names",new ArrayList<>(java.util.Collections.singletonList(documentName))));break; case "Page view": case "Page grid": toggleGrid(); break; case "Share PDF": shareDocument(); break; case "Save to device": saveLauncher.launch(documentName + ".pdf"); break; default: new ProtectedDialogs.Builder(this).setTitle(documentName).setMessage(UiText.format(R.string.ui_value_pages_value, sizes.size(), android.text.format.Formatter.formatFileSize(this, new File(path).length()))).setPositiveButton(UiText.get(R.string.ui_close), null).show(); } return true; }); menu.show();
    }

    void closeTextSelection(){selectionRequest++;if(selection!=null)selection.close();}
    private void selectOnPage(int index,String query){
        if(selection!=null)selection.close();if(grid){grid=false;rebuildPages(index);}currentPage=index;int token=++selectionRequest;
        worker.execute(()->{try(PDDocument doc=PDDocument.load(new File(path),password)){if(!doc.getCurrentAccessPermission().canExtractContent())throw new java.io.IOException(UiText.get(R.string.ui_this_pdf_does_not_allow_copying));ArrayList<PdfKeywordSearch.Glyph> glyphs=PdfKeywordSearch.glyphs(doc,index);if(glyphs.isEmpty()){runOnUiThread(()->toast(UiText.get(R.string.ui_run_ocr_pdf_first_to_add_selectable_text)));return;}Bitmap image=new PDFRenderer(doc).renderImage(index,Math.min(2200/doc.getPage(index).getCropBox().getWidth(),3000/doc.getPage(index).getCropBox().getHeight()));runOnUiThread(()->{if(destroyed||token!=selectionRequest){image.recycle();return;}if(selection!=null)selection.close();PageSlot slot=slots.get(index);PdfPageSelection overlay=new PdfPageSelection(this,image,glyphs,query,()->{PdfPageSelection current=selection;selection=null;if(current!=null&&current.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)current.getParent()).removeView(current);});selection=overlay;slot.frame.addView(overlay,new FrameLayout.LayoutParams(-1,-1));slot.frame.post(()->scroll.smoothScrollTo(0,slot.frame.getTop()));FolderLock.guard(this,path);});}catch(Exception e){runOnUiThread(()->toast(UiText.format(R.string.ui_could_not_select_text_value, e.getMessage())));}});
    }
    private void documentTools(){String[] tools={"Edit scan pages","PDF page settings","Conversion history","To Word","To Excel","To PowerPoint","Batch OCR","Collage","OCR PDF","Extract PDF Pages","Reorder Pages","Rotate PDF","Merge Files","Add Watermark","Lock","Remove PDF password","Compress","Erase Marks"};new ProtectedDialogs.Builder(this).setTitle(UiText.get(R.string.ui_document_tools)).setItems(UiText.labels(tools),(d,w)->openDocumentTool(tools[w])).show();}
    private void openDocumentTool(String operation){
        if(operation.equals("Edit scan pages")){editScan();return;}if(operation.equals("Conversion history")){startActivity(new Intent(this,ConversionHistoryActivity.class));return;}if(operation.equals("PDF page settings")){startActivity(new Intent(this,PdfLayoutActivity.class).putExtra("path",path).putExtra("name",documentName).putExtra("pdf_password",password));return;}
        if(operation.equals("To Word")){dispatch("To Word");return;}
        Class<?> type=operation.equals("To Excel")||operation.equals("To PowerPoint")||operation.equals("Batch OCR")||operation.equals("Collage")?ExtendedToolsActivity.class:operation.equals("Erase Marks")?EraseMarksActivity.class:ToolWorkflowActivity.class;
        Intent intent=new Intent(this,type).putExtra("operation",operation).putExtra("path",path).putExtra("name",documentName).putExtra("pdf_password",password).putExtra("page",currentPage);
        if(type==ToolWorkflowActivity.class){ArrayList<String> paths=new ArrayList<>(),names=new ArrayList<>();try(DocumentStore store=new DocumentStore(this);android.database.Cursor rows=store.db().rawQuery("SELECT path,name FROM docs WHERE deleted_at=0 AND "+FolderLock.visibleSql(this,store)+" ORDER BY last_opened DESC",null)){while(rows.moveToNext()){paths.add(rows.getString(0));names.add(rows.getString(1));}}intent.putStringArrayListExtra("paths",paths).putStringArrayListExtra("names",names);}startActivity(intent);
    }
    private void editScan(){if(!FolderLock.ensureDocument(this,path,this::editScan))return;if(ScanArchive.exists(this,path)){worker.execute(()->{try{Intent editor=ScanArchive.intent(this,path,documentName,password);runOnUiThread(()->{if(!destroyed)startActivity(editor);});}catch(Exception e){runOnUiThread(()->toast(UiText.format(R.string.ui_cannot_reopen_original_scans_value, e.getMessage())));}});return;}
        new ProtectedDialogs.Builder(this).setTitle(UiText.get(R.string.ui_edit_pdf_pages_as_images)).setMessage(UiText.get(R.string.ui_the_original_uncropped_photos_are_unavailable_for_this_pdf_edit_a_rendered)).setNegativeButton(UiText.get(R.string.ui_cancel),null).setPositiveButton(UiText.get(R.string.ui_edit_copy),(d,w)->renderScanCopy()).show();
    }
    private void renderScanCopy(){android.app.ProgressDialog progress=android.app.ProgressDialog.show(this,"",UiText.get(R.string.ui_preparing_pages),true,false);worker.execute(()->{ArrayList<String> photos=new ArrayList<>();try(PDDocument doc=PDDocument.load(new File(path),password)){if(!doc.getCurrentAccessPermission().canModify()||!doc.getCurrentAccessPermission().canExtractContent())throw new java.io.IOException(UiText.get(R.string.ui_this_pdf_does_not_allow_image_editing));File dir=new File(getFilesDir(),"captures");if(!dir.isDirectory()&&!dir.mkdirs())throw new java.io.IOException(UiText.get(R.string.ui_storage_unavailable));PDFRenderer render=new PDFRenderer(doc);for(int i=0;i<doc.getNumberOfPages();i++){if(destroyed)throw new java.io.InterruptedIOException();PDRectangle box=doc.getPage(i).getCropBox();Bitmap image=render.renderImage(i,Math.min(2400/box.getWidth(),3200/box.getHeight()));File photo=new File(dir,"pdf-edit-"+java.util.UUID.randomUUID()+".jpg");photos.add(Uri.fromFile(photo).toString());try(OutputStream out=new java.io.FileOutputStream(photo)){if(!image.compress(Bitmap.CompressFormat.JPEG,96,out))throw new java.io.IOException(UiText.get(R.string.ui_cannot_prepare_page));}finally{image.recycle();}}Intent editor=new Intent(this,ScanEditorActivity.class).putStringArrayListExtra("pages",photos).putExtra("name",documentName+" · edited").putExtra("capture_mode","Single").putExtra("initial_filter",0).putExtra("start_crop",false).putExtra("edit_source",path).putExtra("edit_password",password);runOnUiThread(()->{progress.dismiss();if(!destroyed)startActivity(editor);});}catch(Exception|OutOfMemoryError e){for(String photo:photos)new File(Uri.parse(photo).getPath()).delete();runOnUiThread(()->{progress.dismiss();if(!destroyed)toast(UiText.format(R.string.ui_cannot_prepare_pages_value, e.getMessage()));});}});}
    private void findText(){EditText input=new EditText(this);input.setSingleLine();input.setHint(UiText.get(R.string.ui_word_or_phrase));new ProtectedDialogs.Builder(this).setTitle(UiText.get(R.string.ui_find_in_document)).setView(input).setNegativeButton(UiText.get(R.string.ui_cancel),null).setPositiveButton(UiText.get(R.string.ui_find),(d,w)->{String query=input.getText().toString().trim();if(query.isEmpty())return;android.app.ProgressDialog progress=android.app.ProgressDialog.show(this,"",UiText.get(R.string.ui_searching),true,false);worker.execute(()->{try(PDDocument doc=PDDocument.load(new File(path),password)){
        if(!doc.getCurrentAccessPermission().canExtractContent())throw new java.io.IOException(UiText.get(R.string.ui_this_pdf_does_not_allow_text_extraction));
        com.tom_roush.pdfbox.text.PDFTextStripper stripper=new com.tom_roush.pdfbox.text.PDFTextStripper();stripper.setSortByPosition(true);ArrayList<Integer> indices=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();String needle=DocumentStore.normalize(query);
        for(int i=0;i<doc.getNumberOfPages();i++){stripper.setStartPage(i+1);stripper.setEndPage(i+1);String value=stripper.getText(doc).replaceAll("\\s+"," ");String normalized=DocumentStore.normalize(value);int position=normalized.indexOf(needle);if(position>=0){indices.add(i);int left=Math.max(0,Math.min(value.length(),position)-35),right=Math.min(value.length(),position+needle.length()+75);labels.add(UiText.format(R.string.ui_page_value_value_fb9f3900, (i+1), value.substring(left,Math.max(left,right))));}}
        runOnUiThread(()->{progress.dismiss();if(destroyed)return;if(indices.isEmpty())new ProtectedDialogs.Builder(this).setTitle(UiText.get(R.string.ui_no_matches)).setMessage(UiText.get(R.string.ui_no_matching_embedded_text_was_found_scanned_pages_need_ocr_pdf_before_they)).setPositiveButton(UiText.get(R.string.ui_close),null).show();else new ProtectedDialogs.Builder(this).setTitle(UiText.format(R.string.ui_value_matching_page_s, indices.size())).setItems(labels.toArray(new String[0]),(x,y)->{currentPage=indices.get(y);grid=false;rebuildPages(currentPage);selectOnPage(currentPage,query);}).setNegativeButton(UiText.get(R.string.ui_close),null).show();});
        }catch(Exception|OutOfMemoryError e){runOnUiThread(()->{progress.dismiss();toast(UiText.format(R.string.ui_could_not_search_value, e.getMessage()));});}});}).show();}
    private void chooseTextPages(){String[] labels=new String[sizes.size()];boolean[] chosen=new boolean[labels.length];for(int i=0;i<labels.length;i++){labels[i]=UiText.format(R.string.ui_page_value_67f70d13, (i+1));chosen[i]=i==currentPage;}AlertDialog dialog=new ProtectedDialogs.Builder(this).setTitle(UiText.get(R.string.ui_extract_text_selected_pages)).setMultiChoiceItems(labels,chosen,(d,w,value)->chosen[w]=value).setNegativeButton(UiText.get(R.string.ui_cancel),null).setNeutralButton(UiText.get(R.string.ui_select_all),null).setPositiveButton(UiText.get(R.string.ui_extract),null).create();dialog.setOnShowListener(d->{dialog.getButton(-3).setOnClickListener(v->{boolean all=true;for(boolean value:chosen)all&=value;for(int i=0;i<chosen.length;i++){chosen[i]=!all;dialog.getListView().setItemChecked(i,!all);}});dialog.getButton(-1).setOnClickListener(v->{ArrayList<Integer> selected=new ArrayList<>();for(int i=0;i<chosen.length;i++)if(chosen[i])selected.add(i);if(selected.isEmpty()){toast(UiText.get(R.string.ui_select_at_least_one_page));return;}dialog.dismiss();extractText(selected);});});dialog.show();}
    private void extractText(ArrayList<Integer> selected){android.app.ProgressDialog progress=android.app.ProgressDialog.show(this,"",UiText.get(R.string.ui_extracting_text),true,false);worker.execute(()->{try(PDDocument doc=PDDocument.load(new File(path),password)){
        if(!doc.getCurrentAccessPermission().canExtractContent())throw new java.io.IOException(UiText.get(R.string.ui_this_pdf_does_not_allow_text_extraction));StringBuilder value=new StringBuilder();com.tom_roush.pdfbox.text.PDFTextStripper stripper=new com.tom_roush.pdfbox.text.PDFTextStripper();stripper.setSortByPosition(true);
        for(Integer page:selected){stripper.setStartPage(page+1);stripper.setEndPage(page+1);String text=stripper.getText(doc).trim();if(text.isEmpty()){if(com.paperflow.scanengine.LocalOcr.activeLanguages(this).isEmpty())throw new LanguageAccess.Missing("ocr","");PDPage p=doc.getPage(page);PDRectangle box=p.getCropBox();boolean turn=Math.abs(p.getRotation())%180==90;Bitmap image=new PDFRenderer(doc).renderImage(page,Math.min(2000/(turn?box.getHeight():box.getWidth()),2600/(turn?box.getWidth():box.getHeight())));try{text=com.paperflow.scanengine.LocalOcr.text(this,image);}finally{image.recycle();}}if(value.length()>0)value.append("\n\n");value.append(text);}
        if(value.toString().trim().isEmpty())throw new java.io.IOException(UiText.get(R.string.ui_no_text_found_in_the_selected_pages));File output=new File(getCacheDir(),"extracted-"+System.nanoTime()+".txt");try(OutputStream out=new java.io.FileOutputStream(output)){out.write(value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}String extracted=value.toString();runOnUiThread(()->{progress.dismiss();if(destroyed)return;textExport=output;TextView text=text(extracted,16,INK);text.setTextIsSelectable(true);text.setPadding(dp(16),dp(16),dp(16),dp(16));ScrollView sheet=new ScrollView(this);sheet.addView(text);new ProtectedDialogs.Builder(this).setTitle(UiText.format(R.string.ui_text_from_value_page_s, selected.size())).setView(ocrResultView(sheet,extracted)).setNegativeButton(UiText.get(R.string.ui_close),null).setPositiveButton(UiText.get(R.string.ui_save_txt),(d,w)->textSaveLauncher.launch(documentName+".txt")).show();});
        }catch(Exception|OutOfMemoryError e){runOnUiThread(()->{progress.dismiss();if(!LanguageAccess.openIfMissing(this,e))toast(UiText.format(R.string.ui_could_not_extract_text_value, e.getMessage()));});}});}
    void translateSelection(String value){if(FolderLock.ensureDocument(this,path,()->translateSelection(value)))TextTranslationActivity.open(this,value,path);}
    private View ocrResultView(ScrollView content,String value){LinearLayout body=new LinearLayout(this);body.setOrientation(android.widget.LinearLayout.VERTICAL);LinearLayout actions=new LinearLayout(this);android.widget.Button copy=new android.widget.Button(this),translate=new android.widget.Button(this);copy.setText(UiText.get(R.string.ui_copy_all));translate.setText(UiText.get(R.string.ui_translate));actions.addView(copy,new LinearLayout.LayoutParams(0,-2,1));actions.addView(translate,new LinearLayout.LayoutParams(0,-2,1));body.addView(actions);body.addView(content,new LinearLayout.LayoutParams(-1,dp(320)));copy.setOnClickListener(v->((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText(documentName,value)));translate.setOnClickListener(v->TextTranslationActivity.open(this,value,path));return body;}
    private void saveText(Uri uri){if(!FolderLock.ensureDocument(this,path,()->saveText(uri)))return;File source=textExport;if(source==null)return;worker.execute(()->{try(InputStream in=new FileInputStream(source);OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new java.io.IOException(UiText.get(R.string.ui_destination_unavailable));byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)out.write(b,0,n);runOnUiThread(()->toast(UiText.get(R.string.ui_text_saved)));}catch(Exception e){runOnUiThread(()->toast(UiText.get(R.string.ui_could_not_save_text)));}});}
    private void showPageText(int page) {
        if(destroyed||sizes.isEmpty())return;
        worker.execute(()->{
            try(PDDocument document=PDDocument.load(new File(path),password,com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(24*1024*1024))){
                if(!document.getCurrentAccessPermission().canExtractContent())throw new java.io.IOException(UiText.get(R.string.ui_this_pdf_does_not_allow_text_copying));
                com.tom_roush.pdfbox.text.PDFTextStripper stripper=new com.tom_roush.pdfbox.text.PDFTextStripper();
                stripper.setSortByPosition(true);stripper.setStartPage(page+1);stripper.setEndPage(page+1);String value=stripper.getText(document).trim();
                runOnUiThread(()->{
                    if(destroyed||isFinishing())return;
                    if(value.isEmpty()){
                        new ProtectedDialogs.Builder(this).setTitle(UiText.get(R.string.ui_no_selectable_text)).setMessage(UiText.get(R.string.ui_convert_this_document_with_ocr_pdf_to_add_text_you_can_select_copy_and_sear)).setNegativeButton(UiText.get(R.string.ui_close),null).setPositiveButton(UiText.get(R.string.ui_ocr_pdf),(d,w)->startActivity(new Intent(this,ToolWorkflowActivity.class).putExtra("operation","OCR PDF").putExtra("path",path).putExtra("name",documentName))).show();return;
                    }
                    TextView text=text(value,16,INK);text.setTextIsSelectable(true);text.setPadding(dp(20),dp(16),dp(20),dp(16));
                    ScrollView scroll=new ScrollView(this);scroll.addView(text);scroll.setLayoutParams(new android.view.ViewGroup.LayoutParams(-1,dp(320)));
                    new ProtectedDialogs.Builder(this).setTitle(UiText.format(R.string.ui_page_value_text, (page+1))).setView(scroll).setNegativeButton(UiText.get(R.string.ui_close),null).setNeutralButton(UiText.get(R.string.ui_translate),(d,w)->TextTranslationActivity.open(this,value,path)).setPositiveButton(UiText.get(R.string.ui_copy_page_text),(d,w)->{
                        android.content.ClipboardManager clipboard=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
                        if(clipboard!=null)clipboard.setPrimaryClip(android.content.ClipData.newPlainText(documentName,value));
                    }).show();
                });
            }catch(Exception|OutOfMemoryError error){runOnUiThread(()->toast(UiText.format(R.string.ui_could_not_read_page_text_value, error.getMessage())));}
        });
    }

    private void saveToDevice(Uri uri) { if(!FolderLock.ensureDocument(this,path,()->saveToDevice(uri)))return;worker.execute(() -> { try (InputStream in = new FileInputStream(path); OutputStream out = getContentResolver().openOutputStream(uri)) { if (out == null) throw new IllegalStateException(); byte[] b = new byte[16384]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); runOnUiThread(() -> toast(UiText.get(R.string.ui_pdf_saved))); } catch (Exception e) { runOnUiThread(() -> toast(UiText.get(R.string.ui_could_not_save_pdf))); } }); }

    private void shareDocument() {
        try { Intent send = new Intent(Intent.ACTION_SEND); send.setType("application/pdf"); send.putExtra(Intent.EXTRA_STREAM, FileProvider.getUriForFile(this, getPackageName() + ".files", new File(path))); send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION); startActivity(Intent.createChooser(send, UiText.get(R.string.ui_share_pdf))); }
        catch (Exception e) { toast(UiText.get(R.string.ui_could_not_share_this_document)); }
    }

    private void dispatch(String action) { Intent intent = new Intent(this, MainActivity.class); intent.putExtra("document_action", action); intent.putExtra("path", path); intent.putExtra("name", documentName); intent.putExtra("page", currentPage);if(action.equals("Edit")||action.equals("To Word")||action.equals("Add"))intent.putExtra("pdf_password",password); intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP); startActivity(intent); finish(); }

    private void chooseSignaturePage() {
        Intent intent = new Intent(this, SignatureEditorActivity.class);
        intent.putExtra("path", path);
        intent.putExtra("name", documentName);
        intent.putExtra("page", currentPage);
        intent.putExtra("pdf_password", password);
        startActivity(intent);
    }
    class ZoomImage extends View {
        private Bitmap bitmap; private float scale = 1, dx, dy, lastX, lastY; private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG); private final ScaleGestureDetector detector;
        private final RectF destination = new RectF();
        private final int touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        private float downX, downY;
        private boolean clickCandidate;
        ZoomImage() { super(PdfViewerActivity.this); detector = new ScaleGestureDetector(PdfViewerActivity.this, new ScaleGestureDetector.SimpleOnScaleGestureListener() { @Override public boolean onScale(ScaleGestureDetector d) { scale = Math.max(1, Math.min(5, scale * d.getScaleFactor())); if (scale == 1) { dx = 0; dy = 0; } invalidate(); return true; } }); }
        void setBitmap(Bitmap b) { bitmap = b; invalidate(); }
        @Override protected void onDraw(Canvas canvas) { if (bitmap == null) return; float fit = Math.min(getWidth() / (float) bitmap.getWidth(), getHeight() / (float) bitmap.getHeight()); float w = bitmap.getWidth() * fit * scale, h = bitmap.getHeight() * fit * scale; destination.set((getWidth() - w) / 2 + dx, (getHeight() - h) / 2 + dy, (getWidth() + w) / 2 + dx, (getHeight() + h) / 2 + dy); canvas.drawBitmap(bitmap, null, destination, paint); }
        @Override public boolean performClick() { return super.performClick(); }
        @Override public boolean onTouchEvent(MotionEvent event) {
            detector.onTouchEvent(event);
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                downX = lastX = event.getX(); downY = lastY = event.getY(); clickCandidate = true;
            } else if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) {
                clickCandidate = false;
            } else if (action == MotionEvent.ACTION_MOVE) {
                float travelX = event.getX() - downX, travelY = event.getY() - downY;
                if (travelX * travelX + travelY * travelY > (float) touchSlop * touchSlop) clickCandidate = false;
                if (event.getPointerCount() == 1 && !detector.isInProgress()) {
                    dx += event.getX() - lastX; dy += event.getY() - lastY;
                    lastX = event.getX(); lastY = event.getY(); invalidate();
                }
            } else if (action == MotionEvent.ACTION_UP) {
                boolean clicked = clickCandidate; clickCandidate = false;
                if (clicked) performClick();
            }
            return true;
        }
        void release() { if (bitmap != null) { bitmap.recycle(); bitmap = null; } }
    }

    private static class PageSlot { final int index; FrameLayout frame; ImageView image; Bitmap bitmap; boolean pending; PageSlot(int i) { index = i; } }
    private void recycleSlots() {selectionRequest++;if(selection!=null)selection.close(); for (PageSlot slot : slots) { if (slot.image != null) slot.image.setImageDrawable(null); if (slot.bitmap != null) { slot.bitmap.recycle(); slot.bitmap = null; } } }
    private void closeDocument() { if (renderer != null) { renderer.close(); renderer = null; } if (descriptor != null) { try { descriptor.close(); } catch (Exception ignored) { } descriptor = null; } if (boxDocument != null) { try { boxDocument.close(); } catch (Exception ignored) { } boxDocument = null; } }
    private ImageView icon(String name, String description, int padding) { ImageView image = new ImageView(this); image.setImageDrawable(new AppIcon(name, INK)); image.setPadding(dp(padding), dp(padding), dp(padding), dp(padding)); image.setContentDescription(description); return image; }
    private TextView text(String value, int size, int color) { TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view; }
    private void toast(String message) { if (!destroyed) Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override protected void onSaveInstanceState(Bundle state) { if(textExport!=null)state.putString("text_export",textExport.getAbsolutePath());state.putBoolean("grid", grid); state.putInt("page", currentPage); super.onSaveInstanceState(state); }
    @Override protected void onResume(){super.onResume();FolderLock.guard(this,path);}
    @Override protected void onDestroy() { destroyed = true; viewVersion++; recycleSlots(); worker.execute(this::closeDocument); worker.shutdown(); super.onDestroy(); }
}

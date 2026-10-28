package com.example.myapplication;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import static org.junit.Assert.*;

/** Exercises reader controls through their visible actions, including cancelled external pickers. */
public class PdfViewerUiTest {
    final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    final Context context=inst.getTargetContext();
    final List<Intent> outgoing=new CopyOnWriteArrayList<>();
    ActivityScenario<PdfViewerActivity> scenario;
    PdfViewerActivity activity;
    File source;
    Instrumentation.ActivityMonitor monitor;
    long gestureTime;
    @Before public void prepare()throws Exception{
        PDFBoxResourceLoader.init(context);source=File.createTempFile("reader-ui-",".pdf",context.getCacheDir());
        monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){if(!new ComponentName(context,PdfViewerActivity.class).equals(intent.getComponent())){outgoing.add(intent);return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);}return null;}};
    }
    @After public void close()throws Exception{
        inst.removeMonitor(monitor);if(scenario!=null)scenario.close();
        if(activity!=null)assertTrue(((ExecutorService)field(activity,"worker")).awaitTermination(15,TimeUnit.SECONDS));
        for(Intent intent:outgoing){ArrayList<String> photos=intent.getStringArrayListExtra("pages");if(photos!=null)for(String photo:photos){Uri uri=Uri.parse(photo);if("file".equals(uri.getScheme()))new File(uri.getPath()).delete();}if(new ComponentName(context,TextTranslationActivity.class).equals(intent.getComponent())&&intent.hasExtra("text_path"))new File(intent.getStringExtra("text_path")).delete();}
        if(source!=null)source.delete();
    }
    Object field(Object owner,String name){try{java.lang.reflect.Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}catch(Exception error){throw new AssertionError(error);}}
    <T>T main(Callable<T> action){scenario.onActivity(a->activity=a);AtomicReference<T> result=new AtomicReference<>();inst.runOnMainSync(()->{try{result.set(action.call());}catch(Exception error){throw new AssertionError(error);}});return result.get();}
    void open(boolean blank)throws Exception{
        try(PDDocument doc=new PDDocument()){for(int i=0;i<2;i++){PDPage page=new PDPage(new PDRectangle(160,240));doc.addPage(page);if(!blank)try(PDPageContentStream stream=new PDPageContentStream(doc,page)){stream.beginText();stream.setFont(PDType1Font.HELVETICA,12);stream.newLineAtOffset(12,180);stream.showText("Reader UI page "+(i+1));stream.endText();}}doc.save(source);}
        scenario=ActivityScenario.launch(new Intent(context,PdfViewerActivity.class).putExtra("path",source.getPath()).putExtra("name","Reader controls"));scenario.onActivity(a->activity=a);ready();inst.addMonitor(monitor);
    }
    List<?> slots(){return (List<?>)field(activity,"slots");}
    void ready(){UiTestWait.until(()->slots().size()==2&&field(slots().get(0),"bitmap")!=null,"Reader UI did not render");inst.waitForIdleSync();}
    View description(View root,String value){if(root.isShown()&&value.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=description(((ViewGroup)root).getChildAt(i),value);if(found!=null)return found;}return null;}
    void press(int resource){main(()->{View view=description(activity.getWindow().getDecorView(),UiText.get(resource));assertNotNull("Missing reader action "+resource,view);assertTrue(view.performClick());return null;});inst.waitForIdleSync();}
    void choose(String label){UiTestWait.until(()->{View root=focusedDialog();ListView list=root==null?null:find(root,ListView.class);return list!=null&&list.isShown()&&list.getChildCount()>0;},"Reader choices did not attach and receive focus");int position=main(()->{ListView list=find(dialog(),ListView.class);assertNotNull(list);for(int i=0;i<list.getCount();i++)if(label.equals(String.valueOf(list.getAdapter().getItem(i)))){list.setSelection(i);return i;}throw new AssertionError("Missing reader menu item "+label);});inst.waitForIdleSync();main(()->{ListView list=find(dialog(),ListView.class);View row=list.getChildAt(position-list.getFirstVisiblePosition());assertNotNull("Reader menu choice must be visible",row);assertTrue(list.performItemClick(row,position,list.getAdapter().getItemId(position)));return null;});inst.waitForIdleSync();}
    void menu(String value){press(R.string.ui_more_options);choose(UiText.label(value));}
    View focusedDialog(){List<View> roots=android.view.inspector.WindowInspector.getGlobalWindowViews();for(int i=roots.size()-1;i>=0;i--){View root=roots.get(i);if(root!=activity.getWindow().getDecorView()&&root.hasWindowFocus()&&root.isShown())return root;}return null;}
    View dialog(){View root=focusedDialog();assertNotNull("No reader dialog",root);return root;}
    boolean zoomReady(){View root=focusedDialog();View view=root==null?null:zoom(root);return view!=null&&view.getWidth()>0&&field(view,"bitmap")!=null;}
    void awaitDialogButton(int id,int label){UiTestWait.until(()->{View root=focusedDialog();Button button=root==null?null:root.findViewById(id);return button!=null&&button.isShown()&&button.isEnabled()&&UiText.get(label).contentEquals(button.getText());},"Reader dialog did not attach and receive focus");}
    <T extends View>T find(View root,Class<T> type){if(type.isInstance(root))return type.cast(root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){T found=find(((ViewGroup)root).getChildAt(i),type);if(found!=null)return found;}return null;}
    View zoom(View root){if(root instanceof PdfViewerActivity.ZoomImage)return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=zoom(((ViewGroup)root).getChildAt(i));if(found!=null)return found;}return null;}
    void button(int id){UiTestWait.until(()->{View root=focusedDialog();View button=root==null?null:root.findViewById(id);return button!=null&&button.isShown()&&button.isEnabled();},"Reader dialog button did not attach");main(()->{Button button=dialog().findViewById(id);assertNotNull(button);assertTrue(button.performClick());return null;});inst.waitForIdleSync();}
    void secondPage(){press(R.string.ui_page_grid);UiTestWait.until(()->field(slots().get(1),"bitmap")!=null,"Second thumbnail did not render");main(()->{assertTrue(((View)field(slots().get(1),"frame")).performClick());return null;});inst.waitForIdleSync();assertEquals(1,main(()->(Integer)field(activity,"currentPage")).intValue());}
    Intent last(){assertFalse("Reader did not launch the requested action",outgoing.isEmpty());return outgoing.get(outgoing.size()-1);}
    void pointer(View view,long down,long time,int action,float span,boolean two){
        int count=two?2:1;MotionEvent.PointerProperties[] properties=new MotionEvent.PointerProperties[count];MotionEvent.PointerCoords[] points=new MotionEvent.PointerCoords[count];
        for(int i=0;i<count;i++){properties[i]=new MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=MotionEvent.TOOL_TYPE_FINGER;points[i]=new MotionEvent.PointerCoords();points[i].x=view.getWidth()/2f+(i==0?-span:span)/2;points[i].y=view.getHeight()/2f;points[i].pressure=1;points[i].size=1;}
        MotionEvent event=MotionEvent.obtain(down,time,action,count,properties,points,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);try{assertTrue(view.dispatchTouchEvent(event));}finally{event.recycle();}
    }
    long startGesture(){gestureTime=Math.max(android.os.SystemClock.uptimeMillis(),gestureTime+1000);return gestureTime;}
    void pinch(View view,float from,float to){long now=startGesture();pointer(view,now,now,MotionEvent.ACTION_DOWN,from,false);pointer(view,now,now+16,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),from,true);for(int i=1;i<=8;i++)pointer(view,now,now+16+i*16,MotionEvent.ACTION_MOVE,from+(to-from)*i/8,true);pointer(view,now,now+160,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),to,true);pointer(view,now,now+176,MotionEvent.ACTION_UP,to,false);}
    @Test public void gridPageSelectionAndZoomReleaseTheirBitmaps()throws Exception{
        open(false);Bitmap first=main(()->(Bitmap)field(slots().get(0),"bitmap"));secondPage();assertTrue(first.isRecycled());
        main(()->{assertTrue(((View)field(slots().get(1),"frame")).performClick());return null;});
        UiTestWait.until(this::zoomReady,"Zoom preview did not render");
        Bitmap zoomed=main(()->(Bitmap)field(zoom(dialog()),"bitmap"));assertFalse(zoomed.isRecycled());button(android.R.id.button1);assertTrue(zoomed.isRecycled());assertEquals(1,main(()->(Integer)field(activity,"currentPage")).intValue());
    }
    @Test public void pageTextCanBeCopiedThroughTheReaderMenu()throws Exception{
        open(false);menu("Select text");awaitDialogButton(android.R.id.button1,R.string.ui_copy_page_text);
        main(()->{TextView text=find(find(dialog(),ScrollView.class),TextView.class);assertNotNull(text);assertTrue(text.isTextSelectable());assertEquals("Reader UI page 1",text.getText().toString());return null;});button(android.R.id.button1);
        assertEquals("Reader UI page 1",main(()->((ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip().getItemAt(0).getText().toString()));
    }
    @Test public void pinchZoomClampsItsScaleAndResetsPanningAtMinimumZoom()throws Exception{
        open(false);main(()->{assertTrue(((View)field(slots().get(0),"frame")).performClick());return null;});UiTestWait.until(this::zoomReady,"Zoom preview did not render");
        main(()->{
            View view=zoom(dialog());float width=view.getWidth();int[] clicks={0};view.setOnClickListener(v->clicks[0]++);
            long tap=startGesture();pointer(view,tap,tap,MotionEvent.ACTION_DOWN,width*.7f,false);pointer(view,tap,tap+16,MotionEvent.ACTION_UP,width*.7f,false);assertEquals(1,clicks[0]);
            assertTrue(view.performClick());assertEquals(2,clicks[0]);
            long cancelled=startGesture();pointer(view,cancelled,cancelled,MotionEvent.ACTION_DOWN,width*.7f,false);pointer(view,cancelled,cancelled+16,MotionEvent.ACTION_CANCEL,width*.7f,false);assertEquals(2,clicks[0]);
            // Keep both fingers inside the preview and above Android's minimum scaling span.
            pinch(view,width*.7f,width*.95f);float scale=(Float)field(view,"scale");assertTrue("A real two-finger gesture must zoom the page",scale>1);assertTrue(scale<=5);
            for(int i=0;i<10;i++)pinch(view,width*.7f,width*.95f);assertEquals(5f,(Float)field(view,"scale"),.0001f);
            long now=startGesture();for(int i=0;i<3;i++){MotionEvent event=MotionEvent.obtain(now,now+i*16,i==0?MotionEvent.ACTION_DOWN:i==1?MotionEvent.ACTION_MOVE:MotionEvent.ACTION_UP,120+i*30,140+i*20,0);try{assertTrue(view.dispatchTouchEvent(event));}finally{event.recycle();}}assertTrue((Float)field(view,"dx")>0);
            // Android consumes a density-dependent part of each pinch as touch slop.
            // Require real progress on every gesture, then verify the exact lower clamp.
            for(int i=0;i<40&&(Float)field(view,"scale")>1f;i++){
                float before=(Float)field(view,"scale");pinch(view,width*.95f,width*.65f);
                assertTrue("Every inward pinch must reduce the zoom",(Float)field(view,"scale")<before);
            }
            assertEquals(1f,(Float)field(view,"scale"),.0001f);assertEquals(0f,(Float)field(view,"dx"),0);assertEquals(0f,(Float)field(view,"dy"),0);assertEquals("Pinching and panning must not dispatch clicks",2,clicks[0]);return null;
        });button(android.R.id.button1);
    }
    @Test public void pageTextTranslationPassesTheSelectedTextAndSourceDocument()throws Exception{
        open(false);menu("Select text");awaitDialogButton(android.R.id.button3,R.string.ui_translate);button(android.R.id.button3);
        assertEquals(new ComponentName(context,TextTranslationActivity.class),last().getComponent());assertEquals(source.getPath(),last().getStringExtra("origin_path"));assertEquals("Reader UI page 1",new String(Files.readAllBytes(new File(last().getStringExtra("text_path")).toPath()),java.nio.charset.StandardCharsets.UTF_8));
        main(()->{activity.translateSelection("Selected words only");return null;});assertEquals(2,outgoing.size());assertEquals(new ComponentName(context,TextTranslationActivity.class),last().getComponent());assertEquals(source.getPath(),last().getStringExtra("origin_path"));assertEquals("Selected words only",new String(Files.readAllBytes(new File(last().getStringExtra("text_path")).toPath()),java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test public void scannedPageOffersTheExistingOcrPdfTool()throws Exception{
        open(true);menu("Select text");awaitDialogButton(android.R.id.button1,R.string.ui_ocr_pdf);button(android.R.id.button1);
        assertEquals(new ComponentName(context,ToolWorkflowActivity.class),last().getComponent());assertEquals("OCR PDF",last().getStringExtra("operation"));assertEquals(source.getPath(),last().getStringExtra("path"));assertTrue(source.isFile());
    }
    @Test public void cancelledSavePickerKeepsTheOriginalDocument()throws Exception{
        open(false);byte[] original=Files.readAllBytes(source.toPath());menu("Save to device");assertEquals(Intent.ACTION_CREATE_DOCUMENT,last().getAction());assertEquals("application/pdf",last().getType());assertEquals("Reader controls.pdf",last().getStringExtra(Intent.EXTRA_TITLE));assertArrayEquals(original,Files.readAllBytes(source.toPath()));assertFalse(activity.isFinishing());
    }
    @Test public void cloudExportReceivesOnlyTheExplicitlyChosenDocument()throws Exception{
        open(false);menu("Save to cloud");assertEquals(new ComponentName(context,CloudExportActivity.class),last().getComponent());assertEquals(Collections.singletonList(source.getPath()),last().getStringArrayListExtra("paths"));assertEquals(Collections.singletonList("Reader controls"),last().getStringArrayListExtra("names"));
    }
    @Test public void signingUsesThePageChosenFromTheGrid()throws Exception{
        open(false);secondPage();press(R.string.ui_sign);assertEquals(new ComponentName(context,SignatureEditorActivity.class),last().getComponent());assertEquals(1,last().getIntExtra("page",-1));assertEquals(source.getPath(),last().getStringExtra("path"));assertEquals("Reader controls",last().getStringExtra("name"));assertEquals("",last().getStringExtra("pdf_password"));
    }
    @Test public void editScanCreatesSeparatePageImagesAndPreservesThePdf()throws Exception{
        open(false);byte[] original=Files.readAllBytes(source.toPath());menu("Document tools");choose(UiText.label("Edit scan pages"));button(android.R.id.button1);
        UiTestWait.until(()->!outgoing.isEmpty(),"Reader did not prepare an editable copy");Intent intent=last();assertEquals(new ComponentName(context,ScanEditorActivity.class),intent.getComponent());assertEquals(source.getPath(),intent.getStringExtra("edit_source"));assertFalse(intent.getBooleanExtra("start_crop",true));ArrayList<String> photos=intent.getStringArrayListExtra("pages");assertNotNull(photos);assertEquals(2,photos.size());
        for(String photo:photos){Bitmap bitmap=BitmapFactory.decodeFile(Uri.parse(photo).getPath());assertNotNull(bitmap);try{assertTrue(bitmap.getWidth()>0);assertTrue(bitmap.getHeight()>bitmap.getWidth());}finally{bitmap.recycle();}}assertArrayEquals(original,Files.readAllBytes(source.toPath()));
    }
    @Test public void addActionPassesTheDocumentToTheMainScreen()throws Exception{assertMainAction("Add");}
    @Test public void editActionPassesTheDocumentToTheMainScreen()throws Exception{assertMainAction("Edit");}
    @Test public void wordActionPassesTheDocumentToTheMainScreen()throws Exception{assertMainAction("To Word");}
    void assertMainAction(String label)throws Exception{
        open(false);main(()->{View view=description(activity.getWindow().getDecorView(),UiText.label(label));assertNotNull(view);assertTrue(view.performClick());return null;});assertEquals(new ComponentName(context,MainActivity.class),last().getComponent());assertEquals(label,last().getStringExtra("document_action"));assertEquals(source.getPath(),last().getStringExtra("path"));assertEquals("",last().getStringExtra("pdf_password"));assertTrue(activity.isFinishing());
    }

    @Test public void pageSelectionCopiesAndTranslatesRealGlyphsAndReleasesItsOverlay()throws Exception{
        open(false);
        main(()->{assertTrue(((View)field(slots().get(0),"frame")).performLongClick());return null;});
        UiTestWait.until(()->field(activity,"selection")!=null&&field(field(activity,"selection"),"actionMode")!=null,"Text selection controls did not attach");
        main(()->{
            PdfPageSelection selection=(PdfPageSelection)field(activity,"selection");
            ActionMode mode=(ActionMode)field(selection,"actionMode");
            assertTrue(mode.getMenu().performIdentifierAction(2,0));
            assertEquals("Reader UI page 1",selection.selectedText());
            Bitmap frame=Bitmap.createBitmap(selection.getWidth(),selection.getHeight(),Bitmap.Config.ARGB_8888);
            try{Canvas canvas=new Canvas(frame);for(int i=0;i<20;i++)selection.draw(canvas);}finally{frame.recycle();}
            assertEquals("Reader UI page 1",selection.selectedText());
            assertTrue(mode.getMenu().performIdentifierAction(1,0));
            assertEquals("Reader UI page 1",((ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip().getItemAt(0).getText().toString());
            mode.getMenu().add(0,999,9,"Unsupported");assertFalse(mode.getMenu().performIdentifierAction(999,0));
            assertTrue(mode.getMenu().performIdentifierAction(3,0));
            Bitmap image=(Bitmap)field(selection,"image");mode.finish();assertTrue(image.isRecycled());assertNull(field(activity,"selection"));
            MotionEvent event=MotionEvent.obtain(0,0,MotionEvent.ACTION_DOWN,1,1,0);
            try{assertFalse(selection.onTouchEvent(event));}finally{event.recycle();}
            return null;
        });
        assertEquals(new ComponentName(context,TextTranslationActivity.class),last().getComponent());
        assertEquals("Reader UI page 1",new String(Files.readAllBytes(new File(last().getStringExtra("text_path")).toPath()),java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test public void draggingPageTextSupportsClicksAndCancellationWithoutClosingTheDocument()throws Exception{
        open(false);main(()->{assertTrue(((View)field(slots().get(0),"frame")).performLongClick());return null;});
        UiTestWait.until(()->field(activity,"selection")!=null&&((View)field(activity,"selection")).getWidth()>0,"Text selection did not render");
        main(()->{
            PdfPageSelection selection=(PdfPageSelection)field(activity,"selection");
            @SuppressWarnings("unchecked") ArrayList<PdfKeywordSearch.Glyph> glyphs=(ArrayList<PdfKeywordSearch.Glyph>)field(selection,"glyphs");
            Bitmap image=(Bitmap)field(selection,"image");float fit=Math.min(selection.getWidth()/(float)image.getWidth(),selection.getHeight()/(float)image.getHeight());
            float width=image.getWidth()*fit,height=image.getHeight()*fit,left=(selection.getWidth()-width)/2,top=(selection.getHeight()-height)/2;
            int[] clicks={0};selection.setOnClickListener(v->clicks[0]++);long now=android.os.SystemClock.uptimeMillis();
            for(int step=0;step<3;step++){
                RectF glyph=glyphs.get(step==0?0:glyphs.size()-1).box;
                MotionEvent event=MotionEvent.obtain(now,now+step*16,step==0?MotionEvent.ACTION_DOWN:step==1?MotionEvent.ACTION_MOVE:MotionEvent.ACTION_UP,left+glyph.centerX()*width,top+glyph.centerY()*height,0);
                try{assertTrue(selection.onTouchEvent(event));}finally{event.recycle();}
            }
            assertEquals("Reader UI page 1",selection.selectedText());assertEquals(1,clicks[0]);
            ActionMode mode=(ActionMode)field(selection,"actionMode");assertNotNull(mode);
            for(int handle=0;handle<2;handle++){
                RectF edge=glyphs.get(handle==0?0:glyphs.size()-1).box,next=glyphs.get(handle==0?1:glyphs.size()-2).box;
                for(int step=0;step<3;step++){
                    float x=step==0?(handle==0?edge.left:edge.right):next.centerX(),y=step==0?edge.bottom:next.centerY();
                    MotionEvent event=MotionEvent.obtain(now+100+handle*100,now+100+handle*100+step*16,step==0?MotionEvent.ACTION_DOWN:step==1?MotionEvent.ACTION_MOVE:MotionEvent.ACTION_UP,left+x*width,top+y*height,0);
                    try{assertTrue(selection.onTouchEvent(event));}finally{event.recycle();}
                }
                assertFalse("Dragging a selection endpoint must resize the selection","Reader UI page 1".equals(selection.selectedText()));
                assertTrue(mode.getMenu().performIdentifierAction(2,0));assertEquals("Reader UI page 1",selection.selectedText());
            }
            assertEquals(3,clicks[0]);
            gestureTime=Math.max(gestureTime,now+1000);pinch(selection,width*.7f,width*.95f);
            long panTime=startGesture();pointer(selection,panTime,panTime,MotionEvent.ACTION_DOWN,width*.7f,false);pointer(selection,panTime,panTime+16,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),width*.7f,true);pointer(selection,panTime,panTime+32,MotionEvent.ACTION_CANCEL,width*.7f,true);
            assertSame(selection,field(activity,"selection"));assertEquals(3,clicks[0]);
            MotionEvent cancel=MotionEvent.obtain(now+400,now+400,MotionEvent.ACTION_CANCEL,0,0,0);
            try{assertTrue(selection.onTouchEvent(cancel));}finally{cancel.recycle();}
            assertEquals(3,clicks[0]);selection.close();assertNull(field(activity,"selection"));assertFalse(activity.isFinishing());return null;
        });
    }
}

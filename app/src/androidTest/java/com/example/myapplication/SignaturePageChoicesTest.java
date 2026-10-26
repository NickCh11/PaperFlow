package com.example.myapplication;

import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SignaturePageChoicesTest {
    final android.app.Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    final Context context=inst.getTargetContext();
    ActivityScenario<SignatureEditorActivity> scenario;
    SignatureEditorActivity activity;
    SignatureEditorActivity.Session session;
    File source;
    final List<File> drawnFiles=new ArrayList<>();
    @Before public void open()throws Exception{
        PDFBoxResourceLoader.init(context);source=File.createTempFile("signature-page-choices-",".pdf",context.getCacheDir());
        try(PDDocument document=new PDDocument()){for(int i=0;i<3;i++)document.addPage(new PDPage(new PDRectangle(200,300)));document.save(source);}
        scenario=ActivityScenario.launch(new Intent(context,SignatureEditorActivity.class).putExtra("path",source.getPath()).putExtra("name","Choice fixture"));
        scenario.onActivity(a->{activity=a;session=(SignatureEditorActivity.Session)field(a,"session");});ready();
    }
    @After public void close()throws Exception{if(scenario!=null)scenario.close();if(session!=null)assertTrue(session.worker.awaitTermination(5,TimeUnit.SECONDS));for(File file:drawnFiles)file.delete();if(source!=null)source.delete();}
    Object field(Object owner,String name){try{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}catch(Exception error){throw new AssertionError(error);}}
    <T>T main(Callable<T> call){scenario.onActivity(a->activity=a);AtomicReference<T> value=new AtomicReference<>();inst.runOnMainSync(()->{try{value.set(call.call());}catch(Exception error){throw new AssertionError(error);}});return value.get();}
    void ready(){UiTestWait.until(()->!session.busy&&session.pageBitmap!=null,"Signing page did not render");inst.waitForIdleSync();}
    View action(View root,String label){if(root instanceof TextView&&root.isShown()&&root.isClickable()&&label.contentEquals(((TextView)root).getText()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View item=action(((ViewGroup)root).getChildAt(i),label);if(item!=null)return item;}return null;}
    void click(int text){main(()->{View action=action(activity.getWindow().getDecorView(),context.getString(text));assertNotNull("Missing signing action",action);assertTrue(action.performClick());return null;});inst.waitForIdleSync();}
    View dialog(){List<View> roots=android.view.inspector.WindowInspector.getGlobalWindowViews();for(int i=roots.size()-1;i>=0;i--){View root=roots.get(i);if(root!=activity.getWindow().getDecorView()&&root.isShown()&&root.hasWindowFocus())return root;}throw new AssertionError("No focused signing dialog");}
    <T extends View>T find(View root,Class<T> type){if(type.isInstance(root))return type.cast(root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){T child=find(((ViewGroup)root).getChildAt(i),type);if(child!=null)return child;}return null;}
    ListView visibleChoices(){ListView list=find(dialog(),ListView.class);assertNotNull("Signing page choices must be displayed",list);assertTrue("Signing page choices must be visible",list.isShown());assertTrue(list.getGlobalVisibleRect(new Rect()));return list;}
    boolean visibleText(View root,String text){if(root instanceof TextView&&text.contentEquals(((TextView)root).getText()))return root.isShown()&&root.getGlobalVisibleRect(new Rect());if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)if(visibleText(((ViewGroup)root).getChildAt(i),text))return true;return false;}
    void choose(String label){int position=main(()->{ListView list=visibleChoices();for(int i=0;i<list.getAdapter().getCount();i++)if(label.contentEquals(String.valueOf(list.getAdapter().getItem(i)))){list.setSelection(i);return i;}throw new AssertionError("Missing choice "+label);});inst.waitForIdleSync();toggle(position);}
    void toggle(int position){main(()->{visibleChoices().setSelection(position);return null;});inst.waitForIdleSync();main(()->{ListView list=visibleChoices();View row=list.getChildAt(position-list.getFirstVisiblePosition());assertNotNull("Choice must be in the visible list",row);assertTrue(list.performItemClick(row,position,list.getAdapter().getItemId(position)));return null;});inst.waitForIdleSync();}
    void button(int id){main(()->{Button button=dialog().findViewById(id);assertNotNull(button);assertTrue(button.performClick());return null;});inst.waitForIdleSync();}
    void more(int item){click(R.string.ui_more);choose(context.getString(item));}
    void addDate(){click(R.string.ui_date);assertEquals(1,session.marks.size());assertEquals(0,session.selected);}
    void bulk(boolean all){more(all?R.string.ui_apply_selected_mark_to_all_pages:R.string.ui_apply_selected_mark_to_chosen_pages);}

    View description(View root,String text){if(root.isShown()&&text.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View match=description(((ViewGroup)root).getChildAt(i),text);if(match!=null)return match;}return null;}
    void drawMode(){
        click(R.string.ui_sign_stamp);main(()->{android.app.Dialog library=(android.app.Dialog)field(activity,"library");View draw=action(library.getWindow().getDecorView(),context.getString(R.string.ui_draw));assertNotNull(draw);assertTrue(draw.performClick());return null;});
        UiTestWait.until(()->{for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews()){View pad=description(root,context.getString(R.string.ui_signature_drawing_area));if(session.draw&&pad!=null&&pad.getWidth()>0&&pad.getHeight()>0)return true;}return false;},"Signature drawing area did not appear");
    }
    void stroke(float x,float y){main(()->{View pad=description(activity.getWindow().getDecorView(),context.getString(R.string.ui_signature_drawing_area));assertNotNull(pad);long now=android.os.SystemClock.uptimeMillis();int[] actions={MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP};for(int i=0;i<actions.length;i++){MotionEvent event=MotionEvent.obtain(now,now+i*30,actions[i],pad.getWidth()*(x+i*.15f),pad.getHeight()*(y+i*.12f),0);try{assertTrue(pad.dispatchTouchEvent(event));}finally{event.recycle();}}return null;});inst.waitForIdleSync();}
    File finishDraw(){click(R.string.ui_done);UiTestWait.until(()->!session.draw,"Signature drawing did not finish");assertEquals(1,session.marks.size());File saved=new File(session.marks.get(0).file);drawnFiles.add(saved);assertTrue(saved.isFile());return saved;}

    @Test public void emptyDrawingCannotBeSavedAndCancelLeavesNoMark(){
        drawMode();click(R.string.ui_done);assertTrue(session.draw);assertTrue(session.marks.isEmpty());click(R.string.ui_cancel);assertFalse(session.draw);assertTrue(session.strokes.isEmpty());assertTrue(session.marks.isEmpty());
    }
    @Test public void drawnStrokesCanBeUndoneClearedAndCancelled(){
        drawMode();stroke(.15f,.2f);stroke(.35f,.4f);assertEquals(2,session.strokes.size());assertTrue(session.strokes.get(0).points.size()>=4);click(R.string.ui_undo);assertEquals(1,session.strokes.size());click(R.string.ui_clear);assertTrue(session.strokes.isEmpty());click(R.string.ui_undo);assertTrue(session.strokes.isEmpty());click(R.string.ui_cancel);assertFalse(session.draw);assertTrue(session.marks.isEmpty());
    }
    @Test public void selectedInkProducesATrimmedReusableSignaturePng()throws Exception{
        drawMode();click(R.string.ui_ink);choose(context.getString(R.string.ui_red));stroke(.15f,.2f);assertEquals(0xffc63232,session.strokes.get(0).color);File saved=finishDraw();
        Bitmap bitmap=BitmapFactory.decodeFile(saved.getPath());assertNotNull(bitmap);try{assertTrue(bitmap.getWidth()<1600);assertTrue(bitmap.getHeight()<600);int[] pixels=new int[bitmap.getWidth()*bitmap.getHeight()];bitmap.getPixels(pixels,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight());boolean red=false,transparent=false;for(int pixel:pixels){transparent|=Color.alpha(pixel)==0;red|=Color.alpha(pixel)>128&&Color.red(pixel)>Color.blue(pixel)*2;}assertTrue("Saved signature must contain the selected ink",red);assertTrue("Signature background must stay transparent",transparent);}finally{bitmap.recycle();}
        assertTrue(SignatureStore.list(context,false).contains(saved));assertTrue(source.isFile());
    }
    @Test public void drawnSignatureSurvivesRecreationAndCanBeReusedFromTheLibrary(){
        drawMode();stroke(.2f,.25f);File saved=finishDraw();Bitmap original=session.marks.get(0).bitmap;scenario.recreate();scenario.onActivity(a->activity=a);assertEquals(1,session.marks.size());assertSame(original,session.marks.get(0).bitmap);assertFalse(original.isRecycled());
        click(R.string.ui_sign_stamp);main(()->{assertEquals(saved,SignatureStore.list(context,false).get(0));android.app.Dialog library=(android.app.Dialog)field(activity,"library");View image=description(library.getWindow().getDecorView(),context.getString(R.string.ui_use_saved_signature));assertNotNull(image);assertTrue(image.performClick());return null;});assertEquals(2,session.marks.size());assertEquals(saved.getPath(),session.marks.get(1).file);assertTrue(original.sameAs(session.marks.get(1).bitmap));
    }

    @Test public void selectedPageDialogShowsChoicesAndAppliesOnlyCheckedPages(){
        addDate();bulk(false);main(()->{ListView list=visibleChoices();assertEquals(3,list.getCount());assertTrue("Page-copy explanation must remain visible",visibleText(dialog(),context.getString(R.string.ui_copies_keep_the_same_relative_position_and_width_existing_marks_are_kept_re)));assertTrue(list.isItemChecked(0));assertFalse(list.isItemChecked(1));assertFalse(list.isItemChecked(2));return null;});
        toggle(2);button(android.R.id.button1);assertEquals(2,session.marks.size());assertEquals(0,session.marks.get(0).page);assertEquals(2,session.marks.get(1).page);assertSame(session.marks.get(0).bitmap,session.marks.get(1).bitmap);
    }
    @Test public void cancellingPageSelectionDoesNotAddMarks(){
        addDate();Bitmap original=session.marks.get(0).bitmap;bulk(false);toggle(1);button(android.R.id.button2);assertEquals(1,session.marks.size());assertSame(original,session.marks.get(0).bitmap);assertFalse(original.isRecycled());
    }
    @Test public void allPagesCanBeUncheckedBeforeApplyingTheSignature(){
        addDate();bulk(true);main(()->{ListView list=visibleChoices();for(int i=0;i<3;i++)assertTrue(list.isItemChecked(i));return null;});toggle(2);button(android.R.id.button1);assertEquals(2,session.marks.size());assertEquals(0,session.marks.get(0).page);assertEquals(1,session.marks.get(1).page);
    }
    @Test public void pagePickerAndNavigationStayWithinTheDocument(){
        more(R.string.ui_choose_page);choose("Page 3");ready();assertEquals(2,session.page);click(R.string.ui_next_page);assertEquals(2,session.page);assertFalse(session.busy);click(R.string.ui_previous_page);ready();assertEquals(1,session.page);
    }
    @Test public void deletingEachPageKeepsSharedMarkImagesUntilTheLastCopy(){
        addDate();bulk(true);button(android.R.id.button1);assertEquals(3,session.marks.size());Bitmap shared=session.marks.get(0).bitmap;
        for(int page=0;page<3;page++){
            more(R.string.ui_delete);button(android.R.id.button1);assertEquals(2-page,session.marks.size());assertEquals(page==2,shared.isRecycled());
            if(page<2){click(R.string.ui_next_page);ready();}
        }
        assertTrue(source.isFile());
    }
}

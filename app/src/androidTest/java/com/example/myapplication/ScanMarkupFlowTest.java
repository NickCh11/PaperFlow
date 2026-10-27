package com.example.myapplication;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.SystemClock;
import android.view.*;
import android.widget.TextView;
import android.widget.SeekBar;
import android.widget.ListView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import static org.junit.Assert.*;

/** Markup is checked through visible tools and its actual encoded image. */
public class ScanMarkupFlowTest {
    final Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
    ActivityScenario<ScanEditorActivity> scenario;ScanEditorActivity activity;File source;byte[] original;
    final ArrayList<File> outputs=new ArrayList<>();final ArrayList<ExecutorService> workers=new ArrayList<>();
    Object field(Object owner,String name){try{java.lang.reflect.Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}catch(Exception error){throw new AssertionError(error);}}
    <T>T main(Callable<T> action){AtomicReference<T> result=new AtomicReference<>();InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{result.set(action.call());}catch(Exception error){throw new AssertionError(error);}});return result.get();}
    @Before public void open()throws Exception{
        source=File.createTempFile("markup-ui-",".png",context.getCacheDir());Bitmap image=Bitmap.createBitmap(320,480,Bitmap.Config.ARGB_8888);image.eraseColor(Color.WHITE);
        try(OutputStream out=new FileOutputStream(source)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}finally{image.recycle();}original=Files.readAllBytes(source.toPath());
        Intent intent=new Intent(context,ScanEditorActivity.class).putStringArrayListExtra("pages",new ArrayList<>(Collections.singletonList(Uri.fromFile(source).toString()))).putExtra("start_crop",false).putExtra("initial_filter",0).putExtra("name","Markup fixture");
        scenario=ActivityScenario.launch(intent);current();ready();
    }
    void current(){scenario.onActivity(a->activity=a);workers.add((ExecutorService)field(activity,"worker"));}
    void ready(){UiTestWait.until(()->field(activity,"displayed")!=null&&!((Boolean)field(activity,"loadingPage")),"Scan editor preview did not render");}
    ScanEditorActivity.MarkupState state(){return (ScanEditorActivity.MarkupState)field(activity,"markupState");}
    View action(View root,String label){if(root.isShown()&&root instanceof TextView&&label.contentEquals(((TextView)root).getText()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=action(((ViewGroup)root).getChildAt(i),label);if(found!=null)return found;}return null;}
    void press(int label){main(()->{View view=action(activity.getWindow().getDecorView(),UiText.get(label));assertNotNull("Missing markup action "+label,view);assertTrue(view.performClick());return null;});}
    void markup(){press(R.string.ui_markup);UiTestWait.until(()->state().bitmap!=null&&!state().loading&&field(activity,"markupView")!=null&&((View)field(activity,"markupView")).getWidth()>0,"Markup image did not render");}
    void stroke(){main(()->{View view=(View)field(activity,"markupView");Bitmap bitmap=state().bitmap;float scale=Math.min((view.getWidth()-24*context.getResources().getDisplayMetrics().density)/(float)bitmap.getWidth(),(view.getHeight()-24*context.getResources().getDisplayMetrics().density)/(float)bitmap.getHeight());float width=bitmap.getWidth()*scale,left=(view.getWidth()-width)/2;long time=SystemClock.uptimeMillis();for(int i=0;i<3;i++){MotionEvent event=MotionEvent.obtain(time,time+i*16,i==0?MotionEvent.ACTION_DOWN:i==1?MotionEvent.ACTION_MOVE:MotionEvent.ACTION_UP,left+width*(i==0?.25f:.75f),view.getHeight()/2f,0);try{assertTrue(view.dispatchTouchEvent(event));}finally{event.recycle();}}return null;});}
    @After public void cleanup()throws Exception{
        if(scenario!=null)scenario.close();for(ExecutorService worker:workers)assertTrue(worker.awaitTermination(15,TimeUnit.SECONDS));
        if(source!=null){assertArrayEquals(original,Files.readAllBytes(source.toPath()));source.delete();}for(File file:outputs)file.delete();
    }

    @Test public void drawingSurvivesRecreationAndApplyWritesInkToANewImage()throws Exception{
        markup();stroke();assertEquals(1,main(()->state().strokes.size()).intValue());
        scenario.recreate();current();UiTestWait.until(()->state().active&&state().bitmap!=null&&!state().loading,"Markup did not survive recreation");
        assertEquals(1,main(()->state().strokes.size()).intValue());press(R.string.ui_apply);
        UiTestWait.until(()->!state().active&&!state().applying&&!((Boolean)field(activity,"loadingPage")),"Markup was not applied");
        @SuppressWarnings("unchecked") ArrayList<String> pages=(ArrayList<String>)field(activity,"pages");File saved=new File(Uri.parse(pages.get(0)).getPath());outputs.add(saved);
        assertNotEquals(source.getPath(),saved.getPath());Bitmap output=BitmapFactory.decodeFile(saved.getPath());assertNotNull(output);
        try{int pixel=output.getPixel(output.getWidth()/2,output.getHeight()/2);assertTrue("Encoded markup lost its red ink",Color.red(pixel)>Color.green(pixel)+30);}finally{output.recycle();}
        assertNull(main(()->state().bitmap));assertTrue(main(()->state().strokes.isEmpty()));
    }

    @Test public void undoClearAndCancelDiscardMarkupAndKeepTheOriginalPage()throws Exception{
        markup();stroke();press(R.string.ui_undo);assertTrue(main(()->state().strokes.isEmpty()));
        stroke();press(R.string.ui_clear);assertTrue(main(()->state().strokes.isEmpty()));
        press(R.string.ui_apply);assertTrue(main(()->state().active)); // Empty markup is rejected.
        stroke();Bitmap editing=main(()->state().bitmap);press(R.string.ui_cancel);ready();
        assertFalse(main(()->state().active));assertTrue(editing.isRecycled());
        @SuppressWarnings("unchecked") ArrayList<String> pages=(ArrayList<String>)field(activity,"pages");assertEquals(Uri.fromFile(source).toString(),pages.get(0));
    }

    private View focused(){for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews())if(root!=activity.getWindow().getDecorView()&&root.isShown()&&root.hasWindowFocus())return root;return null;}
    private <T extends View>void collect(View view,Class<T> type,List<T> result){if(view==null)return;if(type.isInstance(view))result.add(type.cast(view));if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)collect(((ViewGroup)view).getChildAt(i),type,result);}
    private View described(View root,String description){if(description.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=described(((ViewGroup)root).getChildAt(i),description);if(found!=null)return found;}return null;}

    @Test public void brightnessAndContrastGesturesUpdateTheirLabelsAndResetBothAdjustments()throws Exception{
        main(()->{View more=described(activity.getWindow().getDecorView(),UiText.get(R.string.ui_more_page_options));assertNotNull(more);assertTrue(more.performClick());return null;});
        UiTestWait.until(()->{ArrayList<ListView> lists=new ArrayList<>();collect(focused(),ListView.class,lists);return !lists.isEmpty();},"Editor options did not attach");
        main(()->{ArrayList<ListView> lists=new ArrayList<>();collect(focused(),ListView.class,lists);ListView list=lists.get(0);for(int i=0;i<list.getCount();i++)if(UiText.get(R.string.ui_brightness_contrast).equals(list.getAdapter().getItem(i).toString())){assertTrue(list.performItemClick(list.getChildAt(i-list.getFirstVisiblePosition()),i,list.getAdapter().getItemId(i)));return null;}throw new AssertionError("Missing tonal adjustment action");});
        UiTestWait.until(()->{ArrayList<SeekBar> bars=new ArrayList<>();collect(focused(),SeekBar.class,bars);return bars.size()==2&&bars.get(1).getWidth()>0;},"Tonal sliders did not attach");
        main(()->{
            ArrayList<SeekBar> bars=new ArrayList<>();collect(focused(),SeekBar.class,bars);long now=SystemClock.uptimeMillis();
            for(SeekBar bar:bars)for(int i=0;i<2;i++){MotionEvent event=MotionEvent.obtain(now,now+i*16,i==0?MotionEvent.ACTION_DOWN:MotionEvent.ACTION_UP,bar.getWidth()*.75f,bar.getHeight()*.5f,0);try{assertTrue(bar.dispatchTouchEvent(event));}finally{event.recycle();}}
            @SuppressWarnings("unchecked") ArrayList<Integer> light=(ArrayList<Integer>)field(activity,"brightness");@SuppressWarnings("unchecked") ArrayList<Integer> contrast=(ArrayList<Integer>)field(activity,"contrast");
            assertTrue(light.get(0)>0);assertTrue(contrast.get(0)>0);assertNotNull(action(focused(),UiText.format(R.string.ui_brightness_value,light.get(0))));assertNotNull(action(focused(),UiText.format(R.string.ui_contrast_value,contrast.get(0))));
            assertTrue(focused().findViewById(android.R.id.button3).performClick());return null;
        });ready();
        main(()->{assertEquals(Integer.valueOf(0),((List<?>)field(activity,"brightness")).get(0));assertEquals(Integer.valueOf(0),((List<?>)field(activity,"contrast")).get(0));return null;});
    }

    @Test public void cancellingWhileMarkupIsQueuedCannotResurrectTheAbandonedPreview()throws Exception{
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        ((ExecutorService)field(activity,"worker")).execute(()->{entered.countDown();try{release.await(30,TimeUnit.SECONDS);}catch(InterruptedException error){Thread.currentThread().interrupt();}});
        try{
            assertTrue(entered.await(10,TimeUnit.SECONDS));press(R.string.ui_markup);
            UiTestWait.until(()->state().active&&state().loading,"Markup request was not queued");
            press(R.string.ui_cancel);release.countDown();ready();
            assertFalse(main(()->state().active));assertNull(main(()->state().bitmap));
            @SuppressWarnings("unchecked") ArrayList<String> pages=(ArrayList<String>)field(activity,"pages");assertEquals(Uri.fromFile(source).toString(),pages.get(0));
        }finally{release.countDown();}
    }

    @Test public void unreadableMarkupInputReturnsControlAndTheEditorCanRecover()throws Exception{
        try{
            Files.write(source.toPath(),new byte[]{1,2,3});press(R.string.ui_markup);
            UiTestWait.until(()->!state().active&&!state().loading&&!((Boolean)field(activity,"loadingPage")),"Unreadable markup left the editor loading");
            assertNull(main(()->state().bitmap));Files.write(source.toPath(),original);
            press(R.string.ui_left);ready();markup();assertNotNull(main(()->state().bitmap));
            press(R.string.ui_cancel);ready();assertFalse(main(()->state().active));
        }finally{Files.write(source.toPath(),original);}
    }
}

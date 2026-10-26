package com.example.myapplication;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.paperflow.imageprocessing.*;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.File;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** User-facing language, preview, clipboard and export controls. */
@RunWith(AndroidJUnit4.class)
public class PhotoTranslationUiTest {
    final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    final Context context=inst.getTargetContext();
    ActivityScenario<LocalTranslationActivity> scenario;
    LocalTranslationActivity activity;
    LocalTranslationActivity.TranslationSession session;
    Instrumentation.ActivityMonitor monitor;
    final AtomicReference<Intent> launched=new AtomicReference<>();
    String previousTarget;boolean hadTarget;

    @Before public void open(){
        SharedPreferences prefs=context.getSharedPreferences("photo-translation",0);hadTarget=prefs.contains("target");previousTarget=prefs.getString("target","en");prefs.edit().putString("target","en").commit();
        scenario=ActivityScenario.launch(LocalTranslationActivity.class);scenario.onActivity(a->{activity=a;session=(LocalTranslationActivity.TranslationSession)field(a,"session");});
    }
    @After public void close()throws Exception{
        if(monitor!=null)inst.removeMonitor(monitor);
        if(scenario!=null)scenario.close();
        if(session!=null)assertTrue("Session worker did not stop",session.worker.awaitTermination(5,TimeUnit.SECONDS));
        SharedPreferences.Editor edit=context.getSharedPreferences("photo-translation",0).edit();if(hadTarget)edit.putString("target",previousTarget);else edit.remove("target");edit.commit();
    }
    Object field(Object owner,String name){try{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}catch(Exception error){throw new AssertionError(error);}}
    <T>T main(Callable<T> action){AtomicReference<T> value=new AtomicReference<>();inst.runOnMainSync(()->{try{value.set(action.call());}catch(Exception error){throw new AssertionError(error);}});return value.get();}
    <T extends View>T find(View root,Class<T> type){if(type.isInstance(root)&&root.isShown())return type.cast(root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){T child=find(((ViewGroup)root).getChildAt(i),type);if(child!=null)return child;}return null;}
    View dialog(){List<View> roots=android.view.inspector.WindowInspector.getGlobalWindowViews();for(int i=roots.size()-1;i>=0;i--){View root=roots.get(i);View button=root.findViewById(android.R.id.button2);if(button!=null&&button.isShown())return root;View positive=root.findViewById(android.R.id.button1);if(positive!=null&&positive.isShown())return root;if(root!=activity.getWindow().getDecorView()&&root.isShown()&&root.hasWindowFocus()&&find(root,ListView.class)!=null)return root;}throw new AssertionError("No visible dialog");}
    View action(View root,String label){if(root instanceof TextView&&root.isClickable()&&root.isShown()&&label.contentEquals(((TextView)root).getText()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=action(((ViewGroup)root).getChildAt(i),label);if(found!=null)return found;}return null;}
    void click(String field){main(()->{View control=(View)field(activity,field);assertTrue(control.isShown());assertTrue(control.performClick());return null;});inst.waitForIdleSync();}
    void clickAction(int text){main(()->{View button=action(activity.getWindow().getDecorView(),context.getString(text));assertNotNull("Missing visible action",button);assertTrue(button.performClick());return null;});inst.waitForIdleSync();}
    void button(int id){main(()->{Button button=dialog().findViewById(id);assertNotNull(button);assertTrue(button.performClick());return null;});inst.waitForIdleSync();}
    void waitFor(BooleanSupplier condition)throws Exception{long end=android.os.SystemClock.elapsedRealtime()+10000;while(android.os.SystemClock.elapsedRealtime()<end){inst.waitForIdleSync();if(condition.getAsBoolean())return;Thread.sleep(30);}fail("UI operation timed out");}
    void chooseFirst(){main(()->{ListView list=find(dialog(),ListView.class);assertNotNull(list);View row=list.getChildAt(0);assertNotNull("Choice row must be visible",row);assertTrue(list.performItemClick(row,0,list.getAdapter().getItemId(0)));return null;});inst.waitForIdleSync();}
    void language(String control,String code)throws Exception{
        click(control);String name=TranslationCatalog.language(context,code).name;
        main(()->{EditText search=find(dialog(),EditText.class);assertNotNull(search);search.setText(name);return null;});
        waitFor(()->main(()->{ListView list=find(dialog(),ListView.class);return list!=null&&list.getAdapter().getCount()==1&&list.getChildCount()==1;}));chooseFirst();
    }
    File translatedPreview()throws Exception{
        Bitmap source=Bitmap.createBitmap(500,700,Bitmap.Config.ARGB_8888);source.eraseColor(Color.YELLOW);Bitmap translated=source.copy(Bitmap.Config.ARGB_8888,true);translated.eraseColor(Color.GREEN);
        OcrTextBox word=new OcrTextBox("ORIGINAL",new ImageRect(40,50,350,100),50,100);ArrayList<TranslationRenderer.Block> blocks=TranslationRenderer.blocks(Collections.singletonList(word));blocks.get(0).translation="TRANSLATED 12345";
        File snapshot;try{snapshot=TranslationSnapshot.write(context,source,new TranslationRenderer.Result(translated,Collections.singletonList(word)),blocks,"en","en","en",true,false);}finally{source.recycle();translated.recycle();}
        main(()->{session.busy=true;session.restoring=true;return null;});TranslationSnapshot.restore(session,snapshot.getPath());waitFor(()->!session.busy);assertFalse(session.message,session.error);return snapshot;
    }
    void intercept(){monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){launched.set(new Intent(intent));return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);}};inst.addMonitor(monitor);}

    @Test public void targetLanguageSearchPersistsAndAccessibilityMatchesTheVisibleTarget()throws Exception{
        language("target","el");assertEquals("el",session.target);assertEquals("el",context.getSharedPreferences("photo-translation",0).getString("target",""));
        main(()->{TextView target=(TextView)field(activity,"target");assertEquals(target.getText().toString(),String.valueOf(target.getContentDescription()));return null;});
        scenario.recreate();scenario.onActivity(a->activity=a);assertEquals("el",((LocalTranslationActivity.TranslationSession)field(activity,"session")).target);
    }
    @Test public void sourceSearchSupportsExplicitLanguageAndReturningToAutomatic()throws Exception{
        language("detected","el");assertEquals("el",session.sourceOverride);main(()->{TextView source=(TextView)field(activity,"detected");assertTrue(String.valueOf(source.getContentDescription()).contains(source.getText()));return null;});click("detected");chooseFirst();assertEquals("auto",session.sourceOverride);
    }
    @Test public void cancellingTheLanguageChooserPreservesTheCurrentTarget(){
        click("target");main(()->{EditText search=find(dialog(),EditText.class);search.setText("Greek");return null;});button(android.R.id.button2);assertEquals("en",session.target);assertFalse(session.busy);
    }
    @Test public void importAndCameraSendTheExpectedRequestAndCancellationKeepsTheEmptyScreen(){
        intercept();clickAction(R.string.ui_translation_import_images);Intent images=launched.get();assertNotNull(images);assertEquals(Intent.ACTION_OPEN_DOCUMENT,images.getAction());assertTrue(images.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false));assertArrayEquals(new String[]{"image/*"},images.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));
        launched.set(null);clickAction(R.string.ui_camera);Intent camera=launched.get();assertNotNull(camera);assertEquals(CameraCaptureActivity.class.getName(),camera.getComponent().getClassName());assertTrue(camera.getBooleanExtra("translation_capture",false));assertEquals("Photo",camera.getStringExtra("capture_mode"));assertEquals("auto",camera.getStringExtra("translation_source"));assertEquals("en",camera.getStringExtra("translation_target"));assertNull(session.source);assertFalse(session.busy);
    }
    @Test public void copyingTranslationCopiesTheDisplayedText()throws Exception{
        translatedPreview();clickAction(R.string.ui_copy_text);main(()->{TextView text=displayedText(dialog(),"TRANSLATED 12345");assertNotNull("Translation text must be visible",text);assertTrue(text.isTextSelectable());return null;});button(android.R.id.button1);
        String copied=main(()->{ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);assertNotNull(clipboard.getPrimaryClip());return clipboard.getPrimaryClip().getItemAt(0).coerceToText(activity).toString();});assertEquals("TRANSLATED 12345",copied);
    }
    @Test public void originalPreviewSelectionSurvivesActivityRecreation()throws Exception{
        translatedPreview();click("originalTab");assertSame(session.source,field(field(activity,"preview"),"bitmap"));scenario.recreate();scenario.onActivity(a->activity=a);
        assertTrue((Boolean)field(activity,"original"));assertSame(session.source,field(field(activity,"preview"),"bitmap"));click("translatedTab");assertSame(session.result.bitmap,field(field(activity,"preview"),"bitmap"));
    }
    @Test public void cancellingExportDoesNotCreateAFileOrLoseThePreview()throws Exception{
        translatedPreview();Bitmap previous=session.result.bitmap;clickAction(R.string.ui_save_to_device);button(android.R.id.button2);assertFalse(session.busy);assertNull(session.lastExport);assertTrue(session.exportFiles.isEmpty());assertSame(previous,session.result.bitmap);assertFalse(previous.isRecycled());
    }
    @Test public void cancellingTextEditsPreservesRecognizedAndTranslatedText()throws Exception{
        translatedPreview();clickMore("Edit recognized text");main(()->{EditText edit=find(dialog(),EditText.class);assertEquals("ORIGINAL",edit.getText().toString());edit.setText("UNSAVED SOURCE");return null;});button(android.R.id.button2);assertEquals("ORIGINAL",session.blocks.get(0).source);
        clickMore("Edit translation");main(()->{EditText edit=find(dialog(),EditText.class);assertEquals("TRANSLATED 12345",edit.getText().toString());edit.setText("UNSAVED TRANSLATION");return null;});button(android.R.id.button2);assertEquals("TRANSLATED 12345",session.translatedText());assertFalse(session.busy);
    }
    @Test public void offlineExplanationIsVisibleAndClosesBackToTheSameScreen(){
        clickMore("About offline translation");main(()->{TextView text=displayedText(dialog(),context.getString(R.string.ui_offline_translation_help));assertNotNull(text);assertTrue(text.getGlobalVisibleRect(new Rect()));return null;});button(android.R.id.button1);assertFalse(activity.isFinishing());assertNull(session.source);
    }
    @Test public void cancellingNewPhotoImportAndCameraKeepsTheCurrentTranslation()throws Exception{
        translatedPreview();Bitmap source=session.source,translated=session.result.bitmap;intercept();
        for(int choice:new int[]{1,0}){
            launched.set(null);clickAction(R.string.ui_new_translation);
            main(()->{ListView list=find(dialog(),ListView.class);View row=list.getChildAt(choice);assertNotNull(row);assertTrue(list.performItemClick(row,choice,list.getAdapter().getItemId(choice)));return null;});inst.waitForIdleSync();
            assertNotNull(launched.get());if(choice==1)assertEquals(Intent.ACTION_OPEN_DOCUMENT,launched.get().getAction());else assertEquals(CameraCaptureActivity.class.getName(),launched.get().getComponent().getClassName());
            assertSame(source,session.source);assertSame(translated,session.result.bitmap);assertFalse(source.isRecycled());assertFalse(translated.isRecycled());assertFalse(session.busy);
        }
    }
    TextView displayedText(View root,String text){if(root instanceof TextView&&root.isShown()&&text.contentEquals(((TextView)root).getText()))return (TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView match=displayedText(((ViewGroup)root).getChildAt(i),text);if(match!=null)return match;}return null;}
    void clickMore(String label){main(()->{ViewGroup root=activity.findViewById(android.R.id.content);View more=findDescription(root,context.getString(R.string.ui_more_options));assertNotNull(more);assertTrue(more.performClick());return null;});inst.waitForIdleSync();
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(UiText.label(label))).perform(androidx.test.espresso.action.ViewActions.click());inst.waitForIdleSync();}
    View findDescription(View root,String description){if(root.isClickable()&&root.isShown()&&description.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=findDescription(((ViewGroup)root).getChildAt(i),description);if(found!=null)return found;}return null;}
}

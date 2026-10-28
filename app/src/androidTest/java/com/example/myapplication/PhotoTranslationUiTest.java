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
    View visibleDialog(){List<View> roots=android.view.inspector.WindowInspector.getGlobalWindowViews();for(int i=roots.size()-1;i>=0;i--){View root=roots.get(i);if(root==activity.getWindow().getDecorView()||!root.isShown()||!root.hasWindowFocus())continue;View negative=root.findViewById(android.R.id.button2),positive=root.findViewById(android.R.id.button1);if(negative!=null&&negative.isShown()||positive!=null&&positive.isShown()||find(root,ListView.class)!=null)return root;}return null;}
    View dialog(){View root=visibleDialog();assertNotNull("No visible dialog",root);return root;}
    void awaitDialog(){UiTestWait.until(()->visibleDialog()!=null,"Photo translation dialog did not attach and receive focus");}
    void awaitChoices(){UiTestWait.until(()->{View root=visibleDialog();ListView list=root==null?null:find(root,ListView.class);return list!=null&&list.getChildCount()>0;},"Photo translation choices did not attach");}
    View action(View root,String label){if(root instanceof TextView&&root.isClickable()&&root.isShown()&&label.contentEquals(((TextView)root).getText()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=action(((ViewGroup)root).getChildAt(i),label);if(found!=null)return found;}return null;}
    void click(String field){main(()->{View control=(View)field(activity,field);assertTrue(control.isShown());assertTrue(control.performClick());return null;});inst.waitForIdleSync();if(field.equals("target")||field.equals("detected"))awaitDialog();}
    void clickAction(int text){main(()->{View button=action(activity.getWindow().getDecorView(),context.getString(text));assertNotNull("Missing visible action",button);assertTrue(button.performClick());return null;});inst.waitForIdleSync();if(text==R.string.ui_new_translation||text==R.string.ui_copy_text||text==R.string.ui_save_to_device)awaitDialog();}
    void button(int id){UiTestWait.until(()->{View root=visibleDialog();View button=root==null?null:root.findViewById(id);return button!=null&&button.isShown()&&button.isEnabled();},"Photo translation dialog button did not attach");main(()->{Button button=dialog().findViewById(id);assertNotNull(button);assertTrue(button.performClick());return null;});inst.waitForIdleSync();}
    void waitFor(BooleanSupplier condition)throws Exception{long end=android.os.SystemClock.elapsedRealtime()+10000;while(android.os.SystemClock.elapsedRealtime()<end){inst.waitForIdleSync();if(condition.getAsBoolean())return;Thread.sleep(30);}fail("UI operation timed out");}
    void chooseFirst(){awaitChoices();main(()->{ListView list=find(dialog(),ListView.class);assertNotNull(list);View row=list.getChildAt(0);assertNotNull("Choice row must be visible",row);assertTrue(list.performItemClick(row,0,list.getAdapter().getItemId(0)));return null;});inst.waitForIdleSync();}
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
        intercept();clickAction(R.string.ui_translation_import_files);Intent images=launched.get();assertNotNull(images);assertEquals(Intent.ACTION_OPEN_DOCUMENT,images.getAction());assertTrue(images.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE,false));assertArrayEquals(new String[]{"image/*","application/pdf"},images.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));
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
            launched.set(null);clickAction(R.string.ui_new_translation);awaitChoices();
            main(()->{ListView list=find(dialog(),ListView.class);View row=list.getChildAt(choice);assertNotNull(row);assertTrue(list.performItemClick(row,choice,list.getAdapter().getItemId(choice)));return null;});inst.waitForIdleSync();
            assertNotNull(launched.get());if(choice==1)assertEquals(Intent.ACTION_OPEN_DOCUMENT,launched.get().getAction());else assertEquals(CameraCaptureActivity.class.getName(),launched.get().getComponent().getClassName());
            assertSame(source,session.source);assertSame(translated,session.result.bitmap);assertFalse(source.isRecycled());assertFalse(translated.isRecycled());assertFalse(session.busy);
        }
    }
    void recreateWithFreshSession(java.util.function.Consumer<android.os.Bundle> savedState)throws Exception{
        LocalTranslationActivity previous=activity;LocalTranslationActivity.TranslationSession old=session;Bitmap oldSource=old.source,oldResult=old.result==null?null:old.result.bitmap;java.util.concurrent.atomic.AtomicBoolean reset=new java.util.concurrent.atomic.AtomicBoolean();
        Application app=(Application)context.getApplicationContext();
        Application.ActivityLifecycleCallbacks callback=new Application.ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a,android.os.Bundle b){}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivityDestroyed(Activity a){}
            public void onActivitySaveInstanceState(Activity a,android.os.Bundle state){if(a!=previous||!reset.compareAndSet(false,true))return;savedState.accept(state);
                // Emulate a fresh ViewModel while retaining our persisted fixture files.
                // This checks saved-state restoration; it does not kill the Android process.
                old.snapshots.clear();old.importedSources.clear();old.source=null;old.result=null;previous.getViewModelStore().clear();}
        };
        app.registerActivityLifecycleCallbacks(callback);
        try{scenario.recreate();scenario.onActivity(a->{activity=a;session=(LocalTranslationActivity.TranslationSession)field(a,"session");});assertTrue(reset.get());assertNotSame(old,session);assertTrue(old.worker.awaitTermination(10,TimeUnit.SECONDS));}
        finally{app.unregisterActivityLifecycleCallbacks(callback);if(reset.get()){if(oldSource!=null&&!oldSource.isRecycled())oldSource.recycle();if(oldResult!=null&&!oldResult.isRecycled())oldResult.recycle();}}
    }
    @Test public void freshSavedStateRestoresOwnedPagesAndExportsButRejectsForeignAndMissingPaths()throws Exception{
        File snapshot=translatedPreview(),first=File.createTempFile("translation-restored-one-",".png",context.getCacheDir()),second=File.createTempFile("translation-restored-two-",".png",context.getCacheDir()),foreign=File.createTempFile("translation-foreign-",".png",context.getExternalCacheDir());
        java.nio.file.Files.copy(new File(snapshot,"result.png").toPath(),first.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);java.nio.file.Files.copy(first.toPath(),second.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);java.nio.file.Files.copy(first.toPath(),foreign.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        try{
            recreateWithFreshSession(state->{state.putStringArrayList("captured_pages",new ArrayList<>(Arrays.asList(null,new File(snapshot,"source.png").getPath(),new File(snapshot,"source.png").getPath())));state.putInt("captured_index",1);state.putString("translation_capture_mode","Book");state.putString("translation_session",snapshot.getPath());state.putString("pending_export",first.getPath());state.putStringArrayList("translated_page_snapshots",new ArrayList<>(Arrays.asList(null,snapshot.getPath(),foreign.getPath(),new File(snapshot.getParentFile(),"missing-"+System.nanoTime()).getPath())));state.putStringArrayList("translation_export_files",new ArrayList<>(Arrays.asList(first.getPath(),foreign.getPath(),new File(context.getCacheDir(),"missing-"+System.nanoTime()).getPath(),second.getPath())));state.putInt("translation_export_page_count",2);});
            UiTestWait.until(()->!session.busy&&session.source!=null,"Fresh translation session did not restore");assertFalse(session.message,session.error);assertFalse(session.restoring);assertEquals(2,session.capturedPages.size());assertEquals(1,session.capturedIndex);assertEquals("Book",session.captureMode);assertEquals(snapshot.getCanonicalFile(),session.snapshot.getCanonicalFile());assertEquals(Collections.singleton(1),session.pageSnapshots.keySet());assertEquals(Arrays.asList(first.getCanonicalFile(),second.getCanonicalFile()),session.exportFiles);assertEquals(first.getCanonicalFile(),session.lastExport);assertEquals(2,session.exportPageCount);assertEquals("TRANSLATED 12345",session.translatedText());assertTrue(foreign.isFile());
        }finally{first.delete();second.delete();foreign.delete();}
    }
    @Test public void unavailableSavedTranslationFailsWithoutAdoptingForeignOutputPaths()throws Exception{
        File foreign=File.createTempFile("translation-foreign-",".png",context.getExternalCacheDir());
        try{
            recreateWithFreshSession(state->{state.remove("captured_pages");state.remove("translation_capture_mode");state.putString("translation_session",foreign.getPath());state.putString("pending_export",foreign.getPath());state.putStringArrayList("translated_page_snapshots",new ArrayList<>(Arrays.asList(null,foreign.getPath())));state.putStringArrayList("translation_export_files",new ArrayList<>(Collections.singletonList(foreign.getPath())));});
            UiTestWait.until(()->!session.busy&&session.error,"Invalid saved translation did not report an error");assertFalse(session.restoring);assertNull(session.source);assertNull(session.result);assertNull(session.lastExport);assertTrue(session.capturedPages.isEmpty());assertTrue(session.pageSnapshots.isEmpty());assertTrue(session.exportFiles.isEmpty());assertEquals("Photo",session.captureMode);assertTrue(foreign.isFile());
        }finally{foreign.delete();}
    }
    void previewPointer(View view,long time,int action,float span,boolean two){
        MotionEvent.PointerProperties first=new MotionEvent.PointerProperties();first.id=0;first.toolType=MotionEvent.TOOL_TYPE_FINGER;MotionEvent.PointerProperties second=new MotionEvent.PointerProperties();second.id=1;second.toolType=MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords left=new MotionEvent.PointerCoords();left.x=view.getWidth()/2f-span/2;left.y=view.getHeight()/2f;left.pressure=left.size=1;MotionEvent.PointerCoords right=new MotionEvent.PointerCoords();right.x=view.getWidth()/2f+span/2;right.y=left.y;right.pressure=right.size=1;
        MotionEvent event=MotionEvent.obtain(time,time,action,two?2:1,two?new MotionEvent.PointerProperties[]{first,second}:new MotionEvent.PointerProperties[]{first},two?new MotionEvent.PointerCoords[]{left,right}:new MotionEvent.PointerCoords[]{left},0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try{assertTrue(view.dispatchTouchEvent(event));}finally{event.recycle();}
    }
    long previewGestureTime;
    void zoomPreview(View view,float from,float to){
        long time=previewGestureTime=Math.max(android.os.SystemClock.uptimeMillis(),previewGestureTime+1000);
        previewPointer(view,time,MotionEvent.ACTION_DOWN,from,false);previewPointer(view,time+16,MotionEvent.ACTION_POINTER_DOWN|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),from,true);
        for(int i=1;i<=8;i++)previewPointer(view,time+16+i*16,MotionEvent.ACTION_MOVE,from+(to-from)*i/8,true);
        previewPointer(view,time+160,MotionEvent.ACTION_POINTER_UP|(1<<MotionEvent.ACTION_POINTER_INDEX_SHIFT),to,true);previewPointer(view,time+176,MotionEvent.ACTION_UP,to,false);
    }
    @Test public void previewPinchClampsZoomAndSwitchingImagesResetsPanning()throws Exception{
        translatedPreview();main(()->{
            View view=(View)field(activity,"preview");int[] clicks={0};view.setOnClickListener(v->clicks[0]++);float width=view.getWidth();assertTrue(width>0);
            for(int i=0;i<40&&(Float)field(view,"zoom")<6;i++){float before=(Float)field(view,"zoom");zoomPreview(view,width*.6f,width*.95f);float after=(Float)field(view,"zoom");assertTrue("Every outward gesture must increase zoom until the limit",after>before);assertTrue("Zoom must stay within its upper limit",after<=6);}assertEquals(6f,(Float)field(view,"zoom"),.001f);assertEquals(0,clicks[0]);
            long now=previewGestureTime+1000;
            for(int i=0;i<3;i++){MotionEvent event=MotionEvent.obtain(now,now+i*16,i==0?MotionEvent.ACTION_DOWN:i==1?MotionEvent.ACTION_MOVE:MotionEvent.ACTION_UP,100+i*20,100+i*15,0);try{assertTrue(view.dispatchTouchEvent(event));}finally{event.recycle();}}
            assertTrue((Float)field(view,"panX")>0);assertTrue((Float)field(view,"panY")>0);assertEquals(0,clicks[0]);
            for(int i=0;i<40&&(Float)field(view,"zoom")>1;i++)zoomPreview(view,width*.95f,width*.6f);
            assertEquals(1f,(Float)field(view,"zoom"),.001f);assertEquals(0f,(Float)field(view,"panX"),.001f);assertEquals(0f,(Float)field(view,"panY"),.001f);
            long tap=previewGestureTime+1000;for(int i=0;i<2;i++){MotionEvent event=MotionEvent.obtain(tap,tap+i*16,i==0?MotionEvent.ACTION_DOWN:MotionEvent.ACTION_UP,100,100,0);try{assertTrue(view.dispatchTouchEvent(event));}finally{event.recycle();}}assertEquals(1,clicks[0]);
            zoomPreview(view,width*.6f,width*.95f);assertTrue((Float)field(view,"zoom")>1);return null;
        });click("originalTab");main(()->{Object view=field(activity,"preview");assertEquals(1f,(Float)field(view,"zoom"),.001f);assertEquals(0f,(Float)field(view,"panX"),.001f);assertSame(session.source,field(view,"bitmap"));assertFalse(session.result.bitmap.isRecycled());return null;});
    }
    @Test public void returnedCameraPhotoIsAdoptedWithItsLanguageChoices()throws Exception{
        File captures=new File(context.getFilesDir(),"captures");assertTrue(captures.isDirectory()||captures.mkdirs());File input=File.createTempFile("translation-camera-result-",".png",captures);
        Bitmap bitmap=Bitmap.createBitmap(96,128,Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);try(java.io.OutputStream out=new java.io.FileOutputStream(input)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}
        Intent result=new Intent().putStringArrayListExtra("pages",new ArrayList<>(Collections.singletonList(input.getPath()))).putExtra("capture_mode","Photo").putExtra("translation_source","en").putExtra("translation_target","en");
        monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){launched.set(intent);return new Instrumentation.ActivityResult(Activity.RESULT_OK,result);}};inst.addMonitor(monitor);
        try{
            clickAction(R.string.ui_camera);UiTestWait.until(()->session.source!=null&&!session.busy,"Returned camera photo did not finish loading");
            assertEquals(Collections.singletonList(input.getPath()),session.capturedPages);assertEquals("Photo",session.captureMode);assertEquals("en",session.sourceOverride);assertEquals("en",session.target);assertTrue(session.explicitTarget);assertTrue(session.importedSources.contains(input.getCanonicalFile()));
            assertEquals(Color.WHITE,session.source.getPixel(0,0));assertFalse(session.source.isRecycled());
            // A blank photo is valid input; OCR must report no readable text rather than lose it.
            assertTrue(session.error);assertTrue(input.isFile());
            scenario.recreate();scenario.onActivity(a->activity=a);assertSame(session,field(activity,"session"));assertEquals(1,session.capturedPages.size());assertFalse(session.source.isRecycled());
        }finally{input.delete();}
    }
    @Test public void cameraImportRequestOpensImagePickerAndCancellationKeepsThePreview()throws Exception{
        translatedPreview();Bitmap source=session.source,output=session.result.bitmap;
        monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){launched.set(new Intent(intent));if(intent.getComponent()!=null&&CameraCaptureActivity.class.getName().equals(intent.getComponent().getClassName()))return new Instrumentation.ActivityResult(Activity.RESULT_OK,new Intent().putExtra("import_pdf",true));return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);}};inst.addMonitor(monitor);
        clickAction(R.string.ui_new_translation);chooseFirst();UiTestWait.until(()->launched.get()!=null&&Intent.ACTION_OPEN_DOCUMENT.equals(launched.get().getAction()),"Camera import option did not open the picker");
        assertArrayEquals(new String[]{"image/*","application/pdf"},launched.get().getStringArrayExtra(Intent.EXTRA_MIME_TYPES));assertSame(source,session.source);assertSame(output,session.result.bitmap);assertFalse(session.busy);assertFalse(output.isRecycled());
    }
    TextView displayedText(View root,String text){if(root instanceof TextView&&root.isShown()&&text.contentEquals(((TextView)root).getText()))return (TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView match=displayedText(((ViewGroup)root).getChildAt(i),text);if(match!=null)return match;}return null;}
    void clickMore(String label){main(()->{ViewGroup root=activity.findViewById(android.R.id.content);View more=findDescription(root,context.getString(R.string.ui_more_options));assertNotNull(more);assertTrue(more.performClick());return null;});inst.waitForIdleSync();
        awaitChoices();int position=main(()->{ListView list=find(dialog(),ListView.class);for(int i=0;i<list.getCount();i++)if(UiText.label(label).equals(String.valueOf(list.getAdapter().getItem(i)))){list.setSelection(i);return i;}throw new AssertionError("Missing photo translation menu item "+label);});
        UiTestWait.until(()->{View root=visibleDialog();ListView list=root==null?null:find(root,ListView.class);View row=list==null?null:list.getChildAt(position-list.getFirstVisiblePosition());return row!=null&&row.isShown()&&row.getGlobalVisibleRect(new Rect());},"Photo translation menu item did not scroll into view");
        main(()->{ListView list=find(dialog(),ListView.class);View row=list.getChildAt(position-list.getFirstVisiblePosition());assertTrue(row.isShown());assertTrue(row.getGlobalVisibleRect(new Rect()));assertTrue(list.performItemClick(row,position,list.getAdapter().getItemId(position)));return null;});inst.waitForIdleSync();UiTestWait.until(()->{View root=visibleDialog();View button=root==null?null:root.findViewById(android.R.id.button1);return button!=null&&button.isShown();},"Photo translation result dialog did not attach");}
    View findDescription(View root,String description){if(root.isClickable()&&root.isShown()&&description.contentEquals(root.getContentDescription()==null?"":root.getContentDescription()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=findDescription(((ViewGroup)root).getChildAt(i),description);if(found!=null)return found;}return null;}
}

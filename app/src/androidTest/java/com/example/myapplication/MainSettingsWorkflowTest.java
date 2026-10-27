package com.example.myapplication;

import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.*;
import static org.junit.Assert.*;

/** Real settings and tool-search controls; restore only preferences owned by these tests. */
public class MainSettingsWorkflowTest {
    final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    final Context context=inst.getTargetContext();
    final String[] keys={"launch_camera","auto_name","prefix","name_pattern","name_counter","folder"};
    final Map<String,Object> previous=new HashMap<>();
    final List<ExecutorService> workers=new ArrayList<>();
    final List<Long> folders=new ArrayList<>();
    final List<Intent> outgoing=new CopyOnWriteArrayList<>();
    SharedPreferences prefs;
    ActivityScenario<MainActivity> scenario;
    MainActivity activity;
    Instrumentation.ActivityMonitor monitor;
    @Before public void open(){prefs=context.getSharedPreferences("scan_defaults",0);for(String key:keys)if(prefs.contains(key))previous.put(key,prefs.getAll().get(key));prefs.edit().putBoolean("launch_camera",false).commit();scenario=ActivityScenario.launch(MainActivity.class);scenario.onActivity(a->{activity=a;remember();});}
    @After public void close()throws Exception{
        if(monitor!=null)inst.removeMonitor(monitor);if(scenario!=null)scenario.close();for(ExecutorService worker:workers)assertTrue(worker.awaitTermination(15,TimeUnit.SECONDS));
        SharedPreferences.Editor edit=prefs.edit();for(String key:keys){Object value=previous.get(key);if(value==null)edit.remove(key);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else edit.putString(key,(String)value);}edit.commit();
        try(DocumentStore store=new DocumentStore(context)){for(int i=folders.size()-1;i>=0;i--)store.db().delete("folders","id=?",new String[]{Long.toString(folders.get(i))});}
    }
    Object field(Object owner,String key){try{java.lang.reflect.Field f=owner.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(owner);}catch(Exception error){throw new AssertionError(error);}}
    void remember(){for(String name:new String[]{"pdfWorker","libraryWorker","thumbnailWorker"}){ExecutorService worker=(ExecutorService)field(activity,name);if(!workers.contains(worker))workers.add(worker);}ExecutorService word=((MainActivity.WordState)field(activity,"wordState")).worker;if(!workers.contains(word))workers.add(word);}
    <T>T main(Callable<T> action){scenario.onActivity(a->{activity=a;remember();});AtomicReference<T> result=new AtomicReference<>();inst.runOnMainSync(()->{try{result.set(action.call());}catch(Exception error){throw new AssertionError(error);}});return result.get();}
    View tagged(View root,String tag){if(tag.equals(root.getTag()))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=tagged(((ViewGroup)root).getChildAt(i),tag);if(found!=null)return found;}return null;}
    View action(View root,String text){if(root.isClickable()&&root.isShown()&&(text.contentEquals(root.getContentDescription()==null?"":root.getContentDescription())||root instanceof TextView&&text.contentEquals(((TextView)root).getText())))return root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){View found=action(((ViewGroup)root).getChildAt(i),text);if(found!=null)return found;}return null;}
    <T extends View>T find(View root,Class<T> type){if(type.isInstance(root))return type.cast(root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){T found=find(((ViewGroup)root).getChildAt(i),type);if(found!=null)return found;}return null;}
    void tab(String tag){main(()->{View view=tagged(activity.getWindow().getDecorView(),tag);assertNotNull(view);assertTrue(view.performClick());return null;});inst.waitForIdleSync();}
    void click(int resource){main(()->{View view=action(activity.getWindow().getDecorView(),UiText.get(resource));assertNotNull("Missing settings control "+resource,view);assertTrue(view.performClick());return null;});inst.waitForIdleSync();}
    View focused(){List<View> roots=android.view.inspector.WindowInspector.getGlobalWindowViews();for(int i=roots.size()-1;i>=0;i--){View root=roots.get(i);if(root!=activity.getWindow().getDecorView()&&root.isShown()&&root.hasWindowFocus())return root;}return null;}
    void awaitDialog(){UiTestWait.until(()->focused()!=null,"Settings dialog did not receive focus");}
    void button(int id){UiTestWait.until(()->{View root=focused();View button=root==null?null:root.findViewById(id);return button!=null&&button.isShown()&&button.isEnabled();},"Settings dialog button did not attach");main(()->{assertTrue(focused().findViewById(id).performClick());return null;});inst.waitForIdleSync();}
    void choose(String label){UiTestWait.until(()->{View root=focused();ListView list=root==null?null:find(root,ListView.class);if(list==null||list.getChildCount()==0)return false;for(int i=0;i<list.getCount();i++)if(label.equals(String.valueOf(list.getAdapter().getItem(i))))return true;return false;},"Settings choice did not attach: "+label);int position=main(()->{ListView list=find(focused(),ListView.class);for(int i=0;i<list.getCount();i++)if(label.equals(String.valueOf(list.getAdapter().getItem(i)))){list.setSelection(i);return i;}throw new AssertionError("Missing settings choice "+label);});UiTestWait.until(()->{View root=focused();ListView list=root==null?null:find(root,ListView.class);View row=list==null?null:list.getChildAt(position-list.getFirstVisiblePosition());return row!=null&&row.isShown()&&row.getGlobalVisibleRect(new android.graphics.Rect());},"Settings choice did not scroll into view");main(()->{ListView list=find(focused(),ListView.class);assertTrue(list.performItemClick(list.getChildAt(position-list.getFirstVisiblePosition()),position,list.getAdapter().getItemId(position)));return null;});inst.waitForIdleSync();}
    void preference(int resource){tab("Settings");click(R.string.ui_scan_and_export_preferences);choose(UiText.get(resource));awaitDialog();if(resource==R.string.ui_new_document_naming)UiTestWait.until(()->{View root=focused();return root!=null&&find(root,EditText.class)!=null;},"Document naming fields did not attach");}
    void edits(View root,List<EditText> fields){if(root instanceof EditText)fields.add((EditText)root);if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)edits(((ViewGroup)root).getChildAt(i),fields);}
    void intercept(){monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){if(new ComponentName(context,ToolWorkflowActivity.class).equals(intent.getComponent())||new ComponentName(context,LocalTranslationActivity.class).equals(intent.getComponent())){outgoing.add(intent);return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);}return null;}};inst.addMonitor(monitor);}
    void search(String label){tab("Tools");click(R.string.ui_search_tools);awaitDialog();main(()->{EditText query=find(focused(),EditText.class);assertNotNull(query);query.setText(label);return null;});}
    void awaitCount(int count){UiTestWait.until(()->{View root=focused();ListView list=root==null?null:find(root,ListView.class);return list!=null&&list.getAdapter().getCount()==count&&(count==0||list.getChildCount()>0);},"Tool search did not finish filtering");}
    @Test public void toolSearchFindsTheOcrPdfWorkflow(){searchTool("OCR PDF",ToolWorkflowActivity.class);}
    @Test public void toolSearchFindsThePhotoTranslationScreen(){searchTool("Photo Translation",LocalTranslationActivity.class);}
    void searchTool(String label,Class<?> destination){intercept();String value=UiText.label(label);search(value);awaitCount(1);choose(value);assertEquals(1,outgoing.size());assertEquals(new ComponentName(context,destination),outgoing.get(0).getComponent());if(destination==ToolWorkflowActivity.class)assertEquals(label,outgoing.get(0).getStringExtra("operation"));assertEquals("Tools",main(()->field(activity,"section")));}
    @Test public void emptyToolSearchCanBeClearedWithoutLaunchingAnything(){
        intercept();search("no-such-tool-"+UUID.randomUUID());awaitCount(0);main(()->{find(focused(),EditText.class).setText("");return null;});UiTestWait.until(()->{View root=focused();ListView list=root==null?null:find(root,ListView.class);return list!=null&&list.getCount()>10;},"Clearing tool search did not restore the list");assertTrue(outgoing.isEmpty());button(android.R.id.button2);assertEquals("Tools",main(()->field(activity,"section")));
    }
    @Test public void openSourceNoticesAreSelectableAndMatchTheBundledFile()throws Exception{
        tab("Settings");click(R.string.ui_open_source_licenses);awaitDialog();String expected;try(InputStream in=context.getAssets().open("licenses/THIRD_PARTY_NOTICES.md");ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);expected=out.toString("UTF-8");}final String notices=expected;main(()->{TextView text=find(find(focused(),ScrollView.class),TextView.class);assertNotNull(text);assertTrue(text.isTextSelectable());assertEquals(notices,text.getText().toString());return null;});button(android.R.id.button1);assertEquals("Settings",main(()->field(activity,"section")));
    }
    @Test public void documentNamingRejectsInvalidPatternsAndPersistsValidPlaceholders(){
        prefs.edit().putBoolean("auto_name",true).putLong("name_counter",0).commit();preference(R.string.ui_new_document_naming);main(()->{List<EditText> fields=new ArrayList<>();edits(focused(),fields);assertEquals(2,fields.size());fields.get(1).setText("{unknown}");return null;});button(android.R.id.button1);main(()->{List<EditText> fields=new ArrayList<>();edits(focused(),fields);assertNotNull(fields.get(1).getError());fields.get(0).setText("  Receipts  ");fields.get(1).setText("{prefix}-{n}");return null;});button(android.R.id.button1);assertEquals("Receipts",prefs.getString("prefix",""));assertEquals("{prefix}-{n}",prefs.getString("name_pattern",""));assertEquals("Receipts-0001",ScanNaming.next(context));assertEquals("Receipts-0002",ScanNaming.next(context));
    }
    @Test public void cancellingNamingEditsPreservesTheExistingPreferences(){
        prefs.edit().putString("prefix","Keep prefix").putString("name_pattern","{prefix}-{n}").commit();preference(R.string.ui_new_document_naming);main(()->{List<EditText> fields=new ArrayList<>();edits(focused(),fields);fields.get(0).setText("Unsaved");fields.get(1).setText("Discarded");return null;});button(android.R.id.button2);assertEquals("Keep prefix",prefs.getString("prefix",""));assertEquals("{prefix}-{n}",prefs.getString("name_pattern",""));
    }
    @Test public void manualNamingDoesNotAdvanceTheAutomaticCounter(){
        prefs.edit().putBoolean("auto_name",true).putLong("name_counter",42).commit();preference(R.string.ui_new_document_naming);main(()->{CheckBox auto=find(focused(),CheckBox.class);assertNotNull(auto);auto.performClick();assertFalse(auto.isChecked());List<EditText> fields=new ArrayList<>();edits(focused(),fields);fields.get(1).setText("");return null;});button(android.R.id.button1);assertFalse(prefs.getBoolean("auto_name",true));assertEquals("Untitled",ScanNaming.next(context));assertEquals(42,prefs.getLong("name_counter",0));
    }
    @Test public void defaultLibraryFolderSupportsNestedFoldersAndReturningToTheRoot(){
        String parent="settings-"+UUID.randomUUID(),child="nested-"+UUID.randomUUID();long id;
        try(DocumentStore store=new DocumentStore(context)){ContentValues values=new ContentValues();values.put("name",parent);values.put("parent",0);long first=store.db().insertOrThrow("folders",null,values);folders.add(first);values.put("name",child);values.put("parent",first);id=store.db().insertOrThrow("folders",null,values);folders.add(id);}
        preference(R.string.ui_default_library_folder);choose(parent+" / "+child);assertEquals(id,prefs.getLong("folder",0));scenario.recreate();scenario.onActivity(a->{activity=a;remember();});assertEquals(id,prefs.getLong("folder",0));preference(R.string.ui_default_library_folder);choose(UiText.get(R.string.ui_all_documents));assertEquals(0,prefs.getLong("folder",-1));
    }
}

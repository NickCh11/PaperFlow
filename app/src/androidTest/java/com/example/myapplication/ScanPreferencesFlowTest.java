package com.example.myapplication;

import android.content.*;
import android.content.pm.PackageManager;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

/** Exercises the settings dialogs and their persisted values, including cancel. */
public class ScanPreferencesFlowTest {
    private SharedPreferences prefs;
    private Map<String,?> original;
    @Before public void prepare() {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        prefs=context.getSharedPreferences("scan_defaults",0);original=new HashMap<>(prefs.getAll());
        assertTrue(prefs.edit().clear().putBoolean("launch_camera",false).commit());
    }
    @After public void restore() {
        SharedPreferences.Editor edit=prefs.edit().clear();
        for(Map.Entry<String,?> entry:original.entrySet()) {
            String key=entry.getKey();Object value=entry.getValue();
            if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
            else if(value instanceof Integer)edit.putInt(key,(Integer)value);
            else if(value instanceof Long)edit.putLong(key,(Long)value);
            else if(value instanceof Float)edit.putFloat(key,(Float)value);
            else if(value instanceof String)edit.putString(key,(String)value);
            else if(value instanceof Set){@SuppressWarnings("unchecked") Set<String> strings=(Set<String>)value;edit.putStringSet(key,new HashSet<>(strings));}
            else throw new AssertionError("Unsupported saved preference: "+key);
        }
        assertTrue(edit.commit());
    }
    private void open(MainActivity activity,int setting) {
        try { java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod("scanPreferences");method.setAccessible(true);method.invoke(activity); }
        catch(Exception error){throw new AssertionError(error);}
        choose(setting);
    }
    private <T extends View> T find(View view,Class<T> type) {
        if(type.isInstance(view)&&view.isShown())return type.cast(view);
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            T result=find(((ViewGroup)view).getChildAt(i),type);if(result!=null)return result;
        }
        return null;
    }
    private View dialog() {
        for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews())
            if(root.isShown()&&(root.findViewById(android.R.id.button1)!=null||find(root,ListView.class)!=null))return root;
        throw new AssertionError("Settings dialog is missing");
    }
    private ListView choices() { ListView list=find(dialog(),ListView.class);assertNotNull("Settings choices must be visible alongside the explanation",list);return list; }
    private void choose(int index) { ListView list=choices();assertTrue(index<list.getAdapter().getCount());assertTrue(list.performItemClick(null,index,list.getAdapter().getItemId(index))); }
    private void button(int id) { Button button=dialog().findViewById(id);assertNotNull(button);assertTrue(button.performClick()); }

    @Test public void captureFilterPaperDewarpAndLaunchChoicesPersistAcrossRecreation() {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a->{open(a,0);choose(3);});
            scenario.onActivity(a->{assertEquals("Book",prefs.getString("mode",""));open(a,1);choose(0);});
            scenario.onActivity(a->{assertEquals(0,prefs.getInt("filter",-1));open(a,2);choose(2);});
            scenario.onActivity(a->{assertEquals(2,prefs.getInt("paper",-1));open(a,5);choose(2);});
            scenario.onActivity(a->{assertEquals(2,prefs.getInt("dewarp",-1));open(a,5);assertEquals(2,choices().getCheckedItemPosition());choose(0);});
            scenario.onActivity(a->{open(a,8);choose(1);});
            scenario.onActivity(a->{assertTrue(prefs.getBoolean("launch_camera",false));open(a,8);assertEquals(1,choices().getCheckedItemPosition());choose(0);});
            scenario.recreate();
            scenario.onActivity(a->{assertEquals("Book",prefs.getString("mode",""));assertEquals(0,prefs.getInt("filter",-1));assertEquals(2,prefs.getInt("paper",-1));assertEquals(0,prefs.getInt("dewarp",-1));assertFalse(prefs.getBoolean("launch_camera",true));});
        }
    }
    private boolean visibleExplanation(View view,String text) {
        if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return view.isShown()&&view.getGlobalVisibleRect(new android.graphics.Rect());
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(visibleExplanation(((ViewGroup)view).getChildAt(i),text))return true;
        return false;
    }
    private void awaitChoiceRows(int explanation) {
        UiTestWait.until(()->{ListView list=choices();return list.isLaidOut()&&list.getHeight()>0&&list.getChildCount()==list.getAdapter().getCount()&&visibleExplanation(dialog(),UiText.get(explanation));},"Preference checkbox rows were not laid out");
    }
    @Test public void galleryChoicesRemainVisibleAndCancelKeepsTheSavedSelection() {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a->open(a,7));awaitChoiceRows(R.string.ui_photos_are_saved_in_pictures_paperflow_after_a_successful_scan_export_galle);
            scenario.onActivity(a->{assertEquals(2,choices().getCount());choose(0);choose(1);button(android.R.id.button1);});
            scenario.onActivity(a->{assertTrue("Saved originals",prefs.getBoolean("gallery_originals",false));assertTrue("Saved processed",prefs.getBoolean("gallery_processed",false));open(a,7);});
            awaitChoiceRows(R.string.ui_photos_are_saved_in_pictures_paperflow_after_a_successful_scan_export_galle);
            scenario.onActivity(a->{assertTrue("Reopened originals",choices().isItemChecked(0));assertTrue("Reopened processed",choices().isItemChecked(1));choose(0);button(android.R.id.button2);});
            scenario.onActivity(a->{assertTrue("Cancel kept originals",prefs.getBoolean("gallery_originals",false));assertTrue("Cancel kept processed",prefs.getBoolean("gallery_processed",false));open(a,7);});
            awaitChoiceRows(R.string.ui_photos_are_saved_in_pictures_paperflow_after_a_successful_scan_export_galle);
            scenario.onActivity(a->{choose(0);choose(1);button(android.R.id.button1);});
            scenario.onActivity(a->{assertFalse(prefs.getBoolean("gallery_originals",true));assertFalse(prefs.getBoolean("gallery_processed",true));});
        }
    }
    @Test public void cleanupChoicesRemainVisibleAndOnlyApplyPersistsChanges() {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a->open(a,6));awaitChoiceRows(R.string.ui_review_the_preview_edge_detection_can_confuse_colored_illustrations_with_fi);
            scenario.onActivity(a->{assertEquals(2,choices().getCount());choose(0);choose(1);button(android.R.id.button1);});
            scenario.onActivity(a->{assertTrue("Saved edge cleanup",prefs.getBoolean("edge_fingers",false));assertTrue("Saved moire cleanup",prefs.getBoolean("demoire",false));open(a,6);});
            awaitChoiceRows(R.string.ui_review_the_preview_edge_detection_can_confuse_colored_illustrations_with_fi);
            scenario.onActivity(a->{assertTrue("Reopened edge cleanup",choices().isItemChecked(0));assertTrue("Reopened moire cleanup",choices().isItemChecked(1));choose(0);button(android.R.id.button2);});
            scenario.onActivity(a->{assertTrue("Cancel kept edge cleanup",prefs.getBoolean("edge_fingers",false));assertTrue("Cancel kept moire cleanup",prefs.getBoolean("demoire",false));open(a,6);});
            awaitChoiceRows(R.string.ui_review_the_preview_edge_detection_can_confuse_colored_illustrations_with_fi);
            scenario.onActivity(a->{choose(0);choose(1);button(android.R.id.button1);});
            scenario.onActivity(a->{assertFalse(prefs.getBoolean("edge_fingers",true));assertFalse(prefs.getBoolean("demoire",true));});
        }
    }
    @Test public void deniedGalleryPermissionDisablesCopiesButOtherResultsLeaveThemAlone() {
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)) {
            scenario.onActivity(a->{
                prefs.edit().putBoolean("gallery_originals",true).putBoolean("gallery_processed",true).commit();
                a.onRequestPermissionsResult(730,new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},new int[]{PackageManager.PERMISSION_DENIED});
                assertTrue("Saved gallery_originals",prefs.getBoolean("gallery_originals",false));
                a.onRequestPermissionsResult(731,new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},new int[]{PackageManager.PERMISSION_GRANTED});
                assertTrue("Saved gallery_processed",prefs.getBoolean("gallery_processed",false));
                a.onRequestPermissionsResult(731,new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},new int[]{PackageManager.PERMISSION_DENIED});
                assertFalse(prefs.getBoolean("gallery_originals",true));assertFalse(prefs.getBoolean("gallery_processed",true));
                prefs.edit().putBoolean("gallery_originals",true).putBoolean("gallery_processed",true).commit();
                a.onRequestPermissionsResult(731,new String[]{},new int[]{});
                assertFalse(prefs.getBoolean("gallery_originals",true));assertFalse(prefs.getBoolean("gallery_processed",true));
            });
        }
    }
}

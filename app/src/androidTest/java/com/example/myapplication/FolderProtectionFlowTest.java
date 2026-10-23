package com.example.myapplication;

import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Method;
import java.util.*;
import static org.junit.Assert.*;

/** Enter protection through the folder menu, rather than calling PIN setup directly. */
@RunWith(AndroidJUnit4.class)
public class FolderProtectionFlowTest {
    final android.app.Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    void ui(Runnable action){if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())action.run();else inst.runOnMainSync(action);}
    View text(View view,String text,boolean partial){
        if(view.isShown()&&view instanceof TextView&&(partial?((TextView)view).getText().toString().contains(text):text.contentEquals(((TextView)view).getText())))return view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=text(group.getChildAt(i),text,partial);if(found!=null)return found;}}return null;
    }
    View text(String value){List<View> roots=android.view.inspector.WindowInspector.getGlobalWindowViews();for(int i=roots.size()-1;i>=0;i--){View found=text(roots.get(i),value,false);if(found!=null)return found;}return null;}
    void tap(String value){ui(()->{View view=text(value);assertNotNull("Missing control: "+value,view);View item=view;while(item.getParent() instanceof View){if(item.getParent() instanceof AdapterView){AdapterView<?> list=(AdapterView<?>)item.getParent();int position=list.getPositionForView(item);assertTrue(list.performItemClick(item,position,list.getItemIdAtPosition(position)));return;}item=(View)item.getParent();}assertTrue(view.performClick());});inst.waitForIdleSync();}
    void waitText(String value){UiTestWait.until(()->{boolean[] visible={false};ui(()->visible[0]=text(value)!=null);return visible[0];},"Missing dialog: "+value);}
    void enterPins(String... values){ui(()->{ArrayList<EditText> edits=new ArrayList<>();for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews())edits(root,edits);assertEquals(values.length,edits.size());for(int i=0;i<values.length;i++)edits.get(i).setText(values[i]);});}
    void edits(View view,List<EditText> out){if(view.isShown()&&view instanceof EditText)out.add((EditText)view);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)edits(group.getChildAt(i),out);}}
    @Test public void biometricErrorsOfferPinAndCancellationStaysCancelled(){
        Context context=inst.getTargetContext();java.util.concurrent.atomic.AtomicBoolean unlocked=new java.util.concurrent.atomic.AtomicBoolean();
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(new Intent(context,MainActivity.class))){
            for(int error:new int[]{androidx.biometric.BiometricPrompt.ERROR_HW_UNAVAILABLE,androidx.biometric.BiometricPrompt.ERROR_LOCKOUT,androidx.biometric.BiometricPrompt.ERROR_NEGATIVE_BUTTON}){
                scenario.onActivity(activity->FolderLock.authenticationError(activity,Long.MAX_VALUE,()->unlocked.set(true),error));
                waitText(context.getString(R.string.ui_folder_pin));tap(context.getString(R.string.ui_cancel));
            }
            for(int error:new int[]{androidx.biometric.BiometricPrompt.ERROR_USER_CANCELED,androidx.biometric.BiometricPrompt.ERROR_CANCELED})scenario.onActivity(activity->FolderLock.authenticationError(activity,Long.MAX_VALUE,()->unlocked.set(true),error));
            inst.waitForIdleSync();ui(()->assertNull(text(context.getString(R.string.ui_folder_pin))));assertFalse(unlocked.get());
        }
    }
    @Test public void folderMenuOpensPinSetupAndProtectionSettings() throws Exception {
        Context context=inst.getTargetContext();String name="QA protection menu "+System.nanoTime();long folder;
        try(DocumentStore store=new DocumentStore(context)){ContentValues values=new ContentValues();values.put("name",name);folder=store.db().insertOrThrow("folders",null,values);}
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(new Intent(context,MainActivity.class))){
            scenario.onActivity(activity->{try{Method method=MainActivity.class.getDeclaredMethod("renderAllDocuments");method.setAccessible(true);method.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}});
            scenario.onActivity(activity->assertTrue(text(activity.findViewById(android.R.id.content),name,true).performLongClick()));
            tap(context.getString(R.string.ui_folder_protection));waitText(context.getString(R.string.ui_protect_folder));enterPins("735291","735291");tap(context.getString(R.string.ui_save));
            UiTestWait.until(()->FolderLock.protectedFolder(context,folder),"PIN setup was not saved");
            UiTestWait.until(()->{boolean[] gone={false};ui(()->gone[0]=text(context.getString(R.string.ui_protect_folder))==null);return gone[0];},"PIN setup did not close");
            scenario.onActivity(activity->assertTrue(text(activity.findViewById(android.R.id.content),name,true).performLongClick()));
            tap(context.getString(R.string.ui_folder_protection));waitText(context.getString(R.string.ui_folder_pin));enterPins("735291");tap(context.getString(R.string.ui_unlock));
            waitText(context.getString(R.string.ui_remove_protection));tap(context.getString(R.string.ui_remove_protection));assertFalse(FolderLock.protectedFolder(context,folder));
        }finally{
            context.getSharedPreferences("folder_locks",0).edit().remove("pin_"+folder).remove("bio_"+folder).remove("fails_"+folder).remove("wait_"+folder).commit();ui(FolderLock::lockAll);
            try(DocumentStore store=new DocumentStore(context)){store.db().delete("folders","id=?",new String[]{Long.toString(folder)});}
        }
    }
}

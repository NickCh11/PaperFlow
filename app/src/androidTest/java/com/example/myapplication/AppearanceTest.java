package com.example.myapplication;

import android.app.UiModeManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.ColorUtils;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class AppearanceTest {
    private Context context;
    private int previous,systemMode;
    private boolean launchCamera;
    @Before public void before(){
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();previous=AppTheme.selected();
        systemMode=context.getSystemService(UiModeManager.class).getNightMode();
        launchCamera=context.getSharedPreferences("scan_defaults",0).getBoolean("launch_camera",false);
        context.getSharedPreferences("scan_defaults",0).edit().putBoolean("launch_camera",false).commit();
    }
    @After public void after()throws Exception{
        setSystemMode(systemMode);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->AppTheme.select(previous));
        context.getSharedPreferences("scan_defaults",0).edit().putBoolean("launch_camera",launchCamera).commit();
    }
    @Test public void explicitAppearancePersistsAndKeepsSettingsOnRecreation(){
        try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
            scenario.onActivity(a->{findTag(a.getWindow().getDecorView(),"Settings").performClick();AppTheme.select(AppCompatDelegate.MODE_NIGHT_YES);});
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();scenario.recreate();
            scenario.onActivity(a->{assertTrue(findTag(a.getWindow().getDecorView(),"Settings").isSelected());assertTrue(AppTheme.isDark());assertEquals(Configuration.UI_MODE_NIGHT_YES,a.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK);assertNotNull(findText(a.getWindow().getDecorView(),AppTheme.summary()));assertEquals(AppCompatDelegate.MODE_NIGHT_YES,context.getSharedPreferences("appearance",0).getInt("mode",0));assertPalette(a);assertFalse(androidx.core.view.WindowCompat.getInsetsController(a.getWindow(),a.getWindow().getDecorView()).isAppearanceLightNavigationBars());AppTheme.select(AppCompatDelegate.MODE_NIGHT_NO);});
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();scenario.recreate();
            scenario.onActivity(a->{assertFalse(AppTheme.isDark());assertEquals(Configuration.UI_MODE_NIGHT_NO,a.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK);assertPalette(a);});
        }
    }
    @Test public void systemDefaultTracksActualDeviceNightMode()throws Exception{
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->AppTheme.select(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM));
        for(boolean dark:new boolean[]{true,false}){
            try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
                setSystemMode(dark?UiModeManager.MODE_NIGHT_YES:UiModeManager.MODE_NIGHT_NO);
                UiTestWait.until(()->AppTheme.isDark()==dark,"System night configuration did not update");
                InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                scenario.onActivity(a->{assertEquals(dark?Configuration.UI_MODE_NIGHT_YES:Configuration.UI_MODE_NIGHT_NO,a.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK);assertPalette(a);});
            }
        }
    }
    @Test public void navigationAndToolControlsStayInsideScreenInBothModes(){
        for(int mode:new int[]{AppCompatDelegate.MODE_NIGHT_NO,AppCompatDelegate.MODE_NIGHT_YES}){
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->AppTheme.select(mode));
            try(ActivityScenario<MainActivity> scenario=ActivityScenario.launch(MainActivity.class)){
                for(String tab:new String[]{"Home","Tools","Settings"}){
                    scenario.onActivity(a->findTag(a.getWindow().getDecorView(),tab).performClick());
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                    scenario.onActivity(a->{View root=((ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);assertBounds(root,root.getWidth());});
                }
            }
            for(Class<?> screen:new Class<?>[]{LocalTranslationActivity.class,EraseMarksActivity.class,LanguageManagerActivity.class,ToolWorkflowActivity.class}){
                try(ActivityScenario<?> scenario=ActivityScenario.launch(new Intent(context,screen).putExtra("operation","Rotate PDF"))){
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
                    scenario.onActivity(a->{View root=((ViewGroup)a.findViewById(android.R.id.content)).getChildAt(0);assertBounds(root,root.getWidth());});
                }
            }
        }
    }
    private static void assertPalette(Context a){
        assertEquals(a.getColor(R.color.paperflow_background),AppTheme.background());assertEquals(a.getColor(R.color.paperflow_ink),AppTheme.ink());
        assertTrue(ColorUtils.calculateContrast(AppTheme.ink(),AppTheme.surface())>=4.5);assertTrue(ColorUtils.calculateContrast(AppTheme.muted(),AppTheme.surface())>=4.5);assertTrue(ColorUtils.calculateContrast(AppTheme.onAccent(),AppTheme.accent())>=4.5);
    }
    private static void assertBounds(View view,int width){
        if(view.getVisibility()!=View.VISIBLE||view instanceof HorizontalScrollView)return;
        Rect visible=new Rect();if(!view.getGlobalVisibleRect(visible))return;
        int[] xy=new int[2];view.getLocationInWindow(xy);
        if(view.isClickable()||view instanceof TextView){assertTrue("Control extends past left edge: "+view,xy[0]>=0);assertTrue("Control extends past right edge: "+view,xy[0]+view.getWidth()<=width);}
        if(view instanceof TextView){TextView text=(TextView)view;if(text.getLayout()!=null&&text.getMaxLines()>1)assertTrue("Text clipped vertically: "+text.getText(),text.getLayout().getHeight()<=text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom()+2);}
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)assertBounds(((ViewGroup)view).getChildAt(i),width);
    }
    private static TextView findTag(View view,String tag){if(view instanceof TextView&&tag.equals(view.getTag()))return (TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView found=findTag(((ViewGroup)view).getChildAt(i),tag);if(found!=null)return found;}return null;}
    private static TextView findText(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView found=findText(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}return null;}
    private void setSystemMode(int mode)throws Exception{
        String command="cmd uimode night "+(mode==UiModeManager.MODE_NIGHT_YES?"yes":mode==UiModeManager.MODE_NIGHT_NO?"no":"auto");
        shell(command);
        // Some API 29 Google APIs images lock night mode and do not give shell
        // MODIFY_DAY_NIGHT_MODE. These CI images allow su; production needs no permission.
        if(context.getSystemService(UiModeManager.class).getNightMode()!=mode)shell("su 0 "+command);
        assertEquals("The emulator must actually accept the system night-mode change",mode,context.getSystemService(UiModeManager.class).getNightMode());
    }
    private static void shell(String command)throws Exception{try(ParcelFileDescriptor descriptor=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);java.io.InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(descriptor)){byte[] buffer=new byte[1024];while(input.read(buffer)!=-1){}}}
}

package com.example.myapplication;

import android.app.LocaleManager;
import android.content.Context;
import android.os.Build;
import android.os.LocaleList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import org.junit.runner.RunWith;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.Assert.*;

/** These tests automatically exercise every locale registered by a future translation PR. */
@RunWith(AndroidJUnit4.class)
public class AppLanguageTest {
    private Context context;
    private ActivityScenario<MainActivity> scenario;
    private String previousLocales;
    private boolean launchCamera;

    @Before public void open() {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        previousLocales=UiText.selected().toLanguageTags();
        launchCamera=context.getSharedPreferences("scan_defaults",0).getBoolean("launch_camera",false);
        context.getSharedPreferences("scan_defaults",0).edit().putBoolean("launch_camera",false).commit();
        scenario=ActivityScenario.launch(MainActivity.class);
    }

    @After public void restore() {
        if(scenario!=null){scenario.onActivity(a->AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(previousLocales)));InstrumentationRegistry.getInstrumentation().waitForIdleSync();scenario.close();}
        context.getSharedPreferences("scan_defaults",0).edit().putBoolean("launch_camera",launchCamera).commit();
    }

    @Test public void systemDefaultMeansDeviceLanguagesRatherThanEnglish() {
        LocaleListCompat automatic=LocaleListCompat.getEmptyLocaleList();
        LocaleList greek=new LocaleList(Locale.forLanguageTag("el-GR"));
        LocaleList german=new LocaleList(Locale.forLanguageTag("de-DE"));
        assertEquals("el-GR",UiText.resolveLocales(automatic,greek).toLanguageTags());
        assertEquals("de-DE",UiText.resolveLocales(automatic,german).toLanguageTags());
        assertEquals("en",UiText.resolveLocales(LocaleListCompat.forLanguageTags("en"),greek).toLanguageTags());
        select("en");
        assertEquals("en",UiText.selected().toLanguageTags());
        select("");
        assertTrue(UiText.selected().isEmpty());
        LocaleList device=Build.VERSION.SDK_INT>=33?context.getSystemService(LocaleManager.class).getSystemLocales():android.content.res.Resources.getSystem().getConfiguration().getLocales();
        assertEquals(device.get(0),UiText.context().getResources().getConfiguration().getLocales().get(0));
    }

    @Test public void settingsPickerHasSystemDefaultAndOnlyRegisteredLanguages() {
        scenario.onActivity(activity->{settings(activity);TextView row=findText(activity.getWindow().getDecorView(),AppLanguage.summary(activity));assertNotNull("App language must be visible in Settings",row);row.performClick();});
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        if(Build.VERSION.SDK_INT>=29)UiTestWait.until(()->{for(View window:android.view.inspector.WindowInspector.getGlobalWindowViews()){TextView choice=findText(window,UiText.get(R.string.app_language_system_default));if(choice!=null&&choice.isShown())return true;}return false;},"System default must be an actual visible choice");
        assertTrue(AppLanguage.supported(context).contains("en"));
        assertFalse(AppLanguage.supported(context).contains("system"));
        if(Build.VERSION.SDK_INT>=29){pick(AppLanguage.supported(context).indexOf("en")+1,AppLanguage.supported(context).size()+1);InstrumentationRegistry.getInstrumentation().waitForIdleSync();assertEquals("en",UiText.selected().toLanguageTags());scenario.onActivity(AppLanguage::show);pick(0,AppLanguage.supported(context).size()+1);InstrumentationRegistry.getInstrumentation().waitForIdleSync();assertTrue(UiText.selected().isEmpty());}
    }

    @Test public void everyRegisteredTranslationLoadsAndKeepsNavigationIdsStable() {
        for(String tag:AppLanguage.supported(context)){
            select(tag);
            scenario.recreate();
            scenario.onActivity(activity->{
                settings(activity);
                assertEquals(Locale.forLanguageTag(tag).getLanguage(),activity.getResources().getConfiguration().getLocales().get(0).getLanguage());
                assertEquals(activity.getString(R.string.ui_settings),UiText.get(R.string.ui_settings));
                TextView settings=findTag(activity.getWindow().getDecorView(),"Settings");
                assertNotNull(settings);
                assertTrue(settings.isSelected());
                assertEquals(UiText.get(R.string.ui_settings),settings.getText().toString());
                MainActivity.ToolEntry entry=new MainActivity.ToolEntry("To Word");
                assertEquals("To Word",entry.id);
                assertEquals(UiText.label("To Word"),entry.toString());
                assertNotNull(findText(activity.getWindow().getDecorView(),AppLanguage.summary(activity)));
                assertNotNull("Appearance must use the selected app language",findText(activity.getWindow().getDecorView(),AppTheme.summary()));
            });
        }
    }

    @Test public void everyFormattedUiResourceAcceptsItsArgumentsInEveryRegisteredLocale()throws Exception {
        for(String tag:AppLanguage.supported(context)){
            select(tag);
            for(Field field:R.string.class.getFields()){
                if(!field.getName().startsWith("ui_")&&!field.getName().startsWith("app_language_"))continue;
                String value=UiText.get(field.getInt(null));
                java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("%(\\d+)\\$s").matcher(value);
                int count=0;while(matcher.find())count=Math.max(count,Integer.parseInt(matcher.group(1)));
                if(count>0){Object[] args=new Object[count];Arrays.fill(args,"Example");String formatted=UiText.format(field.getInt(null),args);assertFalse(field.getName()+" left a placeholder",formatted.matches("(?s).*%[0-9]+\\$s.*"));}
            }
        }
    }

    private static void pick(int position,int count){UiTestWait.until(()->{
        android.widget.ListView found=null;
        for(View root:android.view.inspector.WindowInspector.getGlobalWindowViews()){
            android.widget.ListView list=findList(root);if(list!=null&&list.isShown())found=list;
        }
        if(found==null)return false;
        assertEquals("Picker must contain exactly system default plus registered languages",count,found.getCount());
        View row=found.getChildAt(position-found.getFirstVisiblePosition());
        if(row==null){found.setSelection(position);return false;}
        return found.performItemClick(row,position,found.getAdapter().getItemId(position));
    },"Visible language choice list did not become ready");}
    private static android.widget.ListView findList(View root){if(root instanceof android.widget.ListView)return (android.widget.ListView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){android.widget.ListView found=findList(((ViewGroup)root).getChildAt(i));if(found!=null)return found;}return null;}
    private void select(String tag){scenario.onActivity(a->AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag)));InstrumentationRegistry.getInstrumentation().waitForIdleSync();}
    private static void settings(MainActivity activity){try{Method method=MainActivity.class.getDeclaredMethod("renderSettings");method.setAccessible(true);method.invoke(activity);}catch(Exception e){throw new AssertionError(e);}}
    private static TextView findText(View root,String value){if(root instanceof TextView&&((TextView)root).getText().toString().equals(value))return (TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView found=findText(((ViewGroup)root).getChildAt(i),value);if(found!=null)return found;}return null;}
    private static TextView findTag(View root,String value){if(root instanceof TextView&&value.equals(root.getTag()))return (TextView)root;if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView found=findTag(((ViewGroup)root).getChildAt(i),value);if(found!=null)return found;}return null;}
}

package com.example.myapplication;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

/** Persisted appearance preference; system mode always follows the device configuration. */
final class AppTheme {
    private static Context application;
    static void initialize(Context context) {
        application=context.getApplicationContext();
        AppCompatDelegate.setDefaultNightMode(selected());
    }
    static int selected() {
        int mode=application==null?AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM:application.getSharedPreferences("appearance",0).getInt("mode",AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        return mode==AppCompatDelegate.MODE_NIGHT_NO||mode==AppCompatDelegate.MODE_NIGHT_YES?mode:AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
    }
    static boolean isDark() {
        int mode=selected();
        return mode==AppCompatDelegate.MODE_NIGHT_YES||(mode==AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM&&((application==null?Resources.getSystem():application.getResources()).getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
    }
    static void select(int mode) {
        if(mode!=AppCompatDelegate.MODE_NIGHT_NO&&mode!=AppCompatDelegate.MODE_NIGHT_YES&&mode!=AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)throw new IllegalArgumentException("Invalid appearance mode");
        application.getSharedPreferences("appearance",0).edit().putInt("mode",mode).apply();
        AppCompatDelegate.setDefaultNightMode(mode);
    }
    static int background(){return isDark()?0xff191613:0xfffaf7f2;}
    static int surface(){return isDark()?0xff24201c:0xfffffdf9;}
    static int soft(){return isDark()?0xff322b25:0xfff1e8de;}
    static int ink(){return isDark()?0xfff5eee6:0xff30251f;}
    static int muted(){return isDark()?0xffc5b6a8:0xff746356;}
    static int border(){return isDark()?0xff51453a:0xffded1c4;}
    static int accent(){return isDark()?0xffefad87:0xff984321;}
    static int onAccent(){return isDark()?0xff3b2013:0xffffffff;}
    static void polishAction(android.widget.TextView view){
        view.setFocusable(true);view.setMinimumHeight(Math.round(48*view.getResources().getDisplayMetrics().density));
        view.setMaxLines(2);view.setEllipsize(android.text.TextUtils.TruncateAt.END);
        android.graphics.drawable.Drawable background=view.getBackground();
        view.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(isDark()?0x33ffffff:0x22000000),background,null));
    }
    static String summary(){return UiText.format(R.string.ui_appearance_summary,UiText.get(selected()==AppCompatDelegate.MODE_NIGHT_YES?R.string.ui_appearance_dark:selected()==AppCompatDelegate.MODE_NIGHT_NO?R.string.ui_appearance_light:R.string.app_language_system_default));}
    static void show(AppCompatActivity activity){
        int[] modes={AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,AppCompatDelegate.MODE_NIGHT_NO,AppCompatDelegate.MODE_NIGHT_YES};
        String[] labels={UiText.get(R.string.app_language_system_default),UiText.get(R.string.ui_appearance_light),UiText.get(R.string.ui_appearance_dark)};
        int checked=selected()==modes[1]?1:selected()==modes[2]?2:0;
        new ProtectedDialogs.Builder(activity).setTitle(UiText.get(R.string.ui_appearance_title)).setSingleChoiceItems(labels,checked,(dialog,which)->{dialog.dismiss();select(modes[which]);}).setNegativeButton(android.R.string.cancel,null).show();
    }
}

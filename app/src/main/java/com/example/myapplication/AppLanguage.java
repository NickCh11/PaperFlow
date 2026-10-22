package com.example.myapplication;

import android.content.Context;
import android.content.res.XmlResourceParser;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import java.util.*;
import org.xmlpull.v1.XmlPullParser;

final class AppLanguage {
    static List<String> supported(Context context){ArrayList<String> tags=new ArrayList<>();try(XmlResourceParser xml=context.getResources().getXml(R.xml.app_locales)){while(xml.next()!=XmlPullParser.END_DOCUMENT)if(xml.getEventType()==XmlPullParser.START_TAG&&xml.getName().equals("locale")){String tag=xml.getAttributeValue("http://schemas.android.com/apk/res/android","name");if(tag!=null&&!tags.contains(tag))tags.add(tag);}}catch(Exception error){throw new IllegalStateException("Invalid app locale configuration",error);}return tags;}
    static String summary(Context context){LocaleListCompat selected=UiText.selected();String label=selected.isEmpty()?UiText.get(R.string.app_language_system_default):nativeName(selected.get(0).toLanguageTag());return UiText.format(R.string.app_language_summary,label);}
    private static String nativeName(String tag){Locale locale=Locale.forLanguageTag(tag);return locale.getDisplayName(locale);}
    static void show(AppCompatActivity activity){List<String> tags=supported(activity);String[] labels=new String[tags.size()+1];labels[0]=UiText.get(R.string.app_language_system_default);String chosen=UiText.selected().toLanguageTags();int checked=0;for(int i=0;i<tags.size();i++){labels[i+1]=nativeName(tags.get(i));if(chosen.equalsIgnoreCase(tags.get(i)))checked=i+1;}new ProtectedDialogs.Builder(activity).setTitle(UiText.get(R.string.app_language_title)).setSingleChoiceItems(labels,checked,(dialog,which)->{dialog.dismiss();AppCompatDelegate.setApplicationLocales(which==0?LocaleListCompat.getEmptyLocaleList():LocaleListCompat.forLanguageTags(tags.get(which-1)));}).setNegativeButton(android.R.string.cancel,null).show();}
}

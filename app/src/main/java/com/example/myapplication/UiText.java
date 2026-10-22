package com.example.myapplication;

import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;
import android.view.Menu;
import android.view.MenuItem;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import java.util.*;

/** Resource access for both Activity UI and application-scoped background tasks. */
final class UiText {
    private static Context application;
    private static final Map<MenuItem,String> menuLabels=Collections.synchronizedMap(new WeakHashMap<>());
    static void initialize(Context context){application=context.getApplicationContext();}
    static LocaleListCompat selected(){if(Build.VERSION.SDK_INT>=33&&application!=null)return LocaleListCompat.wrap(application.getSystemService(LocaleManager.class).getApplicationLocales());return AppCompatDelegate.getApplicationLocales();}
    static Context context(){if(application==null)throw new IllegalStateException("UiText has not been initialized");LocaleListCompat chosen=selected();LocaleList locales;if(!chosen.isEmpty())locales=LocaleList.forLanguageTags(chosen.toLanguageTags());else if(Build.VERSION.SDK_INT>=33)locales=application.getSystemService(LocaleManager.class).getSystemLocales();else locales=Resources.getSystem().getConfiguration().getLocales();Configuration configuration=new Configuration(application.getResources().getConfiguration());configuration.setLocales(resolveLocales(chosen,locales));return application.createConfigurationContext(configuration);}
    static LocaleList resolveLocales(LocaleListCompat selected,LocaleList system){return selected.isEmpty()?system:LocaleList.forLanguageTags(selected.toLanguageTags());}
    static String get(int resource){return context().getString(resource);}
    static String format(int resource,Object... values){Context c=context();return String.format(c.getResources().getConfiguration().getLocales().get(0),c.getString(resource),values);}
    /** Only use for canonical UI identifiers, never for filenames, OCR or user-entered text. */
    static String label(String canonical){int resource=UiTextCatalog.resource(canonical);return resource==0?canonical:get(resource);}
    static String[] labels(String[] canonical){String[] result=new String[canonical.length];for(int i=0;i<result.length;i++)result[i]=label(canonical[i]);return result;}
    static MenuItem add(Menu menu,String canonical){MenuItem item=menu.add(label(canonical));menuLabels.put(item,canonical);return item;}
    static String canonical(MenuItem item){String value=menuLabels.get(item);return value==null?item.getTitle().toString():value;}
}

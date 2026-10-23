package com.example.myapplication;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.util.*;

/** The supported OCR languages and the pinned translation routes share one catalog. */
final class TranslationCatalog {
    static final class Language {
        final String code,name,ocr;
        Language(JSONObject entry)throws JSONException{code=entry.getString("code");name=entry.getString("name");ocr=code;}
        String label(){String nativeName=Locale.forLanguageTag(code.replace('_','-')).getDisplayLanguage(Locale.forLanguageTag(code.replace('_','-')));return name.equalsIgnoreCase(nativeName)?name:name+" · "+nativeName;}
    }
    static List<Language> languages(Context context)throws Exception{
        ArrayList<Language> result=new ArrayList<>();JSONArray entries=com.paperflow.translation.OfflineRuntime.get(context).languages();
        for(int i=0;i<entries.length();i++)result.add(new Language(entries.getJSONObject(i)));
        result.sort(Comparator.comparingInt((Language language)->language.code.equals("el")?0:language.code.equals("en")?1:2).thenComparing(language->language.name));
        return Collections.unmodifiableList(result);
    }
    static Language language(Context context,String code)throws Exception{
        for(Language language:languages(context))if(language.code.equals(code))return language;
        throw new IOException(UiText.get(R.string.ui_this_translation_language_is_not_available));
    }
    static List<String> route(String source,String target){
        if(source.equals(target))return Collections.emptyList();
        if(source.equals("en")||target.equals("en"))return Collections.singletonList(source+"-"+target);
        return Arrays.asList(source+"-en","en-"+target);
    }
}

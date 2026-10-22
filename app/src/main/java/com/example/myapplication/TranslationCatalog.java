package com.example.myapplication;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.util.*;

/** The supported OCR languages and the pinned translation routes share one catalog. */
final class TranslationCatalog {
    static final class Language {
        final String code,name,ocr;
        Language(JSONObject entry)throws JSONException{code=entry.getString("code");name=entry.getString("name");ocr=entry.getString("ocr");}
        String label(){String nativeName=Locale.forLanguageTag(code).getDisplayLanguage(Locale.forLanguageTag(code));return name.equalsIgnoreCase(nativeName)?name:name+" · "+nativeName;}
    }
    private static List<Language> cached;
    static synchronized List<Language> languages(Context context)throws Exception{
        if(cached==null){
            ArrayList<Language> result=new ArrayList<>();JSONArray entries=read(context,"translation-languages.json").getJSONArray("languages");
            for(int i=0;i<entries.length();i++)result.add(new Language(entries.getJSONObject(i)));
            result.sort(Comparator.comparingInt((Language language)->language.code.equals("el")?0:language.code.equals("en")?1:2).thenComparing(language->language.name));
            cached=Collections.unmodifiableList(result);
        }
        return cached;
    }
    static Language language(Context context,String code)throws Exception{
        for(Language language:languages(context))if(language.code.equals(code))return language;
        throw new IOException(UiText.get(R.string.ui_this_translation_language_is_not_available));
    }
    static JSONObject read(Context context,String name)throws Exception{
        try(InputStream in=context.getAssets().open("languages/"+name);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
            return new JSONObject(out.toString("UTF-8"));
        }
    }
    static List<String> route(String source,String target){
        if(source.equals(target))return Collections.emptyList();
        if(source.equals("en")||target.equals("en"))return Collections.singletonList(source+"-"+target);
        return Arrays.asList(source+"-en","en-"+target);
    }
}

package com.example.myapplication;

import android.content.Context;
import com.paperflow.translation.OfflineRuntime;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Core language packs contain translation directions and shared PaddleOCR resources. */
final class LanguageModels {
    interface Progress { void update(long bytes,long total); }
    static JSONArray catalog(Context context,String kind)throws Exception {
        JSONArray rows=OfflineRuntime.get(context).languages();
        if(!kind.equals("translation"))return rows;
        // Existing document translation callers still address English-pivot directions.
        JSONArray result=new JSONArray();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);String code=row.getString("code");if(code.equals("en"))continue;
            for(String direction:new String[]{"en-"+code,code+"-en"}){
                JSONObject entry=new JSONObject(row.toString());entry.put("language",code).put("code",direction);result.put(entry);
            }
        }
        return result;
    }
    static File directory(Context context,String kind){return new File(context.getFilesDir(),"offline-language-packs");}
    static String packCode(JSONObject entry){return entry.optString("language",entry.optString("code"));}
    static boolean installed(Context context,String kind,JSONObject entry){
        try {JSONArray rows=OfflineRuntime.get(context).languages();String code=packCode(entry);
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if(row.getString("code").equals(code))return kind.equals("ocr")?row.optBoolean("ocr"):row.optBoolean("installed");}
        }catch(Exception ignored){}return false;
    }
    static String title(JSONObject entry){return entry.optString("name",entry.optString("code"));}
    static void remove(Context context,String kind,JSONObject entry)throws Exception {
        String code=packCode(entry);OfflineRuntime.get(context).remove(code);
        Set<String> selected=new HashSet<>(context.getSharedPreferences("local-languages",0).getStringSet("ocr-enabled",Collections.emptySet()));selected.remove(code);
        context.getSharedPreferences("local-languages",0).edit().putStringSet("ocr-enabled",selected).apply();
    }
    static void download(Context context,String kind,JSONObject entry,BooleanSupplier cancel,Progress progress)throws Exception {
        OfflineRuntime.get(context).download(packCode(entry),cancel,progress::update);
    }
}

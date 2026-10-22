package com.example.myapplication;

import android.content.Context;
import com.playtranslate.bergamot.BergamotNative;
import org.json.*;
import java.io.*;
import java.util.*;

/** Serialized native service; no network path. Models are installed through LanguageModels. */
final class OfflineTranslator implements AutoCloseable {
    private static final Object NATIVE_LOCK=new Object();
    private long service,model;
    private final BergamotNative engine=BergamotNative.INSTANCE;
    OfflineTranslator(Context context,String direction)throws Exception {
        JSONObject entry=null;JSONArray catalog=LanguageModels.catalog(context,"translation");for(int i=0;i<catalog.length();i++)if(catalog.getJSONObject(i).getString("code").equals(direction))entry=catalog.getJSONObject(i);
        if(entry==null||!LanguageModels.installed(context,"translation",entry))throw new IOException(UiText.format(R.string.ui_download_the_value_translation_language_first, direction));
        synchronized(NATIVE_LOCK){engine.ensureLoaded();File dir=new File(LanguageModels.directory(context,"translation"),direction);String weights="",vocab="",lex="";
        JSONArray files=entry.getJSONArray("files");for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);String path=new File(dir,file.getString("path")).getAbsolutePath();switch(file.getString("role")){case "model":weights=path;break;case "vocab":vocab=path;break;case "lex":lex=path;break;}}
        service=engine.createService(0);if(service==0)throw new IOException(UiText.get(R.string.ui_could_not_start_offline_translation));
        model=engine.loadModel(weights,vocab,"",lex,entry.getInt("encoderLayers"),entry.getInt("decoderLayers"),entry.getInt("feedForwardDepth"),entry.getInt("numHeads"),"");
        if(model==0){close();throw new IOException(UiText.get(R.string.ui_could_not_load_the_downloaded_translation_model));}}
    }
    synchronized String translate(String text)throws IOException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException(UiText.get(R.string.ui_translation_cancelled));
        String result; synchronized(NATIVE_LOCK){if(service==0||model==0)throw new IOException(UiText.get(R.string.ui_translator_is_closed));result=engine.translate(service,model,text);}
        if(result==null||result.trim().isEmpty())throw new IOException(UiText.get(R.string.ui_offline_translation_failed));return result;
    }
    @Override public synchronized void close(){synchronized(NATIVE_LOCK){if(model!=0){engine.destroyModel(model);model=0;}if(service!=0){engine.destroyService(service);service=0;}}}
}

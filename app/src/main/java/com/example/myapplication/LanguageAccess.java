package com.example.myapplication;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;
import java.io.IOException;

/** Missing packs are resolved by the user in the language manager, never by a hidden download. */
final class LanguageAccess {
    static final class Missing extends IOException {
        private static final long serialVersionUID=1L;
        final String kind,code;
        Missing(String kind,String code){super(UiText.get(R.string.ui_language_pack_required));this.kind=kind;this.code=code;}
    }
    private LanguageAccess(){}
    static boolean requireOcr(Activity activity){
        if(!com.paperflow.scanengine.LocalOcr.activeLanguages(activity).isEmpty())return true;
        open(activity,"ocr","");return false;
    }
    static Missing missing(Throwable error){
        for(int depth=0;error!=null&&depth<8;depth++,error=error.getCause())if(error instanceof Missing)return (Missing)error;
        return null;
    }
    static boolean openIfMissing(Activity activity,Throwable error){
        Missing request=missing(error);if(request==null)return false;
        open(activity,request.kind,request.code);return true;
    }
    static void open(Activity activity,String kind,String code){
        if(activity.isFinishing()||activity.isDestroyed())return;
        Toast.makeText(activity,R.string.ui_language_pack_required,Toast.LENGTH_LONG).show();
        activity.startActivity(new Intent(activity,LanguageManagerActivity.class).putExtra("kind",kind).putExtra("focus_language",code));
    }
}

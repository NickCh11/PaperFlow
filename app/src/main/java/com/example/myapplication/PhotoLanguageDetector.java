package com.example.myapplication;

import android.content.Context;
import com.paperflow.translation.OfflineRuntime;

/** The upstream CLD2 detector returns an empty code for ambiguous/short input. */
final class PhotoLanguageDetector {
    static String detect(Context context,String input)throws Exception {
        String text=java.text.Normalizer.normalize(input,java.text.Normalizer.Form.NFKC).replace('\u00a0',' ');
        String detected=OfflineRuntime.get(context).detect(text);
        detected=recoverScriptEvidence(detected,text);
        if(detected.isEmpty()){
            // Arabic-script OCR can emit the Arabic shapes of Persian/Urdu kaf
            // and yeh. Retry their equivalent code points before giving up.
            String normalized=text.replace('\u0643','\u06a9').replace('\u064a','\u06cc');
            if(!normalized.equals(text))detected=OfflineRuntime.get(context).detect(normalized);
        }
        // CLD2 explicitly does not distinguish Bosnian from Croatian. Ask for
        // the source rather than silently translating a Bosnian photo as Croatian.
        return detected.equals("hr")||detected.equals("bs")?"":detected;
    }
    static String recoverScriptEvidence(String detected,String text){
        long uyghur=text.codePoints().filter(cp->cp==0x06d5||cp==0x06ad||cp==0x06c7||cp==0x06c6||cp==0x06c8||cp==0x06d0).count();
        long urdu=text.codePoints().filter(cp->cp==0x06d2).count();
        if(detected.isEmpty()&&uyghur>=3)return "ug";
        if(detected.isEmpty()&&urdu>=3)return "ur";
        if(detected.equals("tr")){
            String lower=text.toLowerCase(java.util.Locale.ROOT);
            // Azeri q and schwa are absent from the Turkish alphabet. When OCR
            // loses schwas, require additional Azeri word evidence before rerouting.
            if(lower.indexOf('\u0259')>=0)return "az";
            boolean letter=java.util.regex.Pattern.compile("[qx]").matcher(lower).find();
            boolean word=java.util.regex.Pattern.compile("(?<!\\p{L})(yaranan|deyil|üçün|ucun|haqqında|haqqinda)(?!\\p{L})").matcher(lower).find();
            if(letter&&word)return "az";
        }
        return detected;
    }
}

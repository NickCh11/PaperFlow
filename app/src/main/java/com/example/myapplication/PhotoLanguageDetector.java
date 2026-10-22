package com.example.myapplication;

import android.content.Context;
import org.json.*;
import java.text.Normalizer;
import java.util.*;

/** Small local n-gram rank classifier. A source picker remains available for short or noisy OCR. */
final class PhotoLanguageDetector {
    private static Map<String,Map<String,Integer>> profiles;
    private static synchronized Map<String,Map<String,Integer>> profiles(Context context)throws Exception{
        if(profiles==null){
            Map<String,Map<String,Integer>> result=new HashMap<>();JSONObject entries=TranslationCatalog.read(context,"detection-profiles.json").getJSONObject("profiles");
            Iterator<String> codes=entries.keys();while(codes.hasNext()){
                String code=codes.next();JSONArray grams=entries.getJSONArray(code);Map<String,Integer> ranks=new HashMap<>();
                for(int i=0;i<grams.length();i++)ranks.put(grams.getString(i),i);result.put(code,ranks);
            }
            profiles=result;
        }
        return profiles;
    }
    static String detect(Context context,String input)throws Exception{
        String text=" "+Normalizer.normalize(input,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{M}]+"," ").trim()+" ";
        if(text.length()>12000)text=text.substring(0,12000);
        Map<String,Integer> counts=new HashMap<>();
        for(int n=1;n<=3;n++)for(int start=0;start+n<=text.length();start++){
            String gram=text.substring(start,start+n);if(gram.trim().isEmpty())continue;counts.put(gram,counts.getOrDefault(gram,0)+1);
        }
        if(counts.isEmpty())return "en";
        ArrayList<String> ranked=new ArrayList<>(counts.keySet());ranked.sort(Comparator.comparingInt((String gram)->-counts.get(gram)).thenComparing(Comparator.naturalOrder()));
        int size=Math.min(300,ranked.size());String best="en";long bestDistance=Long.MAX_VALUE;
        for(TranslationCatalog.Language language:TranslationCatalog.languages(context)){
            Map<String,Integer> profile=profiles(context).get(language.code);if(profile==null)continue;long distance=0;
            for(int i=0;i<size;i++)distance+=Math.abs(i-profile.getOrDefault(ranked.get(i),1000));
            if(distance<bestDistance){bestDistance=distance;best=language.code;}
        }
        return best;
    }
}

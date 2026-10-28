package com.example.myapplication;

import android.app.Application;
import android.graphics.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

/** Exercise the photo tool's actual OCR -> language detection path. */
@RunWith(AndroidJUnit4.class)
public class PhotoLanguageDetectionTest {
    @Test public void automaticPhotoRecognitionPassesLanguageAndAmbiguousErrorsToTheSession()throws Exception {
        Application context=(Application)InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        for(String code:new String[]{"en","el"})com.paperflow.translation.OfflineRuntime.get(context).download(code,()->false,(a,b)->{});
        // These are app-boundary checks, not a language/model accuracy corpus:
        // recognized blocks must reach the session with a source language, and
        // ambiguous input must produce the app's actionable error.
        String[][] cases={
            {"en","Hello world. This is an English document.","Please read the instructions before using this product."},
            {"el","Καλημέρα κόσμε. Αυτό είναι ένα ελληνικό έγγραφο.","Η αναγνώριση κειμένου λειτουργεί χωρίς σύνδεση."},
            {"ambiguous","Hello world"},
        };
        JSONArray report=new JSONArray();int failures=0;
        try {
            for(String[] item:cases){
                Bitmap image=Bitmap.createBitmap(1900,900,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(Color.WHITE);
                Paint paint=new Paint(3);paint.setColor(Color.BLACK);paint.setTextSize(48);
                for(int i=1;i<item.length;i++)canvas.drawText(item[i],65,100+i*120,paint);
                LocalTranslationActivity.TranslationSession session=new LocalTranslationActivity.TranslationSession(context);session.source=image;
                JSONObject result=new JSONObject().put("expected",item[0]).put("input",new JSONArray(java.util.Arrays.asList(item).subList(1,item.length)));
                try{
                    LocalTranslationActivity.TranslationSession.Recognition recognized=session.readPhoto("auto");
                    assertFalse("Recognition must return blocks to the app",recognized.blocks.isEmpty());
                    result.put("actual",recognized.language).put("ocr",session.sourceText(recognized.blocks));
                    if(!item[0].equals(recognized.language))failures++;
                }catch(Exception error){
                    result.put("error",String.valueOf(error.getMessage()));
                    if(!item[0].equals("ambiguous")||!context.getString(R.string.ui_source_language_ambiguous).equals(error.getMessage()))failures++;
                }
                finally {session.worker.shutdownNow();image.recycle();}
                report.put(result);
            }
        }finally{
            File folder=new File(context.getExternalFilesDir(null),"ocr-language-audit");folder.mkdirs();
            try(FileOutputStream output=new FileOutputStream(new File(folder,"photo-detection.json"))){output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}
        }
        assertEquals(report.toString(2),0,failures);
    }

    @Test public void unknownTextNeverBecomesTheLanguageNamedNull()throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        for(String text:new String[]{"","12345","Hello world","mango"}){
            String result=PhotoLanguageDetector.detect(context,text);
            assertNotEquals("JSON null must not become a language code","null",result);
        }
        assertEquals("",PhotoLanguageDetector.detect(context,"12345"));
    }
    @Test public void textTranslationExplainsAnAmbiguousSourceInsteadOfAMissingModel()throws Exception {
        Application context=(Application)InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();TextTranslationActivity.Task task=new TextTranslationActivity.Task(context);
        try{
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{task.source="Hello world";task.from="auto";task.to="el";task.translate();});
            long end=android.os.SystemClock.elapsedRealtime()+10000;while(task.busy&&android.os.SystemClock.elapsedRealtime()<end)Thread.sleep(20);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();assertFalse(task.busy);assertTrue(task.message,task.message.contains(context.getString(R.string.ui_source_language_ambiguous)));
        }finally{task.worker.shutdownNow();}
    }
}

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
    JSONObject samples()throws Exception {
        try(InputStream input=InstrumentationRegistry.getInstrumentation().getContext().getAssets().open("language-detection-samples.json");ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] bytes=new byte[8192];int n;while((n=input.read(bytes))!=-1)output.write(bytes,0,n);
            return new JSONObject(output.toString("UTF-8"));
        }
    }
    @Test public void everyDownloadableLanguageHasDetectionGroundTruth()throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        JSONObject fixtures=samples();JSONArray languages=com.paperflow.translation.OfflineRuntime.get(context).languages();
        assertEquals("Update detection fixtures when adding or removing languages",languages.length(),fixtures.length());
        for(int i=0;i<languages.length();i++){String code=languages.getJSONObject(i).getString("code");assertTrue("Missing language test fixture: "+code,fixtures.has(code));assertTrue(fixtures.getJSONObject(code).getJSONArray("expected").length()>0);}
    }
    @Test public void detectsEveryDownloadableLanguageFromItsFullText()throws Exception {
        android.content.Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();JSONObject fixtures=samples();JSONArray report=new JSONArray();int failures=0;
        for(java.util.Iterator<String> keys=fixtures.keys();keys.hasNext();){String code=keys.next();String actual=PhotoLanguageDetector.detect(context,fixtures.getJSONObject(code).getString("text"));report.put(new JSONObject().put("language",code).put("detected",actual));if(!accepts(fixtures.getJSONObject(code),actual))failures++;}
        File folder=new File(context.getExternalFilesDir(null),"ocr-language-audit");folder.mkdirs();try(FileOutputStream output=new FileOutputStream(new File(folder,"all-language-text-detection.json"))){output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}
        assertEquals(report.toString(2),0,failures);
    }
    boolean accepts(JSONObject fixture,String actual)throws Exception {JSONArray expected=fixture.getJSONArray("expected");for(int i=0;i<expected.length();i++)if(expected.getString(i).equals(actual))return true;return false;}

    @Test public void everyDownloadableLanguageImageUsesAutomaticOcr()throws Exception {
        Application context=(Application)InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();JSONObject fixtures=samples();
        JSONArray installed=com.paperflow.translation.OfflineRuntime.get(context).languages();
        for(int i=0;i<installed.length();i++)assertTrue("Seed every OCR script with prepare_models.py --all-ocr --install: "+installed.getJSONObject(i).getString("code"),installed.getJSONObject(i).optBoolean("ocr"));
        JSONArray report=new JSONArray();int failures=0;
        for(java.util.Iterator<String> keys=fixtures.keys();keys.hasNext();){
            String code=keys.next();JSONObject fixture=fixtures.getJSONObject(code);String text=fixture.getString("text");
            Bitmap image=Bitmap.createBitmap(1600,1050,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(image);canvas.drawColor(Color.WHITE);
            android.text.TextPaint paint=new android.text.TextPaint(3);paint.setColor(Color.BLACK);paint.setTextSize(42);
            android.text.StaticLayout layout=android.text.StaticLayout.Builder.obtain(text,0,text.length(),paint,1450).setIncludePad(true).build();
            canvas.save();canvas.translate(65,65);layout.draw(canvas);canvas.restore();
            JSONObject result=new JSONObject().put("language",code);long started=android.os.SystemClock.elapsedRealtime();
            LocalTranslationActivity.TranslationSession session=new LocalTranslationActivity.TranslationSession(context);session.source=image;
            try{
                java.util.List<com.paperflow.imageprocessing.OcrTextBox> words=com.paperflow.scanengine.LocalOcr.recognize(context,image,"auto",0f);
                StringBuilder recognized=new StringBuilder();for(com.paperflow.imageprocessing.OcrTextBox word:words)recognized.append(word.getText()).append(' ');
                String actual=PhotoLanguageDetector.detect(context,recognized.toString());result.put("detected",actual).put("ocr",recognized.toString());
                if(recognized.toString().trim().isEmpty()||!accepts(fixture,actual))failures++;
            }catch(Exception error){failures++;result.put("error",String.valueOf(error.getMessage()));}
            finally{session.worker.shutdownNow();image.recycle();}
            result.put("ms",android.os.SystemClock.elapsedRealtime()-started);report.put(result);
            File folder=new File(context.getExternalFilesDir(null),"ocr-language-audit");folder.mkdirs();try(FileOutputStream output=new FileOutputStream(new File(folder,"all-language-image-detection.json"))){output.write(report.toString(2).getBytes(StandardCharsets.UTF_8));}
        }
        assertEquals(report.toString(2),0,failures);
    }
    @Test public void automaticPhotoLanguageMatchesRecognizedImage()throws Exception {
        Application context=(Application)InstrumentationRegistry.getInstrumentation().getTargetContext().getApplicationContext();
        for(String code:new String[]{"en","el"})com.paperflow.translation.OfflineRuntime.get(context).download(code,()->false,(a,b)->{});
        String[][] cases={
            {"en","Hello world. This is an English document.","Please read the instructions before using this product."},
            {"el","Καλημέρα κόσμε. Αυτό είναι ένα ελληνικό έγγραφο.","Η αναγνώριση κειμένου λειτουργεί χωρίς σύνδεση."},
            {"el","Καλημέρα κόσμε"},
            {"ambiguous","Hello world"},
            {"el","Ελληνικά και English στο ίδιο έγγραφο.","Hello world. Καλημέρα κόσμε.","Αυτό το κείμενο είναι κυρίως γραμμένο στα ελληνικά."},
            {"en","INVOICE","The total amount due must be paid within thirty days."},
            {"fr","Bonjour, ceci est un document écrit en français.","Veuillez lire les instructions avant d'utiliser ce produit."},
            {"de","Guten Tag. Dies ist ein Dokument in deutscher Sprache.","Bitte lesen Sie die Anweisungen, bevor Sie dieses Produkt verwenden."},
            {"es","Hola, este documento está escrito en español.","Lea las instrucciones antes de utilizar este producto."},
            {"ambiguous","12345 250.00 2026"},
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

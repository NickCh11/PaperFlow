package com.example.myapplication;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.SystemClock;
import android.widget.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.paperflow.scanengine.LocalOcr;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.*;
import com.tom_roush.pdfbox.rendering.PDFRenderer;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

/** Exercises production downloads, UI selection and offline PDF text, with explicit ground truth. */
@RunWith(AndroidJUnit4.class)
public class OcrLanguagesQualityTest {
    final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    Context context(){return inst.getTargetContext();}
    Set<String> selected(){return new HashSet<>(context().getSharedPreferences("local-languages",0).getStringSet("ocr-enabled",Collections.emptySet()));}
    void select(String... codes){context().getSharedPreferences("local-languages",0).edit().putStringSet("ocr-enabled",new HashSet<>(Arrays.asList(codes))).commit();}
    void restore(Set<String> codes){select(codes.toArray(new String[0]));}
    JSONObject entry(String code)throws Exception{JSONArray list=LanguageModels.catalog(context(),"ocr");for(int i=0;i<list.length();i++)if(code.equals(list.getJSONObject(i).getString("code")))return list.getJSONObject(i);throw new IOException(code);}
    Object field(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    Activity launch(){return inst.startActivitySync(new Intent(context(),LanguageManagerActivity.class).putExtra("kind","ocr").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
    LinearLayout row(Activity activity,String title)throws Exception{LinearLayout rows=(LinearLayout)field(activity,"rows");for(int i=0;i<rows.getChildCount();i++){LinearLayout row=(LinearLayout)rows.getChildAt(i);if(((CheckBox)row.getChildAt(0)).getText().toString().startsWith(title+" "))return row;}throw new AssertionError("Missing row "+title);}
    File report(String name){File dir=new File(context().getExternalFilesDir(null),"ocr-language-audit");dir.mkdirs();return new File(dir,name);}
    void json(String name,JSONObject value)throws Exception{try(FileOutputStream out=new FileOutputStream(report(name))){out.write(value.toString(2).getBytes(StandardCharsets.UTF_8));}}

    @Test public void downloadSelectPersistDeleteThroughLanguageScreen()throws Exception{
        Set<String> original=selected();JSONObject chosen=null;JSONArray catalog=LanguageModels.catalog(context(),"ocr");
        for(int i=0;i<catalog.length();i++){JSONObject candidate=catalog.getJSONObject(i);if(!LanguageModels.installed(context(),"ocr",candidate)){chosen=candidate;break;}}
        assertNotNull("Need an uninstalled pack to test a fresh download",chosen);
        final JSONObject pack=chosen;String code=pack.getString("code"),title=LanguageModels.title(pack);Activity activity=launch();
        long started=SystemClock.elapsedRealtime();
        try{
            LinearLayout initial=row(activity,title);assertFalse(((CheckBox)initial.getChildAt(0)).isEnabled());assertEquals("Download",((Button)initial.getChildAt(1)).getText().toString());
            inst.runOnMainSync(()->((Button)initial.getChildAt(1)).performClick());
            LanguageManagerActivity.DownloadSession session=(LanguageManagerActivity.DownloadSession)field(activity,"session");
            long deadline=started+90000;while(session.busy&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
            assertFalse("Download timed out",session.busy);inst.waitForIdleSync();assertTrue("Downloaded pack missing",LanguageModels.installed(context(),"ocr",pack));
            LinearLayout downloaded=row(activity,title);CheckBox checkbox=(CheckBox)downloaded.getChildAt(0);assertTrue(checkbox.isEnabled());assertFalse("Download must not enable OCR implicitly",checkbox.isChecked());
            inst.runOnMainSync(checkbox::performClick);assertTrue(selected().contains(code));inst.runOnMainSync(activity::finish);activity=launch();
            LinearLayout reopened=row(activity,title);assertTrue("Selection persists after reopening",((CheckBox)reopened.getChildAt(0)).isChecked());
            inst.runOnMainSync(()->((Button)reopened.getChildAt(1)).performClick());inst.waitForIdleSync();
            java.util.concurrent.atomic.AtomicBoolean clicked=new java.util.concurrent.atomic.AtomicBoolean();
            inst.runOnMainSync(()->{for(android.view.View root:android.view.inspector.WindowInspector.getGlobalWindowViews()){Button positive=root.findViewById(android.R.id.button1);if(positive!=null&&"Delete".contentEquals(positive.getText())){clicked.set(positive.performClick());break;}}});
            assertTrue("Delete confirmation button exists",clicked.get());
            inst.waitForIdleSync();assertFalse(LanguageModels.installed(context(),"ocr",pack));assertFalse(selected().contains(code));assertFalse(((CheckBox)row(activity,title).getChildAt(0)).isEnabled());
        }finally{
            inst.runOnMainSync(activity::finish);
            LanguageModels.remove(context(),"ocr",pack);restore(original);
        }
        json("download-ui.json",new JSONObject().put("language",code).put("bytes",pack.getLong("size")).put("downloadSelectPersist",true).put("ms",SystemClock.elapsedRealtime()-started));
    }

    @Test public void interruptedAndCorruptDownloadsNeverInstall()throws Exception{
        JSONObject pack=new JSONObject(entry("eng").toString()).put("code","qa-ocr-integrity");File target=new File(LanguageModels.directory(context(),"ocr"),"qa-ocr-integrity.traineddata");
        assertFalse(target.exists());
        try{
            try{LanguageModels.download(context(),"ocr",pack,()->true,(a,b)->{});fail("Cancelled download succeeded");}catch(InterruptedIOException expected){}
            assertFalse(target.exists());assertFalse(new File(target+".part").exists());
            pack.put("blobSha1","0000000000000000000000000000000000000000");
            try{LanguageModels.download(context(),"ocr",pack,()->false,(a,b)->{});fail("Invalid digest accepted");}catch(IOException expected){assertTrue(expected.getMessage(),expected.getMessage().contains("integrity"));}
            assertFalse(target.exists());assertFalse(new File(target+".part").exists());
            json("integrity.json",new JSONObject().put("cancelledCleanly",true).put("checksumRejected",true));
        }finally{target.delete();new File(target+".part").delete();}
    }

    static String normalized(String text){return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").trim();}
    static int distance(String[] a,String[] b){int[] prev=new int[b.length+1];for(int j=0;j<=b.length;j++)prev[j]=j;for(int i=1;i<=a.length;i++){int[] next=new int[b.length+1];next[0]=i;for(int j=1;j<=b.length;j++)next[j]=Math.min(Math.min(next[j-1]+1,prev[j]+1),prev[j-1]+(a[i-1].equals(b[j-1])?0:1));prev=next;}return prev[b.length];}
    Bitmap sample(String[] lines){Bitmap bitmap=Bitmap.createBitmap(1800,1000,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);Paint paint=new Paint(3);paint.setColor(Color.BLACK);paint.setTextSize(52);for(int i=0;i<lines.length;i++)canvas.drawText(lines[i],80,140+i*120,paint);return bitmap;}
    JSONObject raw(Bitmap bitmap)throws Exception{
        com.googlecode.tesseract.android.TessBaseAPI api=new com.googlecode.tesseract.android.TessBaseAPI();
        try{assertTrue(api.init(context().getFilesDir().getAbsolutePath(),LocalOcr.activeLanguages(context())));api.setImage(bitmap);String text=api.getUTF8Text();JSONArray words=new JSONArray();com.googlecode.tesseract.android.ResultIterator iterator=api.getResultIterator();if(iterator!=null)try{iterator.begin();do{words.put(new JSONObject().put("text",iterator.getUTF8Text(3)).put("confidence",iterator.confidence(3)));}while(iterator.next(3));}finally{iterator.delete();}return new JSONObject().put("text",text).put("words",words);}finally{api.recycle();}
    }
    @Test public void offlineGreekEnglishQualityAndSearchablePdf()throws Exception{
        // UTP may reinstall the app, clearing language files seeded with adb.
        // This test must work independently of the earlier migration tests.
        for(String code:new String[]{"ell","eng"})LanguageModels.download(context(),"ocr",entry(code),()->false,(done,total)->{});
        assertTrue("Download Greek first",LanguageModels.installed(context(),"ocr",entry("ell")));assertTrue("Download English first",LanguageModels.installed(context(),"ocr",entry("eng")));
        PDFBoxResourceLoader.init(context());Set<String> original=selected();JSONArray results=new JSONArray();
        String[][] samples={
            {"Hello world. This is a searchable document.","The quick brown fox jumps over the lazy dog.","Invoice number 12345. Total 250.00 EUR.","Download the language once and work offline."},
            {"Καλημέρα κόσμε. Αυτό είναι ένα έγγραφο.","Η αναγνώριση κειμένου λειτουργεί χωρίς σύνδεση.","Ελληνικά και αγγλικά στην ίδια εφαρμογή.","Αριθμός τιμολογίου 12345. Σύνολο 250 ευρώ."},
            {"Ελληνικά και English στο ίδιο έγγραφο.","Hello world. Καλημέρα κόσμε.","Invoice 12345. Τιμολόγιο 12345.","Αθήνα 2026. Athens 2026."}
        };
        try{for(int i=0;i<samples.length;i++){
            if(i==0)select("eng");else if(i==1)select("ell");else select("eng","ell");
            Bitmap bitmap=sample(samples[i]);try{
                String expected=String.join(" ",samples[i]);long start=SystemClock.elapsedRealtime();String actual=LocalOcr.text(context(),bitmap);long millis=SystemClock.elapsedRealtime()-start;
                String[] words=normalized(expected).split(" +");int errors=distance(words,normalized(actual).split(" +"));
                results.put(new JSONObject().put("languages",LocalOcr.activeLanguages(context())).put("expected",expected).put("actual",actual).put("wordErrors",errors).put("words",words.length).put("wordErrorRate",errors/(double)words.length).put("ms",millis).put("rawTesseract",raw(bitmap)));assertFalse(actual.isEmpty());
                if(i==2){
                    try(FileOutputStream out=new FileOutputStream(report("mixed-sample.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
                    try(PDDocument doc=new PDDocument()){
                        select();SearchablePdf.append(context(),doc,bitmap,900,500);assertTrue(new PDFTextStripper().getText(doc).trim().isEmpty());Bitmap before=new PDFRenderer(doc).renderImage(0,1);
                        select("eng","ell");SearchablePdf.addText(context(),doc,doc.getPage(0),bitmap);PDFTextStripper stripper=new PDFTextStripper();stripper.setSortByPosition(true);String extracted=stripper.getText(doc);assertEquals(normalized(actual),normalized(extracted));
                        Bitmap after=new PDFRenderer(doc).renderImage(0,1);assertTrue("Invisible text changed page appearance",before.sameAs(after));before.recycle();after.recycle();doc.save(report("Greek-English-searchable.pdf"));
                    }
                }
            }finally{bitmap.recycle();}
        }}finally{restore(original);json("quality.json",new JSONObject().put("samples",results).put("pdfTextMatchesRecognized",true).put("pdfAppearanceUnchanged",true));}
    }
}

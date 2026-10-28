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

/** Verify app-owned downloads, UI selection and searchable PDF integration. */
@RunWith(AndroidJUnit4.class)
public class OfflineLanguageIntegrationTest {
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
        // OCR scripts can already be present through another language (or the
        // all-script CI fixture). Download/Delete controls the complete core pack.
        // Exercise the smallest fresh core pack rather than depending on catalog
        // ordering (Afrikaans currently transfers about three times as much).
        for(int i=0;i<catalog.length();i++){JSONObject candidate=catalog.getJSONObject(i);if(!LanguageModels.installed(context(),"translation",candidate)&&(chosen==null||candidate.getLong("size")<chosen.getLong("size")))chosen=candidate;}
        assertNotNull("Need an uninstalled pack to test a fresh download",chosen);
        final JSONObject pack=chosen;String code=pack.getString("code"),title=LanguageModels.title(pack);
        Map<File,byte[]> fixtures=preserveOcrFixtures(code);Activity activity=launch();
        long started=SystemClock.elapsedRealtime();
        LanguageManagerActivity.DownloadSession session=(LanguageManagerActivity.DownloadSession)field(activity,"session");
        Throwable primaryFailure=null;
        try{
            LinearLayout initial=row(activity,title);assertEquals(LanguageModels.installed(context(),"ocr",pack),((CheckBox)initial.getChildAt(0)).isEnabled());assertEquals("Download",((Button)initial.getChildAt(1)).getText().toString());
            inst.runOnMainSync(()->((Button)initial.getChildAt(1)).performClick());
            long deadline=SystemClock.elapsedRealtime()+90000;while(session.busy&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
            assertFalse("Download timed out for "+code+" ("+pack.getLong("size")+" bytes); last status: "+session.status.getValue(),session.busy);inst.waitForIdleSync();assertTrue("Downloaded pack missing",LanguageModels.installed(context(),"ocr",pack));
            LinearLayout downloaded=row(activity,title);CheckBox checkbox=(CheckBox)downloaded.getChildAt(0);assertTrue(checkbox.isEnabled());assertTrue("A complete download must enable OCR automatically",checkbox.isChecked());
            assertTrue(selected().contains(code));inst.runOnMainSync(checkbox::performClick);assertFalse(selected().contains(code));inst.runOnMainSync(checkbox::performClick);assertTrue(selected().contains(code));inst.runOnMainSync(activity::finish);activity=launch();
            LinearLayout reopened=row(activity,title);assertTrue("Selection persists after reopening",((CheckBox)reopened.getChildAt(0)).isChecked());
            Bitmap screen=inst.getUiAutomation().takeScreenshot();if(screen!=null)try(FileOutputStream out=new FileOutputStream(report("language-screen.png"))){screen.compress(Bitmap.CompressFormat.PNG,100,out);}finally{screen.recycle();}
            inst.runOnMainSync(()->((Button)reopened.getChildAt(1)).performClick());inst.waitForIdleSync();
            java.util.concurrent.atomic.AtomicBoolean clicked=new java.util.concurrent.atomic.AtomicBoolean();
            inst.runOnMainSync(()->{for(android.view.View root:android.view.inspector.WindowInspector.getGlobalWindowViews()){Button positive=root.findViewById(android.R.id.button1);if(positive!=null&&positive.isShown()&&"Delete".contentEquals(positive.getText())){clicked.set(positive.performClick());break;}}});
            assertTrue("Delete confirmation button exists",clicked.get());
            inst.waitForIdleSync();assertFalse(LanguageModels.installed(context(),"translation",pack));assertFalse(selected().contains(code));
            // Other installed core packs can retain this script's shared OCR files.
            assertEquals(LanguageModels.installed(context(),"ocr",pack),((CheckBox)row(activity,title).getChildAt(0)).isEnabled());
        }catch(Exception|AssertionError failure){
            primaryFailure=failure;throw failure;
        }finally{
            try{
                // Wait for the worker to release the install lock before removal
                // or fixture restoration. Never replace a timeout with a second
                // "active download" exception from cleanup.
                stopDownload(session);
                LanguageModels.remove(context(),"ocr",pack);
                for(Map.Entry<File,byte[]> fixture:fixtures.entrySet()){java.nio.file.Files.createDirectories(fixture.getKey().toPath().getParent());java.nio.file.Files.write(fixture.getKey().toPath(),fixture.getValue());}
            }catch(Exception|AssertionError cleanupFailure){
                if(primaryFailure!=null)primaryFailure.addSuppressed(cleanupFailure);else throw cleanupFailure;
            }finally{
                inst.runOnMainSync(activity::finish);
                restore(original);
            }
        }
        json("download-ui.json",new JSONObject().put("language",code).put("bytes",pack.getLong("size")).put("downloadSelectPersist",true).put("ms",SystemClock.elapsedRealtime()-started));
    }

    private void stopDownload(LanguageManagerActivity.DownloadSession session)throws Exception{
        inst.runOnMainSync(session::cancel);
        if(session.busy)session.worker.submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);
        assertFalse("Language worker remained busy after cancellation",session.busy);
    }

    private Map<File,byte[]> preserveOcrFixtures(String language)throws Exception{
        JSONObject catalog;
        try(InputStream input=context().getAssets().open("index_v6.json");ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] buffer=new byte[16384];int count;while((count=input.read(buffer))!=-1)bytes.write(buffer,0,count);
            catalog=new JSONObject(bytes.toString("UTF-8"));
        }
        Map<File,byte[]> files=new HashMap<>();Set<String> seen=new HashSet<>();
        JSONObject ocr=catalog.getJSONObject("languages").getJSONObject(language).getJSONObject("assets").getJSONObject("ocr");
        for(Iterator<String> keys=ocr.keys();keys.hasNext();)preservePack(catalog,ocr.getString(keys.next()),seen,files);
        return files;
    }
    private void preservePack(JSONObject catalog,String id,Set<String> seen,Map<File,byte[]> files)throws Exception{
        if(!seen.add(id))return;JSONObject pack=catalog.getJSONObject("packs").getJSONObject(id);
        JSONArray assets=pack.optJSONArray("files");if(assets!=null)for(int i=0;i<assets.length();i++){
            File file=new File(LanguageModels.directory(context(),"ocr"),assets.getJSONObject(i).getString("installPath"));
            if(file.isFile())files.put(file,java.nio.file.Files.readAllBytes(file.toPath()));
        }
        JSONArray dependencies=pack.optJSONArray("dependsOn");if(dependencies!=null)for(int i=0;i<dependencies.length();i++)preservePack(catalog,dependencies.getString(i),seen,files);
    }

    @Test public void interruptedAndCorruptDownloadsNeverInstall()throws Exception{
        JSONObject pack=new JSONObject(entry("en").toString()).put("code","qa-ocr-integrity");
        try{LanguageModels.download(context(),"ocr",pack,()->true,(a,b)->{});fail("Cancelled download succeeded");}catch(InterruptedIOException expected){}
        try{LanguageModels.download(context(),"ocr",pack,()->false,(a,b)->{});fail("Unknown language accepted");}catch(IOException expected){}
        assertFalse(LanguageModels.installed(context(),"ocr",pack));
        json("integrity.json",new JSONObject().put("cancelledCleanly",true).put("unknownLanguageRejected",true));
    }

    @Test public void cancelActiveDownloadAndShareLanguageScreen()throws Exception{
        JSONObject chosen=null;JSONArray catalog=LanguageModels.catalog(context(),"ocr");
        for(int i=0;i<catalog.length();i++){JSONObject candidate=catalog.getJSONObject(i);if(!candidate.optBoolean("installed")&&candidate.getLong("size")>10_000_000){chosen=candidate;break;}}
        assertNotNull("Need a fresh shared language pack",chosen);JSONObject pack=chosen;
        boolean ocrBefore=LanguageModels.installed(context(),"ocr",pack);
        Activity activity=launch();
        try{
            LanguageManagerActivity.DownloadSession session=(LanguageManagerActivity.DownloadSession)field(activity,"session");
            inst.runOnMainSync(()->session.start("ocr",pack));
            Thread.sleep(300);
            assertTrue("Download should still be active",session.busy);
            long started=SystemClock.elapsedRealtime();inst.runOnMainSync(session::cancel);
            while(session.busy&&SystemClock.elapsedRealtime()-started<5000)Thread.sleep(50);
            assertFalse("Cancel did not stop the network operation within 5 seconds",session.busy);
            inst.waitForIdleSync();assertEquals(context().getString(R.string.ui_language_download_cancelled),session.status.getValue());
            assertEquals("Cancelled download changed shared OCR availability",ocrBefore,LanguageModels.installed(context(),"ocr",pack));
            assertFalse("Cancelled pack became usable for translation",LanguageModels.installed(context(),"translation",pack));
            File staging=new File(context().getFilesDir(),"offline-language-packs/.staging");
            assertTrue("Cancelled staging files remain",staging.list()==null||staging.list().length==0);
        }finally{inst.runOnMainSync(activity::finish);}
    }

    @Test public void queuedDownloadCanBeCancelled()throws Exception{
        com.paperflow.translation.OfflineRuntime runtime=com.paperflow.translation.OfflineRuntime.get(context());
        java.util.concurrent.locks.ReentrantLock lock=(java.util.concurrent.locks.ReentrantLock)field(runtime,"installationLock");
        java.util.concurrent.atomic.AtomicBoolean cancelled=new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        lock.lock();
        try{
            java.util.concurrent.Future<Boolean> stopped=worker.submit(()->{try{runtime.download("en",cancelled::get,(done,total)->{});return false;}catch(InterruptedIOException expected){return true;}});
            Thread.sleep(150);cancelled.set(true);
            assertTrue("Queued download ignored cancellation",stopped.get(2,java.util.concurrent.TimeUnit.SECONDS));
        }finally{lock.unlock();worker.shutdownNow();}
    }

    static String normalized(String text){return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+"," ").trim();}
    Bitmap sample(String[] lines){Bitmap bitmap=Bitmap.createBitmap(1800,1000,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.WHITE);Paint paint=new Paint(3);paint.setColor(Color.BLACK);paint.setTextSize(52);for(int i=0;i<lines.length;i++)canvas.drawText(lines[i],80,140+i*120,paint);return bitmap;}
    @Test public void offlineOcrTextBecomesSearchableWithoutChangingThePdfImage()throws Exception{
        PDFBoxResourceLoader.init(context());Set<String> original=selected();
        Bitmap bitmap=sample(new String[]{"Ελληνικά και English στο ίδιο έγγραφο.","Invoice 12345. Τιμολόγιο 12345."});
        Bitmap blank=Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888);blank.eraseColor(Color.WHITE);
        try{
            for(String code:new String[]{"el","en"})LanguageModels.download(context(),"ocr",entry(code),()->false,(done,total)->{});
            select("en","el");String actual=LocalOcr.text(context(),bitmap);assertFalse(actual.trim().isEmpty());
            try(PDDocument doc=new PDDocument()){
                select();SearchablePdf.append(context(),doc,bitmap,900,500);
                assertTrue(new PDFTextStripper().getText(doc).trim().isEmpty());
                Bitmap before=new PDFRenderer(doc).renderImage(0,1);
                try{
                    select("en","el");SearchablePdf.addText(context(),doc,doc.getPage(0),bitmap);
                    PDFTextStripper stripper=new PDFTextStripper();stripper.setSortByPosition(true);
                    assertEquals(normalized(actual),normalized(stripper.getText(doc)));
                    Bitmap after=new PDFRenderer(doc).renderImage(0,1);
                    try{assertTrue("Invisible text changed page appearance",before.sameAs(after));}finally{after.recycle();}
                    doc.save(report("Greek-English-searchable.pdf"));
                }finally{before.recycle();}
            }
            assertTrue("Blank pages must not fail OCR",LocalOcr.text(context(),blank).isEmpty());
        }finally{bitmap.recycle();blank.recycle();restore(original);}
    }
}

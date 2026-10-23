package com.paperflow.translation;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.zip.*;

/** One process-wide offline-translator session and its shared language packs. */
public final class OfflineRuntime {
    public interface Progress { void update(long bytes,long total); }
    public static final class ImageResult {
        public final Bitmap bitmap;
        public final JSONObject metadata;
        ImageResult(Bitmap bitmap,JSONObject metadata){this.bitmap=bitmap;this.metadata=metadata;}
    }
    private static OfflineRuntime instance;
    private final File root;
    private OfflineRuntime(Context context)throws Exception {
        root=new File(context.getFilesDir(),"offline-language-packs");ensureDirectory(root);
        // The new engine never interprets old Tesseract/slimt models as current packs.
        try(InputStream input=context.getAssets().open("index_v6.json")){
            RuntimeNative.open(new String(readAll(input),StandardCharsets.UTF_8),root.getAbsolutePath());
        }
        File staging=new File(root,".staging");if(staging.exists())deleteTree(staging);
        migrateLanguageSelection(context);
    }
    public static synchronized OfflineRuntime get(Context context)throws Exception {
        if(instance==null)instance=new OfflineRuntime(context.getApplicationContext());return instance;
    }
    public JSONArray languages()throws Exception{return new JSONArray(invoke("languages").toString());}
    private void migrateLanguageSelection(Context context)throws Exception {
        android.content.SharedPreferences prefs=context.getSharedPreferences("local-languages",0);
        if(prefs.getBoolean("paddle-codes-v1",false))return;
        Set<String> previous=prefs.getStringSet("ocr-enabled",Collections.emptySet()),selected=new HashSet<>();
        JSONArray rows=languages();
        for(int i=0;i<rows.length();i++){
            String code=rows.getJSONObject(i).getString("code");
            if(previous.contains(code))selected.add(code);
            try{if(previous.contains(Locale.forLanguageTag(code.replace('_','-')).getISO3Language()))selected.add(code);}catch(MissingResourceException ignored){}
        }
        if(previous.contains("chi_sim"))selected.add("zh");if(previous.contains("chi_tra"))selected.add("zh_hant");
        prefs.edit().putStringSet("ocr-enabled",selected).putBoolean("paddle-codes-v1",true).apply();
    }
    private Object invoke(String operation)throws Exception {
        return new JSONTokener(RuntimeNative.call(new JSONObject().put("operation",operation).toString())).nextValue();
    }
    private JSONObject invoke(JSONObject request)throws Exception {return new JSONObject(RuntimeNative.call(request.toString()));}
    public String translate(String source,String target,String text)throws Exception {
        return invoke(new JSONObject().put("operation","translate").put("source",source).put("target",target).put("text",text)).getString("text");
    }
    public String detect(String text)throws Exception {
        return invoke(new JSONObject().put("operation","detect").put("text",text)).optString("code","");
    }
    public JSONObject recognize(Bitmap bitmap,String source,float confidence)throws Exception {
        return image(bitmap,source,"",confidence).metadata;
    }
    public ImageResult image(Bitmap image,String source,String target,float confidence)throws Exception {
        return image(image,source,target,confidence,null);
    }
    public ImageResult image(Bitmap image,String source,String target,float confidence,JSONObject edits)throws Exception {
        // Android ARGB_8888 is native little endian RGBA, as expected by upstream.
        Bitmap rgba=image.getConfig()==Bitmap.Config.ARGB_8888?image:image.copy(Bitmap.Config.ARGB_8888,false);
        try {
            ByteBuffer input=ByteBuffer.allocateDirect(rgba.getByteCount());rgba.copyPixelsToBuffer(input);
            ByteBuffer output=ByteBuffer.allocateDirect(target.isEmpty()?0:input.capacity());
            JSONObject request=new JSONObject().put("source",source).put("target",target).put("confidence",confidence);
            if(edits!=null){if(edits.has("sourceEdits"))request.put("sourceEdits",edits.getJSONArray("sourceEdits"));if(edits.has("translationEdits"))request.put("translationEdits",edits.getJSONArray("translationEdits"));}
            JSONObject metadata=new JSONObject(RuntimeNative.image(input,rgba.getWidth(),rgba.getHeight(),request.toString(),output));
            Bitmap result=null;if(output.capacity()>0){result=Bitmap.createBitmap(rgba.getWidth(),rgba.getHeight(),Bitmap.Config.ARGB_8888);result.copyPixelsFromBuffer(output);}
            return new ImageResult(result,metadata);
        } finally {if(rgba!=image)rgba.recycle();}
    }
    public long createLive(String source,String target){return RuntimeNative.createLive(source,target);}
    public void destroyLive(long pointer){RuntimeNative.destroyLive(pointer);}
    public synchronized void remove(String language)throws Exception {
        JSONObject plan=invoke(new JSONObject().put("operation","deletePlan").put("language",language));
        deletePlan(plan);invoke("refresh");
    }
    public synchronized void download(String language,BooleanSupplier cancelled,Progress progress)throws Exception {
        checkCancelled(cancelled);
        JSONObject plan=invoke(new JSONObject().put("operation","plan").put("language",language));
        install(plan,cancelled,progress);
    }
    public synchronized void checkUpdates(BooleanSupplier cancelled,Progress progress)throws Exception {
        JSONArray before=languages();List<String> installed=new ArrayList<>();
        for(int i=0;i<before.length();i++)if(before.getJSONObject(i).optBoolean("installed"))installed.add(before.getJSONObject(i).getString("code"));
        File pending=new File(root,"index_v6.pending");
        try {
            transfer("https://offline-translator.davidv.dev/index.json",pending,false,cancelled,bytes->progress.update(bytes,0));
            String catalog=new String(Files.readAllBytes(pending.toPath()),StandardCharsets.UTF_8);
            JSONObject data=new JSONObject(catalog);if(data.getInt("formatVersion")!=6)throw new IOException("This catalog needs a newer application version");
            checkCancelled(cancelled);invoke(new JSONObject().put("operation","catalog").put("catalog",catalog));
            Files.move(pending.toPath(),new File(root,"index_v6.json").toPath(),StandardCopyOption.REPLACE_EXISTING);
            for(String code:installed){
                download(code,cancelled,progress);
                for(String kind:new String[]{"ocr","translation"})install(invoke(new JSONObject().put("operation","upgradePlan").put("language",code).put("kind",kind)),cancelled,progress);
            }
        } finally {pending.delete();}
    }
    private void install(JSONObject plan,BooleanSupplier cancelled,Progress progress)throws Exception {
        File staging=new File(new File(root,".staging"),UUID.randomUUID().toString());ensureDirectory(staging);
        List<String> files=new ArrayList<>();List<JSONObject> markers=new ArrayList<>();
        List<String> replacePaths=new ArrayList<>();
        long total=plan.getLong("size"),done=0;
        try {
            JSONArray tasks=plan.getJSONArray("tasks");
            for(int i=0;i<tasks.length();i++){
                checkCancelled(cancelled);
                JSONObject task=tasks.getJSONObject(i),install=task.getJSONObject("install");
                String path=task.getString("path"),type=install.getString("type");
                File target=resolve(staging,path);ensureDirectory(target.getParentFile());
                final long base=done,expected=task.getLong("size");
                transfer(task.getString("url"),target,type.equals("gzip"),cancelled,bytes->progress.update(base+Math.min(bytes,expected),total));
                if(expected>0&&target.length()!=expected)throw new IOException("Language package size mismatch");
                if(type.equals("zip")){
                    JSONObject marker=install.optJSONObject("marker");
                    try(ZipFile archive=new ZipFile(target)){
                        JSONArray names=new JSONArray();Enumeration<? extends ZipEntry> entries=archive.entries();
                        while(entries.hasMoreElements())names.put(entries.nextElement().getName());
                        JSONObject extraction=invoke(new JSONObject().put("operation","zipPlan").put("extractTo",install.getString("extractTo")).put("marker",marker).put("entries",names));
                        JSONArray replace=extraction.getJSONArray("replacePaths");for(int j=0;j<replace.length();j++)replacePaths.add(replace.getString(j));
                        JSONArray mapped=extraction.getJSONArray("entries");
                        for(int j=0;j<mapped.length();j++){
                            checkCancelled(cancelled);JSONObject item=mapped.getJSONObject(j);String destination=item.getString("path");
                            File file=resolve(staging,destination);
                            if(item.getBoolean("directory")){ensureDirectory(file);continue;}
                            ensureDirectory(file.getParentFile());
                            try(InputStream input=archive.getInputStream(archive.getEntry(item.getString("name")));OutputStream output=new FileOutputStream(file)){copy(input,output,cancelled,null);}
                            files.add(destination);
                        }
                    }
                    if(marker!=null)markers.add(marker);
                }
                if(!type.equals("zip")||!install.optBoolean("deleteAfterExtract"))files.add(path);
                done+=expected;progress.update(done,total);
            }
            checkCancelled(cancelled);
            // Commit only complete plans. Shared packs are reused by the native planner.
            for(String path:replacePaths){File old=resolve(root,path);if(old.exists())deleteTree(old);}
            for(String path:files){File destination=resolve(root,path);ensureDirectory(destination.getParentFile());Files.move(resolve(staging,path).toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING);}
            for(JSONObject marker:markers){
                File destination=resolve(root,marker.getString("path"));ensureDirectory(destination.getParentFile());
                File pending=new File(destination.getParentFile(),destination.getName()+".pending");
                try(FileOutputStream output=new FileOutputStream(pending)){output.write(new JSONObject().put("version",marker.getInt("version")).toString().getBytes(StandardCharsets.UTF_8));output.getFD().sync();}
                Files.move(pending.toPath(),destination.toPath(),StandardCopyOption.REPLACE_EXISTING);
            }
            deletePlan(plan.getJSONObject("deleteAfter"));invoke("refresh");
            for(String obsolete:new String[]{"tessdata","translation-models"}){
                File previous=new File(root.getParentFile(),obsolete);if(previous.exists())deleteTree(previous);
            }
        } finally {deleteTree(staging);}
    }
    private void deletePlan(JSONObject plan)throws Exception {
        for(String key:new String[]{"files","directories"}){
            JSONArray paths=plan.getJSONArray(key);for(int i=0;i<paths.length();i++){File file=resolve(root,paths.getString(i));if(file.exists())deleteTree(file);}
        }
    }
    private interface ByteProgress {void update(long bytes);}
    private static void transfer(String url,File target,boolean gzip,BooleanSupplier cancelled,ByteProgress progress)throws Exception {
        IOException last=null;
        for(int attempt=0;attempt<3;attempt++){
            checkCancelled(cancelled);HttpURLConnection connection=null;
            try {
                URL source=new URL(url);if(!source.getProtocol().equals("https"))throw new IOException("Language packs require HTTPS");
                connection=(HttpURLConnection)source.openConnection();connection.setConnectTimeout(20000);connection.setReadTimeout(30000);connection.setRequestProperty("Accept-Encoding","identity");
                int status=connection.getResponseCode();if(status!=200)throw new IOException("Language server returned HTTP "+status);
                try(InputStream raw=connection.getInputStream();InputStream input=gzip?new GZIPInputStream(raw):raw;FileOutputStream output=new FileOutputStream(target)){
                    copy(input,output,cancelled,progress);output.getFD().sync();
                }
                if(target.length()==0)throw new IOException("The language download is empty");return;
            } catch(InterruptedIOException stopped){throw stopped;}
            catch(IOException error){last=error;target.delete();}
            finally {if(connection!=null)connection.disconnect();}
        }
        throw last;
    }
    private static void copy(InputStream input,OutputStream output,BooleanSupplier cancel,ByteProgress progress)throws IOException {
        byte[] bytes=new byte[32768];int n;long done=0;
        while((n=input.read(bytes))!=-1){checkCancelled(cancel);output.write(bytes,0,n);done+=n;if(progress!=null)progress.update(done);}
    }
    private static void checkCancelled(BooleanSupplier cancel)throws InterruptedIOException {if(cancel.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException("Download cancelled");}
    private static File resolve(File base,String path)throws IOException {
        File file=new File(base,path).getCanonicalFile();String prefix=base.getCanonicalPath()+File.separator;
        if(!file.getPath().startsWith(prefix))throw new IOException("Invalid language package path");return file;
    }
    private static void ensureDirectory(File file)throws IOException {if(!file.isDirectory()&&!file.mkdirs())throw new IOException("Language storage is unavailable");}
    private static void deleteTree(File file)throws IOException {
        File[] children=file.listFiles();if(children!=null)for(File child:children)deleteTree(child);
        if(file.exists()&&!file.delete())throw new IOException("Could not remove language package file");
    }
    private static byte[] readAll(InputStream input)throws IOException {ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[8192];int n;while((n=input.read(bytes))!=-1)out.write(bytes,0,n);return out.toByteArray();}
}

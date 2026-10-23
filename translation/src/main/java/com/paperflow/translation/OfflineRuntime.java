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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
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
    private final ReentrantLock installationLock=new ReentrantLock();
    private final Map<String,String> recognizerPacks=new ConcurrentHashMap<>();
    private final Map<String,String> languageScripts=new ConcurrentHashMap<>();
    private OfflineRuntime(Context context)throws Exception {
        root=new File(context.getFilesDir(),"offline-language-packs");ensureDirectory(root);
        // The new engine never interprets old Tesseract/slimt models as current packs.
        try(InputStream input=context.getAssets().open("index_v6.json")){
            String catalog=new String(readAll(input),StandardCharsets.UTF_8);
            RuntimeNative.open(catalog,root.getAbsolutePath());
            loadLanguageMetadata(new JSONObject(catalog));
            File cached=new File(root,"index_v6.json");if(cached.isFile())try{loadLanguageMetadata(new JSONObject(new String(Files.readAllBytes(cached.toPath()),StandardCharsets.UTF_8)));}catch(Exception ignored){/* The native session independently validates its cached catalog. */}
        }
        File staging=new File(root,".staging");if(staging.exists())deleteTree(staging);
        migrateLanguageSelection(context);
    }
    private void loadLanguageMetadata(JSONObject catalog)throws Exception {
        if(catalog.getInt("formatVersion")!=6)return;
        JSONObject languages=catalog.getJSONObject("languages");
        for(Iterator<String> codes=languages.keys();codes.hasNext();){String code=codes.next();JSONObject row=languages.getJSONObject(code);languageScripts.put(code,row.getJSONObject("meta").optString("script"));JSONObject assets=row.optJSONObject("assets");JSONObject ocr=assets==null?null:assets.optJSONObject("ocr");if(ocr!=null)recognizerPacks.put(code,ocr.optString("ppocr",code));}
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
        JSONObject result=invoke(new JSONObject().put("operation","detect").put("text",text));
        return result.isNull("code")?"":result.optString("code","");
    }
    public JSONObject recognize(Bitmap bitmap,String source,float confidence)throws Exception {
        return image(bitmap,source,"",confidence).metadata;
    }
    public ImageResult image(Bitmap image,String source,String target,float confidence)throws Exception {
        return image(image,source,target,confidence,null);
    }
    public ImageResult image(Bitmap image,String source,String target,float confidence,JSONObject edits)throws Exception {
        if(source.contains("+"))return imageWithInstalledRecognizers(image,source,target,confidence,edits);
        if(source.equals("auto")&&target.isEmpty()){
            try{
                ImageResult automatic=imageOnce(image,"auto","",Math.max(50f,confidence),null);
                String text=automatic.metadata.optString("text");
                if(automatic.metadata.getJSONArray("words").length()==0&&uniformImage(image))return automatic;
                String code=detect(text);long letters=text.codePoints().filter(Character::isLetter).count();
                long matching=text.codePoints().filter(cp->Character.isLetter(cp)&&matchesRecognizerScript(code,Character.UnicodeScript.of(cp))).count();
                if(!code.isEmpty()&&letters>0&&matching*100>=letters*90)return automatic;
            }catch(IOException error){if(error.getMessage()==null||!error.getMessage().contains("could not classify script for any detected text strip"))throw error;}
            return imageWithInstalledRecognizers(image,source,target,confidence,edits);
        }
        String selection=source;
        try {return imageOnce(image,selection,target,confidence,edits);}
        catch(IOException error){
            if(!selection.equals("auto")||error.getMessage()==null||!error.getMessage().contains("could not classify script for any detected text strip"))throw error;
            return imageWithInstalledRecognizers(image,source,target,confidence,edits);
        }
    }
    private static boolean uniformImage(Bitmap image){
        int low=255,high=0;
        for(int y=0;y<64;y++)for(int x=0;x<64;x++){
            int pixel=image.getPixel(Math.min(image.getWidth()-1,x*image.getWidth()/64),Math.min(image.getHeight()-1,y*image.getHeight()/64));
            int value=(android.graphics.Color.red(pixel)*3+android.graphics.Color.green(pixel)*6+android.graphics.Color.blue(pixel))/10;
            low=Math.min(low,value);high=Math.max(high,value);if(high-low>8)return false;
        }
        return true;
    }
    private ImageResult imageWithInstalledRecognizers(Bitmap image,String source,String target,float confidence,JSONObject edits)throws Exception {
            // PULC can reject every strip on sparse/small text. Retry the installed
            // Paddle recognizers, retaining the same OCR engine and model files.
            Set<String> allowed=source.contains("+")?new HashSet<>(Arrays.asList(source.split("\\+"))):null;
            Set<String> tried=new HashSet<>();JSONArray rows=languages();
            SortedMap<Integer,JSONArray> selectedLines=new TreeMap<>();Map<Integer,Long> lineScores=new HashMap<>();
            Map<Integer,long[]> wordScores=new HashMap<>();
            ImageResult best=null;String bestCode=null;long bestScore=-1;
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.getJSONObject(i);String code=row.getString("code");
                if(!row.optBoolean("ocr")||(allowed!=null&&!allowed.contains(code)))continue;
                String pack=recognizerPacks.getOrDefault(code,code);if(!tried.add(pack))continue;
                ImageResult candidate=imageOnce(image,code,"",Math.max(50f,confidence),null);
                collectLines(candidate.metadata.getJSONArray("words"),code,selectedLines,lineScores,wordScores);
                String text=candidate.metadata.optString("text");
                String detected=text.trim().isEmpty()?"":detect(text);
                long letters=text.codePoints().filter(Character::isLetterOrDigit).count();
                // CLD2 resolves language where possible; text coverage breaks ties.
                boolean matches=code.equals(detected)||Objects.equals(pack,recognizerPacks.get(detected));
                long score=letters+(matches?1_000_000:0);
                if(score>bestScore){best=candidate;bestCode=matches&&!detected.isEmpty()?detected:code;bestScore=score;}
            }
            if(best==null)throw new IOException("No readable text found using the installed OCR languages");
            if(!target.isEmpty())return imageOnce(image,bestCode,target,confidence,edits);
            JSONArray words=new JSONArray();StringBuilder text=new StringBuilder();
            for(JSONArray line:selectedLines.values()){
                if(text.length()>0)text.append('\n');
                for(int i=0;i<line.length();i++){JSONObject word=line.getJSONObject(i);words.put(word);if(i>0)text.append(' ');text.append(word.getString("text"));}
            }
            return new ImageResult(null,new JSONObject().put("words",words).put("text",text.toString()));
    }
    private void collectLines(JSONArray words,String code,Map<Integer,JSONArray> selected,Map<Integer,Long> scores,Map<Integer,long[]> wordScores)throws Exception {
        Map<Integer,JSONArray> lines=new LinkedHashMap<>();
        for(int i=0;i<words.length();i++){JSONObject word=words.getJSONObject(i);int line=word.getInt("line");lines.computeIfAbsent(line,key->new JSONArray()).put(word);}
        String isoScript=languageScripts.getOrDefault(code,"");
        for(Map.Entry<Integer,JSONArray> entry:lines.entrySet()){
            StringBuilder text=new StringBuilder();JSONArray line=entry.getValue();
            for(int i=0;i<line.length();i++){if(i>0)text.append(' ');text.append(line.getJSONObject(i).getString("text"));}
            String value=text.toString();long letters=value.codePoints().filter(Character::isLetter).count();
            long matching=value.codePoints().filter(cp->Character.isLetter(cp)&&matchesRecognizerScript(code,Character.UnicodeScript.of(cp))).count();
            String detected=detect(value);boolean matches=code.equals(detected)||Objects.equals(recognizerPacks.get(code),recognizerPacks.get(detected));
            double meanConfidence=0;for(int i=0;i<line.length();i++)meanConfidence+=Math.max(0,Math.min(1,line.getJSONObject(i).optDouble("confidence",0)));meanConfidence/=Math.max(1,line.length());
            long score=(letters==0?0:matching*1_000_000/letters)+(long)(meanConfidence*10_000)+(matches?1_000:0)+letters;
            long[] candidates=new long[line.length()];
            for(int i=0;i<line.length();i++){
                String word=line.getJSONObject(i).getString("text");long count=word.codePoints().filter(Character::isLetter).count();
                long scriptLetters=word.codePoints().filter(cp->Character.isLetter(cp)&&matchesRecognizerScript(code,Character.UnicodeScript.of(cp))).count();
                double confidence=Math.max(0,Math.min(1,line.getJSONObject(i).optDouble("confidence",0)));
                candidates[i]=count==0?0:scriptLetters*1_000_000/count+(long)(confidence*10_000)+(matches?1_000:0);
            }
            JSONArray previous=selected.get(entry.getKey());
            if(previous!=null&&previous.length()==line.length()){
                long[] priorScores=wordScores.get(entry.getKey());
                for(int i=0;i<line.length();i++)if(candidates[i]>priorScores[i]){
                    JSONObject oldWord=previous.getJSONObject(i),newWord=line.getJSONObject(i);
                    int overlap=Math.min(oldWord.getInt("right"),newWord.getInt("right"))-Math.max(oldWord.getInt("left"),newWord.getInt("left"));
                    int width=Math.min(oldWord.getInt("right")-oldWord.getInt("left"),newWord.getInt("right")-newWord.getInt("left"));
                    if(overlap>=Math.max(1,width/2)){previous.put(i,newWord);priorScores[i]=candidates[i];}
                }
            }else if(!scores.containsKey(entry.getKey())||score>scores.get(entry.getKey())){
                scores.put(entry.getKey(),score);selected.put(entry.getKey(),line);wordScores.put(entry.getKey(),candidates);
            }
        }
    }
    private boolean matchesRecognizerScript(String code,Character.UnicodeScript script){
        String pack=recognizerPacks.get(code);
        for(Map.Entry<String,String> entry:recognizerPacks.entrySet())if(Objects.equals(pack,entry.getValue())&&matchesScript(languageScripts.getOrDefault(entry.getKey(),""),script))return true;
        return false;
    }
    private static boolean matchesScript(String iso,Character.UnicodeScript script){
        switch(iso){
            case "Latn":return script==Character.UnicodeScript.LATIN;
            case "Grek":return script==Character.UnicodeScript.GREEK;
            case "Cyrl":return script==Character.UnicodeScript.CYRILLIC;
            case "Arab":return script==Character.UnicodeScript.ARABIC;
            case "Hebr":return script==Character.UnicodeScript.HEBREW;
            case "Deva":return script==Character.UnicodeScript.DEVANAGARI;
            case "Beng":return script==Character.UnicodeScript.BENGALI;
            case "Geor":return script==Character.UnicodeScript.GEORGIAN;
            case "Gujr":return script==Character.UnicodeScript.GUJARATI;
            case "Hang":return script==Character.UnicodeScript.HANGUL;
            case "Hans":case "Hant":return script==Character.UnicodeScript.HAN;
            case "Jpan":return script==Character.UnicodeScript.HAN||script==Character.UnicodeScript.HIRAGANA||script==Character.UnicodeScript.KATAKANA;
            case "Knda":return script==Character.UnicodeScript.KANNADA;
            case "Mlym":return script==Character.UnicodeScript.MALAYALAM;
            case "Taml":return script==Character.UnicodeScript.TAMIL;
            case "Telu":return script==Character.UnicodeScript.TELUGU;
            case "Thai":return script==Character.UnicodeScript.THAI;
            default:return false;
        }
    }
    private ImageResult imageOnce(Bitmap image,String source,String target,float confidence,JSONObject edits)throws Exception {
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
    public void remove(String language)throws Exception {
        if(!installationLock.tryLock())throw new IOException("Wait for the active language download to finish or cancel it first");
        try {
            JSONObject plan=invoke(new JSONObject().put("operation","deletePlan").put("language",language));
            deletePlan(plan);invoke("refresh");
        } finally {installationLock.unlock();}
    }
    public void download(String language,BooleanSupplier cancelled,Progress progress)throws Exception {
        acquireInstall(cancelled);
        try {
            checkCancelled(cancelled);progress.update(0,0);
            JSONObject plan=invoke(new JSONObject().put("operation","plan").put("language",language));
            install(plan,cancelled,progress);
        } finally {installationLock.unlock();}
    }
    private void acquireInstall(BooleanSupplier cancelled)throws Exception {
        while(true){checkCancelled(cancelled);if(installationLock.tryLock(100,TimeUnit.MILLISECONDS))return;}
    }
    public void checkUpdates(BooleanSupplier cancelled,Progress progress)throws Exception {
        acquireInstall(cancelled);
        try {checkUpdatesLocked(cancelled,progress);} finally {installationLock.unlock();}
    }
    private void checkUpdatesLocked(BooleanSupplier cancelled,Progress progress)throws Exception {
        JSONArray before=languages();List<String> installed=new ArrayList<>();
        for(int i=0;i<before.length();i++)if(before.getJSONObject(i).optBoolean("installed"))installed.add(before.getJSONObject(i).getString("code"));
        File pending=new File(root,"index_v6.pending");
        try {
            transfer("https://offline-translator.davidv.dev/index_v6.json",pending,false,0,cancelled,bytes->progress.update(bytes,0));
            String catalog=new String(Files.readAllBytes(pending.toPath()),StandardCharsets.UTF_8);
            JSONObject data=new JSONObject(catalog);if(data.getInt("formatVersion")!=6)throw new IOException("This catalog needs a newer application version");
            checkCancelled(cancelled);invoke(new JSONObject().put("operation","catalog").put("catalog",catalog));
            Files.move(pending.toPath(),new File(root,"index_v6.json").toPath(),StandardCopyOption.REPLACE_EXISTING);
            loadLanguageMetadata(data);
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
        progress.update(0,total);
        try {
            JSONArray tasks=plan.getJSONArray("tasks");
            for(int i=0;i<tasks.length();i++){
                checkCancelled(cancelled);
                JSONObject task=tasks.getJSONObject(i),install=task.getJSONObject("install");
                String path=task.getString("path"),type=install.getString("type");
                File target=resolve(staging,path);ensureDirectory(target.getParentFile());
                final long base=done,expected=task.getLong("size");
                // Upstream sizes describe the network file, before gunzip.
                transfer(task.getString("url"),target,type.equals("gzip"),expected,cancelled,bytes->progress.update(base+Math.min(bytes,expected),total));
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
    private static void transfer(String url,File target,boolean gzip,long expected,BooleanSupplier cancelled,ByteProgress progress)throws Exception {
        IOException last=null;
        for(int attempt=0;attempt<3;attempt++){
            checkCancelled(cancelled);HttpURLConnection connection=null;
            ScheduledExecutorService watcher=null;
            AtomicBoolean aborted=new AtomicBoolean();
            try {
                URL source=new URL(url);if(!source.getProtocol().equals("https"))throw new IOException("Language packs require HTTPS");
                connection=(HttpURLConnection)source.openConnection();connection.setConnectTimeout(20000);connection.setReadTimeout(30000);connection.setRequestProperty("Accept-Encoding","identity");
                // A flag alone cannot interrupt connect(), TLS or a blocked socket read.
                final HttpURLConnection active=connection;
                watcher=Executors.newSingleThreadScheduledExecutor(r->{Thread thread=new Thread(r,"language-download-cancel");thread.setDaemon(true);return thread;});
                watcher.scheduleWithFixedDelay(()->{if(cancelled.getAsBoolean()){aborted.set(true);active.disconnect();}},0,100,TimeUnit.MILLISECONDS);
                int status=connection.getResponseCode();if(status!=200)throw new IOException("Language server returned HTTP "+status);
                try(CountingInputStream raw=new CountingInputStream(connection.getInputStream(),progress);InputStream input=gzip?new GZIPInputStream(raw):raw;FileOutputStream output=new FileOutputStream(target)){
                    copy(input,output,cancelled,null);output.getFD().sync();
                    if(expected>0&&raw.count!=expected)throw new IOException("Language package download size mismatch: "+target.getName());
                }
                checkCancelled(cancelled);
                if(target.length()==0)throw new IOException("The language download is empty");return;
            } catch(IOException error){
                target.delete();checkCancelled(cancelled);
                if(aborted.get())throw new InterruptedIOException("Download cancelled");
                last=error;
            }
            finally {if(watcher!=null)watcher.shutdownNow();if(connection!=null)connection.disconnect();}
        }
        throw last;
    }
    private static final class CountingInputStream extends FilterInputStream {
        long count,lastUpdate;final ByteProgress progress;
        CountingInputStream(InputStream input,ByteProgress progress){super(input);this.progress=progress;}
        private void advance(int n){if(n<=0)return;count+=n;long now=System.nanoTime();if(now-lastUpdate>=100_000_000L){lastUpdate=now;progress.update(count);}}
        @Override public int read()throws IOException{int value=in.read();if(value>=0)advance(1);return value;}
        @Override public int read(byte[] b,int off,int len)throws IOException{int n=in.read(b,off,len);advance(n);return n;}
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

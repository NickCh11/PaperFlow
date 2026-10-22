package com.example.myapplication;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Pinned manifests and atomic language downloads. Document content never enters this path. */
final class LanguageModels {
    interface Progress { void update(long bytes,long total); }
    static JSONArray catalog(Context c,String kind)throws Exception {
        try(InputStream in=c.getAssets().open("languages/"+kind+".json")){
            ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[8192];int n;
            while((n=in.read(bytes))!=-1)out.write(bytes,0,n);
            return new JSONObject(out.toString("UTF-8")).getJSONArray("languages");
        }
    }
    static File directory(Context c,String kind){return new File(c.getFilesDir(),kind.equals("ocr")?"tessdata":"translation-models");}
    static boolean installed(Context c,String kind,JSONObject entry){
        File dir=directory(c,kind);String code=entry.optString("code");
        if(kind.equals("ocr"))return new File(dir,code+".traineddata").length()==entry.optLong("size");
        try{File folder=new File(dir,code);File marker=new File(folder,"installed");if(!marker.isFile())return false;
            String saved=new String(java.nio.file.Files.readAllBytes(marker.toPath()),java.nio.charset.StandardCharsets.UTF_8);
            JSONObject savedEntry=new JSONObject(saved);if(!savedEntry.getJSONArray("files").toString().equals(entry.getJSONArray("files").toString()))return false;
            JSONArray files=entry.getJSONArray("files");for(int i=0;i<files.length();i++){JSONObject file=files.getJSONObject(i);if(new File(folder,file.getString("path")).length()!=file.getLong("size"))return false;}return true;
        }catch(Exception e){return false;}
    }
    static String title(JSONObject entry){String code=entry.optString("code");if(code.equals("ell"))return "Ελληνικά";if(code.equals("eng"))return "English";if(entry.has("name"))return entry.optString("name");try{return new com.paperflow.scanengine.OcrLanguage(code).displayName(Locale.getDefault());}catch(Exception e){return code;}}
    static void remove(Context c,String kind,JSONObject entry)throws Exception {
        String code=entry.getString("code");File dir=directory(c,kind);
        if(kind.equals("ocr")){File target=new File(dir,code+".traineddata");if(target.isFile()&&!target.delete())throw new IOException(UiText.get(R.string.ui_could_not_remove_language));Set<String> enabled=new HashSet<>(c.getSharedPreferences("local-languages",0).getStringSet("ocr-enabled",Collections.emptySet()));enabled.remove(code);c.getSharedPreferences("local-languages",0).edit().putStringSet("ocr-enabled",enabled).apply();}
        else {File folder=new File(dir,code);File[] files=folder.listFiles();if(files!=null)for(File file:files)if(file.isFile()&&!file.delete())throw new IOException(UiText.get(R.string.ui_could_not_remove_translation_file));if(folder.exists()&&!folder.delete())throw new IOException(UiText.get(R.string.ui_could_not_remove_translation_language));}
    }
    static synchronized void download(Context c,String kind,JSONObject entry,BooleanSupplier cancel,Progress progress)throws Exception {
        if(cancel.getAsBoolean()||Thread.currentThread().isInterrupted())throw new InterruptedIOException(UiText.get(R.string.ui_download_cancelled));
        if(installed(c,kind,entry))return;
        File dir=directory(c,kind);if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException(UiText.get(R.string.ui_storage_unavailable));
        if(kind.equals("ocr")){
            transfer(c,entry,new File(dir,entry.getString("code")+".traineddata"),cancel,progress);
        }else {
            File folder=new File(dir,entry.getString("code"));if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException(UiText.get(R.string.ui_storage_unavailable));
            JSONArray files=entry.getJSONArray("files");long completed=0,total=entry.getLong("size");
            for(int i=0;i<files.length();i++){
                JSONObject file=files.getJSONObject(i);final long base=completed;
                transfer(c,file,new File(folder,file.getString("path")),cancel,(bytes,ignored)->progress.update(base+bytes,total));
                completed+=file.getLong("size");
            }
            if(cancel.getAsBoolean())throw new InterruptedIOException(UiText.get(R.string.ui_download_cancelled));
            try(FileOutputStream out=new FileOutputStream(new File(folder,"installed"))){out.write(entry.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        }
    }
    private static void transfer(Context context,JSONObject entry,File target,BooleanSupplier cancel,Progress progress)throws Exception {
        boolean gitBlob=entry.has("blobSha1");
        ModelDownload.Spec spec=new ModelDownload.Spec(entry.getLong("size"),entry.getString(gitBlob?"blobSha1":"sha256"),gitBlob);
        ArrayList<ModelDownload.Source> sources=new ArrayList<>();
        sources.add(new ModelDownload.Source(entry.getString("url"),entry.optBoolean("gzip")));
        String repository=context.getString(R.string.model_mirror_repository);
        if(!repository.isEmpty())sources.add(new ModelDownload.Source("https://github.com/"+repository+"/releases/download/"+context.getString(R.string.model_release_tag)+"/"+ModelDownload.assetName(spec),false));
        try { ModelDownload.download(sources,target,spec,cancel,progress::update); }
        catch(ModelDownload.Cancelled cancelled){throw new InterruptedIOException(UiText.get(R.string.ui_download_cancelled));}
        catch(ModelDownload.Failure problem){
            String message=problem.reason==ModelDownload.Reason.HTTP?UiText.format(R.string.ui_download_server_returned_value,problem.status):UiText.get(problem.reason==ModelDownload.Reason.SIZE?R.string.ui_language_file_is_larger_than_expected:R.string.ui_language_download_integrity_check_failed);
            throw new IOException(message,problem);
        }
    }
}

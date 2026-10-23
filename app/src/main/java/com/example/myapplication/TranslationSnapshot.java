package com.example.myapplication;

import android.content.Context;
import android.graphics.*;
import org.json.*;
import com.paperflow.imageprocessing.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/** Immutable private snapshots, completed before exposing an export picker. */
final class TranslationSnapshot {
    static void remove(Context c,File directory){try{File dir=owned(c,directory.getPath(),"translation-sessions");for(String name:new String[]{"source.png","result.png","session.json"})new File(dir,name).delete();dir.delete();}catch(IOException ignored){}}
    static File write(Context c,Bitmap source,TranslationRenderer.Result result,List<TranslationRenderer.Block> blocks,String from,String to,String override,boolean explicit,boolean readable)throws Exception{
        File root=new File(c.getFilesDir(),"translation-sessions");if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Storage unavailable");
        File dir=new File(root,UUID.randomUUID().toString());if(!dir.mkdir())throw new IOException("Storage unavailable");
        try{
        image(new File(dir,"source.png"),source);image(new File(dir,"result.png"),result.bitmap);
        JSONArray entries=new JSONArray();for(TranslationRenderer.Block block:blocks)entries.put(new JSONObject().put("words",words(block.words)).put("source",block.source).put("translation",block.translation));
        JSONObject data=new JSONObject().put("from",from).put("to",to).put("override",override).put("explicit",explicit).put("readable",readable).put("blocks",entries).put("words",words(result.words));
        Files.write(new File(dir,"session.json").toPath(),data.toString().getBytes(StandardCharsets.UTF_8));return dir;
        }catch(Exception|OutOfMemoryError failure){remove(c,dir);throw failure;}
    }
    static File owned(Context c,String path,String directory)throws IOException{File f=new File(path).getCanonicalFile(),root=new File(c.getFilesDir(),directory).getCanonicalFile();if(!f.getPath().startsWith(root.getPath()+File.separator))throw new IOException("Invalid session path");return f;}
    static void restore(LocalTranslationActivity.TranslationSession s,String path)throws Exception{
        File dir=owned(s.getApplication(),path,"translation-sessions");JSONObject data=new JSONObject(new String(Files.readAllBytes(new File(dir,"session.json").toPath()),StandardCharsets.UTF_8));
        Bitmap source=BitmapFactory.decodeFile(new File(dir,"source.png").getPath()),result=BitmapFactory.decodeFile(new File(dir,"result.png").getPath());
        if(source==null||result==null){if(source!=null)source.recycle();if(result!=null)result.recycle();throw new IOException("Saved translation is unavailable");}
        ArrayList<TranslationRenderer.Block> blocks=new ArrayList<>();JSONArray entries=data.getJSONArray("blocks");for(int i=0;i<entries.length();i++){JSONObject entry=entries.getJSONObject(i);ArrayList<OcrTextBox> words=readWords(entry.getJSONArray("words"));if(words.isEmpty())continue;TranslationRenderer.Block block=new TranslationRenderer.Block(words.get(0));for(int j=1;j<words.size();j++)block.add(words.get(j));block.source=entry.getString("source");block.translation=entry.getString("translation");blocks.add(block);}
        TranslationRenderer.Result output=new TranslationRenderer.Result(result,readWords(data.getJSONArray("words")));
        s.main.post(()->{if(s.disposed||s.cancelled){source.recycle();result.recycle();if(!s.disposed){s.restoring=false;s.pendingDestination=null;s.fail(new InterruptedIOException(UiText.get(R.string.ui_translation_cancelled)));}return;}s.previousPage=-1;if(s.source!=null&&!s.source.isRecycled())s.source.recycle();if(s.result!=null&&!s.result.bitmap.isRecycled())s.result.bitmap.recycle();s.source=source;s.result=output;s.blocks=blocks;s.sourceLanguage=data.optString("from");s.target=data.optString("to","en");s.sourceOverride=data.optString("override","auto");s.explicitTarget=data.optBoolean("explicit");s.readableLayout=data.optBoolean("readable");s.snapshot=dir;s.snapshots.add(dir);s.busy=false;s.restoring=false;s.message=UiText.get(R.string.ui_ready_to_save);s.publish();if(s.pendingDestination!=null){android.net.Uri destination=s.pendingDestination;s.pendingDestination=null;s.copyOutput(destination);}});
    }
    private static void image(File file,Bitmap bitmap)throws IOException{try(OutputStream out=new FileOutputStream(file)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Cannot save translation session");}}
    private static JSONArray words(List<OcrTextBox> words)throws JSONException{JSONArray array=new JSONArray();for(OcrTextBox w:words){ImageRect r=w.getBox();array.put(new JSONArray().put(w.getText()).put(r.getLeft()).put(r.getTop()).put(r.getRight()).put(r.getBottom()).put(w.getLineHeight()).put(w.getLineBottom()));}return array;}
    static ArrayList<OcrTextBox> readWords(JSONArray array)throws JSONException{ArrayList<OcrTextBox> words=new ArrayList<>();for(int i=0;i<array.length();i++){JSONArray w=array.getJSONArray(i);words.add(new OcrTextBox(w.getString(0),new ImageRect(w.getInt(1),w.getInt(2),w.getInt(3),w.getInt(4)),w.getInt(5),w.getInt(6)));}return words;}
}

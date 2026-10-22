package com.example.myapplication;

import android.content.Context;
import android.database.Cursor;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

final class ConversionHistory {
    private static AtomicFile index(Context c){return new AtomicFile(new File(c.getFilesDir(),"conversion-history.json"));}
    static synchronized JSONArray list(Context c)throws Exception{
        if(!index(c).getBaseFile().exists())return new JSONArray();
        try(InputStream in=index(c).openRead();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)bytes.write(b,0,n);return new JSONArray(bytes.toString("UTF-8"));}
    }
    private static void write(Context c,JSONArray entries)throws Exception{AtomicFile file=index(c);FileOutputStream out=null;try{out=file.startWrite();out.write(entries.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);}catch(Exception e){if(out!=null)file.failWrite(out);throw e;}}
    static synchronized void add(Context c,File file,String name,String mime,List<String> origins)throws Exception{
        DocumentStore.inheritProtection(c,file.getAbsolutePath(),origins);JSONArray entries=list(c);for(int i=0;i<entries.length();i++)if(file.getAbsolutePath().equals(entries.getJSONObject(i).optString("source_result")))return;
        File dir=new File(c.getFilesDir(),"conversions");if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException(UiText.get(R.string.ui_conversion_history_storage_unavailable));
        File saved=new File(dir,UUID.randomUUID()+"-"+name.replaceAll("[\\\\/:*?\"<>|]","_"));
        boolean copied=true;File documents=new File(c.getFilesDir(),"documents").getCanonicalFile();if(!mime.equals("application/pdf")&&file.getCanonicalFile().getParentFile().equals(documents)){saved=file;copied=false;}else Files.copy(file.toPath(),saved.toPath());
        try{DocumentStore.inheritProtection(c,saved.getAbsolutePath(),origins);JSONArray folders=new JSONArray();HashSet<Long> seen=new HashSet<>();try(DocumentStore store=new DocumentStore(c)){for(String origin:origins)try(Cursor row=store.db().rawQuery("SELECT folder_id FROM docs WHERE path=?",new String[]{origin})){if(row.moveToFirst()&&seen.add(row.getLong(0)))folders.put(row.getLong(0));for(Long root:store.protectionRoots(origin))if(seen.add(root))folders.put(root);}}
            JSONObject entry=new JSONObject().put("id",UUID.randomUUID()).put("path",saved.getAbsolutePath()).put("name",name).put("mime",mime).put("created",System.currentTimeMillis()).put("source_result",file.getAbsolutePath()).put("origins",new JSONArray(origins)).put("folders",folders);
            JSONArray updated=new JSONArray().put(entry);for(int i=0;i<entries.length();i++)updated.put(entries.get(i));write(c,updated);
        }catch(Exception e){if(copied)saved.delete();throw e;}
    }
    static synchronized void delete(Context c,String id)throws Exception{JSONArray entries=list(c),kept=new JSONArray();for(int i=0;i<entries.length();i++){JSONObject entry=entries.getJSONObject(i);if(entry.getString("id").equals(id)){File file=new File(entry.getString("path"));if(file.exists()&&!file.delete())throw new IOException(UiText.get(R.string.ui_cannot_delete_conversion));}else kept.put(entry);}write(c,kept);}
}

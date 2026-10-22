package com.example.myapplication;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Local Book draft index. Only files owned by the captures directory may be referenced or deleted. */
final class BookDraftStore {
    static final class Snapshot {
        final ArrayList<String> pages;
        final boolean doublePage;
        Snapshot(ArrayList<String> pages,boolean doublePage){this.pages=pages;this.doublePage=doublePage;}
    }
    private final File captureDirectory;
    private final AtomicFile index;
    BookDraftStore(Context context){this(context.getFilesDir());}
    BookDraftStore(File filesDirectory){captureDirectory=new File(filesDirectory,"captures");index=new AtomicFile(new File(filesDirectory,"book-draft.json"));}
    private File owned(String path)throws IOException{
        File file=new File(path).getCanonicalFile();
        if(!captureDirectory.getCanonicalFile().equals(file.getParentFile()))throw new IOException(UiText.get(R.string.ui_book_draft_points_outside_captures));
        return file;
    }
    synchronized void save(List<String> pages,boolean doublePage)throws IOException{
        if(pages.isEmpty()){index.delete();return;}
        JSONArray items=new JSONArray();
        for(String path:pages){File file=owned(path);if(!file.isFile())throw new IOException(UiText.get(R.string.ui_book_page_is_missing));items.put(file.getAbsolutePath());}
        FileOutputStream output=null;
        try{
            JSONObject json=new JSONObject();json.put("version",1);json.put("doublePage",doublePage);json.put("pages",items);
            output=index.startWrite();output.write(json.toString().getBytes(StandardCharsets.UTF_8));index.finishWrite(output);
        }catch(Exception error){if(output!=null)index.failWrite(output);if(error instanceof IOException)throw (IOException)error;throw new IOException(UiText.get(R.string.ui_could_not_save_book_draft),error);}
    }
    synchronized Snapshot read()throws IOException{
        ArrayList<String> pages=new ArrayList<>();
        if(!index.getBaseFile().exists()&&!new File(index.getBaseFile()+".bak").exists())return new Snapshot(pages,true);
        try(InputStream input=index.openRead();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1){if(bytes.size()+count>1024*1024)throw new IOException(UiText.get(R.string.ui_book_draft_index_is_too_large));bytes.write(buffer,0,count);}
            JSONObject json=new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            if(json.optInt("version")!=1)throw new IOException(UiText.get(R.string.ui_unsupported_book_draft));
            JSONArray items=json.getJSONArray("pages");
            for(int i=0;i<items.length();i++){
                try{File file=owned(items.getString(i));if(file.isFile()&&!pages.contains(file.getAbsolutePath()))pages.add(file.getAbsolutePath());}
                catch(IOException ignored){/* A corrupt index must never access files outside our captures. */}
            }
            return new Snapshot(pages,json.optBoolean("doublePage",true));
        }catch(IOException error){throw error;}catch(Exception error){throw new IOException(UiText.get(R.string.ui_could_not_read_book_draft),error);}
    }
    synchronized void complete(){index.delete();}
    synchronized void discard()throws IOException{
        Snapshot snapshot=read();
        for(String path:snapshot.pages){File file=owned(path);if(file.exists()&&!file.delete())throw new IOException(UiText.get(R.string.ui_could_not_delete_book_draft_page));}
        index.delete();
    }
}

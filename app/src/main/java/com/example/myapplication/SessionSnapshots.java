package com.example.myapplication;

import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Serial, atomic disk snapshots. Lifecycle callbacks enqueue immutable data, never wait. */
final class SessionSnapshots {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->new Thread(r,"paperflow-snapshots"));
    static void write(File path,Callable<String> encode){
        IO.execute(()->{AtomicFile file=new AtomicFile(path);FileOutputStream output=null;
            try{byte[] bytes=encode.call().getBytes(StandardCharsets.UTF_8);if(bytes.length>32*1024*1024)throw new IOException("Session is too large");
                File parent=path.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Session directory unavailable");
                output=file.startWrite();output.write(bytes);file.finishWrite(output);
            }catch(Exception error){if(output!=null)file.failWrite(output);android.util.Log.w("PaperFlow","Could not persist session snapshot");}
        });
    }
    /** Only processing workers may call this; reads follow any queued writes after recreation. */
    static String read(File path)throws Exception {
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("Session read on main thread");
        try{return IO.submit(()->{try(InputStream in=new AtomicFile(path).openRead();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] buffer=new byte[16384];int count;while((count=in.read(buffer))!=-1){if(bytes.size()+count>32*1024*1024)throw new IOException("Session is too large");bytes.write(buffer,0,count);}return bytes.toString(StandardCharsets.UTF_8.name());
        }}).get();}catch(ExecutionException failure){throw new IOException("Could not restore session",failure.getCause());}
    }
    static void delete(File path){IO.execute(()->new AtomicFile(path).delete());}
}

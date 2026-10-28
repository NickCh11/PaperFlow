package com.example.myapplication;

import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/** Serial, atomic disk snapshots. Lifecycle callbacks enqueue immutable data, never wait. */
final class SessionSnapshots {
    private SessionSnapshots() { }
    private static final int MAX_BYTES=32*1024*1024;
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->new Thread(r,"paperflow-snapshots"));
    /** AtomicFile owns the target; closing its encoder must not close the file before sync. */
    static final class LimitedOutput extends FilterOutputStream {
        private final long limit;private long count;
        LimitedOutput(OutputStream target,long limit){super(target);this.limit=limit;}
        @Override public void write(int value)throws IOException {
            if(count>=limit)throw new IOException("Session is too large");out.write(value);count++;
        }
        @Override public void write(byte[] bytes,int offset,int length)throws IOException {
            if(count+length>limit)throw new IOException("Session is too large");out.write(bytes,offset,length);count+=length;
        }
        @Override public void close()throws IOException {flush();}
    }
    static void write(File path,Callable<String> encode){
        IO.execute(()->{AtomicFile file=new AtomicFile(path);FileOutputStream output=null;
            try{String value=java.util.Objects.requireNonNull(encode.call());
                File parent=path.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("Session directory unavailable");
                output=file.startWrite();
                try(Writer writer=new OutputStreamWriter(new LimitedOutput(output,MAX_BYTES),StandardCharsets.UTF_8)){
                    char[] buffer=new char[8192];
                    for(int start=0;start<value.length();){int end=Math.min(value.length(),start+buffer.length);value.getChars(start,end,buffer,0);writer.write(buffer,0,end-start);start=end;}
                }
                file.finishWrite(output);
            }catch(Exception|OutOfMemoryError error){if(output!=null)file.failWrite(output);android.util.Log.w("PaperFlow","Could not persist session snapshot");}
        });
    }
    /** Only processing workers may call this; reads follow any queued writes after recreation. */
    static String read(File path)throws Exception {
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("Session read on main thread");
        try{return IO.submit(()->{try(InputStream in=new AtomicFile(path).openRead();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
            byte[] buffer=new byte[16384];int count;while((count=in.read(buffer))!=-1){if(bytes.size()+count>MAX_BYTES)throw new IOException("Session is too large");bytes.write(buffer,0,count);}return bytes.toString(StandardCharsets.UTF_8.name());
        }}).get();}catch(ExecutionException failure){throw new IOException("Could not restore session",failure.getCause());}
    }
    static void delete(File path){IO.execute(()->new AtomicFile(path).delete());}
}

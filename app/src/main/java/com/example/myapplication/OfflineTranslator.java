package com.example.myapplication;

import android.content.Context;
import com.paperflow.translation.OfflineRuntime;
import java.io.*;

/** Compatibility facade; inference and routing belong to offline-translator. */
final class OfflineTranslator implements AutoCloseable {
    private final OfflineRuntime runtime;
    private final String source,target;
    private boolean closed;
    OfflineTranslator(Context context,String direction)throws Exception {
        String[] codes=direction.split("-",2);if(codes.length!=2)throw new IOException("Invalid translation direction");
        source=codes[0];target=codes[1];runtime=OfflineRuntime.get(context);
    }
    synchronized String translate(String text)throws IOException {
        if(closed||Thread.currentThread().isInterrupted())throw new InterruptedIOException(UiText.get(R.string.ui_translation_cancelled));
        try{return runtime.translate(source,target,text);}catch(Exception error){throw new IOException(error.getMessage(),error);}
    }
    @Override public synchronized void close(){closed=true;}
}

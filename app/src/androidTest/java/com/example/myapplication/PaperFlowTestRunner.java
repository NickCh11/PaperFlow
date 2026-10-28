package com.example.myapplication;

import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import androidx.test.runner.AndroidJUnitRunner;

/** Keep native OCR from oversubscribing small CI emulator CPUs. */
public final class PaperFlowTestRunner extends AndroidJUnitRunner {
    private boolean nativeCoverage;
    @Override public void onCreate(Bundle arguments){
        nativeCoverage="true".equals(arguments.getString("nativeCoverage"));
        if(nativeCoverage)try{NativeCoverage.prepare(getTargetContext());}
        catch(java.io.IOException|ErrnoException error){throw new IllegalStateException("Cannot configure native coverage",error);}
        if("true".equals(arguments.getString("ci"))){
            long heap=Runtime.getRuntime().maxMemory();
            android.util.Log.i("PaperFlowTestRunner","ART maximum heap: "+heap+" bytes");
            if(android.os.Build.VERSION.SDK_INT==29&&heap<256L*1024*1024)
                throw new IllegalStateException("API 29 CI heap is below 256 MiB; run tools/ci/prepare_emulator.py first");
            try{Os.setenv("OMP_THREAD_LIMIT","1",true);}
            catch(ErrnoException error){throw new IllegalStateException("Cannot configure OCR test threads",error);}
        }
        super.onCreate(arguments);
    }
    @Override public void finish(int resultCode,Bundle results){
        if(nativeCoverage)try{NativeCoverage.finish();}
        catch(java.io.IOException|LinkageError error){throw new IllegalStateException("Native coverage collection failed",error);}
        super.finish(resultCode,results);
    }
}

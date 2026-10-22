package com.example.myapplication;

import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import androidx.test.runner.AndroidJUnitRunner;

/** Keep native OCR from oversubscribing small CI emulator CPUs. */
public final class PaperFlowTestRunner extends AndroidJUnitRunner {
    @Override public void onCreate(Bundle arguments){
        if("true".equals(arguments.getString("ci"))){
            try{Os.setenv("OMP_THREAD_LIMIT","1",true);}
            catch(ErrnoException error){throw new IllegalStateException("Cannot configure OCR test threads",error);}
        }
        super.onCreate(arguments);
    }
}

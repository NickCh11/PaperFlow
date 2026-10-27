package com.example.myapplication;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/** Test-APK helper; enabled only when CI supplies the instrumented native library. */
final class NativeCoverage {
    private NativeCoverage() { }
    private static File directory;
    static void prepare(Context context)throws IOException,ErrnoException {
        directory=new File(context.getFilesDir(),"native-coverage/"+UUID.randomUUID());
        if(!directory.mkdirs())throw new IOException("Cannot create native coverage directory");
        Os.setenv("LLVM_PROFILE_FILE",new File(directory,"%m-%p.profraw").getAbsolutePath(),true);
    }
    static void finish()throws IOException {
        // Force the real runtime loader/ABI handshake before resolving the CI-only JNI hook.
        com.paperflow.translation.RuntimeNative runtime=com.paperflow.translation.RuntimeNative.INSTANCE;
        if(runtime==null||writeProfile()!=0)throw new IOException("Native profiler did not write coverage");
        File[] profiles=directory.listFiles((parent,name)->name.endsWith(".profraw"));
        if(profiles==null||profiles.length==0)throw new IOException("Native profiler produced no execution data");
        for(File profile:profiles)if(profile.length()==0)throw new IOException("Empty native execution data");
        Files.write(new File(directory.getParentFile(),"latest.txt").toPath(),directory.getName().getBytes(StandardCharsets.UTF_8));
    }
    private static native int writeProfile();
}

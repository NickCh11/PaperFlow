package com.example.myapplication;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;

/** Explicit ownership allowlist: an Intent path alone is never authority to delete. */
final class TemporaryInputs {
    static File owned(File files,String path)throws IOException {
        File file=new File(path).getCanonicalFile();
        for(String directory:new String[]{"captures","translation-inputs"})
            if(new File(files,directory).getCanonicalFile().equals(file.getParentFile()))return file;
        throw new IOException("Input is not a direct child of a temporary directory");
    }
    static void remove(File files,Collection<File> inputs,Collection<String> activeDraft){
        HashSet<String> retained=new HashSet<>();
        for(String path:activeDraft)try{retained.add(new File(path).getCanonicalPath());}catch(IOException ignored){}
        for(File input:inputs)try{File owned=owned(files,input.getPath());if(!retained.contains(owned.getPath()))owned.delete();}catch(IOException ignored){}
    }
}

package com.example.myapplication;

import android.content.Context;
import java.io.File;

/** Private directories shared by document editing sessions. */
final class DocumentDirectories {
    static File captures(Context context){return ensure(new File(context.getFilesDir(),"captures"));}
    static File documents(Context context){return ensure(new File(context.getFilesDir(),"documents"));}
    private static File ensure(File folder){if(!folder.isDirectory())folder.mkdirs();return folder;}
}

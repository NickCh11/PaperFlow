package com.example.myapplication;

import android.content.Context;
import com.paperflow.translation.OfflineRuntime;

/** The upstream CLD2 detector returns an empty code for ambiguous/short input. */
final class PhotoLanguageDetector {
    static String detect(Context context,String input)throws Exception {return OfflineRuntime.get(context).detect(input);}
}

package com.example.myapplication;

import androidx.appcompat.app.*;

/** Preview/text dialogs close together with a protected document when folder access expires. */
final class ProtectedDialogs {
    static final class Builder extends AlertDialog.Builder {
        private final AppCompatActivity activity;
        Builder(AppCompatActivity activity){super(activity);this.activity=activity;}
        @Override public AlertDialog create(){AlertDialog dialog=super.create();FolderLock.trackDialog(activity,dialog);return dialog;}
        @Override public AlertDialog show(){AlertDialog dialog=create();dialog.show();return dialog;}
    }
}

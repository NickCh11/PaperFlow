package com.example.myapplication;

import android.os.Bundle;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

/** Routes system gestures and toolbar Back through the same unsaved-work checks. */
public abstract class BackHandlingActivity extends AppCompatActivity {
    private OnBackPressedCallback backCallback;
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        backCallback=new OnBackPressedCallback(true){
            @Override public void handleOnBackPressed(){handleBackPressed();}
        };
        getOnBackPressedDispatcher().addCallback(this,backCallback);
    }
    public void handleBackPressed(){
        backCallback.setEnabled(false);
        try{getOnBackPressedDispatcher().onBackPressed();}
        finally{backCallback.setEnabled(true);}
    }
}

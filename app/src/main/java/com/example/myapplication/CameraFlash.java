package com.example.myapplication;

import androidx.camera.core.ImageCapture;

/** One flash policy for both ordinary capture and live translation. */
final class CameraFlash {
    interface Backend {void captureMode(int mode);void torch(boolean enabled);}
    static int modeForChoice(int choice){return choice==1?ImageCapture.FLASH_MODE_AUTO:choice==2?ImageCapture.FLASH_MODE_ON:ImageCapture.FLASH_MODE_OFF;}
    static void apply(Backend backend,int mode){
        backend.captureMode(mode);
        // On lights the document preview too. Auto leaves the capture decision to CameraX.
        backend.torch(mode==ImageCapture.FLASH_MODE_ON);
    }
}

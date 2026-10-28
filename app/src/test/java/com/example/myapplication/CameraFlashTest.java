package com.example.myapplication;

import androidx.camera.core.ImageCapture;
import org.junit.Test;
import static org.junit.Assert.*;

public class CameraFlashTest {
    @Test public void choicesMapToTheActualCameraXModes(){
        assertEquals(ImageCapture.FLASH_MODE_OFF,CameraFlash.modeForChoice(0));
        assertEquals(ImageCapture.FLASH_MODE_AUTO,CameraFlash.modeForChoice(1));
        assertEquals(ImageCapture.FLASH_MODE_ON,CameraFlash.modeForChoice(2));
    }
    @Test public void switchingFromOnToAutoAndOffReleasesTheTorch(){
        class Backend implements CameraFlash.Backend {int mode;boolean torch;public void captureMode(int value){mode=value;}public void torch(boolean value){torch=value;}}
        Backend backend=new Backend();
        for(int mode:new int[]{ImageCapture.FLASH_MODE_ON,ImageCapture.FLASH_MODE_AUTO,ImageCapture.FLASH_MODE_ON,ImageCapture.FLASH_MODE_OFF}){
            CameraFlash.apply(backend,mode);assertEquals(mode,backend.mode);assertEquals(mode==ImageCapture.FLASH_MODE_ON,backend.torch);
        }
    }
}

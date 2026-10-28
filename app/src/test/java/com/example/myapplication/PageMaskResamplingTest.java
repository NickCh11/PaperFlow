package com.example.myapplication;

import org.junit.Test;
import static org.junit.Assert.*;

public class PageMaskResamplingTest {
    @Test public void upsamplingKeepsCornerProbabilitiesAndDoesNotMutateTheMask(){
        float[] mask={0,.25f,.5f,1},before=mask.clone();
        assertArrayEquals(new byte[]{0,0,64,64,0,0,64,64,(byte)128,(byte)128,(byte)255,(byte)255,(byte)128,(byte)128,(byte)255,(byte)255},PageDetectorModel.resampleMask(mask,2,2,4,4));
        assertArrayEquals(before,mask,0);
        assertArrayEquals(new byte[]{0},PageDetectorModel.resampleMask(mask,2,2,1,1));
    }
    @Test public void missingTruncatedAndOverflowingMasksFailBeforeArrayAccess(){
        assertThrows(IllegalArgumentException.class,()->PageDetectorModel.resampleMask(null,1,1,1,1));
        assertThrows(IllegalArgumentException.class,()->PageDetectorModel.resampleMask(new float[0],1,1,1,1));
        assertThrows(IllegalArgumentException.class,()->PageDetectorModel.resampleMask(new float[3],2,2,1,1));
        assertThrows(IllegalArgumentException.class,()->PageDetectorModel.resampleMask(new float[1],0,1,1,1));
        assertThrows(IllegalArgumentException.class,()->PageDetectorModel.resampleMask(new float[1],Integer.MAX_VALUE,2,1,1));
        assertThrows(IllegalArgumentException.class,()->PageDetectorModel.resampleMask(new float[1],1,1,Integer.MAX_VALUE,2));
    }
}

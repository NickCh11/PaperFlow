package com.example.myapplication;

import org.junit.Test;
import static org.junit.Assert.*;

public class LivePageOutlineTest {
    private float[] page(float x){return new float[]{.15f+x,.15f,.75f+x,.15f,.75f+x,.85f,.15f+x,.85f};}
    private LivePageOutline locked(){LivePageOutline outline=new LivePageOutline();assertNull(outline.update(page(0),0));assertNotNull(outline.update(page(0),120));return outline;}

    @Test public void oneNoisyFrameCannotEstablishAnOutline(){
        LivePageOutline outline=new LivePageOutline();assertNull(outline.update(page(0),0));assertNull(outline.update(page(.1f),120));assertNull(outline.update(null,240));assertNull(outline.update(page(.1f),360));assertNotNull(outline.update(page(.1f),480));
    }
    @Test public void smallJitterIsSmoothedAndReturnedArraysAreOwnedSnapshots(){
        LivePageOutline outline=locked();float[] raw=page(.02f),shown=outline.update(raw,240);assertNotNull(shown);
        assertTrue(shown[0]>.15f);assertTrue(shown[0]<raw[0]);raw[0]=1;shown[0]=1;
        float[] next=outline.update(page(0),360);assertTrue(next[0]<.16f);
    }
    @Test public void aBriefMissKeepsTheGuideButAPersistentMissRemovesIt(){
        LivePageOutline outline=locked();assertNotNull(outline.update(null,300));assertNotNull(outline.update(null,470));assertNull(outline.update(null,471));
        assertNull(outline.update(page(0),500));assertNotNull(outline.update(page(0),620));
    }
    @Test public void aLargeMovementReacquiresInsteadOfLeavingAnOldLockedContour(){
        LivePageOutline outline=locked();assertNull(outline.update(page(.2f),240));float[] next=outline.update(page(.2f),360);assertArrayEquals(page(.2f),next,0);
    }
    @Test public void cameraPausesAndClockResetsCannotReuseAnOldOutline(){
        LivePageOutline outline=locked();assertNull(outline.update(page(0),1621));assertNotNull(outline.update(page(0),1741));assertNull(outline.update(page(0),10));
        assertNotNull(outline.update(page(0),130));assertNull(outline.update(null,2000));
    }
    @Test public void malformedAndNonConvexDetectionsCannotBecomeVisible(){
        float[][] invalid={null,new float[6],new float[]{Float.NaN,0,1,0,1,1,0,1},new float[]{Float.POSITIVE_INFINITY,0,1,0,1,1,0,1},new float[]{-.1f,0,1,0,1,1,0,1},new float[]{0,0,1.1f,0,1,1,0,1},new float[]{0,0,1,1,1,0,0,1},new float[]{0,0,1,0,.5f,.1f,0,1},new float[]{.1f,.1f,.11f,.1f,.11f,.11f,.1f,.11f}};
        for(float[] quad:invalid){LivePageOutline outline=new LivePageOutline();assertNull(outline.update(quad,0));assertNull(outline.update(quad,120));}
    }
}

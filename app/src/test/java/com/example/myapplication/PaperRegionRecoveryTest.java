package com.example.myapplication;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class PaperRegionRecoveryTest {
    @Test public void missingNeutralFooterCanBeRecoveredWithoutColoredDesk(){
        int w=100,h=160;int[] rgb=new int[w*h];byte[] mask=new byte[w*h];Arrays.fill(rgb,0xff754122);
        for(int y=20;y<120;y++)for(int x=20;x<72;x++)rgb[y*w+x]=0xffbbbbbb;
        for(int y=25;y<75;y++)for(int x=25;x<69;x++)mask[y*w+x]=(byte)255;
        byte[] found=PaperRegionRecovery.support(new DocumentCandidateSelector.Image(w,h,rgb,mask));assertNotNull(found);
        assertEquals(255,found[110*w+50]&255);assertEquals(0,found[140*w+50]&255);assertEquals(0,found[90*w+10]&255);
    }
    @Test public void emptyMaskDoesNotInventPage(){assertNull(PaperRegionRecovery.support(new DocumentCandidateSelector.Image(40,60,new int[2400],new byte[2400])));}
    @Test public void coloredRegionCannotBecomePaperSupport(){
        int w=40,h=60;int[] rgb=new int[w*h];byte[] mask=new byte[w*h];Arrays.fill(rgb,0xffe08030);Arrays.fill(mask,(byte)255);
        assertNull(PaperRegionRecovery.support(new DocumentCandidateSelector.Image(w,h,rgb,mask)));
    }
    @Test public void grayCreasePreventsGrowthIntoNeighboringPage(){
        int w=160,h=200;int[] rgb=new int[w*h];byte[] mask=new byte[w*h];Arrays.fill(rgb,0xff303030);for(int y=20;y<180;y++)for(int x=0;x<w;x++)rgb[y*w+x]=0xffdddddd;
        for(int y=20;y<180;y++)for(int x=56;x<=59;x++)rgb[y*w+x]=0xff777777;
        for(int y=30;y<150;y++)for(int x=68;x<140;x++)mask[y*w+x]=(byte)255;
        byte[] found=PaperRegionRecovery.support(new DocumentCandidateSelector.Image(w,h,rgb,mask));assertNotNull(found);
        assertEquals(255,found[100*w+80]&255);assertEquals(0,found[100*w+52]&255);
    }
    @Test public void whiteBackgroundWithoutPhysicalFooterIsNotRecoveryEvidence(){
        int w=100,h=160;int[] rgb=new int[w*h];byte[] mask=new byte[w*h];Arrays.fill(rgb,0xffdddddd);
        for(int y=25;y<100;y++)for(int x=25;x<75;x++)mask[y*w+x]=(byte)255;
        assertNull(PaperRegionRecovery.support(new DocumentCandidateSelector.Image(w,h,rgb,mask)));
    }
}

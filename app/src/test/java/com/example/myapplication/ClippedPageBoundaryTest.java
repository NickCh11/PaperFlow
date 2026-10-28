package com.example.myapplication;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** Synthetic luminance fixtures have independently specified page edges, without a model or camera. */
public class ClippedPageBoundaryTest {
    private byte[] page(int width,int height,double top,double bottom,double topSlope,double bottomSlope,int paper,int background){
        byte[] pixels=new byte[width*height];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)pixels[y*width+x]=(byte)(y>=top+topSlope*x&&y<bottom+bottomSlope*x?paper:background);
        return pixels;
    }
    private void near(float[] expected,float[] actual,float tolerance){assertNotNull("Visible page edges must be detected",actual);assertArrayEquals(expected,actual,tolerance);assertTrue(PageBoundaryDetector.valid(actual));}

    @Test public void brightPageExtendingBeyondBothSidesRetainsItsTopAndBottom(){
        byte[] image=page(300,500,50,450,0,0,220,35);
        near(new float[]{0,.1f,1,.1f,1,.9f,0,.9f},PageBoundaryClipped.detect(image,300,500),.01f);
        near(new float[]{0,.1f,1,.1f,1,.9f,0,.9f},PageBoundaryClipped.detect(image,300,500,true),.01f);
    }

    @Test public void aPageClippedAtTheTopOrBottomUsesTheBrightFrameEdge(){
        near(new float[]{0,0,1,0,1,.9f,0,.9f},PageBoundaryClipped.detect(page(300,500,0,450,0,0,220,35),300,500),.01f);
        near(new float[]{0,.1f,1,.1f,1,1,0,1},PageBoundaryClipped.detect(page(300,500,50,500,0,0,220,35),300,500),.01f);
    }

    @Test public void slopingEdgesAreFittedWithoutShrinkingThePage(){
        near(new float[]{0,.12f,1,.18f,1,.85f,0,.88f},PageBoundaryClipped.detect(page(300,500,60,440,.1,-.05,225,40),300,500),.012f);
    }

    @Test public void landscapeRotationMapsTheRecoveredCornersBackToOriginalCoordinates(){
        int width=300,height=500;byte[] portrait=page(width,height,50,450,0,0,220,35),landscape=new byte[portrait.length];
        for(int y=0;y<height;y++)for(int x=0;x<width;x++)landscape[x*height+height-1-y]=portrait[y*width+x];
        near(new float[]{.1f,0,.9f,0,.9f,1,.1f,1},PageBoundaryClipped.detect(landscape,height,width),.012f);
    }

    @Test public void lowContrastPageNeedsTheExplicitRelaxedDetector(){
        byte[] image=page(300,500,50,450,0,0,110,95);
        assertNull(PageBoundaryClipped.detect(image,300,500));
        near(new float[]{0,.1f,1,.1f,1,.9f,0,.9f},PageBoundaryClipped.detect(image,300,500,true),.012f);
    }

    @Test public void flatTinyShortAndDarkSidedScenesCannotInventAFullPage(){
        for(int value:new int[]{0,90,255}){byte[] flat=new byte[300*500];Arrays.fill(flat,(byte)value);assertNull(PageBoundaryClipped.detect(flat,300,500));assertNull(PageBoundaryClipped.detect(flat,300,500,true));}
        assertNull(PageBoundaryClipped.detect(new byte[70*100],70,100));
        assertNull(PageBoundaryClipped.detect(new byte[90*100],90,100));
        assertNull(PageBoundaryClipped.detect(new byte[500*300],500,300));
        assertNull(PageBoundaryClipped.detect(page(300,500,150,350,0,0,220,35),300,500));
        byte[] narrow=page(300,500,50,450,0,0,220,35);
        for(int y=0;y<500;y++)for(int x=0;x<40;x++){narrow[y*300+x]=35;narrow[y*300+299-x]=35;}
        assertNull(PageBoundaryClipped.detect(narrow,300,500));
        byte[] rightDark=page(300,500,50,450,0,0,220,35);for(int y=0;y<500;y++)for(int x=270;x<300;x++)rightDark[y*300+x]=35;
        assertNull(PageBoundaryClipped.detect(rightDark,300,500));
        byte[] darkTop=page(300,500,0,450,0,0,220,35);Arrays.fill(darkTop,0,3*300,(byte)35);assertNull(PageBoundaryClipped.detect(darkTop,300,500));
    }

    @Test public void aVisibleBottomEdgeCloseToTheFrameIsRetained(){
        byte[] image=page(300,500,50,497,0,0,220,35);
        near(new float[]{0,.1f,1,.1f,1,.994f,0,.994f},PageBoundaryClipped.detect(image,300,500),.012f);
    }

    @Test public void invalidQuadsRejectNonFiniteOutOfFrameConcaveAndDegenerateCorners(){
        assertFalse(PageBoundaryDetector.valid(null));assertFalse(PageBoundaryDetector.valid(new float[6]));
        float[] page={.1f,.1f,.9f,.1f,.9f,.9f,.1f,.9f};assertTrue(PageBoundaryDetector.valid(page));
        for(float value:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-.01f,1.01f}){float[] bad=page.clone();bad[0]=value;assertFalse(PageBoundaryDetector.valid(bad));}
        assertFalse(PageBoundaryDetector.valid(new float[]{.1f,.1f,.9f,.9f,.9f,.1f,.1f,.9f}));
        assertFalse(PageBoundaryDetector.valid(new float[]{.1f,.1f,.11f,.1f,.11f,.9f,.1f,.9f}));
        assertFalse(PageBoundaryDetector.valid(new float[]{.1f,.1f,.2f,.1f,.2f,.2f,.1f,.2f}));
    }
}

package com.example.myapplication;

import org.junit.Test;
import static org.junit.Assert.*;

public class PageResponseGeometryTest {
    @Test public void textHolesFillButExteriorRemainsEmpty(){
        int w=100,h=100;byte[] map=new byte[w*h];for(int y=10;y<90;y++)for(int x=15;x<85;x++)map[y*w+x]=(byte)230;
        for(int y=30;y<70;y++)for(int x=35;x<65;x++)map[y*w+x]=0;
        byte[] filled=PageResponseGeometry.filled(map,w,h);assertEquals(255,filled[50*w+50]&255);assertEquals(0,filled[5*w+5]&255);
        byte[] edge=PageResponseGeometry.boundary(filled,w,h);assertEquals(0,edge[50*w+50]);assertEquals(255,edge[10*w+50]&255);
    }
    @Test public void emptyResponseStaysEmpty(){byte[] map=new byte[400];assertArrayEquals(map,PageResponseGeometry.filled(map,20,20));assertArrayEquals(map,PageResponseGeometry.boundary(map,20,20));}
    @Test public void clippedPageKeepsItsImageBorder(){int w=100,h=100;byte[] map=new byte[w*h];for(int y=10;y<90;y++)for(int x=0;x<85;x++)map[y*w+x]=(byte)255;byte[] edge=PageResponseGeometry.boundary(PageResponseGeometry.filled(map,w,h),w,h);assertEquals(255,edge[50*w]&255);assertEquals(0,edge[50*w+50]);}
    @Test public void alignedShadowFragmentsSurviveWithoutMergingNeighboringPage(){
        int w=100,h=140;byte[] map=new byte[w*h];
        for(int y=10;y<70;y++)for(int x=30;x<75;x++)map[y*w+x]=(byte)255;
        for(int y=90;y<130;y++)for(int x=35;x<70;x++)map[y*w+x]=(byte)255;
        for(int y=20;y<80;y++)for(int x=0;x<20;x++)map[y*w+x]=(byte)255;
        byte[] filled=PageResponseGeometry.filled(map,w,h);assertEquals(255,filled[110*w+50]&255);assertEquals(0,filled[40*w+10]&255);
    }
    @Test public void enclosingProposalPreservesTheWholeMask(){
        int w=100,h=140;byte[] map=new byte[w*h];for(int y=10;y<120;y++)for(int x=20;x<75;x++)map[y*w+x]=(byte)255;
        for(int y=120;y<133;y++)for(int x=50;x<70;x++)map[y*w+x]=(byte)255;
        float[] q=PageResponseGeometry.enclosing(map,w,h);assertNotNull(q);assertTrue(PageResponseGeometry.coverage(map,w,h,q)>.99);
        assertNull(PageResponseGeometry.enclosing(new byte[w*h],w,h));
    }
}

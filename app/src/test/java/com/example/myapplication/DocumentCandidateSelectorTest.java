package com.example.myapplication;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class DocumentCandidateSelectorTest {
    private final float[] page={.25f,.15f,.75f,.15f,.75f,.85f,.25f,.85f};
    private DocumentCandidateSelector.Image fixture(boolean omitBottom){int w=400,h=400;int[] pixels=new int[w*h];byte[] mask=new byte[w*h];Arrays.fill(pixels,0xff404040);for(int y=60;y<340;y++)for(int x=100;x<300;x++){pixels[y*w+x]=0xffeeeeee;if(!omitBottom||y<240)mask[y*w+x]=(byte)255;}return new DocumentCandidateSelector.Image(w,h,pixels,mask);}
    @Test public void containingPageDoesNotJustifyIncludingTable(){DocumentCandidateSelector.Image image=fixture(false);float[] large={.05f,.05f,.95f,.05f,.95f,.95f,.05f,.95f};assertArrayEquals(page,DocumentCandidateSelector.select(image,Arrays.asList(large,page)),0);assertTrue(DocumentCandidateSelector.score(image,page)>DocumentCandidateSelector.score(image,large)+.3);}
    @Test public void realBottomEdgeRescuesMissingBlankMask(){DocumentCandidateSelector.Image image=fixture(true);float[] cut={.25f,.15f,.75f,.15f,.75f,.60f,.25f,.60f};assertArrayEquals(page,DocumentCandidateSelector.select(image,Arrays.asList(cut,page)),0);}
    @Test public void snapUsesImageEdgeInsteadOfCoarseMaskEdge(){DocumentCandidateSelector.Image image=fixture(false);float[] coarse={.27f,.17f,.73f,.17f,.73f,.83f,.27f,.83f};float[] refined=DocumentCandidateSelector.snap(image,coarse);assertNotNull(refined);assertArrayEquals(page,refined,.015f);}
    @Test public void goodTopCanCombineWithAnotherProposalsGoodBottom(){DocumentCandidateSelector.Image image=fixture(false);float[] badTop={.25f,.25f,.75f,.25f,.75f,.85f,.25f,.85f},badBottom={.25f,.15f,.75f,.15f,.75f,.7f,.25f,.7f};float[] combined=DocumentCandidateSelector.select(image,Arrays.asList(badTop,badBottom));assertArrayEquals(page,combined,.001f);}
    @Test public void printedMarginIsNotThePhysicalPaperBoundary(){
        int w=400,h=400;int[] rgb=new int[w*h];byte[] mask=new byte[w*h];Arrays.fill(rgb,0xff303030);
        for(int y=40;y<360;y++)for(int x=0;x<360;x++){rgb[y*w+x]=0xffeeeeee;if(x>=44)mask[y*w+x]=(byte)255;}
        for(int y=40;y<360;y++)for(int x=39;x<=41;x++)rgb[y*w+x]=0xff303030;
        float[] physical={0,.1f,.9f,.1f,.9f,.9f,0,.9f},margin={.11f,.1f,.9f,.1f,.9f,.9f,.11f,.9f};
        assertArrayEquals(physical,DocumentCandidateSelector.select(new DocumentCandidateSelector.Image(w,h,rgb,mask),Arrays.asList(physical,margin)),.001f);
    }
    @Test public void lowContrastPageDoesNotFallBackToSmallPrintedDiagram(){
        DocumentCandidateSelector.Image image=fixture(false);Arrays.fill(image.rgb,0xffdddddd);
        float[] diagram={.4f,.4f,.6f,.4f,.6f,.6f,.4f,.6f};
        assertArrayEquals(page,DocumentCandidateSelector.select(image,Arrays.asList(diagram,page),diagram),.001f);
    }
    @Test public void absentImageProposalDoesNotProtectAnIncompleteLearnedFit(){
        DocumentCandidateSelector.Image image=fixture(false);Arrays.fill(image.rgb,0xffdddddd);
        float[] partial={.25f,.15f,.75f,.15f,.75f,.6f,.25f,.6f};
        assertArrayEquals(page,DocumentCandidateSelector.select(image,Arrays.asList(partial,page),null),.001f);
    }
}

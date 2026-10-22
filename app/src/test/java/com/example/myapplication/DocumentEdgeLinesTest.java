package com.example.myapplication;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class DocumentEdgeLinesTest {
    @Test public void flatMapHasNoMeasuredEdges() {
        byte[] map = new byte[240*240]; Arrays.fill(map,(byte)128);
        assertTrue(DocumentEdgeLines.extract(map,240,240).isEmpty());
    }

    @Test public void rectangularProbabilityRegionProducesFourLongSidesWithoutMutatingInput() {
        int w=240,h=320; byte[] map=new byte[w*h];
        for(int y=40;y<280;y++) for(int x=30;x<210;x++) map[y*w+x]=(byte)245;
        byte[] original=map.clone();
        List<DocumentEdgeLines.Line> lines=DocumentEdgeLines.extract(map,w,h);
        assertArrayEquals(original,map);
        assertTrue("Left edge",hasLine(lines,30,150,true,190));
        assertTrue("Right edge",hasLine(lines,210,150,true,190));
        assertTrue("Top edge",hasLine(lines,120,40,false,140));
        assertTrue("Bottom edge",hasLine(lines,120,280,false,140));
        for(DocumentEdgeLines.Line line:lines) {
            assertFalse(line.synthetic);
            assertEquals(1,Math.hypot(line.a,line.b),1e-9);
            assertEquals(0,line.distance(line.x1,line.y1),1e-7);
            assertEquals(0,line.distance(line.x2,line.y2),1e-7);
        }
    }

    @Test public void bookLimitsAndSyntheticBordersAreExplicit() {
        byte[] map=new byte[240*320];
        for(int y=40;y<280;y++) for(int x=30;x<210;x++) map[y*240+x]=(byte)255;
        List<DocumentEdgeLines.Line> lines=DocumentEdgeLines.extract(map,240,320,120,60);
        assertFalse(lines.isEmpty());
        List<DocumentEdgeLines.Line> all=DocumentEdgeLines.withImageBorders(lines,240,320);
        assertEquals(lines.size()+4,all.size());
        for(int i=0;i<lines.size();i++) assertFalse(all.get(i).synthetic);
        for(int i=lines.size();i<all.size();i++) assertTrue(all.get(i).synthetic);
        assertTrue(DocumentEdgeLines.extract(map,240,320,map.length+1,60).isEmpty());
    }

    private static boolean hasLine(List<DocumentEdgeLines.Line> lines,double x,double y,
                                   boolean vertical,double minimumLength) {
        for(DocumentEdgeLines.Line l:lines) if(l.length>=minimumLength&&l.distance(x,y)<5
                &&(vertical?Math.abs(l.a)>.98:Math.abs(l.b)>.98)) return true;
        return false;
    }
}

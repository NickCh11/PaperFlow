package com.example.myapplication;

import org.junit.Test;
import static org.junit.Assert.*;

public class DocumentQuadFitterTest {
    @Test public void rectangleUsesRawSupportedEdges() {
        int w = 416, h = 320;
        byte[] map = rectangle(w,h,52,43,365,278);
        float[] quad = DocumentQuadFitter.fit(map,w,h);
        assertNotNull(quad);
        assertArrayEquals(new float[]{52f/w,43f/h,365f/w,43f/h,365f/w,278f/h,52f/w,278f/h},quad,.025f);
    }

    @Test public void clippedRectangleCanUseSyntheticImageSide() {
        int w = 416, h = 320;
        byte[] map = rectangle(w,h,0,42,360,280);
        float[] quad = DocumentQuadFitter.fit(map,w,h);
        assertNotNull(quad);
        assertEquals(0,quad[0],.025f);
        assertEquals(0,quad[6],.025f);
        assertEquals(360f/w,quad[2],.025f);
        assertEquals(280f/h,quad[5],.025f);
    }

    @Test public void blankMapRequiresCallerFallback() {
        assertNull(DocumentQuadFitter.fit(new byte[416*320],416,320));
    }

    @Test public void sourceResponseIsUnchanged() {
        byte[] map = rectangle(416,320,35,35,375,285), before = map.clone();
        DocumentQuadFitter.fit(map,416,320);
        assertArrayEquals(before,map);
    }

    @Test public void invalidAndTooShortInputsHaveNoQuad() {
        assertNull(DocumentQuadFitter.fit(null,416,320));
        assertNull(DocumentQuadFitter.fit(new byte[30],416,320));
        assertNull(DocumentQuadFitter.fit(rectangle(48,48,4,4,43,43),48,48));
    }

    private static byte[] rectangle(int w, int h, int left, int top, int right, int bottom) {
        byte[] map = new byte[w*h];
        for (int y = top; y <= bottom; y++) for (int x = left; x <= right; x++) {
            if (x-left < 3 || right-x < 3 || y-top < 3 || bottom-y < 3) map[y*w+x] = (byte)255;
        }
        return map;
    }
}

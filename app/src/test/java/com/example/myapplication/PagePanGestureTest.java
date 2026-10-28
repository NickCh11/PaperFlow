package com.example.myapplication;

import android.view.MotionEvent;
import org.junit.Test;
import static org.junit.Assert.*;

public class PagePanGestureTest {
    @Test public void twoFingerMovesProduceOnlyRelativeTranslation(){
        PagePanGesture pan=new PagePanGesture();
        assertFalse(pan.update(MotionEvent.ACTION_DOWN,1,5,8));
        assertTrue(pan.update(MotionEvent.ACTION_POINTER_DOWN,2,10,20));assertEquals(0,pan.deltaX,0);
        assertTrue(pan.update(MotionEvent.ACTION_MOVE,2,17,16));assertEquals(7,pan.deltaX,0);assertEquals(-4,pan.deltaY,0);
        assertTrue(pan.update(MotionEvent.ACTION_MOVE,2,20,21));assertEquals(3,pan.deltaX,0);assertEquals(5,pan.deltaY,0);
    }
    @Test public void theRemainingFingerCannotAccidentallySelectOrDraw(){
        PagePanGesture pan=new PagePanGesture();pan.update(MotionEvent.ACTION_POINTER_DOWN,2,10,20);
        assertTrue(pan.update(MotionEvent.ACTION_POINTER_UP,2,10,20));
        assertTrue(pan.update(MotionEvent.ACTION_MOVE,1,30,40));assertEquals(0,pan.deltaX,0);
        assertTrue(pan.update(MotionEvent.ACTION_UP,1,30,40));
        assertFalse(pan.update(MotionEvent.ACTION_DOWN,1,5,8));
    }
    @Test public void cancellingWithTwoPointersReleasesTheNextSingleFingerGesture(){
        PagePanGesture pan=new PagePanGesture();pan.update(MotionEvent.ACTION_POINTER_DOWN,2,10,20);
        assertTrue(pan.update(MotionEvent.ACTION_CANCEL,2,10,20));
        assertFalse(pan.update(MotionEvent.ACTION_DOWN,1,5,8));assertEquals(0,pan.deltaY,0);
        assertFalse(pan.update(MotionEvent.ACTION_CANCEL,1,5,8));
    }
}

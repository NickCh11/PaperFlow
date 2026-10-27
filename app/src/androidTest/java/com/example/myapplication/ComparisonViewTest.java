package com.example.myapplication;

import android.os.SystemClock;
import android.view.MotionEvent;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class ComparisonViewTest {
    private void touch(ComparisonView view, int action) {
        long now=SystemClock.uptimeMillis();
        MotionEvent event=MotionEvent.obtain(now,now,action,20,20,0);
        try { assertTrue(view.onTouchEvent(event)); } finally { event.recycle(); }
    }

    @Test public void holdingShowsOriginalAndReleaseRestoresResultWithoutTogglingAgain() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            ArrayList<Boolean> previews=new ArrayList<>();int[] clicks={0};
            ComparisonView view=new ComparisonView(InstrumentationRegistry.getInstrumentation().getTargetContext(),previews::add);
            view.setOnClickListener(v->clicks[0]++);
            touch(view,MotionEvent.ACTION_DOWN);assertTrue(view.isActivated());assertTrue(view.isPressed());
            touch(view,MotionEvent.ACTION_MOVE);touch(view,MotionEvent.ACTION_UP);
            assertEquals(Arrays.asList(true,false),previews);assertEquals(1,clicks[0]);
            assertFalse(view.isActivated());assertFalse(view.isPressed());
        });
    }

    @Test public void cancellationRestoresResultWithoutIssuingAClickAndNextHoldStillWorks() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            ArrayList<Boolean> previews=new ArrayList<>();int[] clicks={0};
            ComparisonView view=new ComparisonView(InstrumentationRegistry.getInstrumentation().getTargetContext(),previews::add);
            view.setOnClickListener(v->clicks[0]++);
            touch(view,MotionEvent.ACTION_DOWN);touch(view,MotionEvent.ACTION_CANCEL);
            assertEquals(0,clicks[0]);assertFalse(view.isActivated());assertFalse(view.isPressed());
            touch(view,MotionEvent.ACTION_DOWN);touch(view,MotionEvent.ACTION_UP);
            assertEquals(Arrays.asList(true,false,true,false),previews);assertEquals(1,clicks[0]);
        });
    }

    @Test public void accessibilityClicksToggleBothPreviewsAndDisabledControlCannotChangeThem() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            ArrayList<Boolean> previews=new ArrayList<>();
            ComparisonView view=new ComparisonView(InstrumentationRegistry.getInstrumentation().getTargetContext(),previews::add);
            assertTrue(view.performClick());assertTrue(view.isActivated());
            assertTrue(view.performClick());assertFalse(view.isActivated());
            view.setEnabled(false);assertFalse(view.performClick());
            long now=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,20,20,0);
            try { assertFalse(view.onTouchEvent(event)); } finally { event.recycle(); }
            assertEquals(Arrays.asList(true,false),previews);
        });
    }

    @Test public void orphanedReleaseDoesNotTriggerComparisonOrLeaveTheControlPressed() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            ArrayList<Boolean> previews=new ArrayList<>();
            ComparisonView view=new ComparisonView(InstrumentationRegistry.getInstrumentation().getTargetContext(),previews::add);
            long now=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(now,now,MotionEvent.ACTION_UP,20,20,0);
            try { assertFalse(view.onTouchEvent(event)); } finally { event.recycle(); }
            touch(view,MotionEvent.ACTION_CANCEL);assertTrue(previews.isEmpty());assertFalse(view.isPressed());
        });
    }
}

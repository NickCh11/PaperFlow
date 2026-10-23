package com.example.myapplication;
import org.junit.Test;
import static org.junit.Assert.*;

public class PageWindowTest {
    @Test public void everyPageIsReachableWithoutAnUnboundedWindow(){
        for(int total:new int[]{0,1,30,31,1000,5000}){
            int covered=0;
            for(int start=0;start<total;start+=PageWindow.SIZE){
                assertEquals(start,PageWindow.start(start,total));
                int end=PageWindow.end(start,total);
                assertTrue(end-start<=30);covered+=end-start;
            }
            assertEquals(total,covered);
        }
    }
    @Test public void removalOfLastPageClampsToAnExistingWindow(){
        assertEquals(30,PageWindow.start(60,60));
        assertEquals(0,PageWindow.start(-30,100));
        assertEquals(0,PageWindow.start(30,0));
    }
}

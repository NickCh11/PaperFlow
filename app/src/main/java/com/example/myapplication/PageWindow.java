package com.example.myapplication;

/** Bounded UI windows; selection/order can still cover the entire document. */
final class PageWindow {
    static final int SIZE = 30;
    static int start(int requested,int total) {
        if(total<=0)return 0;
        return Math.max(0,Math.min(requested,((total-1)/SIZE)*SIZE));
    }
    static int end(int start,int total) { return Math.min(total,start+SIZE); }
}

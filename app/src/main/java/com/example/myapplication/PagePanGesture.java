package com.example.myapplication;

import android.view.MotionEvent;

/** Shared two-finger translation; consume the remaining finger until the gesture ends. */
final class PagePanGesture {
    private boolean active;
    private float lastX,lastY;
    float deltaX,deltaY;
    boolean onTouch(MotionEvent event){
        int count=event.getPointerCount();float x=event.getX(),y=event.getY();
        if(count>1){x=(x+event.getX(1))/2;y=(y+event.getY(1))/2;}
        return update(event.getActionMasked(),count,x,y);
    }
    boolean update(int action,int pointers,float x,float y){
        deltaX=deltaY=0;
        if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){boolean consumed=active||pointers>1;active=false;return consumed;}
        if(pointers>1){
            if(active&&action==MotionEvent.ACTION_MOVE){deltaX=x-lastX;deltaY=y-lastY;}
            lastX=x;lastY=y;active=true;return true;
        }
        return active;
    }
}

package com.example.myapplication;

/** Analyzer-thread state: smooth small detector jitter, without hiding a genuinely moving page. */
final class LivePageOutline {
    private static final long HOLD_MISSING_MS=350, RESTART_GAP_MS=1500;
    private static final float CONFIRM_DISTANCE=.06f, REACQUIRE_DISTANCE=.12f, SMOOTHING=.35f;
    private float[] candidate, visible;
    private long lastSeen=Long.MIN_VALUE;

    float[] update(float[] detected,long now){
        if(lastSeen!=Long.MIN_VALUE&&(now<lastSeen||now-lastSeen>RESTART_GAP_MS))reset();
        if(!valid(detected)){
            candidate=null;
            if(lastSeen==Long.MIN_VALUE||now-lastSeen>HOLD_MISSING_MS)reset();
            return snapshot();
        }
        lastSeen=now;
        if(visible==null){
            if(candidate==null||distance(candidate,detected)>CONFIRM_DISTANCE){candidate=detected.clone();return null;}
            visible=new float[8];for(int i=0;i<8;i++)visible[i]=(candidate[i]+detected[i])*.5f;
            candidate=null;
        }else if(distance(visible,detected)>REACQUIRE_DISTANCE){
            visible=null;candidate=detected.clone();return null;
        }else{
            for(int i=0;i<8;i++)visible[i]+=(detected[i]-visible[i])*SMOOTHING;
        }
        return snapshot();
    }

    private float[] snapshot(){return visible==null?null:visible.clone();}
    private void reset(){candidate=visible=null;lastSeen=Long.MIN_VALUE;}
    private static float distance(float[] first,float[] second){float result=0;for(int i=0;i<8;i+=2)result=Math.max(result,(float)Math.hypot(first[i]-second[i],first[i+1]-second[i+1]));return result;}
    private static boolean valid(float[] quad){
        if(quad==null||quad.length!=8)return false;
        for(float value:quad)if(!Float.isFinite(value)||value<0||value>1)return false;
        float area=0;
        for(int i=0;i<4;i++){
            int next=(i+1)%4,after=(i+2)%4;
            float cross=(quad[next*2]-quad[i*2])*(quad[after*2+1]-quad[next*2+1])-(quad[next*2+1]-quad[i*2+1])*(quad[after*2]-quad[next*2]);
            if(cross<=0)return false;
            area+=quad[i*2]*quad[next*2+1]-quad[next*2]*quad[i*2+1];
        }
        return area>.02f;
    }
}

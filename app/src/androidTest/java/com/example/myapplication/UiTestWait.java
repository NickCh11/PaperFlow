package com.example.myapplication;

import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Wait for attached/enabled controls rather than assuming an idle queue drew a frame. */
final class UiTestWait {
    static void until(BooleanSupplier condition,String message){
        long deadline=SystemClock.elapsedRealtime()+15000;
        do{
            AtomicBoolean ready=new AtomicBoolean();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(()->ready.set(condition.getAsBoolean()));
            if(ready.get())return;
            SystemClock.sleep(30);
        }while(SystemClock.elapsedRealtime()<deadline);
        throw new AssertionError(message);
    }
}

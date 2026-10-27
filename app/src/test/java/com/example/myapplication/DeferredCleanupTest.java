package com.example.myapplication;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class DeferredCleanupTest {
    @Test public void callerDoesNotWaitForBusyCleanupAndRequestsCoalesce() throws Exception {
        ExecutorService owner = Executors.newSingleThreadExecutor();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        DeferredCleanup cleanup = new DeferredCleanup(owner, () -> {
            calls.incrementAndGet(); entered.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        try {
            caller.submit(cleanup::request).get(2,TimeUnit.SECONDS);
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            for(int i=0;i<100;i++) cleanup.request();
            assertEquals(1,calls.get());
            release.countDown(); owner.submit(()->{}).get(2,TimeUnit.SECONDS);
            cleanup.request(); owner.submit(()->{}).get(2,TimeUnit.SECONDS);
            assertEquals(2,calls.get());
        } finally { release.countDown(); owner.shutdownNow(); caller.shutdownNow(); }
    }

    @Test public void failedSubmissionDoesNotPermanentlySuppressCleanup() {
        List<Runnable> queued=new ArrayList<>();
        Executor executor=new Executor(){boolean first=true;public void execute(Runnable r){if(first){first=false;throw new RejectedExecutionException();}queued.add(r);}};
        DeferredCleanup cleanup=new DeferredCleanup(executor,()->{});
        try { cleanup.request(); fail(); } catch(RejectedExecutionException expected) { }
        cleanup.request(); assertEquals(1,queued.size()); queued.get(0).run();
    }
}

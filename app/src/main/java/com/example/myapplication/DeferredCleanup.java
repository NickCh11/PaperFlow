package com.example.myapplication;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coalesces cleanup requests without waiting for a busy resource owner. */
final class DeferredCleanup {
    private final Executor executor;
    private final Runnable cleanup;
    private final AtomicBoolean pending = new AtomicBoolean();

    DeferredCleanup(Executor executor, Runnable cleanup) {
        this.executor = executor;
        this.cleanup = cleanup;
    }

    void request() {
        if (!pending.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try { cleanup.run(); }
                finally { pending.set(false); }
            });
        } catch (RuntimeException rejected) {
            pending.set(false);
            throw rejected;
        }
    }
}

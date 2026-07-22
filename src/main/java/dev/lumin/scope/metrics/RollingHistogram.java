package dev.lumin.scope.metrics;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A histogram over a sliding window, built from N sub-histograms rotated in
 * turn.
 *
 * <p>Diagnostics need recent behaviour, not lifetime behaviour: a proxy that was
 * unhealthy this morning and is fine now should read as fine. Rotating buckets
 * gives that with no per-sample bookkeeping and no unbounded memory.
 */
public final class RollingHistogram {

    private final Histogram[] windows;
    private final AtomicInteger cursor = new AtomicInteger();

    public RollingHistogram(int windowCount) {
        this.windows = new Histogram[Math.max(2, windowCount)];
        for (int i = 0; i < windows.length; i++) {
            windows[i] = new Histogram();
        }
    }

    public void record(long millis) {
        windows[cursor.get() % windows.length].record(millis);
    }

    /** Advances to the next window and clears it. Call once per window period. */
    public void rotate() {
        int next = (cursor.incrementAndGet()) % windows.length;
        windows[next].reset();
    }

    /** A merged view of every window. The caller owns the returned instance. */
    public Histogram snapshot() {
        Histogram merged = new Histogram();
        for (Histogram window : windows) {
            merged.mergeFrom(window);
        }
        return merged;
    }
}

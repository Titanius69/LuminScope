package dev.lumin.scope.metrics;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JVM-level readings, sampled on a timer.
 *
 * <p>Garbage collection is tracked as pause counts rather than total time,
 * because what hurts a proxy is one 400 ms stop-the-world pause, not four
 * hundred 1 ms ones. Direct memory is included because Netty buffer leaks are
 * both real and unusually hard to diagnose without it: the heap looks fine, the
 * process keeps growing, and nothing in a normal profiler explains it.
 */
public final class JvmMetrics {

    /** A GC pause longer than this is worth counting separately. */
    public static final long LONG_PAUSE_MILLIS = 50L;

    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
    private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
    private final List<BufferPoolMXBean> bufferPools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);

    private final AtomicLong lastGcCount = new AtomicLong(-1);
    private final AtomicLong lastGcTime = new AtomicLong(-1);
    private final AtomicLong longPausesInWindow = new AtomicLong();
    private final AtomicLong gcMillisInWindow = new AtomicLong();
    private final AtomicLong gcCountInWindow = new AtomicLong();

    /** Samples the GC counters and folds the delta into the current window. */
    public void sample() {
        long count = 0;
        long time = 0;
        for (GarbageCollectorMXBean collector : collectors) {
            long collections = collector.getCollectionCount();
            long millis = collector.getCollectionTime();
            if (collections > 0) {
                count += collections;
            }
            if (millis > 0) {
                time += millis;
            }
        }

        long previousCount = lastGcCount.getAndSet(count);
        long previousTime = lastGcTime.getAndSet(time);
        if (previousCount < 0) {
            return;
        }

        long deltaCount = count - previousCount;
        long deltaTime = time - previousTime;
        if (deltaCount <= 0) {
            return;
        }
        gcCountInWindow.addAndGet(deltaCount);
        gcMillisInWindow.addAndGet(deltaTime);

        // The management beans give totals, not individual pauses. Attributing
        // the whole delta to the average pause is the honest approximation: if
        // the mean pause in this interval crossed the threshold, at least one
        // real pause did too.
        if (deltaTime / deltaCount >= LONG_PAUSE_MILLIS) {
            longPausesInWindow.addAndGet(deltaCount);
        }
    }

    /** Clears the per-window GC counters. Called once per reporting window. */
    public void rotate() {
        longPausesInWindow.set(0);
        gcMillisInWindow.set(0);
        gcCountInWindow.set(0);
    }

    public long longPauses() {
        return longPausesInWindow.get();
    }

    public long gcMillis() {
        return gcMillisInWindow.get();
    }

    public long gcCount() {
        return gcCountInWindow.get();
    }

    public long heapUsed() {
        return memory.getHeapMemoryUsage().getUsed();
    }

    public long heapMax() {
        MemoryUsage usage = memory.getHeapMemoryUsage();
        return usage.getMax() > 0 ? usage.getMax() : usage.getCommitted();
    }

    /** Heap usage as a fraction of the maximum. */
    public double heapFraction() {
        long max = heapMax();
        return max <= 0 ? 0 : (double) heapUsed() / max;
    }

    public long directMemoryUsed() {
        for (BufferPoolMXBean pool : bufferPools) {
            if (pool.getName().equals("direct")) {
                return pool.getMemoryUsed();
            }
        }
        return -1;
    }

    public long directBufferCount() {
        for (BufferPoolMXBean pool : bufferPools) {
            if (pool.getName().equals("direct")) {
                return pool.getCount();
            }
        }
        return -1;
    }

    public int threadCount() {
        return threads.getThreadCount();
    }

    public int peakThreadCount() {
        return threads.getPeakThreadCount();
    }

    /** System load average per core, or -1 where the platform does not report it. */
    public double loadPerCore() {
        double load = os.getSystemLoadAverage();
        int cores = Math.max(1, os.getAvailableProcessors());
        return load < 0 ? -1 : load / cores;
    }

    public int availableProcessors() {
        return os.getAvailableProcessors();
    }

    public long uptimeMillis() {
        return ManagementFactory.getRuntimeMXBean().getUptime();
    }
}

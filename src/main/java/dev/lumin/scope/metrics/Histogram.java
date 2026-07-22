package dev.lumin.scope.metrics;

/**
 * A fixed-bucket latency histogram.
 *
 * <p>Averages lie about latency. If the median server switch takes 200 ms and
 * the 99th percentile takes eight seconds, one player in a hundred is convinced
 * the network is dead, and the mean will happily report "0.3 s, all fine". So
 * everything here is recorded as percentiles.
 *
 * <p>Resolution is 1 ms up to one second and 100 ms up to sixty seconds, which
 * costs about 6 KB per histogram and is far more precision than any operational
 * decision needs. Recording is a bounds check and an increment, so it is cheap
 * enough to call on hot paths.
 */
public final class Histogram {

    private static final int FINE_BUCKETS = 1000;      // 0..999 ms, 1 ms each
    private static final int COARSE_BUCKETS = 590;     // 1..60 s, 100 ms each
    private static final int OVERFLOW = FINE_BUCKETS + COARSE_BUCKETS;

    private final long[] buckets = new long[OVERFLOW + 1];
    private long count;
    private long sum;
    private long max;

    public synchronized void record(long millis) {
        if (millis < 0) {
            return;
        }
        buckets[bucketOf(millis)]++;
        count++;
        sum += millis;
        if (millis > max) {
            max = millis;
        }
    }

    public synchronized long count() {
        return count;
    }

    public synchronized long max() {
        return max;
    }

    public synchronized double mean() {
        return count == 0 ? -1 : (double) sum / count;
    }

    /** Percentile in milliseconds, or -1 when there is no data. */
    public synchronized long percentile(double fraction) {
        if (count == 0) {
            return -1;
        }
        long target = (long) Math.ceil(fraction * count);
        long seen = 0;
        for (int i = 0; i < buckets.length; i++) {
            seen += buckets[i];
            if (seen >= target) {
                return valueOf(i);
            }
        }
        return max;
    }

    public synchronized void reset() {
        java.util.Arrays.fill(buckets, 0L);
        count = 0;
        sum = 0;
        max = 0;
    }

    /** Folds another histogram into this one. Used when merging rolling windows. */
    public synchronized void mergeFrom(Histogram other) {
        long[] snapshot;
        long otherCount;
        long otherSum;
        long otherMax;
        synchronized (other) {
            snapshot = other.buckets.clone();
            otherCount = other.count;
            otherSum = other.sum;
            otherMax = other.max;
        }
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] += snapshot[i];
        }
        count += otherCount;
        sum += otherSum;
        max = Math.max(max, otherMax);
    }

    private static int bucketOf(long millis) {
        if (millis < FINE_BUCKETS) {
            return (int) millis;
        }
        long coarse = (millis - FINE_BUCKETS) / 100;
        if (coarse >= COARSE_BUCKETS) {
            return OVERFLOW;
        }
        return FINE_BUCKETS + (int) coarse;
    }

    private static long valueOf(int bucket) {
        if (bucket < FINE_BUCKETS) {
            return bucket;
        }
        if (bucket >= OVERFLOW) {
            return 60_000L;
        }
        return FINE_BUCKETS + (bucket - FINE_BUCKETS) * 100L;
    }
}

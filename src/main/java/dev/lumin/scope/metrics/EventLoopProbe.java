package dev.lumin.scope.metrics;

import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures how long a task waits before the proxy's I/O threads get to it.
 *
 * <p>This is the single most useful number on a proxy. Everything the proxy does
 * happens on a small pool of event loop threads, so if one of them is stuck in a
 * synchronous database call, the whole network stalls and every other metric
 * looks innocent. Scheduling delay makes that visible directly.
 *
 * <p>It is measured, not read: a no-op task is submitted to each loop and the
 * gap between submission and execution is recorded. That is deliberate. Velocity
 * does not expose its event loop groups publicly, and any measurement that
 * depends on internal fields staying put will break on an upgrade. Here the
 * reflection is only used to <em>find</em> the executors; if it fails, the probe
 * degrades to the proxy scheduler instead and says so, rather than throwing.
 */
public final class EventLoopProbe {

    /** Beyond this, assume the probe task was lost and re-arm. */
    private static final long STUCK_RESET_MILLIS = 30_000L;

    /** In-flight probes are never queued, so a stall cannot pile up work. */
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final AtomicLong probeStartedAt = new AtomicLong();

    private final ProxyServer proxy;
    private final Logger logger;
    private final RollingHistogram lag = new RollingHistogram(5);
    private final AtomicLong lastLagMillis = new AtomicLong(-1);

    private List<ScheduledExecutorService> loops = List.of();
    private volatile boolean direct;
    private volatile Object pluginInstance;

    public EventLoopProbe(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    /** The proxy scheduler needs a plugin instance for the fallback path. */
    public void bindPlugin(Object pluginInstance) {
        this.pluginInstance = pluginInstance;
    }

    /**
     * Whether the probe found the real I/O executors. When false the readings
     * come from the proxy scheduler, which is still useful for spotting a
     * saturated JVM but does not isolate the network threads.
     */
    public boolean direct() {
        return direct;
    }

    public int loopCount() {
        return loops.size();
    }

    public long lastLagMillis() {
        return lastLagMillis.get();
    }

    public Histogram snapshot() {
        return lag.snapshot();
    }

    public void rotate() {
        lag.rotate();
    }

    /** Locates the I/O executors once at startup. Safe to call again on reload. */
    public void discover() {
        try {
            List<ScheduledExecutorService> found = new ArrayList<>();
            Map<Object, Boolean> seen = new IdentityHashMap<>();
            collect(proxy, found, seen, 0);
            if (!found.isEmpty()) {
                loops = List.copyOf(found);
                direct = true;
                logger.info("Event loop probe attached to {} I/O thread(s).", loops.size());
                return;
            }
        } catch (RuntimeException ex) {
            logger.debug("Event loop discovery failed", ex);
        }
        loops = List.of();
        direct = false;
        logger.info("Event loop probe could not reach the proxy's I/O executors on this build. "
                + "Falling back to scheduler timing, which still detects a saturated JVM.");
    }

    /**
     * Walks the object graph looking for Netty event executors.
     *
     * <p>Netty's {@code EventExecutorGroup} is both a {@link ScheduledExecutorService}
     * and an {@link Iterable} of its children, which is enough to identify one
     * without compiling against Netty at all.
     */
    private void collect(Object target, List<ScheduledExecutorService> out, Map<Object, Boolean> seen, int depth) {
        if (target == null || depth > 2 || seen.putIfAbsent(target, Boolean.TRUE) != null) {
            return;
        }
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(target);
                } catch (ReflectiveOperationException | RuntimeException ex) {
                    continue;
                }
                if (value == null) {
                    continue;
                }
                if (value instanceof ScheduledExecutorService executor && value instanceof Iterable<?> children) {
                    for (Object child : children) {
                        if (child instanceof ScheduledExecutorService childExecutor) {
                            out.add(childExecutor);
                        }
                    }
                    if (out.isEmpty()) {
                        out.add(executor);
                    }
                } else if (value.getClass().getName().startsWith("com.velocitypowered")) {
                    collect(value, out, seen, depth + 1);
                }
            }
            type = type.getSuperclass();
        }
    }

    /**
     * Submits one probe round. Returns immediately; the reading lands when the
     * loops get around to running the task, which is the entire point.
     */
    public void probe() {
        long now = System.nanoTime();
        if (!inFlight.compareAndSet(false, true)) {
            // The previous probe has not come back. That outstanding wait is
            // itself the measurement, so record it rather than queueing more work.
            long outstanding = (now - probeStartedAt.get()) / 1_000_000L;
            lag.record(outstanding);
            lastLagMillis.set(outstanding);
            if (outstanding > STUCK_RESET_MILLIS) {
                // Either a task was lost or the loop is wedged. Re-arm so the
                // probe keeps reporting instead of going silent forever.
                inFlight.set(false);
            }
            return;
        }

        probeStartedAt.set(now);
        int pending = Math.max(1, loops.size());
        AtomicInteger remaining = new AtomicInteger(pending);
        AtomicLong worst = new AtomicLong();

        Runnable onComplete = () -> {
            long observed = System.nanoTime() - now;
            worst.accumulateAndGet(observed, Math::max);
            if (remaining.decrementAndGet() == 0) {
                long millis = worst.get() / 1_000_000L;
                lag.record(millis);
                lastLagMillis.set(millis);
                inFlight.set(false);
            }
        };

        if (loops.isEmpty()) {
            if (pluginInstance == null) {
                inFlight.set(false);
                return;
            }
            proxy.getScheduler().buildTask(pluginInstance, onComplete).schedule();
            return;
        }

        for (ScheduledExecutorService loop : loops) {
            try {
                loop.execute(onComplete);
            } catch (RuntimeException ex) {
                // A shutting-down loop still has to decrement, or the probe latches.
                onComplete.run();
            }
        }
    }
}

package dev.lumin.scope.watchdog;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Finds out which plugin is stalling the proxy's I/O threads.
 *
 * <p>When an event loop stops making progress, every player on every backend
 * feels it, and the proxy log says nothing at all. The usual outcome is an
 * operator disabling plugins one at a time over a week. This does it in one
 * step: when the scheduling delay crosses the threshold, the I/O thread stacks
 * are sampled and the topmost frame belonging to a plugin is recorded.
 *
 * <p>Attribution works by mapping each loaded plugin's main class package to its
 * id. It is not perfect — a plugin calling into a shared library that blocks
 * will be blamed for the library's behaviour — but that is the right answer
 * anyway: the plugin chose to make that call on this thread.
 *
 * <p>Sampling only happens while the proxy is already stalled, so the cost is
 * paid exactly when it does not matter and never when it does.
 */
public final class BlockingCallDetector {

    /** One plugin caught on an I/O thread, with how often and where. */
    public record Suspect(String pluginId, String location, long hits, Instant lastSeen) {
    }

    private static final String[] IGNORED_PREFIXES = {
            "java.", "javax.", "jdk.", "sun.", "io.netty.", "com.velocitypowered.",
            "net.kyori.", "org.slf4j.", "com.google.", "kotlin."
    };

    private final ProxyServer proxy;
    private final Map<String, String> packageToPlugin = new LinkedHashMap<>();
    private final Map<String, AtomicLong> hits = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastSeen = new ConcurrentHashMap<>();
    private final AtomicLong stallCount = new AtomicLong();

    private volatile String selfPackage = "";

    public BlockingCallDetector(ProxyServer proxy) {
        this.proxy = proxy;
    }

    /** Builds the package-to-plugin map. Call once every plugin has loaded. */
    public void indexPlugins() {
        packageToPlugin.clear();
        for (PluginContainer container : proxy.getPluginManager().getPlugins()) {
            container.getInstance().ifPresent(instance -> {
                String packageName = instance.getClass().getPackageName();
                if (!packageName.isEmpty()) {
                    packageToPlugin.put(packageName, container.getDescription().getId());
                }
            });
        }
        selfPackage = getClass().getPackageName().split("\\.watchdog")[0];
    }

    public long stalls() {
        return stallCount.get();
    }

    /**
     * Samples the I/O threads. Call only when a stall has already been observed.
     */
    public void sample() {
        stallCount.incrementAndGet();
        for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
            if (!isIoThread(entry.getKey())) {
                continue;
            }
            String culprit = attribute(entry.getValue());
            if (culprit != null) {
                hits.computeIfAbsent(culprit, key -> new AtomicLong()).incrementAndGet();
                lastSeen.put(culprit, Instant.now());
            }
        }
    }

    /** The worst offenders, most hits first. */
    public List<Suspect> suspects(int limit) {
        List<Suspect> out = new ArrayList<>();
        for (Map.Entry<String, AtomicLong> entry : hits.entrySet()) {
            String key = entry.getKey();
            int separator = key.indexOf('|');
            out.add(new Suspect(
                    separator < 0 ? "unknown" : key.substring(0, separator),
                    separator < 0 ? key : key.substring(separator + 1),
                    entry.getValue().get(),
                    lastSeen.getOrDefault(key, Instant.EPOCH)
            ));
        }
        out.sort(Comparator.comparingLong(Suspect::hits).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    public void reset() {
        hits.clear();
        lastSeen.clear();
        stallCount.set(0);
    }

    private static boolean isIoThread(Thread thread) {
        String name = thread.getName();
        return name.startsWith("Netty")
                || name.contains("epoll")
                || name.contains("kqueue")
                || name.startsWith("nioEventLoopGroup");
    }

    /**
     * Walks the stack top-down and returns the first frame belonging to a
     * plugin, formatted as {@code pluginId|Class.method:line}.
     */
    private String attribute(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if (isIgnored(className) || className.startsWith(selfPackage)) {
                continue;
            }
            String pluginId = pluginFor(className);
            if (pluginId == null) {
                continue;
            }
            String simpleName = className.substring(className.lastIndexOf('.') + 1);
            return pluginId + "|" + simpleName + "." + frame.getMethodName() + ":" + frame.getLineNumber();
        }
        return null;
    }

    private String pluginFor(String className) {
        String best = null;
        String bestPackage = "";
        for (Map.Entry<String, String> entry : packageToPlugin.entrySet()) {
            String packageName = entry.getKey();
            if (className.startsWith(packageName + ".") && packageName.length() > bestPackage.length()) {
                best = entry.getValue();
                bestPackage = packageName;
            }
        }
        return best;
    }

    private static boolean isIgnored(String className) {
        for (String prefix : IGNORED_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}

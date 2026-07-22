package dev.lumin.scope.export;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.lumin.scope.config.ScopeConfig;
import dev.lumin.scope.metrics.EventLoopProbe;
import dev.lumin.scope.metrics.Histogram;
import dev.lumin.scope.metrics.JvmMetrics;
import dev.lumin.scope.metrics.LatencyTracker;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * OpenMetrics endpoint for networks that already run Prometheus.
 *
 * <p>Written by hand rather than through a metrics library. The output is a few
 * dozen lines of text; pulling in Micrometer and its dependency tree to produce
 * it would add more weight to the jar than the entire rest of the plugin.
 *
 * <p>Binds to localhost by default and supports a bearer token, because a
 * metrics endpoint reveals a fair amount about a network's internals.
 */
public final class PrometheusExporter {

    private final ProxyServer proxy;
    private final Logger logger;
    private final JvmMetrics jvm;
    private final EventLoopProbe eventLoop;
    private final LatencyTracker latency;

    private HttpServer server;
    private ExecutorService executor;
    private volatile String bearerToken = "";

    public PrometheusExporter(ProxyServer proxy,
                              Logger logger,
                              JvmMetrics jvm,
                              EventLoopProbe eventLoop,
                              LatencyTracker latency) {
        this.proxy = proxy;
        this.logger = logger;
        this.jvm = jvm;
        this.eventLoop = eventLoop;
        this.latency = latency;
    }

    public void start(ScopeConfig.Prometheus config) {
        stop();
        this.bearerToken = config.bearerToken();
        try {
            server = HttpServer.create(new InetSocketAddress(config.bindAddress(), config.port()), 16);
            executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "LuminScope Metrics");
                thread.setDaemon(true);
                return thread;
            });
            server.setExecutor(executor);
            server.createContext(config.path(), this::handle);
            server.start();
            logger.info("Prometheus metrics on http://{}:{}{}",
                    config.bindAddress(), config.port(), config.path());
        } catch (IOException ex) {
            logger.error("Could not bind the metrics endpoint to {}:{}. Everything else keeps working.",
                    config.bindAddress(), config.port(), ex);
            stop();
        }
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException ex) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!bearerToken.isBlank()) {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            if (authorization == null || !authorization.equals("Bearer " + bearerToken)) {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
                return;
            }
        }
        byte[] body = render().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; version=0.0.4; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
        exchange.close();
    }

    private String render() {
        StringBuilder out = new StringBuilder(4096);

        gauge(out, "luminscope_players_online", "Players connected to the proxy",
                proxy.getAllPlayers().size());
        gauge(out, "luminscope_servers_registered", "Backends registered on the proxy",
                proxy.getAllServers().size());

        gauge(out, "luminscope_heap_used_bytes", "Proxy heap in use", jvm.heapUsed());
        gauge(out, "luminscope_heap_max_bytes", "Proxy heap maximum", jvm.heapMax());
        gauge(out, "luminscope_direct_memory_bytes", "Netty off-heap buffer usage",
                jvm.directMemoryUsed());
        gauge(out, "luminscope_threads", "Live thread count", jvm.threadCount());
        gauge(out, "luminscope_gc_pause_millis_window", "Garbage collection time in the current window",
                jvm.gcMillis());
        gauge(out, "luminscope_gc_long_pauses_window",
                "Collections in the current window averaging over the long-pause threshold",
                jvm.longPauses());

        Histogram lag = eventLoop.snapshot();
        percentiles(out, "luminscope_event_loop_lag_millis",
                "Delay before a task runs on the proxy I/O threads", lag);
        gauge(out, "luminscope_event_loop_probe_direct",
                "1 when timing comes from the real I/O executors", eventLoop.direct() ? 1 : 0);

        percentiles(out, "luminscope_client_rtt_millis",
                "Player to proxy round trip", latency.clientRtt());

        out.append("# HELP luminscope_backend_rtt_millis Proxy to backend round trip\n");
        out.append("# TYPE luminscope_backend_rtt_millis gauge\n");
        for (String server : latency.backends().keySet()) {
            Histogram histogram = latency.backendRtt(server);
            if (histogram.count() == 0) {
                continue;
            }
            labelled(out, "luminscope_backend_rtt_millis", server, "0.5", histogram.percentile(0.50D));
            labelled(out, "luminscope_backend_rtt_millis", server, "0.95", histogram.percentile(0.95D));
            labelled(out, "luminscope_backend_rtt_millis", server, "0.99", histogram.percentile(0.99D));
        }

        percentiles(out, "luminscope_server_switch_millis",
                "Total time for a player to move between backends",
                latency.switchTimeOverall(LatencyTracker.Phase.TOTAL));

        return out.toString();
    }

    private static void gauge(StringBuilder out, String name, String help, double value) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\n');
        out.append("# TYPE ").append(name).append(" gauge\n");
        out.append(name).append(' ').append(format(value)).append('\n');
    }

    private static void percentiles(StringBuilder out, String name, String help, Histogram histogram) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\n');
        out.append("# TYPE ").append(name).append(" summary\n");
        out.append(name).append("{quantile=\"0.5\"} ").append(format(histogram.percentile(0.50D))).append('\n');
        out.append(name).append("{quantile=\"0.95\"} ").append(format(histogram.percentile(0.95D))).append('\n');
        out.append(name).append("{quantile=\"0.99\"} ").append(format(histogram.percentile(0.99D))).append('\n');
        out.append(name).append("_count ").append(histogram.count()).append('\n');
    }

    private static void labelled(StringBuilder out, String name, String server, String quantile, double value) {
        out.append(name)
                .append("{server=\"").append(escape(server)).append("\",quantile=\"").append(quantile).append("\"} ")
                .append(format(value)).append('\n');
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "");
    }

    private static String format(double value) {
        if (value < 0) {
            return "NaN";
        }
        return String.format(Locale.ROOT, "%.0f", value);
    }
}

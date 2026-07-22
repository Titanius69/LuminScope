package dev.lumin.scope.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.lumin.scope.LuminScopePlugin;
import dev.lumin.scope.diagnosis.Finding;
import dev.lumin.scope.metrics.Histogram;
import dev.lumin.scope.metrics.JvmMetrics;
import dev.lumin.scope.metrics.LatencyTracker;
import dev.lumin.scope.watchdog.BlockingCallDetector;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * The operator-facing command tree.
 *
 * <p>The default view leads with conclusions, not numbers. The numbers are one
 * subcommand away for anyone who wants them, but the first thing someone sees
 * when they type this during an incident should be a sentence they can act on.
 */
public final class ScopeCommand {

    public static final String PERMISSION = "luminscope.admin";

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final LuminScopePlugin plugin;

    public ScopeCommand(LuminScopePlugin plugin) {
        this.plugin = plugin;
    }

    public BrigadierCommand build() {
        LiteralCommandNode<CommandSource> root = LiteralArgumentBuilder.<CommandSource>literal("luminscope")
                .requires(source -> source.hasPermission(PERMISSION))
                .executes(this::diagnose)
                .then(LiteralArgumentBuilder.<CommandSource>literal("latency")
                        .executes(this::latency))
                .then(LiteralArgumentBuilder.<CommandSource>literal("jvm")
                        .executes(this::jvm))
                .then(LiteralArgumentBuilder.<CommandSource>literal("blocking")
                        .executes(this::blocking))
                .then(LiteralArgumentBuilder.<CommandSource>literal("healing")
                        .executes(this::healingStatus)
                        .then(LiteralArgumentBuilder.<CommandSource>literal("restore")
                                .then(RequiredArgumentBuilder.<CommandSource, String>argument("server", StringArgumentType.word())
                                        .suggests(this::suggestServers)
                                        .executes(this::restoreServer))))
                .then(LiteralArgumentBuilder.<CommandSource>literal("reset")
                        .executes(this::reset))
                .then(LiteralArgumentBuilder.<CommandSource>literal("reload")
                        .executes(this::reload))
                .build();
        return new BrigadierCommand(root);
    }

    // ------------------------------------------------------------- subcommands

    private int diagnose(CommandContext<CommandSource> context) {
        CommandSource source = context.getSource();
        header(source, "Diagnosis");

        List<Finding> findings = plugin.latestFindings();
        if (findings.isEmpty()) {
            send(source, "<gray>No analysis has run yet. Try again in a few seconds.</gray>");
            return 1;
        }
        for (Finding finding : findings) {
            send(source, " " + severityTag(finding.severity()) + " <white>"
                    + escape(finding.headline()) + "</white>");
            send(source, "   <gray>" + escape(finding.detail()) + "</gray>");
            send(source, "   <aqua>\u2192</aqua> <gray>" + escape(finding.action()) + "</gray>");
        }
        send(source, "<dark_gray>Numbers behind this: /luminscope latency, /luminscope jvm</dark_gray>");
        return 1;
    }

    private int latency(CommandContext<CommandSource> context) {
        CommandSource source = context.getSource();
        header(source, "Latency");

        LatencyTracker latency = plugin.latency();
        line(source, "Player \u2192 proxy", latency.clientRtt());
        line(source, "Proxy \u2192 backends", latency.backendRttOverall());
        line(source, "Server switch (total)", latency.switchTimeOverall(LatencyTracker.Phase.TOTAL));
        line(source, "Server switch (connect)", latency.switchTimeOverall(LatencyTracker.Phase.CONNECT));

        send(source, "<dark_gray>Per backend:</dark_gray>");
        for (RegisteredServer server : plugin.proxy().getAllServers()) {
            String name = server.getServerInfo().getName();
            Histogram histogram = latency.backendRtt(name);
            if (histogram.count() == 0) {
                send(source, "  <gray>" + escape(name) + "</gray> <dark_gray>no samples yet</dark_gray>");
                continue;
            }
            String failures = String.format(Locale.ROOT, "%.0f%% failed",
                    latency.pingFailureRate(name) * 100);
            send(source, "  <white>" + escape(name) + "</white> <gray>p50 "
                    + histogram.percentile(0.50D) + "ms \u00b7 p95 " + histogram.percentile(0.95D)
                    + "ms \u00b7 p99 " + histogram.percentile(0.99D) + "ms \u00b7 " + failures + "</gray>");
        }
        return 1;
    }

    private int jvm(CommandContext<CommandSource> context) {
        CommandSource source = context.getSource();
        header(source, "Proxy internals");

        JvmMetrics metrics = plugin.jvm();
        send(source, " <gray>Heap</gray> <white>" + (metrics.heapUsed() / 1_048_576) + " MB / "
                + (metrics.heapMax() / 1_048_576) + " MB</white> <gray>("
                + String.format(Locale.ROOT, "%.0f%%", metrics.heapFraction() * 100) + ")</gray>");
        send(source, " <gray>Off-heap buffers</gray> <white>"
                + (metrics.directMemoryUsed() < 0 ? "unavailable"
                        : (metrics.directMemoryUsed() / 1_048_576) + " MB across "
                          + metrics.directBufferCount() + " buffers") + "</white>");
        send(source, " <gray>GC this window</gray> <white>" + metrics.gcCount() + " collections, "
                + metrics.gcMillis() + " ms, " + metrics.longPauses() + " long</white>");
        send(source, " <gray>Threads</gray> <white>" + metrics.threadCount()
                + "</white> <gray>(peak " + metrics.peakThreadCount() + ")</gray>");
        send(source, " <gray>Load per core</gray> <white>"
                + (metrics.loadPerCore() < 0 ? "unavailable"
                        : String.format(Locale.ROOT, "%.2f", metrics.loadPerCore())) + "</white>");

        Histogram lag = plugin.eventLoop().snapshot();
        send(source, " <gray>I/O thread delay</gray> <white>p50 " + lag.percentile(0.50D)
                + "ms \u00b7 p99 " + lag.percentile(0.99D) + "ms \u00b7 max " + lag.max() + "ms</white>");
        send(source, " <gray>Timing source</gray> <white>"
                + (plugin.eventLoop().direct()
                        ? plugin.eventLoop().loopCount() + " I/O thread(s), direct"
                        : "proxy scheduler (approximate)") + "</white>");
        return 1;
    }

    private int blocking(CommandContext<CommandSource> context) {
        CommandSource source = context.getSource();
        header(source, "Blocking calls");

        BlockingCallDetector detector = plugin.blocking();
        if (detector.stalls() == 0) {
            send(source, "<green>No stalls recorded since the last reset.</green>");
            return 1;
        }
        send(source, "<gray>" + detector.stalls() + " stall(s) sampled.</gray>");

        List<BlockingCallDetector.Suspect> suspects =
                detector.suspects(plugin.config().blocking().maxSuspects());
        if (suspects.isEmpty()) {
            send(source, "<gray>No plugin code was on the I/O threads during those stalls. "
                    + "That points at garbage collection or the host, not a plugin.</gray>");
            return 1;
        }
        for (BlockingCallDetector.Suspect suspect : suspects) {
            send(source, " <red>" + escape(suspect.pluginId()) + "</red> <gray>caught "
                    + suspect.hits() + "x at</gray> <white>" + escape(suspect.location()) + "</white>");
        }
        send(source, "<dark_gray>These plugins ran code on the network threads while the proxy was "
                + "stalled. That work belongs on the scheduler.</dark_gray>");
        return 1;
    }

    private int healingStatus(CommandContext<CommandSource> context) {
        CommandSource source = context.getSource();
        header(source, "Self-healing");

        if (!plugin.selfHealing().enabled()) {
            send(source, "<gray>Disabled. Enable it in config.conf under self-healing.</gray>");
            return 1;
        }
        plugin.selfHealing().statuses().forEach((server, status) -> {
            String colour = switch (status) {
                case HEALTHY -> "<green>";
                case RAMPING -> "<yellow>";
                case DRAINED -> "<red>";
            };
            send(source, " <white>" + escape(server) + "</white> " + colour
                    + status.name().toLowerCase(Locale.ROOT) + "</" + colour.substring(1));
        });
        return 1;
    }

    private int restoreServer(CommandContext<CommandSource> context) {
        String server = context.getArgument("server", String.class);
        plugin.selfHealing().report(server, true);
        for (int i = 0; i < plugin.config().selfHealing().recoveryChecks(); i++) {
            plugin.selfHealing().report(server, true);
        }
        send(context.getSource(), "<green>" + escape(server)
                + " has been put back into rotation manually.</green>");
        return 1;
    }

    private int reset(CommandContext<CommandSource> context) {
        plugin.resetCounters();
        send(context.getSource(), "<green>Counters cleared. Measurement starts fresh from now.</green>");
        return 1;
    }

    private int reload(CommandContext<CommandSource> context) {
        if (plugin.reload()) {
            send(context.getSource(), "<green>Configuration reloaded.</green>");
        } else {
            send(context.getSource(), "<red>Reload failed. The previous configuration is still active. "
                    + "The console has the parse error.</red>");
        }
        return 1;
    }

    private CompletableFuture<Suggestions> suggestServers(CommandContext<CommandSource> context,
                                                          SuggestionsBuilder builder) {
        plugin.proxy().getAllServers()
                .forEach(server -> builder.suggest(server.getServerInfo().getName()));
        return builder.buildFuture();
    }

    // ----------------------------------------------------------------- helpers

    private static void line(CommandSource source, String label, Histogram histogram) {
        if (histogram.count() == 0) {
            send(source, " <gray>" + label + "</gray> <dark_gray>no samples yet</dark_gray>");
            return;
        }
        send(source, " <gray>" + label + "</gray> <white>p50 " + histogram.percentile(0.50D)
                + "ms \u00b7 p95 " + histogram.percentile(0.95D)
                + "ms \u00b7 p99 " + histogram.percentile(0.99D) + "ms</white> <dark_gray>("
                + histogram.count() + " samples)</dark_gray>");
    }

    private static String severityTag(Finding.Severity severity) {
        return switch (severity) {
            case CRITICAL -> "<red>[!]</red>";
            case WARNING -> "<yellow>[~]</yellow>";
            case INFO -> "<dark_gray>[i]</dark_gray>";
        };
    }

    private static void header(CommandSource source, String title) {
        send(source, "<dark_gray>\u2500\u2500\u2500 <white><bold>LuminScope</bold></white> \u00b7 "
                + title + " \u2500\u2500\u2500</dark_gray>");
    }

    private static String escape(String input) {
        return input.replace("<", "\\<");
    }

    private static void send(CommandSource source, String miniMessage) {
        source.sendMessage(MINI.deserialize(miniMessage));
    }
}

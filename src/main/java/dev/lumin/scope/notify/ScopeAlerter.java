package dev.lumin.scope.notify;

import dev.lumin.scope.config.ScopeConfig;
import dev.lumin.scope.diagnosis.Finding;
import org.slf4j.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Posts findings to a Discord webhook.
 *
 * <p>Each finding id has its own cooldown, so an ongoing problem is reported
 * once and then left alone until it either clears or the cooldown expires.
 * Repeating the same alert every thirty seconds is the fastest way to teach a
 * team to ignore the channel.
 */
public final class ScopeAlerter {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final Logger logger;
    private final Map<String, Instant> lastSent = new ConcurrentHashMap<>();
    private volatile ScopeConfig config;

    public ScopeAlerter(ScopeConfig config, Logger logger) {
        this.config = config;
        this.logger = logger;
    }

    public void applyConfig(ScopeConfig config) {
        this.config = config;
    }

    /** Sends anything new that meets the configured severity floor. */
    public void publish(List<Finding> findings) {
        ScopeConfig.Alerts settings = config.alerts();
        if (!settings.enabled() || settings.webhookUrl().isBlank()) {
            return;
        }
        Finding.Severity floor = parseSeverity(settings.minSeverity());
        Instant now = Instant.now();
        Duration cooldown = Duration.ofMinutes(settings.cooldownMinutes());

        for (Finding finding : findings) {
            if (finding.severity().ordinal() < floor.ordinal()) {
                continue;
            }
            Instant previous = lastSent.get(finding.id());
            if (previous != null && previous.plus(cooldown).isAfter(now)) {
                continue;
            }
            lastSent.put(finding.id(), now);
            send(settings, finding);
        }
    }

    /** Free-text notice used by the self-healing actions. */
    public void notice(String message) {
        ScopeConfig.Alerts settings = config.alerts();
        if (!settings.enabled() || settings.webhookUrl().isBlank()) {
            return;
        }
        post(settings, "{\"username\":\"LuminScope\",\"content\":" + quote(message) + "}");
    }

    private void send(ScopeConfig.Alerts settings, Finding finding) {
        int color = switch (finding.severity()) {
            case CRITICAL -> 0x9A2036;
            case WARNING -> 0xA9721A;
            case INFO -> 0x5A6772;
        };
        StringBuilder json = new StringBuilder();
        json.append("{\"username\":\"LuminScope\"");
        if (!settings.mention().isBlank() && finding.severity() == Finding.Severity.CRITICAL) {
            json.append(",\"content\":").append(quote(settings.mention()));
        }
        json.append(",\"embeds\":[{")
                .append("\"title\":").append(quote(finding.headline()))
                .append(",\"description\":").append(quote(finding.detail()))
                .append(",\"color\":").append(color)
                .append(",\"fields\":[{\"name\":\"What to do next\",\"value\":")
                .append(quote(finding.action())).append(",\"inline\":false}]")
                .append(",\"timestamp\":").append(quote(Instant.now().toString()))
                .append("}]}");
        post(settings, json.toString());
    }

    private void post(ScopeConfig.Alerts settings, String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(settings.webhookUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "LuminScope")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .thenAccept(response -> {
                        if (response.statusCode() >= 300) {
                            logger.warn("Discord webhook rejected the alert with HTTP {}", response.statusCode());
                        }
                    })
                    .exceptionally(error -> {
                        logger.warn("Could not reach the Discord webhook: {}", error.getMessage());
                        return null;
                    });
        } catch (IllegalArgumentException ex) {
            logger.warn("The configured alert webhook URL is not a valid URL");
        }
    }

    private static Finding.Severity parseSeverity(String raw) {
        for (Finding.Severity severity : Finding.Severity.values()) {
            if (severity.name().equalsIgnoreCase(raw)) {
                return severity;
            }
        }
        return Finding.Severity.WARNING;
    }

    /** Minimal JSON string escaping; the payloads here are short and known. */
    private static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 16);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.append('"').toString();
    }
}

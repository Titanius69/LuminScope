package dev.lumin.scope.diagnosis;

/**
 * One conclusion drawn from the metrics.
 *
 * <p>Raw numbers are not the product. Most operators cannot read a Grafana
 * dashboard and should not have to; what they need is a sentence naming the
 * likely cause and the next thing to try. A finding is that sentence.
 */
public record Finding(Severity severity, String id, String headline, String detail, String action) {

    public enum Severity {
        INFO,
        WARNING,
        CRITICAL;

        public String id() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public static Finding critical(String id, String headline, String detail, String action) {
        return new Finding(Severity.CRITICAL, id, headline, detail, action);
    }

    public static Finding warning(String id, String headline, String detail, String action) {
        return new Finding(Severity.WARNING, id, headline, detail, action);
    }

    public static Finding info(String id, String headline, String detail, String action) {
        return new Finding(Severity.INFO, id, headline, detail, action);
    }
}

package id.co.blackheart.service.alert;

/**
 * Three-level severity gate for operational alerts.
 *
 * <ul>
 *   <li>{@link #INFO} — heartbeat / informational. Not emailed by default;
 *       Telegram only if {@code app.alerts.telegram.info-enabled=true}.</li>
 *   <li>{@link #WARN} — degraded but non-urgent. Telegram + email.</li>
 *   <li>{@link #CRITICAL} — human action required within the hour:
 *       kill-switch trip, ingest stalled, data missing, verdict drift on
 *       a PROMOTED strategy.</li>
 * </ul>
 */
public enum AlertSeverity {
    INFO,
    WARN,
    CRITICAL;

    public boolean atLeast(AlertSeverity other) {
        return this.ordinal() >= other.ordinal();
    }
}

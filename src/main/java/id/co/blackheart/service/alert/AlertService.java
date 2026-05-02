package id.co.blackheart.service.alert;

import com.fasterxml.jackson.databind.JsonNode;
import id.co.blackheart.model.AlertEvent;
import id.co.blackheart.repository.AlertEventRepository;
import id.co.blackheart.service.notification.TelegramNotificationService;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 7.1 — central dispatch for operational alerts.
 *
 * <p>Every call to {@link #raise} writes one {@code alert_event} row,
 * regardless of dedup. Dedup only suppresses the outbound Telegram / email
 * fan-out — it never suppresses the persisted record, so the timeline and
 * flap rate stay observable.
 *
 * <p>Why a dedicated service instead of letting callers ping Telegram
 * directly: every operational alert needs the same scaffolding (severity
 * gate, dedup, dual sink, persistence). Centralising it means the
 * liveness watchdog (7.3), risk-guard (7.2), P&amp;L deviation (7.4), and
 * verdict-drift cron (7.5) all agree on what gets sent and how often.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AlertService {

    private final AlertEventRepository repository;
    private final TelegramNotificationService telegram;
    private final JavaMailSender mailSender;

    @Value("${app.alerts.email.enabled:false}")
    private boolean emailEnabled;

    @Value("${app.alerts.email.to:}")
    private String emailTo;

    @Value("${app.alerts.email.from:${app.mail.from:noreply@meridian-edge.com}}")
    private String emailFrom;

    @Value("${app.alerts.email.from-name:Blackheart Ops}")
    private String emailFromName;

    @Value("${app.alerts.telegram.enabled:true}")
    private boolean telegramEnabled;

    @Value("${app.alerts.telegram.info-enabled:false}")
    private boolean telegramInfoEnabled;

    @Value("${app.alerts.dedup.ttl-minutes:60}")
    private long dedupTtlMinutes;

    /**
     * In-memory dedup cache. Single-JVM only — fine because the trading
     * JVM is the primary alert source; the research JVM raises rarely
     * (Phase 7.5 nightly verdict drift) and a duplicate fan-out across
     * JVM boundaries is acceptable. Bounded by hand below to prevent
     * unbounded growth from a misbehaving caller.
     */
    private final ConcurrentHashMap<String, Instant> dedupCache = new ConcurrentHashMap<>();

    private static final int DEDUP_MAX_ENTRIES = 10_000;

    /**
     * Raise an alert. Always persists; outbound send is gated by severity,
     * sink config, and dedup.
     *
     * @param severity   gate for outbound fan-out
     * @param kind       stable code (e.g. {@code KILL_SWITCH_TRIPPED}) for filtering
     * @param message    human-readable, ~one sentence
     * @param context    optional structured context (entity ids, metrics) — JSONB
     * @param dedupeKey  optional. When present, repeated raises within
     *                   {@code app.alerts.dedup.ttl-minutes} suppress fan-out
     *                   (but the row is still persisted with suppressed=true).
     */
    public void raise(AlertSeverity severity,
                      String kind,
                      String message,
                      JsonNode context,
                      String dedupeKey) {
        boolean hasKey = dedupeKey != null && !dedupeKey.isBlank();
        // Atomic check-and-set: two threads racing the same key — the first
        // to win compute() gets isWinner=true and fans out, the rest see
        // suppressed=true. Without this, both observe stale lastSeen=null,
        // both fan out, both stamp suppressed=false → duplicate Telegrams.
        boolean isWinner = hasKey && claimDedupeSlot(dedupeKey);
        boolean suppressed = hasKey && !isWinner;

        Boolean sentTelegram = null;
        Boolean sentEmail = null;

        if (!suppressed) {
            if (shouldSendTelegram(severity)) {
                sentTelegram = trySendTelegram(severity, kind, message);
            }
            if (shouldSendEmail(severity)) {
                sentEmail = trySendEmail(severity, kind, message);
            }
        }

        AlertEvent event = AlertEvent.builder()
                .alertEventId(UUID.randomUUID())
                .severity(severity.name())
                .kind(kind)
                .message(message)
                .context(context)
                .dedupeKey(dedupeKey)
                .suppressed(suppressed)
                .sentTelegram(sentTelegram)
                .sentEmail(sentEmail)
                .createdAt(LocalDateTime.now())
                .build();

        try {
            repository.save(event);
        } catch (RuntimeException e) {
            // Persistence failure must never break the caller — the alert
            // already went out (or was deliberately suppressed). Log and
            // move on, same philosophy as AuditService.
            log.error("Failed to persist alert_event kind={} severity={}", kind, severity, e);
        }
    }

    public void raise(AlertSeverity severity, String kind, String message) {
        raise(severity, kind, message, null, null);
    }

    public void raise(AlertSeverity severity, String kind, String message, String dedupeKey) {
        raise(severity, kind, message, null, dedupeKey);
    }

    private boolean shouldSendTelegram(AlertSeverity severity) {
        if (!telegramEnabled) return false;
        if (severity == AlertSeverity.INFO) return telegramInfoEnabled;
        return true;
    }

    private boolean shouldSendEmail(AlertSeverity severity) {
        if (!emailEnabled) return false;
        if (emailTo == null || emailTo.isBlank()) return false;
        return severity.atLeast(AlertSeverity.WARN);
    }

    private Boolean trySendTelegram(AlertSeverity severity, String kind, String message) {
        try {
            String body = "<b>[" + severity.name() + "] " + escapeHtml(kind) + "</b>\n"
                    + escapeHtml(message);
            telegram.sendMessage(body);
            return Boolean.TRUE;
        } catch (RuntimeException e) {
            log.warn("Telegram alert send failed kind={}", kind, e);
            return Boolean.FALSE;
        }
    }

    private Boolean trySendEmail(AlertSeverity severity, String kind, String message) {
        MimeMessage mime = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(mime, false, StandardCharsets.UTF_8.name());
            helper.setFrom(new InternetAddress(emailFrom, emailFromName, StandardCharsets.UTF_8.name()));
            for (String addr : emailTo.split(",")) {
                String trimmed = addr.trim();
                if (!trimmed.isBlank()) helper.addTo(trimmed);
            }
            helper.setSubject("[" + severity.name() + "] BLACKHEART — " + kind);
            helper.setText(message, false);
            mailSender.send(mime);
            return Boolean.TRUE;
        } catch (MessagingException | UnsupportedEncodingException | RuntimeException e) {
            log.warn("Email alert send failed kind={}", kind, e);
            return Boolean.FALSE;
        }
    }

    /**
     * Atomic claim. Returns {@code true} when this caller is the first within
     * the TTL window — caller fans out and the slot is now held. Returns
     * {@code false} when another raiser still holds the slot — caller is
     * suppressed. Implemented via {@link ConcurrentHashMap#compute} so the
     * read-and-write happen under the bin lock; concurrent raises with the
     * same key see one winner, never two.
     */
    private boolean claimDedupeSlot(String key) {
        Instant now = Instant.now();
        Duration ttl = Duration.ofMinutes(dedupTtlMinutes);
        boolean[] won = new boolean[]{false};
        dedupCache.compute(key, (k, lastSeen) -> {
            if (lastSeen != null && Duration.between(lastSeen, now).compareTo(ttl) < 0) {
                return lastSeen;
            }
            won[0] = true;
            return now;
        });
        if (won[0] && dedupCache.size() > DEDUP_MAX_ENTRIES) {
            evictExpired();
            // Hard cap: if eviction couldn't trim the map (every entry still
            // within TTL), drop the oldest entries by timestamp until we're
            // back under the limit. Better to occasionally re-fan-out a stale
            // key than to grow the map without bound.
            if (dedupCache.size() > DEDUP_MAX_ENTRIES) {
                forceTrim();
            }
        }
        return won[0];
    }

    private void evictExpired() {
        Duration ttl = Duration.ofMinutes(dedupTtlMinutes);
        Instant cutoff = Instant.now().minus(ttl);
        Iterator<Map.Entry<String, Instant>> it = dedupCache.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isBefore(cutoff)) it.remove();
        }
    }

    private void forceTrim() {
        int target = DEDUP_MAX_ENTRIES * 9 / 10;
        int toDrop = dedupCache.size() - target;
        if (toDrop <= 0) return;
        // Cheap: snapshot timestamps, find threshold, evict below it. Not
        // strictly LRU under concurrent writes but only fires under the
        // pathological case where a caller overflows TTL with fresh keys.
        Instant[] times = dedupCache.values().toArray(new Instant[0]);
        java.util.Arrays.sort(times);
        if (toDrop >= times.length) return;
        Instant threshold = times[toDrop];
        Iterator<Map.Entry<String, Instant>> it = dedupCache.entrySet().iterator();
        while (it.hasNext() && dedupCache.size() > target) {
            if (it.next().getValue().compareTo(threshold) < 0) it.remove();
        }
        log.warn("AlertService dedup map force-trimmed under load (size now {})", dedupCache.size());
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}

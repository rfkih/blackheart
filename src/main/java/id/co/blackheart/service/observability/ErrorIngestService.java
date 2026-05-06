package id.co.blackheart.service.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.model.ErrorLog;
import id.co.blackheart.repository.ErrorLogRepository;
import id.co.blackheart.service.notification.TelegramNotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists captured errors and dispatches Telegram alerts for severe ones.
 *
 * <p>Called from {@code DbErrorAppender} on a dedicated worker thread (not the
 * Logback thread, not a request thread). Methods are {@code @Async} on top of
 * that so the appender's worker is never held up by a slow DB or Telegram
 * call: the bridge submits and returns.
 *
 * <p>Dedup model: every save tries to find an open row for the fingerprint;
 * if found, bumps {@code occurrence_count}/{@code last_seen_at}; otherwise
 * inserts. The race where two events for an unseen fingerprint hit at the
 * same time is caught via {@link DataIntegrityViolationException} on the
 * partial unique index — second insert retries as a bump.
 *
 * <p>Telegram throttle: a fingerprint sends at first occurrence and at count
 * crossings (10, 100, 1000). Anything else is silent — the row updates, the
 * dashboard reflects it, but the user is not paged again.
 */
@Service
@Slf4j
public class ErrorIngestService {

    private static final int[] ALERT_COUNT_CROSSINGS = {10, 100, 1000};

    private final ErrorLogRepository repository;
    private final SeverityClassifier classifier;
    private final TelegramNotificationService telegram;
    private final ObjectMapper objectMapper;
    private final String jvm;

    public ErrorIngestService(ErrorLogRepository repository,
                              SeverityClassifier classifier,
                              TelegramNotificationService telegram,
                              ObjectMapper objectMapper,
                              @Value("${blackheart.jvm.name:trading}") String jvm) {
        this.repository = repository;
        this.classifier = classifier;
        this.telegram = telegram;
        this.objectMapper = objectMapper;
        this.jvm = jvm;
    }

    @Async("taskExecutor")
    public void ingest(ErrorEvent event) {
        try {
            saveOrBump(event);
        } catch (Exception e) {
            // The error logger must never recursively log into itself — drop
            // the persistence failure to a single warn, no stack.
            log.warn("[error-ingest] failed to persist event from {}: {}",
                    event.loggerName(), e.getMessage());
        }
    }

    /** New transaction so the appender path never piggybacks on a request transaction. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void saveOrBump(ErrorEvent event) {
        String severity = classifier.classify(event.loggerName(), event.exceptionClass());

        Optional<ErrorLog> open = repository.findOpenByFingerprint(event.fingerprint());
        if (open.isPresent()) {
            ErrorLog row = open.get();
            int newCount = row.getOccurrenceCount() + 1;
            repository.bumpOccurrence(row.getErrorId(), event.occurredAt());
            maybeAlert(row, newCount, severity, event);
            return;
        }

        ErrorLog row = buildRow(event, severity);
        try {
            row = repository.saveAndFlush(row);
        } catch (DataIntegrityViolationException race) {
            // Concurrent insert won the unique-index race — fall back to bump.
            Optional<ErrorLog> after = repository.findOpenByFingerprint(event.fingerprint());
            if (after.isPresent()) {
                ErrorLog winner = after.get();
                repository.bumpOccurrence(winner.getErrorId(), event.occurredAt());
                maybeAlert(winner, winner.getOccurrenceCount() + 1, severity, event);
            }
            return;
        }
        maybeAlert(row, 1, severity, event);
    }

    private ErrorLog buildRow(ErrorEvent event, String severity) {
        Map<String, String> mdc = event.mdc() == null ? Map.of() : event.mdc();
        // Frontend/middleware reports stamp their own jvm ("frontend",
        // "middleware") via the REST controller. Logback-sourced events leave
        // it null so we fall back to this JVM's configured name.
        String stamp = !StringUtils.hasText(event.jvm()) ? jvm : event.jvm();
        return ErrorLog.builder()
                .occurredAt(event.occurredAt())
                .lastSeenAt(event.occurredAt())
                .jvm(stamp)
                .loggerName(truncate(event.loggerName(), 255))
                .threadName(truncate(event.threadName(), 120))
                .level(event.level())
                .message(event.message() == null ? "" : event.message())
                .exceptionClass(truncate(event.exceptionClass(), 255))
                .stackTrace(event.stackTrace())
                .mdc(objectMapper.valueToTree(mdc))
                .fingerprint(event.fingerprint())
                .occurrenceCount(1)
                .severity(severity)
                .status("NEW")
                .build();
    }

    private void maybeAlert(ErrorLog row, int newCount, String severity, ErrorEvent event) {
        if (!shouldAlert(severity, newCount)) return;

        String text = formatAlert(row, newCount, severity, event);
        try {
            telegram.sendMessage(text);
            UUID id = row.getErrorId();
            if (id != null) {
                repository.markNotified(id, LocalDateTime.now(), new String[]{"telegram"});
            }
        } catch (Exception e) {
            log.warn("[error-ingest] telegram dispatch failed: {}", e.getMessage());
        }
    }

    private boolean shouldAlert(String severity, int newCount) {
        if ("CRITICAL".equals(severity)) {
            // Every CRITICAL first occurrence pages; subsequent CRITICALs at
            // count thresholds so a stuck bug doesn't silently recur 10000x.
            if (newCount == 1) return true;
            for (int t : ALERT_COUNT_CROSSINGS) if (newCount == t) return true;
            return false;
        }
        if ("HIGH".equals(severity)) {
            // HIGH is quieter — first occurrence + 100/1000 only.
            if (newCount == 1) return true;
            return newCount == 100 || newCount == 1000;
        }
        return false;
    }

    private String formatAlert(ErrorLog row, int newCount, String severity, ErrorEvent event) {
        String header = newCount == 1
                ? "🚨 <b>" + severity + "</b> error (new)"
                : "🚨 <b>" + severity + "</b> error — " + newCount + "× occurrences";
        String exClass = row.getExceptionClass() == null ? "(no exception)" : row.getExceptionClass();
        String msg = row.getMessage() == null ? "" : row.getMessage();
        if (msg.length() > 500) msg = msg.substring(0, 500) + "…";
        // Read the source stamp off the persisted row, not the host JVM —
        // a frontend-sourced CRITICAL must page as "frontend", not "trading".
        String source = row.getJvm() == null ? jvm : row.getJvm();
        return header
                + "\nJVM: <code>" + source + "</code>"
                + "\nLogger: <code>" + row.getLoggerName() + "</code>"
                + "\nException: <code>" + exClass + "</code>"
                + "\nMessage: " + escape(msg)
                + "\nFingerprint: <code>" + event.fingerprint() + "</code>";
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}

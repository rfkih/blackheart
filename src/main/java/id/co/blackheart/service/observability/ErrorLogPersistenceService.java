package id.co.blackheart.service.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.model.ErrorLog;
import id.co.blackheart.repository.ErrorLogRepository;
import id.co.blackheart.service.notification.TelegramNotificationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Separated from ErrorIngestService so @Transactional(REQUIRES_NEW) is applied
 * through Spring's proxy rather than via a self-invocation that bypasses it.
 */
@Service
@Slf4j
class ErrorLogPersistenceService {

    private static final int[] ALERT_COUNT_CROSSINGS = {10, 100, 1000};

    private final ErrorLogRepository repository;
    private final SeverityClassifier classifier;
    private final TelegramNotificationService telegram;
    private final ObjectMapper objectMapper;
    private final String jvm;

    ErrorLogPersistenceService(ErrorLogRepository repository,
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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveOrBump(ErrorEvent event) {
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
            if (newCount == 1) return true;
            for (int t : ALERT_COUNT_CROSSINGS) if (newCount == t) return true;
            return false;
        }
        if ("HIGH".equals(severity)) {
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

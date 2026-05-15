package id.co.blackheart.service.observability;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Plain data carrier for a captured error. Built by the Logback appender from
 * an {@code ILoggingEvent}, or by the REST controller from a frontend /
 * middleware error report. Immutable.
 *
 * <p>{@code jvm} is the source-of-record stamp written into {@code error_log.jvm}.
 * The Logback appender path passes {@code null} so {@code ErrorIngestService}
 * falls back to {@code blackheart.jvm.name} (= "trading"/"research"). The REST
 * controller path passes the explicit source ("frontend", "middleware") so a
 * frontend-sourced row is never mis-attributed to the JVM that proxied it.
 */
public record ErrorEvent(
        LocalDateTime occurredAt,
        String loggerName,
        String threadName,
        String level,
        String message,
        String exceptionClass,
        String stackTrace,
        Map<String, String> mdc,
        /** Hash of (loggerName + exceptionClass + first 5 stack-frame package.class). */
        String fingerprint,
        /** Source stamp; null = fall back to the JVM's blackheart.jvm.name property. */
        String jvm
) { }

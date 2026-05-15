package id.co.blackheart.service.observability;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

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

    private final ErrorLogPersistenceService persistence;

    public ErrorIngestService(ErrorLogPersistenceService persistence) {
        this.persistence = persistence;
    }

    @Async("taskExecutor")
    public void ingest(ErrorEvent event) {
        try {
            persistence.saveOrBump(event);
        } catch (Exception e) {
            // The error logger must never recursively log into itself — drop
            // the persistence failure to a single warn, no stack.
            log.warn("[error-ingest] failed to persist event from {}: {}",
                    event.loggerName(), e.getMessage());
        }
    }
}

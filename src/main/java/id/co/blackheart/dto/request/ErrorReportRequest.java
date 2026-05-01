package id.co.blackheart.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Frontend (and Phase C middleware) error report payload — POSTed to
 * /api/v1/errors. The controller turns this into an
 * {@link id.co.blackheart.service.observability.ErrorEvent} and hands off to
 * {@code ErrorIngestService}, which dedupes via the same fingerprint partial
 * unique index that the JVM logback path uses.
 *
 * <p>All fields are user-supplied. Sizes are capped to keep an abusive client
 * from filling the column with megabytes of payload — Postgres TEXT columns
 * accept anything but the row goes into the trading DB so it pays to stay
 * reasonable. Truncation policy lives in the service so a too-long field is
 * not a 400; we want the row written anyway.
 *
 * <p>Required: {@code message}. Everything else is optional but recommended
 * — the more context the agent has, the better its triage.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrorReportRequest {

    /**
     * Source bucket: "frontend" or "middleware". Stamps {@code error_log.jvm}.
     * Defaults to "frontend" when omitted because that is the dominant caller.
     */
    @Size(max = 20)
    private String source;

    /**
     * Optional logical logger name — e.g. "frontend.trade.NewOrderForm".
     * Drives severity classification (see {@code SeverityClassifier}). Falling
     * back to "frontend.unknown" keeps the prefix-routing rules intact when
     * the client forgets to set it.
     */
    @Size(max = 255)
    private String loggerName;

    /** "ERROR" / "WARN" — only ERROR-level reports are persisted. */
    @Size(max = 10)
    private String level;

    @NotBlank
    @Size(max = 5000, message = "message must be at most 5000 characters")
    private String message;

    /**
     * Exception class / error name — JS errors expose this via {@code e.name}
     * (e.g. "TypeError", "ChunkLoadError"). Used for severity rules + the
     * fingerprint hash.
     */
    @Size(max = 255)
    private String exceptionClass;

    /** Stack trace as captured by the browser. Truncated to 16 KB on save. */
    @Size(max = 32_000)
    private String stackTrace;

    /**
     * Optional pre-computed fingerprint. Browsers can compute it client-side
     * (more stable for SPA route-changes), but if absent the server hashes
     * loggerName + exceptionClass + the first lines of stackTrace.
     */
    @Size(max = 64)
    private String fingerprint;

    /**
     * Free-form context map — URL, route, user agent, app version, build SHA,
     * userId if known. Stored verbatim into {@code error_log.mdc} (JSONB).
     * Keep keys flat; nested values get JSON-stringified by Jackson.
     */
    private Map<String, String> mdc;
}

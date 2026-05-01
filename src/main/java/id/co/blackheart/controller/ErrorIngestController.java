package id.co.blackheart.controller;

import id.co.blackheart.dto.request.ErrorReportRequest;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.observability.ErrorEvent;
import id.co.blackheart.service.observability.ErrorIngestService;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;

/**
 * Phase B endpoint for the cross-cutting error pipeline.
 *
 * <p>Public — no auth required. The browser fires errors before the user
 * reaches a logged-in state (login-page crash, register flow, expired token
 * redirect), so gating this with the JWT filter would silently drop the most
 * useful reports. Spam protection comes from two places:
 * <ol>
 *   <li>The fingerprint dedup on {@code error_log} — repeats UPSERT into one
 *       row, they don't multiply.</li>
 *   <li>The ingest path is async + bounded; a flood drops the newest events
 *       at the {@code DbErrorAppender} queue boundary, not at the request
 *       layer.</li>
 * </ol>
 *
 * <p>Phase C will add a {@code source=middleware} branch — same DTO, same
 * controller, same downstream service.
 */
@RestController
@RequestMapping("/api/v1/errors")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "ErrorIngestController",
     description = "Frontend + middleware error capture (Phase B/C)")
public class ErrorIngestController {

    private static final String DEFAULT_SOURCE = "frontend";
    private static final String DEFAULT_LOGGER = "frontend.unknown";
    private static final int FINGERPRINT_STACK_LINES = 5;

    private final ErrorIngestService ingestService;

    @PostMapping
    @Operation(
            summary = "Submit a frontend / middleware error report",
            description = "Async — returns 202 once queued. Repeats with the same fingerprint UPSERT into the existing open row instead of creating duplicates."
    )
    public ResponseEntity<ResponseDto> ingest(@Valid @RequestBody ErrorReportRequest request) {
        String source = blankOr(request.getSource(), DEFAULT_SOURCE);
        if (!"frontend".equals(source) && !"middleware".equals(source)) {
            // Anything outside the two known buckets gets normalised to
            // "frontend" — better to capture the row than reject it. A bad
            // source value is itself a finding.
            source = DEFAULT_SOURCE;
        }

        String loggerName = blankOr(request.getLoggerName(), DEFAULT_LOGGER);
        String level      = blankOr(request.getLevel(), "ERROR");
        String exClass    = blankOrNull(request.getExceptionClass());
        String stack      = blankOrNull(request.getStackTrace());
        String message    = request.getMessage();

        String fingerprint = blankOr(
                request.getFingerprint(),
                computeFingerprint(loggerName, exClass, stack));

        Map<String, String> mdc = request.getMdc() == null ? Map.of() : request.getMdc();

        ErrorEvent event = new ErrorEvent(
                LocalDateTime.now(),
                loggerName,
                // Threads in a browser are a fiction; stamp the source as the
                // "thread" so the dashboard column stays informative.
                source,
                level,
                message,
                exClass,
                stack,
                mdc,
                fingerprint,
                source
        );

        ingestService.ingest(event);

        return ResponseEntity.accepted().body(ResponseDto.builder()
                .responseCode(HttpStatus.ACCEPTED.value() + ResponseCode.SUCCESS.getCode())
                .data(Map.of(
                        "fingerprint", fingerprint,
                        "queued", true
                ))
                .build());
    }

    private static String blankOr(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }

    private static String blankOrNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Server-side fingerprint when the client didn't supply one. Hashes
     * loggerName + exceptionClass + first {@value #FINGERPRINT_STACK_LINES}
     * lines of the stack — line-based instead of frame-based because we
     * have no structured frames here, just the JS error stack string.
     *
     * <p>SHA-256 hex; same column shape as {@code DbErrorAppender}'s
     * fingerprint so the partial unique index dedups across sources.
     */
    private static String computeFingerprint(String loggerName, String exClass, String stack) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(loggerName == null ? "?" : loggerName).append('|');
        sb.append(exClass == null ? "?" : exClass).append('|');
        if (stack != null) {
            String[] lines = stack.split("\\R", FINGERPRINT_STACK_LINES + 1);
            int limit = Math.min(FINGERPRINT_STACK_LINES, lines.length);
            for (int i = 0; i < limit; i++) {
                sb.append(lines[i].trim()).append('|');
            }
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException impossible) {
            // SHA-256 is mandatory on every JRE.
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}

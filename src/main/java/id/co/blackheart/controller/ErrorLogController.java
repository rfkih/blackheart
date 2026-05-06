package id.co.blackheart.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.model.ErrorLog;
import id.co.blackheart.repository.ErrorLogRepository;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Admin-only inbox for the cross-cutting {@code error_log}. Reads the same
 * fingerprint-deduped rows that {@code DbErrorAppender} and the frontend
 * ingest controller write, and exposes a status flip so the operator can
 * close out triaged rows from the UI instead of running SQL.
 *
 * <p>Stack traces are intentionally omitted from the list response — they
 * are heavy and only needed when drilling in. The single-row endpoint
 * returns the full row including the stack.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/error-log")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "ErrorLogController", description = "Admin inbox for error_log")
public class ErrorLogController {

    private static final int MAX_PAGE_SIZE = 200;
    private static final Set<String> ALLOWED_STATUS = Set.of(
            "NEW", "INVESTIGATING", "RESOLVED", "IGNORED", "WONT_FIX");
    private static final Set<String> TERMINAL_STATUS = Set.of(
            "RESOLVED", "IGNORED", "WONT_FIX");
    private static final Set<String> ALLOWED_SEVERITY = Set.of(
            "CRITICAL", "HIGH", "MEDIUM", "LOW");

    /**
     * MDC keys whose values are likely to carry secrets if any code path ever
     * stuffs them in. We can't audit every call site that puts things into
     * MDC, so the inbox redacts on the way out as defense in depth.
     */
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "lastSeenAt", "occurredAt", "occurrenceCount");

    private static final Set<String> MDC_REDACT_KEYS = Set.of(
            "authorization", "password", "token", "secret",
            "apikey", "api_key", "cookie", "set-cookie",
            "x-api-key", "x-auth-token", "x-orch-token");
    private static final String REDACTED = "[REDACTED]";

    private final ErrorLogRepository errorLogRepository;
    private final JwtService jwtService;

    @GetMapping
    @Operation(
            summary = "List error_log rows with optional filters, search, and sort",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ResponseDto> list(
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String jvm,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "lastSeenAt,desc") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize, parseSort(sort));

        String sevFilter    = normaliseUpper(severity, ALLOWED_SEVERITY);
        String statusFilter = normaliseUpper(status, ALLOWED_STATUS);
        String jvmFilter    = (jvm == null || jvm.isBlank()) ? null : jvm.trim();
        String searchLike   = (search == null || search.isBlank())
                ? null : "%" + search.trim().toLowerCase() + "%";

        Specification<ErrorLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (sevFilter != null)    predicates.add(cb.equal(root.get("severity"), sevFilter));
            if (statusFilter != null) predicates.add(cb.equal(root.get("status"), statusFilter));
            if (jvmFilter != null)    predicates.add(cb.equal(root.get("jvm"), jvmFilter));
            if (since != null)        predicates.add(cb.greaterThanOrEqualTo(root.get("lastSeenAt"), since));
            if (searchLike != null)   predicates.add(cb.or(
                    cb.like(cb.lower(root.get("message")), searchLike),
                    cb.like(cb.lower(root.get("loggerName")), searchLike),
                    cb.like(cb.lower(root.get("exceptionClass")), searchLike)
            ));
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        Page<ErrorLog> rows = errorLogRepository.findAll(spec, pageable);

        List<Map<String, Object>> content = rows.getContent().stream()
                .map(ErrorLogController::toListRow)
                .toList();

        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(Map.of(
                        "content", content,
                        "page", rows.getNumber(),
                        "size", rows.getSize(),
                        "totalElements", rows.getTotalElements(),
                        "totalPages", rows.getTotalPages()
                ))
                .build());
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Single error_log row including stack trace and MDC",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ResponseDto> get(@PathVariable("id") UUID id) {
        ErrorLog row = errorLogRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("error_log row not found"));
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(toDetailRow(row))
                .build());
    }

    @GetMapping("/open-count")
    @Operation(
            summary = "Count of open (NEW/INVESTIGATING) rows at or above minSeverity",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ResponseDto> openCount(
            @RequestParam(required = false) String minSeverity
    ) {
        Integer rank = severityRank(minSeverity);
        long count = errorLogRepository.countOpen(rank);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(Map.of("count", count))
                .build());
    }

    @PatchMapping("/{id}/status")
    @Transactional
    @Operation(
            summary = "Flip the status of an error_log row (operator triage action)",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ResponseDto> updateStatus(
            @PathVariable("id") UUID id,
            @RequestBody StatusUpdateRequest body,
            HttpServletRequest httpRequest
    ) {
        if (body == null || body.status() == null || body.status().isBlank()) {
            throw new IllegalArgumentException("status is required");
        }
        String next = body.status().trim().toUpperCase();
        if (!ALLOWED_STATUS.contains(next)) {
            throw new IllegalArgumentException("Unknown status: " + next);
        }
        boolean terminal = TERMINAL_STATUS.contains(next);
        LocalDateTime resolvedAt = terminal ? LocalDateTime.now() : null;
        String resolvedBy = terminal ? resolveActor(httpRequest) : null;

        // Reopening a terminal row collides with the partial unique index on
        // (fingerprint WHERE status IN NEW/INVESTIGATING) when a fresh open
        // row already exists for the same fingerprint. Pre-check and 409 so
        // the operator gets a meaningful error instead of a generic 500.
        if (!terminal) {
            ErrorLog existing = errorLogRepository.findById(id)
                    .orElseThrow(() -> new IllegalArgumentException("error_log row not found"));
            boolean wasTerminal = TERMINAL_STATUS.contains(existing.getStatus());
            if (wasTerminal) {
                String fp = existing.getFingerprint();
                if (fp != null) {
                    errorLogRepository.findOpenByFingerprint(fp)
                            .filter(r -> !r.getErrorId().equals(id))
                            .ifPresent(r -> {
                                throw new IllegalStateException(
                                        "Cannot reopen — fingerprint already has an open row: "
                                                + r.getErrorId());
                            });
                }
            }
        }

        int rows = errorLogRepository.updateStatus(id, next, resolvedAt, resolvedBy);
        if (rows == 0) {
            throw new IllegalArgumentException("error_log row not found");
        }

        // LinkedHashMap (not Map.of) because resolvedAt / resolvedBy are null
        // when transitioning to a non-terminal status, and Map.of rejects null
        // values with NullPointerException.
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorId", id.toString());
        data.put("status", next);
        data.put("resolvedAt", resolvedAt == null ? null : resolvedAt.toString());
        data.put("resolvedBy", resolvedBy);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(data)
                .build());
    }

    private static Sort parseSort(String sort) {
        if (sort == null || sort.isBlank())
            return Sort.by(Sort.Direction.DESC, "lastSeenAt");
        String[] parts = sort.split(",", 2);
        String field = parts[0].trim();
        if (!ALLOWED_SORT_FIELDS.contains(field)) field = "lastSeenAt";
        Sort.Direction dir = (parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim()))
                ? Sort.Direction.ASC : Sort.Direction.DESC;
        return Sort.by(dir, field);
    }

    private String resolveActor(HttpServletRequest request) {
        // Best-effort actor stamp. The operator's UUID is what we have on
        // hand — pretty names live in the user table and are not worth a
        // join here. Falls back to "admin" if the JWT can't be parsed.
        try {
            String header = request.getHeader("Authorization");
            if (header != null && header.startsWith("Bearer ")) {
                String token = header.substring("Bearer ".length());
                UUID userId = jwtService.extractUserId(token);
                if (userId != null) return userId.toString();
            }
        } catch (RuntimeException e) {
            log.warn("Failed to extract user identity from token — proceeding as anonymous: {}", e.getMessage());
            // fall through
        }
        return "admin";
    }

    private static String normaliseUpper(String raw, Set<String> allowed) {
        if (raw == null || raw.isBlank()) return null;
        String upper = raw.trim().toUpperCase();
        return allowed.contains(upper) ? upper : null;
    }

    private static Integer severityRank(String s) {
        if (s == null || s.isBlank()) return null;
        return switch (s.trim().toUpperCase()) {
            case "LOW" -> 1;
            case "MEDIUM" -> 2;
            case "HIGH" -> 3;
            case "CRITICAL" -> 4;
            default -> null;
        };
    }

    private static Map<String, Object> toListRow(ErrorLog e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("errorId", e.getErrorId());
        row.put("severity", e.getSeverity());
        row.put("status", e.getStatus());
        row.put("jvm", e.getJvm());
        row.put("loggerName", e.getLoggerName());
        row.put("threadName", e.getThreadName());
        row.put("level", e.getLevel());
        row.put("message", e.getMessage());
        row.put("exceptionClass", e.getExceptionClass());
        row.put("fingerprint", e.getFingerprint());
        row.put("occurrenceCount", e.getOccurrenceCount());
        row.put("occurredAt", e.getOccurredAt() == null ? null : e.getOccurredAt().toString());
        row.put("lastSeenAt", e.getLastSeenAt() == null ? null : e.getLastSeenAt().toString());
        row.put("resolvedAt", e.getResolvedAt() == null ? null : e.getResolvedAt().toString());
        row.put("resolvedBy", e.getResolvedBy());
        return row;
    }

    private static Map<String, Object> toDetailRow(ErrorLog e) {
        Map<String, Object> row = toListRow(e);
        row.put("stackTrace", e.getStackTrace());
        row.put("mdc", redactMdc(e.getMdc()));
        row.put("notifiedAt", e.getNotifiedAt() == null ? null : e.getNotifiedAt().toString());
        row.put("notificationChannels", e.getNotificationChannels());
        row.put("developerFindingId", e.getDeveloperFindingId());
        return row;
    }

    private static JsonNode redactMdc(JsonNode mdc) {
        if (mdc == null || !mdc.isObject()) return mdc;
        ObjectNode copy = ((ObjectNode) mdc).deepCopy();
        java.util.List<String> targets = new java.util.ArrayList<>();
        copy.fieldNames().forEachRemaining(name -> {
            if (MDC_REDACT_KEYS.contains(name.toLowerCase())) targets.add(name);
        });
        for (String name : targets) copy.put(name, REDACTED);
        return copy;
    }

    public record StatusUpdateRequest(String status) {}
}

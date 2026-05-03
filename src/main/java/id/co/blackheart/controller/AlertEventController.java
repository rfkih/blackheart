package id.co.blackheart.controller;

import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.model.AlertEvent;
import id.co.blackheart.repository.AlertEventRepository;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Admin-only feed of {@link AlertEvent}s — the operator's "inbox" for
 * Phase 7 system signals (kill-switch trips, ingest stalls, P&amp;L
 * deviation, verdict drift). Reads from {@code alert_event} which is
 * written by {@code AlertService.raise} on every operational alert.
 *
 * <p>Same admin gate as {@code /actuator/**} and the research dashboard:
 * the rows describe SYSTEM-actor mutations, not user actions, and a
 * non-admin caller has no use for them.
 */
@RestController
@RequestMapping("/api/v1/alerts")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "AlertEventController", description = "Admin feed of operational alerts")
public class AlertEventController {

    private static final int MAX_PAGE_SIZE = 200;
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("createdAt", "severity");

    private final AlertEventRepository alertEventRepository;

    @GetMapping
    @Operation(
            summary = "List alert events with optional filters, search, and sort",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ResponseDto> list(
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
            @RequestParam(defaultValue = "true") boolean includeSuppressed,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "createdAt,desc") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize);

        String sevFilter    = (severity == null || severity.isBlank()) ? null : severity.trim().toUpperCase();
        String kindFilter   = (kind == null || kind.isBlank()) ? null : kind.trim();
        String searchFilter = (search == null || search.isBlank()) ? null : search.trim();

        String[] parts = sort.split(",", 2);
        String sortColumn = ALLOWED_SORT_FIELDS.contains(parts[0].trim()) ? parts[0].trim() : "createdAt";
        String sortDir = (parts.length > 1 && "asc".equalsIgnoreCase(parts[1].trim())) ? "ASC" : "DESC";

        Page<AlertEvent> events = alertEventRepository.findFiltered(
                sevFilter, kindFilter, since, includeSuppressed, searchFilter, sortColumn, sortDir, pageable);

        List<Map<String, Object>> rows = events.getContent().stream()
                .map(AlertEventController::toRow)
                .toList();

        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(Map.of(
                        "content", rows,
                        "page", events.getNumber(),
                        "size", events.getSize(),
                        "totalElements", events.getTotalElements(),
                        "totalPages", events.getTotalPages()
                ))
                .build());
    }

    @GetMapping("/unread-count")
    @Operation(
            summary = "Count of non-suppressed alerts since the given cutoff",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ResponseDto> unreadCount(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime since,
            @RequestParam(required = false) String minSeverity
    ) {
        // Default cutoff: last 24h. Cheap query, prevents an "everything
        // since the dawn of time" count on a fresh client install.
        LocalDateTime effectiveSince = since != null ? since : LocalDateTime.now().minusDays(1);
        Integer rank = severityRank(minSeverity);
        long count = alertEventRepository.countUnread(effectiveSince, rank);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(Map.of(
                        "count", count,
                        "since", effectiveSince.toString()
                ))
                .build());
    }

    private static Integer severityRank(String s) {
        if (s == null || s.isBlank()) return null;
        return switch (s.trim().toUpperCase()) {
            case "INFO" -> 1;
            case "WARN" -> 2;
            case "CRITICAL" -> 3;
            default -> null;
        };
    }

    private static Map<String, Object> toRow(AlertEvent e) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("alertEventId", e.getAlertEventId());
        row.put("severity", e.getSeverity());
        row.put("kind", e.getKind());
        row.put("message", e.getMessage());
        row.put("context", e.getContext());
        row.put("dedupeKey", e.getDedupeKey());
        row.put("suppressed", e.isSuppressed());
        row.put("sentTelegram", e.getSentTelegram());
        row.put("sentEmail", e.getSentEmail());
        row.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
        return row;
    }
}

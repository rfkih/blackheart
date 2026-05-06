package id.co.blackheart.controller;

import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.model.AuditEvent;
import id.co.blackheart.repository.AuditEventRepository;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.util.AuthHeaderUtil;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read access to the audit log. The caller can only see their own actions —
 * admins get the same scoping by default; expose a separate admin-wide
 * endpoint later if the use case emerges.
 *
 * <p>Pagination is page/size based to match how Spring Data exposes it. Page
 * size is clamped to a small ceiling so a malicious caller can't ask for a
 * 100k-row dump in one shot.
 */
@RestController
@RequestMapping("/api/v1/audit-events")
@RequiredArgsConstructor
@Tag(name = "AuditEventController", description = "Read the caller's audit-event history")
public class AuditEventController {

    private static final int DEFAULT_PAGE_SIZE = 25;
    private static final int MAX_PAGE_SIZE = 100;

    private final AuditEventRepository auditEventRepository;
    private final JwtService jwtService;

    @GetMapping
    @Operation(
            summary = "List the calling user's audit events, newest first",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ResponseDto> listMyAuditEvents(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        UUID userId = jwtService.extractUserId(AuthHeaderUtil.extractToken(authHeader));
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize);

        Page<AuditEvent> events = auditEventRepository
                .findByActorUserIdOrderByCreatedAtDesc(userId, pageable);

        List<Map<String, Object>> rows = events.getContent().stream()
                .map(AuditEventController::toRow)
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

    /**
     * Flatten the entity into the wire shape the UI consumes. We deliberately
     * <i>don't</i> echo {@code beforeData} / {@code afterData} JSON in this
     * list view — those payloads can be heavy and the list shows summaries
     * only. A future GET-by-id endpoint can return the full snapshot.
     */
    private static Map<String, Object> toRow(AuditEvent e) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("auditEventId", e.getAuditEventId());
        row.put("actorUserId", e.getActorUserId());
        row.put("action", e.getAction());
        row.put("entityType", e.getEntityType());
        row.put("entityId", e.getEntityId());
        row.put("reason", e.getReason());
        row.put("createdAt", e.getCreatedAt() == null ? null : e.getCreatedAt().toString());
        return row;
    }
}

package id.co.blackheart.controller;

import id.co.blackheart.dto.request.SupportMessageRequest;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.model.SupportMessage;
import id.co.blackheart.service.support.SupportMessageService;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.util.AuthHeaderUtil;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// POST: any authenticated user. GET + PATCH: admin only.
@RestController
@RequestMapping("/api/v1/support")
@RequiredArgsConstructor
@Tag(name = "SupportMessageController", description = "User contact-form submission + admin inbox")
public class SupportMessageController {

    private static final int MAX_PAGE_SIZE = 100;

    private final SupportMessageService supportMessageService;
    private final JwtService jwtService;

    @PostMapping
    @Operation(
            summary = "Submit a support message",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ResponseDto> submit(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody SupportMessageRequest request
    ) {
        UUID userId = jwtService.extractUserId(AuthHeaderUtil.extractToken(authHeader));
        SupportMessage saved = supportMessageService.submit(
                userId, request.getSubject().trim(), request.getBody().trim(), request.getDiagnostic());
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseDto.builder()
                .responseCode(HttpStatus.CREATED.value() + ResponseCode.SUCCESS.getCode())
                .data(Map.of(
                        "supportMessageId", saved.getSupportMessageId(),
                        "createdAt", saved.getCreatedAt().toString(),
                        "message", "Thanks — your message reached the inbox."
                ))
                .build());
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "[Admin] List support messages",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ResponseDto> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        Page<SupportMessage> result = supportMessageService.listForAdmin(status, pageable);

        List<Map<String, Object>> rows = result.getContent().stream()
                .map(SupportMessageController::toRow)
                .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", rows);
        body.put("page", result.getNumber());
        body.put("size", result.getSize());
        body.put("totalElements", result.getTotalElements());
        body.put("totalPages", result.getTotalPages());
        body.put("unreadCount", supportMessageService.countNew());

        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(body)
                .build());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "[Admin] Update message status (NEW / READ / RESOLVED)",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ResponseDto> updateStatus(
            @PathVariable UUID id,
            @RequestBody Map<String, String> body
    ) {
        String newStatus = body == null ? null : body.get("status");
        SupportMessage updated = supportMessageService.updateStatus(id, newStatus);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(toRow(updated))
                .build());
    }

    private static Map<String, Object> toRow(SupportMessage m) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("supportMessageId", m.getSupportMessageId());
        row.put("fromUserId", m.getFromUserId());
        row.put("fromEmail", m.getFromEmail());
        row.put("subject", m.getSubject());
        row.put("body", m.getBody());
        row.put("diagnostic", m.getDiagnostic());
        row.put("status", m.getStatus());
        row.put("createdAt", iso(m.getCreatedAt()));
        row.put("readAt", iso(m.getReadAt()));
        return row;
    }

    private static String iso(LocalDateTime t) {
        return t == null ? null : t.toString();
    }
}

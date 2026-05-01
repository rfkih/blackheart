package id.co.blackheart.controller;

import id.co.blackheart.dto.request.CreateResearchQueueItemRequest;
import id.co.blackheart.dto.request.UpdateResearchQueueItemRequest;
import id.co.blackheart.dto.response.ResearchQueueItemResponse;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.research.ResearchQueueService;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * CRUD over the unattended-research work queue. Lives on the research
 * JVM ({@code @Profile("research")}) where the orchestrator also runs
 * — keeps row-locking semantics on the same node.
 *
 * <p>Most endpoints are admin-only because the queue drives token spend
 * and JVM CPU on the research host. The {@code /me} POST is the
 * exception — user-scoped queueing that mirrors {@code /sweeps}
 * ownership, so the frontend can submit work without admin role.
 */
@RestController
@RequestMapping("/api/v1/research/queue")
@Profile("research")
@RequiredArgsConstructor
@Tag(name = "ResearchQueueController",
     description = "Admin-only CRUD for the unattended-research work queue")
public class ResearchQueueController {

    private final ResearchQueueService service;
    private final JwtService jwtService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ResponseDto> list(
            @RequestParam(required = false) String strategyCode,
            @RequestParam(required = false) List<String> status) {
        List<ResearchQueueItemResponse> rows = service.list(strategyCode, status);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(rows)
                .build());
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDto> create(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody CreateResearchQueueItemRequest request) {
        String actor = resolveActor(authHeader);
        ResearchQueueItemResponse created = service.create(request, actor);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseDto.builder()
                .responseCode(HttpStatus.CREATED.value() + ResponseCode.SUCCESS.getCode())
                .data(created)
                .build());
    }

    /**
     * User-scoped queue create. No admin gate; ownership is tracked via
     * {@code created_by = jwtUserId}. Mirrors the {@code /sweeps}
     * ownership pattern so the frontend can submit queue work without
     * routing through an admin session. The orchestrator's tick path
     * picks these rows up the same as admin-queued ones.
     */
    @PostMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDto> createForSelf(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody CreateResearchQueueItemRequest request) {
        UUID userId = jwtService.extractUserId(authHeader.substring(7));
        ResearchQueueItemResponse created = service.create(request, userId.toString());
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseDto.builder()
                .responseCode(HttpStatus.CREATED.value() + ResponseCode.SUCCESS.getCode())
                .data(created)
                .build());
    }

    @PatchMapping("/{queueId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ResponseDto> updatePriority(
            @PathVariable UUID queueId,
            @Valid @RequestBody UpdateResearchQueueItemRequest request) {
        ResearchQueueItemResponse updated = service.updatePriority(queueId, request);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(updated)
                .build());
    }

    @DeleteMapping("/{queueId}")
    @PreAuthorize("hasRole('ADMIN')")
    @SecurityRequirement(name = "bearerAuth")
    public ResponseEntity<ResponseDto> cancel(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID queueId) {
        String actor = resolveActor(authHeader);
        ResearchQueueItemResponse cancelled = service.cancel(queueId, actor);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(cancelled)
                .build());
    }

    private String resolveActor(String authHeader) {
        try {
            String token = authHeader != null && authHeader.startsWith("Bearer ")
                    ? authHeader.substring(7)
                    : authHeader;
            UUID userId = jwtService.extractUserId(token);
            return userId != null ? userId.toString() : "ADMIN_UI";
        } catch (Exception e) {
            return "ADMIN_UI";
        }
    }
}

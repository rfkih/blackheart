package id.co.blackheart.controller;

import id.co.blackheart.dto.lsr.LsrParams;
import id.co.blackheart.dto.request.LsrParamUpdateRequest;
import id.co.blackheart.dto.response.LsrParamResponse;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.strategy.LsrStrategyParamService;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST API for managing per-account-strategy LSR strategy parameter overrides.
 *
 * <p>All endpoints require a valid Bearer JWT. The caller's email (extracted from the JWT)
 * is recorded as the {@code updated_by} audit field on every write.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET  /api/v1/lsr-params/defaults                     — canonical defaults (no auth required for reading)</li>
 *   <li>GET  /api/v1/lsr-params/{accountStrategyId}          — effective params for an account strategy</li>
 *   <li>PUT  /api/v1/lsr-params/{accountStrategyId}          — replace all overrides</li>
 *   <li>PATCH /api/v1/lsr-params/{accountStrategyId}         — merge partial overrides</li>
 *   <li>DELETE /api/v1/lsr-params/{accountStrategyId}        — reset to defaults</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/lsr-params")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "LsrStrategyParamController", description = "LSR strategy per-account-strategy parameter management")
public class LsrStrategyParamController {

    private final LsrStrategyParamService paramService;
    private final JwtService jwtService;

    // ── Defaults ──────────────────────────────────────────────────────────────────

    @GetMapping("/defaults")
    @Operation(summary = "Get the canonical LSR strategy default parameters",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> getDefaults() {
        LsrParams defaults = LsrParams.defaults();
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(LsrParamResponse.builder()
                        .accountStrategyId(null)
                        .hasCustomParams(false)
                        .overrides(Map.of())
                        .effectiveParams(defaults)
                        .version(null)
                        .updatedAt(null)
                        .build())
                .build());
    }

    // ── Per-account-strategy ──────────────────────────────────────────────────────

    @GetMapping("/{accountStrategyId}")
    @Operation(summary = "Get effective LSR params for an account strategy (defaults + overrides)",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> getParams(
            @PathVariable UUID accountStrategyId) {
        LsrParamResponse response = paramService.getParamResponse(accountStrategyId);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(response)
                .build());
    }

    @PutMapping("/{accountStrategyId}")
    @Operation(summary = "Replace all LSR param overrides for an account strategy (non-null fields only)",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> putParams(
            @PathVariable UUID accountStrategyId,
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody LsrParamUpdateRequest request) {
        String callerEmail = extractEmail(authHeader);
        LsrParamResponse response = paramService.putParams(accountStrategyId, request, callerEmail);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(response)
                .build());
    }

    @PatchMapping("/{accountStrategyId}")
    @Operation(summary = "Merge partial LSR param overrides for an account strategy",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> patchParams(
            @PathVariable UUID accountStrategyId,
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody LsrParamUpdateRequest request) {
        String callerEmail = extractEmail(authHeader);
        LsrParamResponse response = paramService.patchParams(accountStrategyId, request, callerEmail);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(response)
                .build());
    }

    @DeleteMapping("/{accountStrategyId}")
    @Operation(summary = "Reset LSR params to defaults by removing all overrides for an account strategy",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> resetParams(
            @PathVariable UUID accountStrategyId,
            @RequestHeader("Authorization") String authHeader) {
        String callerEmail = extractEmail(authHeader);
        paramService.resetToDefaults(accountStrategyId, callerEmail);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .responseDesc("LSR params reset to defaults")
                .build());
    }

    // ── Helper ────────────────────────────────────────────────────────────────────

    private String extractEmail(String authHeader) {
        return jwtService.extractEmail(authHeader.substring(7));
    }
}

package id.co.blackheart.controller;

import id.co.blackheart.dto.request.StrategyParamCreateRequest;
import id.co.blackheart.dto.request.StrategyParamUpdateRequest;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.dto.response.StrategyParamResponse;
import id.co.blackheart.model.StrategyParam;
import id.co.blackheart.service.strategy.AccountStrategyOwnershipGuard;
import id.co.blackheart.service.strategy.StrategyParamService;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.util.AuthHeaderUtil;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Saved-preset CRUD for the unified {@code strategy_param} table (V29+).
 *
 * <p>Each preset is a named override map for one {@code account_strategy}.
 * At most one preset per account_strategy is {@code active} — live trading
 * reads it; backtests can target any preset by {@code paramId}, including
 * soft-deleted ones (so historical runs stay reproducible).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET    /?accountStrategyId=...} — list non-deleted presets</li>
 *   <li>{@code GET    /{paramId}}              — fetch one preset (incl. soft-deleted)</li>
 *   <li>{@code POST   /}                       — create preset (optionally activate)</li>
 *   <li>{@code PATCH  /{paramId}}              — update name / overrides</li>
 *   <li>{@code POST   /{paramId}/activate}     — promote to active (atomic)</li>
 *   <li>{@code POST   /{paramId}/deactivate}   — clear active flag</li>
 *   <li>{@code DELETE /{paramId}}              — soft-delete</li>
 * </ul>
 *
 * <p>The legacy per-strategy endpoints ({@code /api/v1/lsr-params},
 * {@code /vcb-params}, {@code /vbo-params}) are kept as shims so the existing
 * frontend forms keep working — they read/write the active preset via this
 * same service.
 */
@RestController
@RequestMapping("/api/v1/strategy-params")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "StrategyParamController",
     description = "Saved parameter presets — one row per preset, ≤1 active per account_strategy")
public class StrategyParamController {

    private static final String NO_PARAM_MSG_PREFIX = "No strategy_param with paramId=";

    private final StrategyParamService paramService;
    private final AccountStrategyOwnershipGuard ownershipGuard;
    private final JwtService jwtService;

    // ── Read ─────────────────────────────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "List non-deleted saved presets for an account_strategy",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> listPresets(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam UUID accountStrategyId) {

        ownershipGuard.assertOwned(extractUserId(authHeader), accountStrategyId);
        List<StrategyParamResponse> body = paramService.listByAccountStrategy(accountStrategyId)
                .stream()
                .map(StrategyParamController::toResponse)
                .toList();
        return ok(body);
    }

    @GetMapping("/{paramId}")
    @Operation(summary = "Fetch a saved preset by id (includes soft-deleted)",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> getPreset(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID paramId) {

        StrategyParam entity = paramService.findById(paramId)
                .orElseThrow(() -> new EntityNotFoundException(
                        NO_PARAM_MSG_PREFIX + paramId));
        ownershipGuard.assertOwned(extractUserId(authHeader), entity.getAccountStrategyId());
        return ok(toResponse(entity));
    }

    // ── Write ────────────────────────────────────────────────────────────────────

    @PostMapping
    @Operation(summary = "Create a new saved preset",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> createPreset(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody StrategyParamCreateRequest request) {

        ownershipGuard.assertOwned(extractUserId(authHeader), request.getAccountStrategyId());
        String email = extractEmail(authHeader);
        StrategyParam saved = paramService.create(
                request.getAccountStrategyId(),
                request.getName(),
                request.getOverrides(),
                request.isActivate(),
                request.getSourceBacktestRunId(),
                email);
        return ok(toResponse(saved));
    }

    @PatchMapping("/{paramId}")
    @Operation(summary = "Update a saved preset's name and/or overrides",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> updatePreset(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID paramId,
            @Valid @RequestBody StrategyParamUpdateRequest request) {

        StrategyParam existing = paramService.findById(paramId)
                .orElseThrow(() -> new EntityNotFoundException(
                        NO_PARAM_MSG_PREFIX + paramId));
        ownershipGuard.assertOwned(extractUserId(authHeader), existing.getAccountStrategyId());

        String email = extractEmail(authHeader);
        StrategyParam saved = paramService.update(
                paramId, request.getName(), request.getOverrides(), email);
        return ok(toResponse(saved));
    }

    @PostMapping("/{paramId}/activate")
    @Operation(summary = "Promote preset to active (atomic; clears sibling actives)",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> activatePreset(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID paramId) {

        StrategyParam existing = paramService.findById(paramId)
                .orElseThrow(() -> new EntityNotFoundException(
                        NO_PARAM_MSG_PREFIX + paramId));
        ownershipGuard.assertOwned(extractUserId(authHeader), existing.getAccountStrategyId());

        StrategyParam saved = paramService.activate(paramId, extractEmail(authHeader));
        return ok(toResponse(saved));
    }

    @PostMapping("/{paramId}/deactivate")
    @Operation(summary = "Clear the active flag on a preset",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> deactivatePreset(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID paramId) {

        StrategyParam existing = paramService.findById(paramId)
                .orElseThrow(() -> new EntityNotFoundException(
                        NO_PARAM_MSG_PREFIX + paramId));
        ownershipGuard.assertOwned(extractUserId(authHeader), existing.getAccountStrategyId());

        StrategyParam saved = paramService.deactivate(paramId, extractEmail(authHeader));
        return ok(toResponse(saved));
    }

    @DeleteMapping("/{paramId}")
    @Operation(summary = "Soft-delete a preset (still resolvable by id for historical backtests)",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> deletePreset(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID paramId) {

        StrategyParam existing = paramService.findById(paramId)
                .orElseThrow(() -> new EntityNotFoundException(
                        NO_PARAM_MSG_PREFIX + paramId));
        ownershipGuard.assertOwned(extractUserId(authHeader), existing.getAccountStrategyId());

        paramService.softDelete(paramId, extractEmail(authHeader));
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .responseDesc("strategy_param soft-deleted")
                .build());
    }

    // ── Internals ────────────────────────────────────────────────────────────────

    private static StrategyParamResponse toResponse(StrategyParam e) {
        return StrategyParamResponse.builder()
                .paramId(e.getParamId())
                .accountStrategyId(e.getAccountStrategyId())
                .name(e.getName())
                .overrides(e.getParamOverrides())
                .active(e.isActive())
                .deleted(e.isDeleted())
                .deletedAt(e.getDeletedAt())
                .sourceBacktestRunId(e.getSourceBacktestRunId())
                .version(e.getVersion())
                .createdAt(e.getCreatedTime())
                .createdBy(e.getCreatedBy())
                .updatedAt(e.getUpdatedTime())
                .updatedBy(e.getUpdatedBy())
                .build();
    }

    private ResponseEntity<ResponseDto> ok(Object data) {
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(data)
                .build());
    }

    private String extractEmail(String authHeader) {
        return jwtService.extractEmail(AuthHeaderUtil.extractToken(authHeader));
    }

    private UUID extractUserId(String authHeader) {
        return jwtService.extractUserId(AuthHeaderUtil.extractToken(authHeader));
    }
}

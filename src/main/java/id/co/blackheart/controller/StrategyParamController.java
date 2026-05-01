package id.co.blackheart.controller;

import id.co.blackheart.dto.request.StrategyParamUpdateRequest;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.dto.response.StrategyParamResponse;
import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.model.StrategyDefinition;
import id.co.blackheart.model.StrategyParam;
import id.co.blackheart.repository.AccountStrategyRepository;
import id.co.blackheart.repository.StrategyDefinitionRepository;
import id.co.blackheart.service.strategy.AccountStrategyOwnershipGuard;
import id.co.blackheart.service.strategy.SpecValidator;
import id.co.blackheart.service.strategy.StrategyParamService;
import id.co.blackheart.service.user.JwtService;
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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Unified REST API for spec-driven strategy parameter overrides.
 *
 * <p>Replaces the per-strategy controllers ({@code /api/v1/lsr-params}, {@code /api/v1/vcb-params},
 * {@code /api/v1/vbo-params}) for spec-driven strategies only. The legacy controllers continue
 * to serve LSR / VCB / VBO unchanged — see {@code docs/PARAMETRIC_ENGINE_BLUEPRINT.md} §4.
 *
 * <p>This controller refuses to operate on account_strategies bound to a {@code LEGACY_JAVA}
 * archetype — clients must use the corresponding legacy endpoint instead. The check uses
 * the archetype field on {@code strategy_definition}, resolved via the account_strategy's
 * {@code strategy_definition_id}.
 *
 * <p>All endpoints require a valid Bearer JWT. Caller email is recorded as {@code updated_by}.
 */
@RestController
@RequestMapping("/api/v1/strategy-params")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "StrategyParamController",
     description = "Unified parameter overrides for spec-driven strategies")
public class StrategyParamController {

    private static final String LEGACY_ARCHETYPE = "LEGACY_JAVA";

    private final StrategyParamService paramService;
    private final AccountStrategyRepository accountStrategyRepository;
    private final StrategyDefinitionRepository definitionRepository;
    private final AccountStrategyOwnershipGuard ownershipGuard;
    private final JwtService jwtService;
    private final SpecValidator specValidator;

    // ── Read ──────────────────────────────────────────────────────────────────────

    @GetMapping("/{accountStrategyId}")
    @Operation(summary = "Get parameter overrides for a spec-driven strategy",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> getParams(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID accountStrategyId) {

        AccountStrategy as = ownershipGuard.assertOwned(extractUserId(authHeader), accountStrategyId);
        StrategyDefinition def = resolveSpecDrivenDefinition(as);

        Optional<StrategyParam> entity = paramService.findEntity(accountStrategyId);
        StrategyParamResponse response = toResponse(as, def, entity);

        return ok(response);
    }

    // ── Write ─────────────────────────────────────────────────────────────────────

    @PutMapping("/{accountStrategyId}")
    @Operation(summary = "Replace all parameter overrides for a spec-driven strategy",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> putParams(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID accountStrategyId,
            @Valid @RequestBody StrategyParamUpdateRequest request) {

        AccountStrategy as = ownershipGuard.assertOwned(extractUserId(authHeader), accountStrategyId);
        StrategyDefinition def = resolveSpecDrivenDefinition(as);
        specValidator.validate(def.getArchetype(), request.getOverrides());

        String callerEmail = extractEmail(authHeader);
        StrategyParam saved = paramService.putOverrides(
                accountStrategyId, request.getOverrides(), callerEmail);

        return ok(toResponse(as, def, Optional.of(saved)));
    }

    @PatchMapping("/{accountStrategyId}")
    @Operation(summary = "Merge partial parameter overrides for a spec-driven strategy",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> patchParams(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID accountStrategyId,
            @Valid @RequestBody StrategyParamUpdateRequest request) {

        AccountStrategy as = ownershipGuard.assertOwned(extractUserId(authHeader), accountStrategyId);
        StrategyDefinition def = resolveSpecDrivenDefinition(as);
        specValidator.validate(def.getArchetype(), request.getOverrides());

        String callerEmail = extractEmail(authHeader);
        StrategyParam saved = paramService.patchOverrides(
                accountStrategyId, request.getOverrides(), callerEmail);

        return ok(toResponse(as, def, Optional.of(saved)));
    }

    @DeleteMapping("/{accountStrategyId}")
    @Operation(summary = "Reset parameter overrides to archetype defaults",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> resetParams(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID accountStrategyId) {

        AccountStrategy as = ownershipGuard.assertOwned(extractUserId(authHeader), accountStrategyId);
        resolveSpecDrivenDefinition(as);

        String callerEmail = extractEmail(authHeader);
        paramService.deleteOverrides(accountStrategyId, callerEmail);

        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .responseDesc("strategy_param overrides reset to archetype defaults")
                .build());
    }

    // ── Private ───────────────────────────────────────────────────────────────────

    /**
     * Resolves the strategy_definition for the account_strategy and refuses if the
     * archetype is {@code LEGACY_JAVA} — those strategies must use their dedicated
     * legacy controller (see §4 of the blueprint).
     */
    private StrategyDefinition resolveSpecDrivenDefinition(AccountStrategy as) {
        StrategyDefinition def = definitionRepository.findById(as.getStrategyDefinitionId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Strategy definition not found for accountStrategy="
                                + as.getAccountStrategyId()));
        if (LEGACY_ARCHETYPE.equalsIgnoreCase(def.getArchetype())) {
            throw new IllegalStateException(
                    "accountStrategy=" + as.getAccountStrategyId()
                            + " is bound to LEGACY_JAVA strategy " + def.getStrategyCode()
                            + " — use /api/v1/" + def.getStrategyCode().toLowerCase()
                            + "-params/ instead");
        }
        return def;
    }

    private StrategyParamResponse toResponse(AccountStrategy as,
                                             StrategyDefinition def,
                                             Optional<StrategyParam> entity) {
        Map<String, Object> overrides = entity
                .map(StrategyParam::getParamOverrides)
                .map(HashMap::new)
                .map(m -> (Map<String, Object>) m)
                .orElseGet(HashMap::new);

        return StrategyParamResponse.builder()
                .accountStrategyId(as.getAccountStrategyId())
                .archetype(def.getArchetype())
                .strategyCode(def.getStrategyCode())
                .hasOverrides(!overrides.isEmpty())
                .overrides(overrides)
                .version(entity.map(StrategyParam::getVersion).orElse(null))
                .updatedAt(entity.map(StrategyParam::getUpdatedTime).orElse(null))
                .updatedBy(entity.map(StrategyParam::getUpdatedBy).orElse(null))
                .build();
    }

    private ResponseEntity<ResponseDto> ok(Object data) {
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(data)
                .build());
    }

    private String extractEmail(String authHeader) {
        return jwtService.extractEmail(authHeader.substring(7));
    }

    private UUID extractUserId(String authHeader) {
        return jwtService.extractUserId(authHeader.substring(7));
    }
}

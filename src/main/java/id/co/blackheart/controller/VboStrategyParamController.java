package id.co.blackheart.controller;

import id.co.blackheart.dto.request.VboParamUpdateRequest;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.dto.response.VboParamResponse;
import id.co.blackheart.dto.vbo.VboParams;
import id.co.blackheart.service.strategy.AccountStrategyOwnershipGuard;
import id.co.blackheart.service.strategy.VboStrategyParamService;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.util.AuthHeaderUtil;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/vbo-params")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "VboStrategyParamController", description = "VBO strategy per-account-strategy parameter management")
public class VboStrategyParamController {

    private final VboStrategyParamService paramService;
    private final JwtService jwtService;
    private final AccountStrategyOwnershipGuard ownershipGuard;

    @GetMapping("/defaults")
    @Operation(summary = "Get the canonical VBO strategy default parameters",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> getDefaults() {
        VboParams defaults = VboParams.defaults();
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(VboParamResponse.builder()
                        .accountStrategyId(null)
                        .hasCustomParams(false)
                        .overrides(Map.of())
                        .effectiveParams(defaults)
                        .version(null)
                        .updatedAt(null)
                        .build())
                .build());
    }

    @GetMapping("/{accountStrategyId}")
    @Operation(summary = "Get effective VBO params for an account strategy (defaults + overrides)",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> getParams(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID accountStrategyId) {
        ownershipGuard.assertOwned(extractUserId(authHeader), accountStrategyId);
        VboParamResponse response = paramService.getParamResponse(accountStrategyId);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(response)
                .build());
    }

    @PutMapping("/{accountStrategyId}")
    @Operation(summary = "Replace all VBO param overrides for an account strategy",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> putParams(
            @PathVariable UUID accountStrategyId,
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody VboParamUpdateRequest request) {
        ownershipGuard.assertOwned(extractUserId(authHeader), accountStrategyId);
        String callerEmail = extractEmail(authHeader);
        VboParamResponse response = paramService.putParams(accountStrategyId, request, callerEmail);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(response)
                .build());
    }

    @PatchMapping("/{accountStrategyId}")
    @Operation(summary = "Merge partial VBO param overrides for an account strategy",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> patchParams(
            @PathVariable UUID accountStrategyId,
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody VboParamUpdateRequest request) {
        ownershipGuard.assertOwned(extractUserId(authHeader), accountStrategyId);
        String callerEmail = extractEmail(authHeader);
        VboParamResponse response = paramService.patchParams(accountStrategyId, request, callerEmail);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(response)
                .build());
    }

    @DeleteMapping("/{accountStrategyId}")
    @Operation(summary = "Reset VBO params to defaults by removing all overrides",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> resetParams(
            @PathVariable UUID accountStrategyId,
            @RequestHeader("Authorization") String authHeader) {
        ownershipGuard.assertOwned(extractUserId(authHeader), accountStrategyId);
        String callerEmail = extractEmail(authHeader);
        paramService.resetToDefaults(accountStrategyId, callerEmail);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .responseDesc("VBO params reset to defaults")
                .build());
    }

    private String extractEmail(String authHeader) {
        return jwtService.extractEmail(AuthHeaderUtil.extractToken(authHeader));
    }

    private UUID extractUserId(String authHeader) {
        return jwtService.extractUserId(AuthHeaderUtil.extractToken(authHeader));
    }
}

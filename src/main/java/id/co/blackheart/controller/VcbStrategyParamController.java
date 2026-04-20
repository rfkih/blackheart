package id.co.blackheart.controller;

import id.co.blackheart.dto.request.VcbParamUpdateRequest;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.dto.response.VcbParamResponse;
import id.co.blackheart.dto.vcb.VcbParams;
import id.co.blackheart.service.strategy.VcbStrategyParamService;
import id.co.blackheart.service.user.JwtService;
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
@RequestMapping("/api/v1/vcb-params")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "VcbStrategyParamController", description = "VCB strategy per-account-strategy parameter management")
public class VcbStrategyParamController {

    private final VcbStrategyParamService paramService;
    private final JwtService jwtService;

    @GetMapping("/defaults")
    @Operation(summary = "Get the canonical VCB strategy default parameters",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> getDefaults() {
        VcbParams defaults = VcbParams.defaults();
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(VcbParamResponse.builder()
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
    @Operation(summary = "Get effective VCB params for an account strategy (defaults + overrides)",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> getParams(@PathVariable UUID accountStrategyId) {
        VcbParamResponse response = paramService.getParamResponse(accountStrategyId);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(response)
                .build());
    }

    @PutMapping("/{accountStrategyId}")
    @Operation(summary = "Replace all VCB param overrides for an account strategy",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> putParams(
            @PathVariable UUID accountStrategyId,
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody VcbParamUpdateRequest request) {
        String callerEmail = extractEmail(authHeader);
        VcbParamResponse response = paramService.putParams(accountStrategyId, request, callerEmail);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(response)
                .build());
    }

    @PatchMapping("/{accountStrategyId}")
    @Operation(summary = "Merge partial VCB param overrides for an account strategy",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> patchParams(
            @PathVariable UUID accountStrategyId,
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody VcbParamUpdateRequest request) {
        String callerEmail = extractEmail(authHeader);
        VcbParamResponse response = paramService.patchParams(accountStrategyId, request, callerEmail);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(response)
                .build());
    }

    @DeleteMapping("/{accountStrategyId}")
    @Operation(summary = "Reset VCB params to defaults by removing all overrides",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> resetParams(
            @PathVariable UUID accountStrategyId,
            @RequestHeader("Authorization") String authHeader) {
        String callerEmail = extractEmail(authHeader);
        paramService.resetToDefaults(accountStrategyId, callerEmail);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .responseDesc("VCB params reset to defaults")
                .build());
    }

    private String extractEmail(String authHeader) {
        return jwtService.extractEmail(authHeader.substring(7));
    }
}

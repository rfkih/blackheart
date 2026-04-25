package id.co.blackheart.controller;

import id.co.blackheart.dto.request.CreateAccountStrategyRequest;
import id.co.blackheart.dto.request.UpdateAccountStrategyRequest;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.strategy.AccountStrategyService;
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
@RequestMapping("/api/v1/account-strategies")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "AccountStrategyController", description = "Account strategy inquiry endpoints")
public class AccountStrategyController {

    private final AccountStrategyService accountStrategyService;
    private final JwtService jwtService;

    @GetMapping
    @Operation(summary = "Get all strategies across all accounts belonging to the authenticated user",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> getMyStrategies(
            @RequestHeader("Authorization") String authHeader) {
        UUID userId = extractUserId(authHeader);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(accountStrategyService.getStrategiesByUser(userId))
                .build());
    }

    @GetMapping("/{accountStrategyId}")
    @Operation(summary = "Get a single account strategy by id, scoped to the authenticated user",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> getStrategyById(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID accountStrategyId) {
        UUID userId = extractUserId(authHeader);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(accountStrategyService.getStrategyById(userId, accountStrategyId))
                .build());
    }

    @GetMapping("/account/{accountId}")
    @Operation(summary = "Get all strategies for a specific account belonging to the authenticated user",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> getStrategiesByAccount(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID accountId) {
        UUID userId = extractUserId(authHeader);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(accountStrategyService.getStrategiesByUserAndAccount(userId, accountId))
                .build());
    }

    @PostMapping
    @Operation(summary = "Create a new account strategy on one of the authenticated user's accounts",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> createStrategy(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody CreateAccountStrategyRequest request) {
        UUID userId = extractUserId(authHeader);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseDto.builder()
                .responseCode(HttpStatus.CREATED.value() + ResponseCode.SUCCESS.getCode())
                .data(accountStrategyService.createStrategy(userId, request))
                .build());
    }

    @DeleteMapping("/{accountStrategyId}")
    @Operation(summary = "Soft-delete an account strategy. Historical trades and P&L remain queryable.",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> deleteStrategy(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID accountStrategyId) {
        UUID userId = extractUserId(authHeader);
        accountStrategyService.softDeleteStrategy(userId, accountStrategyId);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(Map.of("accountStrategyId", accountStrategyId, "deleted", true))
                .build());
    }

    /**
     * Activate this preset for its (account, strategy, symbol, interval) tuple.
     * Any currently-active sibling is deactivated atomically. Fails with 409
     * if the target is soft-deleted, or if a sibling with open trades would
     * need to be deactivated to make room — users must close positions before
     * switching presets mid-trade.
     */
    @PostMapping("/{accountStrategyId}/activate")
    @Operation(summary = "Make this preset the active one for its (account, strategy, symbol, interval).",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> activateStrategy(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID accountStrategyId) {
        UUID userId = extractUserId(authHeader);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(accountStrategyService.activateStrategy(userId, accountStrategyId))
                .build());
    }

    /**
     * Deactivate this preset — flips {@code enabled=false} so the live
     * orchestrator stops evaluating it for new entries. Open positions are
     * left intact; the live listener continues managing them until they
     * close naturally.
     */
    @PostMapping("/{accountStrategyId}/deactivate")
    @Operation(summary = "Stop this preset from taking new entries (existing positions are unaffected).",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> deactivateStrategy(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID accountStrategyId) {
        UUID userId = extractUserId(authHeader);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(accountStrategyService.deactivateStrategy(userId, accountStrategyId))
                .build());
    }

    /**
     * Partial update — currently the candle interval. Refuses if open trades
     * reference this strategy (mid-position TF change is unsafe).
     */
    @PatchMapping("/{accountStrategyId}")
    @Operation(summary = "Update editable fields on an account strategy (currently: intervalName).",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> updateStrategy(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID accountStrategyId,
            @Valid @RequestBody UpdateAccountStrategyRequest request) {
        UUID userId = extractUserId(authHeader);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(accountStrategyService.updateStrategy(userId, accountStrategyId, request))
                .build());
    }

    private UUID extractUserId(String authHeader) {
        return jwtService.extractUserId(authHeader.substring(7));
    }
}

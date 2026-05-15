package id.co.blackheart.controller;

import id.co.blackheart.dto.request.CloneAccountStrategyRequest;
import id.co.blackheart.dto.request.CreateAccountStrategyRequest;
import id.co.blackheart.dto.request.UpdateAccountStrategyRequest;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.strategy.AccountStrategyCloneService;
import id.co.blackheart.service.strategy.AccountStrategyService;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.util.AuthHeaderUtil;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
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
    private final AccountStrategyCloneService accountStrategyCloneService;
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

    /**
     * V54 — clone a (typically PUBLIC, research-agent-owned) strategy into one
     * of the calling user's accounts, copying the active strategy_param preset
     * alongside it. The clone lands as PRIVATE / disabled / simulated /
     * STOPPED so the user explicitly opts in before any capital is at risk.
     *
     * <p>If {@code targetAccountId} is omitted, the clone lands in the user's
     * first account (oldest by created_time). The endpoint is idempotent only
     * insofar as a duplicate (same definition + symbol + interval on the
     * target account) is rejected with 409 — re-issuing the same clone after
     * a successful one will collide deliberately.
     */
    @PostMapping("/{accountStrategyId}/clone")
    @Operation(summary = "Clone a public (or owned) strategy into the caller's account, copying the active preset.",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> cloneStrategy(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID accountStrategyId,
            @RequestBody(required = false) CloneAccountStrategyRequest request) {
        UUID userId = extractUserId(authHeader);
        UUID targetAccountId = ObjectUtils.isEmpty(request) ? null : request.getTargetAccountId();
        String createdBy = jwtService.extractEmail(AuthHeaderUtil.extractToken(authHeader));
        UUID newId = accountStrategyCloneService.clone(userId, accountStrategyId, targetAccountId, createdBy);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseDto.builder()
                .responseCode(HttpStatus.CREATED.value() + ResponseCode.SUCCESS.getCode())
                .data(Map.of("accountStrategyId", newId))
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

    /**
     * Returns the live Kelly sizing status — enabled flag, current effective
     * multiplier (computed from the most recent qualifying backtest runs),
     * configured cap, and a human-readable reason. Use this on the strategy
     * detail page to show operators what Kelly is doing right now without
     * making them grep JVM logs.
     */
    @GetMapping("/{accountStrategyId}/kelly-status")
    @Operation(summary = "Get the current Kelly sizing multiplier and qualifying-run count.",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> getKellyStatus(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID accountStrategyId) {
        UUID userId = extractUserId(authHeader);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(accountStrategyService.getKellyStatus(userId, accountStrategyId))
                .build());
    }

    /**
     * Clear the drawdown kill-switch on this strategy. The endpoint is
     * deliberately explicit — the trip is the "look at this" signal, and
     * the user must consciously acknowledge it before live trading
     * resumes for the strategy.
     */
    @PostMapping("/{accountStrategyId}/rearm")
    @Operation(summary = "Re-arm the drawdown kill-switch after manual review.",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> rearmKillSwitch(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID accountStrategyId) {
        UUID userId = extractUserId(authHeader);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(accountStrategyService.rearmKillSwitch(userId, accountStrategyId))
                .build());
    }

    private UUID extractUserId(String authHeader) {
        return jwtService.extractUserId(AuthHeaderUtil.extractToken(authHeader));
    }
}

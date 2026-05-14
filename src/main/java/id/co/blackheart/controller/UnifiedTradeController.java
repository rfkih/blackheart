package id.co.blackheart.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.model.Account;
import id.co.blackheart.model.Trades;
import id.co.blackheart.repository.AccountRepository;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.service.live.LiveTradingDecisionExecutorService;
import id.co.blackheart.service.strategy.AccountStrategyOwnershipGuard;
import id.co.blackheart.service.tradequery.UnifiedTradeService;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.util.AuthHeaderUtil;
import id.co.blackheart.util.ResponseCode;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/trades")
@RequiredArgsConstructor
@Slf4j
public class UnifiedTradeController {

    private final UnifiedTradeService unifiedTradeService;
    private final JwtService jwtService;
    private final TradesRepository tradesRepository;
    private final AccountRepository accountRepository;
    private final AccountStrategyOwnershipGuard ownershipGuard;
    private final LiveTradingDecisionExecutorService liveExecutor;

    @GetMapping
    public ResponseEntity<ResponseDto> getTrades(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(defaultValue = "0") int limit,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID userId = extractUserId(authHeader);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(unifiedTradeService.getTrades(userId, status, accountId, limit, page, size))
                .build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResponseDto> getTradeById(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID id) {
        UUID userId = extractUserId(authHeader);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(unifiedTradeService.getTradeById(userId, id))
                .build());
    }

    /**
     * Phase 2c — realized P&L decomposed into signal alpha, execution
     * drift, and sizing residual. Returns {@code data: null} when the
     * trade is still open or predates Phase 2c (no intent captured).
     */
    @GetMapping("/{id}/attribution")
    public ResponseEntity<ResponseDto> getTradeAttribution(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID id) {
        UUID userId = extractUserId(authHeader);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(unifiedTradeService.getTradeAttribution(userId, id))
                .build());
    }

    /**
     * Manually close every open position on a trade — places a Binance
     * market order in the opposite direction for the trade's full remaining
     * quantity, then lets the listener reconcile fills + persist exit P&L.
     *
     * <p>Idempotent on already-CLOSED trades (no-op return). Returns 404 if
     * the trade doesn't exist; 403 (via {@code assertOwnsAccount}) if the
     * caller doesn't own the trade's account.
     *
     * <p>The exit reason on every closed position is set to
     * {@code MANUAL_CLOSE} so the realised-P&L row carries clear attribution
     * apart from listener-triggered SL/TP closes.
     */
    @PostMapping("/{id}/close")
    public ResponseEntity<ResponseDto> closeTrade(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID id) throws JsonProcessingException {
        UUID userId = extractUserId(authHeader);

        Trades trade = tradesRepository.findByTradeId(id)
                .orElseThrow(() -> new EntityNotFoundException("Trade not found: " + id));
        ownershipGuard.assertOwnsAccount(userId, trade.getAccountId());

        Account account = accountRepository.findByAccountId(trade.getAccountId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "Account not found for trade: " + trade.getAccountId()));

        log.info("[ManualClose] userId={} tradeId={} symbol={} side={} status={}",
                userId, id, trade.getAsset(), trade.getSide(), trade.getStatus());
        liveExecutor.executeManualCloseTrade(account, id);

        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(unifiedTradeService.getTradeById(userId, id))
                .build());
    }

    private UUID extractUserId(String authHeader) {
        return jwtService.extractUserId(AuthHeaderUtil.extractToken(authHeader));
    }
}

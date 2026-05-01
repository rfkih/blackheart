package id.co.blackheart.controller;

import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.strategy.AccountStrategyOwnershipGuard;
import id.co.blackheart.service.tradequery.TradeAnomalyService;
import id.co.blackheart.service.tradequery.TradeQueryService;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.util.ResponseCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/trades")
@RequiredArgsConstructor
public class TradeQueryController {

    private final TradeQueryService tradeQueryService;
    private final TradeAnomalyService tradeAnomalyService;
    private final AccountStrategyOwnershipGuard ownershipGuard;
    private final JwtService jwtService;

    @GetMapping("/account/{accountId}/active")
    public ResponseEntity<ResponseDto> getActiveTradesByAccountId(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID accountId) {
        UUID userId = jwtService.extractUserId(authHeader.substring(7));
        ownershipGuard.assertOwnsAccount(userId, accountId);

        return ResponseEntity.ok().body(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(tradeQueryService.getActiveTradesByAccountId(accountId))
                .build());
    }

    /**
     * Stuck-trade reconciliation feed for the admin /research dashboard.
     * Returns every non-CLOSED parent trade owned by the caller whose state is
     * inconsistent with its child positions (orphan opens, partials with no
     * open legs). Healthy state → empty array. Admin-only because the panel
     * lives on the ops dashboard and the data spans every account the user
     * owns.
     */
    @GetMapping("/anomalies")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ResponseDto> getTradeAnomalies(
            @RequestHeader("Authorization") String authHeader) {
        UUID userId = jwtService.extractUserId(authHeader.substring(7));
        return ResponseEntity.ok().body(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(tradeAnomalyService.getAnomaliesForUser(userId))
                .build());
    }
}
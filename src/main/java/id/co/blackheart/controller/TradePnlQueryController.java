package id.co.blackheart.controller;

import id.co.blackheart.dto.response.ActiveTradePnlResponse;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.strategy.AccountStrategyOwnershipGuard;
import id.co.blackheart.service.tradequery.TradePnlQueryService;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.util.ResponseCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/trades")
@RequiredArgsConstructor
public class TradePnlQueryController {

    private final TradePnlQueryService tradePnlQueryService;
    private final AccountStrategyOwnershipGuard ownershipGuard;
    private final JwtService jwtService;

    @GetMapping("/account/{accountId}/active-pnl")
    public ResponseEntity<ResponseDto> getCurrentActiveTradePnl(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID accountId) {
        UUID userId = jwtService.extractUserId(authHeader.substring(7));
        ownershipGuard.assertOwnsAccount(userId, accountId);

        return ResponseEntity.ok().body(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(tradePnlQueryService.getCurrentActiveTradePnl(accountId))
                .build());
    }
}
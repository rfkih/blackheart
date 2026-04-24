package id.co.blackheart.controller;

import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.strategy.AccountStrategyOwnershipGuard;
import id.co.blackheart.service.tradequery.TradeQueryService;
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
public class TradeQueryController {

    private final TradeQueryService tradeQueryService;
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
}
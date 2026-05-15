package id.co.blackheart.controller;


import id.co.blackheart.dto.request.BinanceOrderDetailRequest;
import id.co.blackheart.dto.request.BinanceOrderRequest;
import id.co.blackheart.dto.response.*;
import id.co.blackheart.model.Account;
import id.co.blackheart.repository.AccountRepository;
import id.co.blackheart.service.strategy.AccountStrategyOwnershipGuard;
import id.co.blackheart.service.trade.TradeExecutionService;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.util.AuthHeaderUtil;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Manual trade-execution endpoints. Automated (signal-driven) trading does NOT
 * go through this controller — it flows through {@code TradeOpenService} /
 * {@code TradeCloseService} internally.
 *
 * <p>SECURITY:
 * <ul>
 *   <li>Admin-only ({@code ROLE_ADMIN}). Manual trade placement is a privileged
 *       operation and is not exposed to ordinary users.</li>
 *   <li>The client supplies {@code accountId} only; the API key and secret are
 *       loaded from the authenticated admin's account row. The request DTO
 *       annotates the credential fields with
 *       {@code @JsonProperty(access = READ_ONLY)} so a malicious client cannot
 *       deserialize values into them — but the server-injected values still
 *       serialise outbound to the Node Binance proxy (which validates them
 *       as required). The earlier {@code @JsonIgnore} blocked both directions
 *       and silently stripped credentials from the outbound call.</li>
 *   <li>Account ownership is verified against the JWT user id.</li>
 * </ul>
 */
@RestController
@RequestMapping(value = "/api/v1/trade")
@Slf4j
@RequiredArgsConstructor
@Tag(name = "TradeController", description = "Controller for Trade Execution")
public class TradeController {

    private final TradeExecutionService tradeExecutionService;
    private final AccountRepository accountRepository;
    private final AccountStrategyOwnershipGuard ownershipGuard;
    private final JwtService jwtService;


    @PostMapping("/place-market-order-binance")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Manually place a Binance market order on an account owned by the caller (ADMIN only).",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> binanceMarketOrder(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody BinanceOrderRequest binanceOrderRequest) {
        UUID userId = extractUserId(authHeader);
        Account account = resolveAccount(userId, binanceOrderRequest.getAccountId());

        // Defence in depth: zero any client-supplied values before populating from DB.
        binanceOrderRequest.setApiKey(account.getApiKey());
        binanceOrderRequest.setApiSecret(account.getApiSecret());

        BinanceOrderResponse response = tradeExecutionService.binanceMarketOrder(binanceOrderRequest);
        return ResponseEntity.ok().body(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(response)
                .build());
    }


    @PostMapping("/order-detail-binance")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Fetch Binance order detail for an account owned by the caller (ADMIN only).",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> orderDetailBinance(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody BinanceOrderDetailRequest binanceOrderDetailRequest) {
        UUID userId = extractUserId(authHeader);
        Account account = resolveAccount(userId, binanceOrderDetailRequest.getAccountId());

        binanceOrderDetailRequest.setApiKey(account.getApiKey());
        binanceOrderDetailRequest.setApiSecret(account.getApiSecret());

        BinanceOrderDetailResponse response = tradeExecutionService.getOrderDetailBinance(binanceOrderDetailRequest);
        return ResponseEntity.ok().body(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(response)
                .build());
    }

    private UUID extractUserId(String authHeader) {
        return jwtService.extractUserId(AuthHeaderUtil.extractToken(authHeader));
    }

    private Account resolveAccount(UUID userId, UUID accountId) {
        if (accountId == null) {
            throw new EntityNotFoundException("Account ID must not be null");
        }
        ownershipGuard.assertOwnsAccount(userId, accountId);
        return accountRepository.findByAccountId(accountId)
                .orElseThrow(() -> new EntityNotFoundException("Account not found: accountId=" + accountId));
    }
}

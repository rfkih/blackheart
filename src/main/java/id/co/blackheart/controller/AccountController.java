package id.co.blackheart.controller;

import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.user.AccountQueryService;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Read-only account summaries for the authenticated user.
 * Used by the frontend account switcher to enumerate Binance accounts without
 * exposing API keys/secrets.
 */
@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "AccountController", description = "Account inquiry endpoints (metadata only, never keys)")
public class AccountController {

    private final AccountQueryService accountQueryService;
    private final JwtService jwtService;

    @GetMapping
    @Operation(summary = "List all accounts owned by the authenticated user",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> listMyAccounts(
            @RequestHeader("Authorization") String authHeader) {
        UUID userId = extractUserId(authHeader);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(accountQueryService.getAccountsByUser(userId))
                .build());
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Get a single account owned by the authenticated user",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> getAccount(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID accountId) {
        UUID userId = extractUserId(authHeader);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(accountQueryService.getAccountForUser(userId, accountId))
                .build());
    }

    private UUID extractUserId(String authHeader) {
        return jwtService.extractUserId(authHeader.substring(7));
    }
}

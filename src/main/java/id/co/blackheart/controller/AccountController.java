package id.co.blackheart.controller;

import id.co.blackheart.dto.request.CreateAccountRequest;
import id.co.blackheart.dto.request.RotateAccountCredentialsRequest;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.user.AccountQueryService;
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

    @PostMapping
    @Operation(
            summary = "Create a new exchange account under the authenticated user",
            description = "Accepts API key + secret in the request body over HTTPS. "
                    + "The logging layer redacts sensitive fields before anything is written.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ResponseDto> createAccount(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody CreateAccountRequest request) {
        UUID userId = extractUserId(authHeader);
        return ResponseEntity.status(HttpStatus.CREATED).body(ResponseDto.builder()
                .responseCode(HttpStatus.CREATED.value() + ResponseCode.SUCCESS.getCode())
                .data(accountQueryService.createAccount(userId, request))
                .build());
    }

    @PatchMapping("/{accountId}/risk-config")
    @Operation(
            summary = "Update per-account risk policy: concurrency caps + vol-targeting toggle/target.",
            description = "Partial update — null fields are left unchanged. Bounds enforced server-side: "
                    + "concurrency caps in [0, 20], book vol target in (0, 50].",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ResponseDto> updateRiskConfig(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID accountId,
            @RequestBody id.co.blackheart.service.user.AccountQueryService.RiskConfigRequest request) {
        UUID userId = extractUserId(authHeader);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(accountQueryService.updateRiskConfig(userId, accountId, request))
                .build());
    }

    @PatchMapping("/{accountId}/credentials")
    @Operation(
            summary = "Rotate the Binance API key + secret for an account the caller owns",
            description = "Accepts a fresh key/secret pair over HTTPS. Both values are "
                    + "re-encrypted at rest via EncryptedStringConverter. Ownership is "
                    + "enforced server-side — foreign account IDs return 404.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ResponseDto> rotateCredentials(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID accountId,
            @Valid @RequestBody RotateAccountCredentialsRequest request) {
        UUID userId = extractUserId(authHeader);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(accountQueryService.rotateCredentials(userId, accountId, request))
                .build());
    }

    private UUID extractUserId(String authHeader) {
        return jwtService.extractUserId(AuthHeaderUtil.extractToken(authHeader));
    }
}

package id.co.blackheart.controller;

import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.tradequery.UnifiedTradeService;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.util.AuthHeaderUtil;
import id.co.blackheart.util.ResponseCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/trades")
@RequiredArgsConstructor
public class UnifiedTradeController {

    private final UnifiedTradeService unifiedTradeService;
    private final JwtService jwtService;

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

    private UUID extractUserId(String authHeader) {
        return jwtService.extractUserId(AuthHeaderUtil.extractToken(authHeader));
    }
}

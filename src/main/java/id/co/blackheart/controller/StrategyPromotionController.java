package id.co.blackheart.controller;

import com.fasterxml.jackson.databind.JsonNode;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.model.PaperTradeRun;
import id.co.blackheart.model.StrategyPromotionLog;
import id.co.blackheart.service.promotion.StrategyPromotionService;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Promotion-pipeline admin endpoints.
 *
 * <p>The promote endpoint is the ONLY supported way to flip
 * {@code account_strategy.simulated} between true/false. Direct
 * UPDATE on the column is technically possible but bypasses the
 * audit trail — operators are expected to go through this controller
 * so every state change has a reason + reviewer + evidence.
 */
@RestController
@RequestMapping("/api/v1/strategy-promotion")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "StrategyPromotionController",
     description = "Promote / demote account strategies between RESEARCH, PAPER_TRADE, PROMOTED, DEMOTED, REJECTED states.")
@PreAuthorize("hasRole('ADMIN')")
public class StrategyPromotionController {

    private final StrategyPromotionService promotionService;
    private final JwtService jwtService;

    @PostMapping("/{accountStrategyId}/promote")
    @Operation(summary = "Transition an account_strategy between promotion states",
               description = "Admin-only. Atomically updates account_strategy.simulated/.enabled "
                       + "AND writes a strategy_promotion_log row. Refuses illegal transitions "
                       + "(e.g. RESEARCH → PROMOTED skipping PAPER_TRADE).",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> promote(
            @PathVariable UUID accountStrategyId,
            @RequestBody PromoteRequest body,
            @RequestHeader("Authorization") String authHeader) {
        UUID reviewerUserId = jwtService.extractUserId(authHeader);
        StrategyPromotionLog row = promotionService.promote(
                accountStrategyId,
                body.getToState(),
                body.getReason(),
                body.getEvidence(),
                reviewerUserId
        );
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .responseDesc("Promotion recorded")
                .data(Map.of(
                        "promotionId", row.getPromotionId(),
                        "fromState", row.getFromState(),
                        "toState", row.getToState(),
                        "reviewerUserId", row.getReviewerUserId(),
                        "createdTime", row.getCreatedTime()
                ))
                .build());
    }

    @GetMapping("/{accountStrategyId}/state")
    @Operation(summary = "Current promotion state",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> currentState(@PathVariable UUID accountStrategyId) {
        String state = promotionService.currentState(accountStrategyId);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(Map.of("state", state))
                .build());
    }

    @GetMapping("/{accountStrategyId}/history")
    @Operation(summary = "Full promotion history (latest first)",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> history(@PathVariable UUID accountStrategyId) {
        List<StrategyPromotionLog> history = promotionService.history(accountStrategyId);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(history)
                .build());
    }

    @GetMapping("/recent")
    @Operation(summary = "Cross-strategy recent promotions feed",
               description = "Newest-first list of promotion log rows across every account_strategy. "
                       + "Used by the /research dashboard's recent-promotions panel. "
                       + "Default limit 50; capped at 200 server-side.",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> recent(
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        List<StrategyPromotionLog> rows = promotionService.recent(limit);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(rows)
                .build());
    }

    @GetMapping("/{accountStrategyId}/paper-trades")
    @Operation(summary = "Recent paper-trade events for the strategy (latest first)",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> paperTrades(@PathVariable UUID accountStrategyId) {
        List<PaperTradeRun> rows = promotionService.paperTrades(accountStrategyId);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(rows)
                .build());
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PromoteRequest {
        private String toState;
        private String reason;
        private JsonNode evidence;
    }
}

package id.co.blackheart.service.promotion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.strategy.EnrichedStrategyContext;
import id.co.blackheart.dto.strategy.StrategyDecision;
import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.model.AuditEvent;
import id.co.blackheart.model.PaperTradeRun;
import id.co.blackheart.model.StrategyPromotionLog;
import id.co.blackheart.repository.AccountStrategyRepository;
import id.co.blackheart.repository.AuditEventRepository;
import id.co.blackheart.repository.PaperTradeRunRepository;
import id.co.blackheart.repository.StrategyPromotionLogRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Promotion-pipeline service. Two responsibilities:
 *
 * <ol>
 *   <li><b>{@link #recordPaperTrade}</b> — called by
 *       {@code LiveTradingDecisionExecutorService} when an account_strategy
 *       has {@code simulated=true}. Persists the would-have-been order to
 *       {@code paper_trade_run} and returns; the live executor short-circuits
 *       the dispatch so no real Binance order is placed.</li>
 *   <li><b>{@link #promote}</b> — called by {@code StrategyPromotionController}
 *       on admin POST. Validates the requested state transition against the
 *       allowed graph (mirror of {@code chk_promotion_states} in V15),
 *       toggles {@code account_strategy.simulated}, and writes a
 *       {@code strategy_promotion_log} row in the same transaction so the
 *       audit trail and the strategy state stay consistent under failure.</li>
 * </ol>
 *
 * <p>Allowed transitions (also enforced by DB CHECK constraint):
 * <pre>
 *   RESEARCH    → PAPER_TRADE
 *   PAPER_TRADE → PROMOTED
 *   PAPER_TRADE → REJECTED
 *   PROMOTED    → DEMOTED
 *   PROMOTED    → PAPER_TRADE   (emergency: yank live capital)
 *   DEMOTED     → PAPER_TRADE
 *   REJECTED    → PAPER_TRADE   (re-quarantine)
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StrategyPromotionService {

    public static final String STATE_RESEARCH    = "RESEARCH";
    public static final String STATE_PAPER_TRADE = "PAPER_TRADE";
    public static final String STATE_PROMOTED    = "PROMOTED";
    public static final String STATE_REJECTED    = "REJECTED";
    public static final String STATE_DEMOTED     = "DEMOTED";

    /**
     * Allowed promotions/demotions. The same graph lives in V15's
     * chk_promotion_states; this is service-layer pre-validation so the
     * REST response is a clean 400 instead of a SQL constraint error.
     *
     * <p><b>SYNC WARNING:</b> this list and the SQL CHECK constraint in
     * {@code db/flyway/V15__create_promotion_pipeline.sql} (search for
     * {@code chk_promotion_states}) MUST stay in lockstep. If you add
     * an entry here, also add it to V15 (or a follow-up migration that
     * drops + recreates the constraint). Otherwise transitions will
     * pass service-layer validation and fail SQL with an opaque
     * constraint error, OR vice-versa — the worse failure mode.
     * (Bug 6 mitigation, 2026-04-28.)
     */
    private static final Set<String> ALLOWED_TRANSITIONS = Set.of(
            STATE_RESEARCH    + "→" + STATE_PAPER_TRADE,
            STATE_PAPER_TRADE + "→" + STATE_PROMOTED,
            STATE_PAPER_TRADE + "→" + STATE_REJECTED,
            STATE_PROMOTED    + "→" + STATE_DEMOTED,
            STATE_PROMOTED    + "→" + STATE_PAPER_TRADE,
            STATE_DEMOTED     + "→" + STATE_PAPER_TRADE,
            STATE_REJECTED    + "→" + STATE_PAPER_TRADE
    );

    private final AccountStrategyRepository accountStrategyRepository;
    private final PaperTradeRunRepository paperTradeRunRepository;
    private final StrategyPromotionLogRepository promotionLogRepository;
    private final AuditEventRepository auditEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * Called by the live executor. Records a simulated trade decision
     * without placing a real order. Failures here are LOGGED but never
     * thrown — losing one paper-trade row is preferable to crashing the
     * live executor on a transient DB hiccup. The live trade flow is
     * already gated by {@code account_strategy.simulated}; if recording
     * fails the only loss is one evidence point in {@code paper_trade_run},
     * not a real position.
     */
    public void recordPaperTrade(
            UUID accountStrategyId,
            StrategyDecision decision,
            EnrichedStrategyContext context
    ) {
        try {
            String side = inferSide(decision);
            BigDecimal price = context != null && context.getMarketData() != null
                    ? context.getMarketData().getClosePrice()
                    : null;

            JsonNode decisionSnap = decision != null
                    ? objectMapper.valueToTree(decision)
                    : null;
            JsonNode contextSnap = context != null
                    ? objectMapper.valueToTree(buildContextSnapshot(context))
                    : null;

            PaperTradeRun row = PaperTradeRun.builder()
                    .paperTradeId(UUID.randomUUID())
                    .accountStrategyId(accountStrategyId)
                    .strategyCode(decision != null ? decision.getStrategyCode() : "UNKNOWN")
                    .symbol(context != null && context.getAccountStrategy() != null
                            ? context.getAccountStrategy().getSymbol()
                            : "UNKNOWN")
                    .intervalName(context != null && context.getAccountStrategy() != null
                            ? context.getAccountStrategy().getIntervalName()
                            : "UNKNOWN")
                    .decisionType(decision != null && decision.getDecisionType() != null
                            ? decision.getDecisionType().name()
                            : "UNKNOWN")
                    .side(side)
                    .intendedPrice(price)
                    .intendedQuantity(decision != null ? decision.getPositionSize() : null)
                    .intendedNotionalUsdt(decision != null ? decision.getNotionalSize() : null)
                    .stopLossPrice(decision != null ? decision.getStopLossPrice() : null)
                    .takeProfitPrice(decision != null ? decision.getTakeProfitPrice1() : null)
                    .decisionSnapshot(decisionSnap)
                    .contextSnapshot(contextSnap)
                    .skipReason("SIMULATED")
                    .createdTime(LocalDateTime.now())
                    .build();

            paperTradeRunRepository.save(row);
            log.info("[PaperTrade] recorded | strategy={} decision={} side={} price={}",
                    row.getStrategyCode(), row.getDecisionType(), side, price);
        } catch (Exception e) {
            // Live flow is intentionally not affected — losing one paper-
            // trade row is preferable to crashing the live executor on a
            // transient DB hiccup. But the failure MUST be operator-
            // visible so evidence loss isn't silent (Bug 4 fix, 2026-04-28).
            // Three signals:
            //   1. ERROR-level log with grep-able tag — surfaces in journald
            //   2. audit_event row written best-effort — surfaces in audit UI
            //   3. error message includes accountStrategyId so the operator
            //      can identify which strategy lost evidence
            log.error("[PaperTrade-EvidenceLoss] failed to record simulated decision " +
                            "| accountStrategyId={} strategy={} decision={} — investigate DB health",
                    accountStrategyId,
                    decision != null ? decision.getStrategyCode() : null,
                    decision != null ? decision.getDecisionType() : null, e);
            recordPaperTradeFailureAudit(accountStrategyId, decision, e);
        }
    }

    /**
     * Best-effort audit row when {@code recordPaperTrade} throws. Direct
     * persistence (not via {@code AuditService}) so we don't require an
     * existing transaction in the live-executor hot path. Catches
     * everything internally because audit failure during failure handling
     * must not propagate.
     */
    private void recordPaperTradeFailureAudit(
            UUID accountStrategyId,
            StrategyDecision decision,
            Exception cause
    ) {
        try {
            AuditEvent ae = AuditEvent.builder()
                    .auditEventId(UUID.randomUUID())
                    .actorUserId(null)  // system, no human actor
                    .action("PAPER_TRADE_RECORD_FAILED")
                    .entityType("AccountStrategy")
                    .entityId(accountStrategyId)
                    .reason("Failed to persist paper_trade_run row: "
                            + (cause != null ? cause.getClass().getSimpleName()
                                    + ": " + cause.getMessage() : "unknown"))
                    .createdAt(LocalDateTime.now())
                    .build();
            auditEventRepository.save(ae);
        } catch (Exception nested) {
            // Audit-write failure during error handling. Last-resort log only.
            log.error("[PaperTrade-EvidenceLoss] audit row also failed", nested);
        }
    }

    /**
     * Promote/demote an account_strategy. Atomic: the state-flag flip on
     * {@code account_strategy} and the {@code strategy_promotion_log}
     * row are in the same transaction.
     */
    @Transactional
    public StrategyPromotionLog promote(
            UUID accountStrategyId,
            String toState,
            String reason,
            JsonNode evidence,
            UUID reviewerUserId
    ) {
        if (toState == null || toState.isBlank()) {
            throw new IllegalArgumentException("toState is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason is required for every promotion");
        }

        // Pessimistic lock so concurrent promote() calls serialize on this
        // row. Without the lock, two admins clicking "promote" at the same
        // moment could both see currentState=PAPER_TRADE and both insert
        // PAPER_TRADE→PROMOTED rows. The DB CHECK constraint accepts both
        // because each is individually legal — duplicate is the result.
        // (Bug 3 fix, 2026-04-28.)
        AccountStrategy as = accountStrategyRepository.findByIdForUpdate(accountStrategyId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "account_strategy not found: " + accountStrategyId));

        // Read state INSIDE the lock window so the second concurrent
        // caller sees the post-flip state (or waits at findByIdForUpdate
        // and then sees the new row when its query runs).
        String fromState = currentState(accountStrategyId);
        String key = fromState + "→" + toState;
        if (!ALLOWED_TRANSITIONS.contains(key)) {
            throw new IllegalStateException(
                    "Illegal promotion: " + fromState + " → " + toState
                            + ". See StrategyPromotionService.ALLOWED_TRANSITIONS for the legal graph.");
        }

        // Apply the state flip on account_strategy. The boolean fields
        // encode the state directly; the promotion_log row carries the
        // semantic name.
        applyStateToAccountStrategy(as, toState);
        accountStrategyRepository.save(as);

        StrategyPromotionLog logRow = StrategyPromotionLog.builder()
                .promotionId(UUID.randomUUID())
                .accountStrategyId(accountStrategyId)
                .strategyCode(as.getStrategyCode())
                .fromState(fromState)
                .toState(toState)
                .reviewerUserId(reviewerUserId)
                .reason(reason)
                .evidence(evidence)
                .createdTime(LocalDateTime.now())
                .build();
        promotionLogRepository.save(logRow);

        log.warn("[Promotion] strategy={} as_id={} {} → {} reviewer={} reason={}",
                as.getStrategyCode(), accountStrategyId, fromState, toState,
                reviewerUserId, reason);

        return logRow;
    }

    /**
     * Inspect last promotion-log row to derive current state. If no row
     * exists, the strategy has never been promoted out of RESEARCH —
     * return RESEARCH regardless of {@code enabled}/{@code simulated}.
     * (Pre-V15 strategies that were enabled directly via UPDATE are
     * grandfathered as RESEARCH so their first promote-event has a clean
     * audit trail.)
     */
    public String currentState(UUID accountStrategyId) {
        Optional<StrategyPromotionLog> last =
                promotionLogRepository.findFirstByAccountStrategyIdOrderByCreatedTimeDesc(accountStrategyId);
        return last.map(StrategyPromotionLog::getToState).orElse(STATE_RESEARCH);
    }

    public List<StrategyPromotionLog> history(UUID accountStrategyId) {
        return promotionLogRepository.findByAccountStrategyIdOrderByCreatedTimeDesc(accountStrategyId);
    }

    public List<PaperTradeRun> paperTrades(UUID accountStrategyId) {
        return paperTradeRunRepository.findByAccountStrategyIdOrderByCreatedTimeDesc(accountStrategyId);
    }

    /**
     * Cross-strategy promotion feed for the /research dashboard. Returns the
     * most-recent {@code limit} rows across every account_strategy, newest
     * first. Capped at 200 to keep payload small even if the caller passes
     * something extreme; the dashboard polls this every 30s.
     */
    public List<StrategyPromotionLog> recent(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 200);
        return promotionLogRepository.findRecent(
                org.springframework.data.domain.PageRequest.of(0, safeLimit));
    }

    // ── internals ────────────────────────────────────────────────────────

    private void applyStateToAccountStrategy(AccountStrategy as, String toState) {
        switch (toState) {
            case STATE_PAPER_TRADE -> {
                as.setEnabled(Boolean.TRUE);
                as.setSimulated(Boolean.TRUE);
            }
            case STATE_PROMOTED -> {
                as.setEnabled(Boolean.TRUE);
                as.setSimulated(Boolean.FALSE);
            }
            case STATE_DEMOTED, STATE_REJECTED -> {
                as.setEnabled(Boolean.FALSE);
                as.setSimulated(Boolean.FALSE);
            }
            default -> throw new IllegalStateException("Unmapped toState: " + toState);
        }
    }

    private String inferSide(StrategyDecision decision) {
        if (decision == null || decision.getDecisionType() == null) return null;
        return switch (decision.getDecisionType()) {
            case OPEN_LONG, CLOSE_LONG -> "LONG";
            case OPEN_SHORT, CLOSE_SHORT -> "SHORT";
            default -> null;
        };
    }

    private Map<String, Object> buildContextSnapshot(EnrichedStrategyContext ctx) {
        Map<String, Object> snap = new HashMap<>();
        if (ctx.getAccountStrategy() != null) {
            snap.put("accountStrategyId", ctx.getAccountStrategy().getAccountStrategyId());
            snap.put("symbol", ctx.getAccountStrategy().getSymbol());
            snap.put("interval", ctx.getAccountStrategy().getIntervalName());
        }
        if (ctx.getMarketData() != null) {
            snap.put("startTime", ctx.getMarketData().getStartTime());
            snap.put("close", ctx.getMarketData().getClosePrice());
        }
        return snap;
    }
}

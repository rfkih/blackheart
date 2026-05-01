package id.co.blackheart.engine;

import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.repository.AccountStrategyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Trips {@code account_strategy.is_kill_switch_tripped} when the engine
 * decides a strategy has misbehaved enough that new entries must be blocked
 * until an operator looks at it. Used by {@link EngineMetrics} to enforce
 * invariant 9 (spec error rate kill-switch).
 *
 * <p>Writes happen in a {@code PROPAGATION_REQUIRES_NEW} transaction so a DB
 * hiccup here can never roll back the live caller's transaction. If the trip
 * fails we log loudly and return — the next error will retry. The drawdown
 * kill-switch path uses the same field, so a manual rearm via
 * {@code POST /api/v1/account-strategies/:id/rearm} clears either trigger.
 */
@Component
@Slf4j
public class EngineKillSwitchService {

    private final AccountStrategyRepository repository;
    private final TransactionTemplate isolatedTx;

    @Autowired
    public EngineKillSwitchService(AccountStrategyRepository repository,
                                   PlatformTransactionManager txManager) {
        this.repository = repository;
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.isolatedTx = tx;
    }

    /**
     * Test-only no-arg constructor. Subclasses in unit tests can override
     * {@link #tripFromEngineErrors(UUID, String)} to record calls without
     * needing a real repository or transaction manager. Production code must
     * use the public DI constructor — Spring will not pick this one because
     * it's protected.
     */
    protected EngineKillSwitchService() {
        this.repository = null;
        this.isolatedTx = null;
    }

    /**
     * Trip the kill switch for the given account strategy. Idempotent — a
     * row already tripped (by drawdown or a prior error spike) is left alone
     * so we don't overwrite the original {@code killSwitchReason} that the
     * operator will be looking at.
     */
    public void tripFromEngineErrors(UUID accountStrategyId, String reason) {
        if (accountStrategyId == null) return;
        try {
            isolatedTx.executeWithoutResult(status -> {
                AccountStrategy as = repository.findById(accountStrategyId).orElse(null);
                if (as == null) {
                    log.warn("Cannot trip kill switch — accountStrategyId={} not found", accountStrategyId);
                    return;
                }
                if (Boolean.TRUE.equals(as.getIsKillSwitchTripped())) {
                    return;
                }
                as.setIsKillSwitchTripped(Boolean.TRUE);
                as.setKillSwitchTrippedAt(LocalDateTime.now());
                as.setKillSwitchReason(reason);
                repository.save(as);
                log.error("Engine kill switch TRIPPED | accountStrategyId={} strategyCode={} reason={}",
                        accountStrategyId, as.getStrategyCode(), reason);
            });
        } catch (RuntimeException ex) {
            log.error("Failed to trip engine kill switch for accountStrategyId={} — will retry on next error",
                    accountStrategyId, ex);
        }
    }
}

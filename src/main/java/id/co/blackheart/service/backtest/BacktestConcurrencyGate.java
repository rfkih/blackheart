package id.co.blackheart.service.backtest;

import id.co.blackheart.exception.BacktestConcurrencyLimitException;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.repository.BacktestRunRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Per-user concurrency gate for backtest submissions. Runs in a dedicated
 * REQUIRES_NEW transaction so the advisory lock, the in-flight count, and
 * the INSERT are atomic from the perspective of concurrent callers from the
 * same user — preventing two simultaneous requests from both seeing "0
 * in-flight" and both succeeding.
 *
 * <p>Limits: research agent (pinned UUID from V54) → 3; everyone else → 1.
 * Both limits are externalized so operators can tune without a redeploy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestConcurrencyGate {

    private static final List<String> IN_FLIGHT_STATUSES = List.of("PENDING", "RUNNING");

    @Value("${blackheart.research-agent.user-id:99999999-9999-9999-9999-000000000001}")
    private String researchAgentUserIdStr;

    @Value("${app.backtest.limit.regular-user:1}")
    private int regularUserLimit;

    @Value("${app.backtest.limit.research-agent:3}")
    private int researchAgentLimit;

    // Cached at first use to avoid UUID.fromString on every gate call.
    private volatile UUID researchAgentUserId;

    private final BacktestRunRepository backtestRunRepository;
    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BacktestRun checkAndSave(BacktestRun run) {
        UUID userId = run.getUserId();
        int limit = limitForUser(userId);

        // pg_try_advisory_xact_lock auto-releases at tx end (REQUIRES_NEW).
        // Non-blocking: if another request from the same user is already inside
        // this gate, the lock returns false → treat as "at capacity".
        if (!acquireAdvisoryLock(userId)) {
            throw new BacktestConcurrencyLimitException(
                    "A backtest request is already being submitted for this account. Try again in a moment.");
        }

        long inFlight = backtestRunRepository.countByUserIdAndStatusIn(userId, IN_FLIGHT_STATUSES);
        if (inFlight >= limit) {
            throw new BacktestConcurrencyLimitException(
                    "Maximum concurrent backtests reached (" + limit + "). "
                    + "Wait for a running backtest to finish before submitting another.");
        }

        log.debug("Concurrency gate passed | userId={} inFlight={} limit={}", userId, inFlight, limit);
        return backtestRunRepository.save(run);
    }

    private boolean acquireAdvisoryLock(UUID userId) {
        Object result = entityManager
                .createNativeQuery("SELECT pg_try_advisory_xact_lock(hashtext(:userId))")
                .setParameter("userId", userId != null ? userId.toString() : "anonymous")
                .getSingleResult();
        return Boolean.TRUE.equals(result);
    }

    private int limitForUser(UUID userId) {
        if (userId == null) return regularUserLimit;
        if (researchAgentUserId == null) {
            researchAgentUserId = UUID.fromString(researchAgentUserIdStr);
        }
        return userId.equals(researchAgentUserId) ? researchAgentLimit : regularUserLimit;
    }
}

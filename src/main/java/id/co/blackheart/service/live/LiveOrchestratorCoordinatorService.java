package id.co.blackheart.service.live;

import id.co.blackheart.model.Account;
import id.co.blackheart.model.AccountStrategy;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.model.Trades;
import id.co.blackheart.repository.TradesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrates live trading for users who have 2 or more active {@link AccountStrategy} records
 * on the same interval.
 *
 * <p><b>Entry phase (no active trade):</b> strategies are evaluated in ascending
 * {@link AccountStrategy#getPriorityOrder()} order. The first strategy that produces an entry
 * signal (OPEN_LONG / OPEN_SHORT) executes the trade; remaining strategies are skipped.
 *
 * <p><b>Active trade phase (trade already open):</b> only the strategy whose
 * {@code accountStrategyId} matches the active trade's {@code accountStrategyId} is executed.
 * All other strategies in the group are skipped until the trade closes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveOrchestratorCoordinatorService {

    private static final List<String> ACTIVE_STATUSES = List.of("OPEN", "PARTIALLY_CLOSED");

    private final TradesRepository tradesRepository;
    private final LiveTradingCoordinatorService liveTradingCoordinatorService;

    public void process(
            Account account,
            List<AccountStrategy> strategies,
            String asset,
            String interval,
            MarketData marketData,
            FeatureStore featureStore
    ) {
        if (strategies == null || strategies.size() < 2) {
            log.warn("[Orchestrator] Requires at least 2 strategies, got={}. Skipping account={}",
                    strategies == null ? 0 : strategies.size(), account.getAccountId());
            return;
        }
        log.info("orchestrator service {}", strategies.size());

        // Sort by priorityOrder ascending — lower number = higher priority.
        List<AccountStrategy> sorted = strategies.stream()
                .sorted(Comparator.comparingInt(AccountStrategy::getPriorityOrder))
                .toList();

        List<UUID> strategyIds = sorted.stream()
                .map(AccountStrategy::getAccountStrategyId)
                .toList();

        List<Trades> activeTrades = tradesRepository.findAllActiveTradesForStrategies(
                account.getAccountId(), strategyIds, asset, interval, ACTIVE_STATUSES);

        if (!activeTrades.isEmpty()) {
            routeToOwner(account, sorted, activeTrades.getFirst(), asset, interval, marketData, featureStore);
        } else {
            fanOutForEntry(account, sorted, asset, interval, marketData, featureStore);
        }
    }

    // ── Active trade routing ───────────────────────────────────────────────────

    /**
     * Finds the strategy that owns the active trade and delegates exclusively to it.
     */
    private void routeToOwner(
            Account account,
            List<AccountStrategy> sorted,
            Trades activeTrade,
            String asset,
            String interval,
            MarketData marketData,
            FeatureStore featureStore
    ) {
        UUID ownerStrategyId = activeTrade.getAccountStrategyId();

        AccountStrategy ownerStrategy = sorted.stream()
                .filter(s -> s.getAccountStrategyId().equals(ownerStrategyId))
                .findFirst()
                .orElse(null);

        if (ownerStrategy == null) {
            log.warn("Orchestrator: active trade {} owner accountStrategyId={} not found in group for account={}",
                    activeTrade.getTradeId(), ownerStrategyId, account.getAccountId());
            return;
        }

        log.debug("Orchestrator routing active trade to strategy={}", ownerStrategy.getStrategyCode());
        liveTradingCoordinatorService.process(account, ownerStrategy, asset, interval, marketData, featureStore);
    }

    // ── Entry fan-out ──────────────────────────────────────────────────────────

    /**
     * Runs strategies in priority order. Stops as soon as one opens a trade.
     * Uses a post-execution DB check to detect whether each strategy opened a trade,
     * since {@link LiveTradingCoordinatorService#process} handles execution internally.
     */
    private void fanOutForEntry(
            Account account,
            List<AccountStrategy> sorted,
            String asset,
            String interval,
            MarketData marketData,
            FeatureStore featureStore
    ) {
        for (AccountStrategy strategy : sorted) {
            log.info("orchestrator service detail {}", strategy);
            liveTradingCoordinatorService.process(account, strategy, asset, interval, marketData, featureStore);

            // Check whether this strategy just opened a trade.
            List<Trades> newTrades = tradesRepository.findAllActiveTradesForStrategies(
                    account.getAccountId(),
                    List.of(strategy.getAccountStrategyId()),
                    asset, interval, ACTIVE_STATUSES);

            if (!newTrades.isEmpty()) {
                log.info("Orchestrator: strategy={} opened a trade, stopping further entry evaluation",
                        strategy.getStrategyCode());
                return;
            }
        }
    }
}
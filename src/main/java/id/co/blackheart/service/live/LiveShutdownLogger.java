package id.co.blackheart.service.live;

import id.co.blackheart.model.Trades;
import id.co.blackheart.repository.TradesRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Forensic shutdown snapshot for the trading JVM. On SIGTERM, after Spring
 * has stopped accepting new HTTP traffic but before beans are torn down,
 * dumps the set of currently-open trades to the log so an operator
 * investigating "what was live when we restarted" has a single grep target.
 *
 * <p>Pairs with {@code server.shutdown=graceful} +
 * {@code spring.lifecycle.timeout-per-shutdown-phase}: the graceful window
 * lets in-flight Binance order RPCs finish; this bean records what was open
 * at the moment shutdown was initiated.
 *
 * <p>Trading JVM only — research never opens trades. Gating via
 * {@code @Profile("!research")} keeps the research JVM from emitting a
 * misleading "0 open trades" line on every restart.
 */
@Component
@Profile("!research")
@RequiredArgsConstructor
@Slf4j
public class LiveShutdownLogger {

    private final TradesRepository tradesRepository;

    @PreDestroy
    public void logOpenTradeSnapshot() {
        try {
            List<Trades> open = tradesRepository.findAllOpen();
            if (open.isEmpty()) {
                log.info("[shutdown] No open trades at shutdown — clean state.");
                return;
            }
            log.warn("[shutdown] {} open trade(s) at shutdown initiation. Operator review recommended.",
                    open.size());
            for (Trades t : open) {
                log.warn("[shutdown] OPEN tradeId={} accountStrategyId={} asset={} interval={} side={} status={} entryTime={}",
                        t.getTradeId(),
                        t.getAccountStrategyId(),
                        t.getAsset(),
                        t.getInterval(),
                        t.getSide(),
                        t.getStatus(),
                        t.getEntryTime());
            }
        } catch (RuntimeException ex) {
            log.warn("[shutdown] Failed to capture open-trade snapshot — continuing shutdown", ex);
        }
    }
}

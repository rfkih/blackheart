package id.co.blackheart.projection;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Row projection for {@code TradesRepository.findAnomaliesByAccountIds}.
 * Carries the parent-trade state plus a count of OPEN child positions so the
 * service layer can classify each row into a stuck-trade anomaly type without
 * a second per-row fetch.
 */
public interface TradeAnomalyProjection {

    UUID getTradeId();

    UUID getAccountId();

    UUID getAccountStrategyId();

    String getAsset();

    String getInterval();

    String getSide();

    String getStatus();

    LocalDateTime getEntryTime();

    Long getTotalLegs();

    Long getOpenLegs();
}

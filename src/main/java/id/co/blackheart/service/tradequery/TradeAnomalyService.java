package id.co.blackheart.service.tradequery;

import id.co.blackheart.dto.response.TradeAnomalyResponse;
import id.co.blackheart.model.Account;
import id.co.blackheart.projection.TradeAnomalyProjection;
import id.co.blackheart.repository.AccountRepository;
import id.co.blackheart.repository.TradesRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Read-side classifier for the admin stuck-trade panel. Walks every non-CLOSED
 * trade for the caller's accounts together with its open-leg count and tags
 * each as a specific anomaly type — the panel uses the tag to render its
 * badge and triage hint.
 *
 * <p>Healthy state returns an empty list; the dashboard renders nothing.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradeAnomalyService {

    private final TradesRepository tradesRepository;
    private final AccountRepository accountRepository;

    public List<TradeAnomalyResponse> getAnomaliesForUser(UUID userId) {
        List<Account> accounts = accountRepository.findByUserId(userId);
        if (accounts.isEmpty()) return Collections.emptyList();

        List<UUID> accountIds = accounts.stream().map(Account::getAccountId).toList();
        List<TradeAnomalyProjection> rows = tradesRepository.findAnomaliesByAccountIds(accountIds);

        return rows.stream()
                .map(this::classify)
                .filter(r -> r != null)
                .toList();
    }

    private TradeAnomalyResponse classify(TradeAnomalyProjection p) {
        long total = p.getTotalLegs() == null ? 0L : p.getTotalLegs();
        long open = p.getOpenLegs() == null ? 0L : p.getOpenLegs();
        String status = p.getStatus();

        String type;
        if ("OPEN".equalsIgnoreCase(status) && total == 0L) {
            type = "OPEN_NO_CHILDREN";
        } else if ("OPEN".equalsIgnoreCase(status) && open == 0L) {
            type = "OPEN_NO_OPEN_CHILDREN";
        } else if ("PARTIALLY_CLOSED".equalsIgnoreCase(status) && open == 0L) {
            type = "PARTIAL_NO_OPEN_CHILDREN";
        } else {
            // Healthy: OPEN with at least one OPEN leg, or PARTIALLY_CLOSED with
            // at least one OPEN leg. Drop from the result set.
            return null;
        }

        return TradeAnomalyResponse.builder()
                .tradeId(p.getTradeId())
                .accountId(p.getAccountId())
                .accountStrategyId(p.getAccountStrategyId())
                .asset(p.getAsset())
                .interval(p.getInterval())
                .side(p.getSide())
                .status(status)
                .entryTime(p.getEntryTime())
                .totalLegs(total)
                .openLegs(open)
                .anomalyType(type)
                .build();
    }
}

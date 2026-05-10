package id.co.blackheart.service.live;

import id.co.blackheart.dto.strategy.PositionSnapshot;
import id.co.blackheart.dto.tradelistener.ListenerContext;
import id.co.blackheart.dto.tradelistener.ListenerDecision;
import id.co.blackheart.model.TradePosition;
import id.co.blackheart.model.Account;
import id.co.blackheart.repository.TradePositionRepository;
import id.co.blackheart.repository.AccountRepository;
import id.co.blackheart.service.tradelistener.TradeListenerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveTradeListenerService {

    private static final String STATUS_OPEN = "OPEN";

    private final TradePositionRepository tradePositionRepository;
    private final AccountRepository accountRepository;
    private final LivePositionSnapshotMapper livePositionSnapshotMapper;
    private final TradeListenerService tradeListenerService;
    private final LiveTradingDecisionExecutorService liveTradingDecisionExecutorService;

    public void process(String asset, BigDecimal latestPrice) {
        List<TradePosition> activeTradePositions =
                tradePositionRepository.findAllByAssetAndStatus(asset, STATUS_OPEN);

        if (activeTradePositions.isEmpty()) {
            return;
        }

        Map<String, TriggeredGroup> grouped = collectTriggeredGroups(asset, latestPrice, activeTradePositions);
        if (grouped.isEmpty()) {
            return;
        }

        for (TriggeredGroup group : grouped.values()) {
            executeGroup(asset, group);
        }
    }

    private Map<String, TriggeredGroup> collectTriggeredGroups(
            String asset, BigDecimal latestPrice, List<TradePosition> activeTradePositions) {
        Map<String, TriggeredGroup> grouped = new LinkedHashMap<>();
        for (TradePosition position : activeTradePositions) {
            try {
                addPositionIfTriggered(asset, latestPrice, position, grouped);
            } catch (Exception e) {
                log.error("Live listener failed | tradePositionId={} asset={}",
                        position.getTradePositionId(), asset, e);
            }
        }
        return grouped;
    }

    private void addPositionIfTriggered(
            String asset,
            BigDecimal latestPrice,
            TradePosition position,
            Map<String, TriggeredGroup> grouped) {
        PositionSnapshot snapshot = livePositionSnapshotMapper.toSnapshot(position);
        ListenerContext context = ListenerContext.builder()
                .asset(asset)
                .interval(position.getInterval())
                .positionSnapshot(snapshot)
                .latestPrice(latestPrice)
                .build();

        ListenerDecision decision = tradeListenerService.evaluate(context);
        if (decision == null || !decision.isTriggered()) return;

        String groupKey = buildGroupKey(position, decision);
        grouped.computeIfAbsent(groupKey, k -> new TriggeredGroup(decision))
                .positions
                .add(position);
    }

    private void executeGroup(String asset, TriggeredGroup group) {
        List<TradePosition> positions = group.positions;
        if (positions.isEmpty()) return;

        TradePosition firstPosition = positions.getFirst();
        Account user = accountRepository.findByAccountId(firstPosition.getAccountId()).orElse(null);
        if (user == null) {
            log.warn("Listener close skipped because user not found | tradeId={} accountId={} groupSize={}",
                    firstPosition.getTradeId(), firstPosition.getAccountId(), positions.size());
            return;
        }

        ListenerDecision listenerDecision = group.listenerDecision;
        try {
            if (positions.size() == 1) {
                liveTradingDecisionExecutorService.executeListenerClosePosition(
                        user, firstPosition, asset, listenerDecision);
            } else {
                liveTradingDecisionExecutorService.executeListenerClosePositions(
                        user, positions, asset, listenerDecision);
            }

            log.info("Grouped listener close executed | asset={} tradeId={} side={} stop={} groupSize={} exitReason={}",
                    asset,
                    firstPosition.getTradeId(),
                    firstPosition.getSide(),
                    normalizePrice(firstPosition.getCurrentStopLossPrice()),
                    positions.size(),
                    listenerDecision.getExitReason());

        } catch (Exception e) {
            log.error("Grouped live listener close failed | tradeId={} asset={} groupSize={}",
                    firstPosition.getTradeId(), asset, positions.size(), e);
        }
    }

    private String buildGroupKey(TradePosition position, ListenerDecision listenerDecision) {
        return new StringBuilder()
                .append(nullSafeUuid(position.getAccountId())).append('|')
                .append(nullSafeUuid(position.getTradeId())).append('|')
                .append(nullSafe(position.getSide())).append('|')
                .append(nullSafe(position.getAsset())).append('|')
                .append(normalizePrice(resolveGroupingExitPrice(position, listenerDecision))).append('|')
                .append(nullSafe(listenerDecision.getExitReason()))
                .toString();
    }

    private BigDecimal resolveGroupingExitPrice(TradePosition position, ListenerDecision listenerDecision) {
        if (listenerDecision == null || listenerDecision.getExitReason() == null) {
            return null;
        }

        String exitReason = listenerDecision.getExitReason();

        if ("STOP_LOSS".equalsIgnoreCase(exitReason)) {
            return position.getCurrentStopLossPrice();
        }

        if ("TRAILING_STOP".equalsIgnoreCase(exitReason)) {
            return position.getTrailingStopPrice();
        }

        if ("TAKE_PROFIT".equalsIgnoreCase(exitReason)) {
            return position.getTakeProfitPrice();
        }

        return listenerDecision.getExitPrice();
    }

    private String normalizePrice(BigDecimal price) {
        return price == null ? "NULL" : price.stripTrailingZeros().toPlainString();
    }

    private String nullSafe(String value) {
        return value == null ? "NULL" : value;
    }

    private String nullSafeUuid(UUID value) {
        return value == null ? "NULL" : value.toString();
    }

    private static class TriggeredGroup {
        private final ListenerDecision listenerDecision;
        private final List<TradePosition> positions = new ArrayList<>();

        private TriggeredGroup(ListenerDecision listenerDecision) {
            this.listenerDecision = listenerDecision;
        }
    }
}
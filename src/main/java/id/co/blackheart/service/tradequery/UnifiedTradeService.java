package id.co.blackheart.service.tradequery;

import id.co.blackheart.dto.response.TradePositionResponse;
import id.co.blackheart.dto.response.TradeResponse;
import id.co.blackheart.model.Account;
import id.co.blackheart.model.TradePosition;
import id.co.blackheart.model.Trades;
import id.co.blackheart.repository.AccountRepository;
import id.co.blackheart.repository.TradePositionRepository;
import id.co.blackheart.repository.TradesRepository;
import id.co.blackheart.service.cache.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UnifiedTradeService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final TradesRepository tradesRepository;
    private final TradePositionRepository tradePositionRepository;
    private final AccountRepository accountRepository;
    private final CacheService cacheService;

    public List<TradeResponse> getTrades(UUID userId, String status, UUID accountId, int limit, int page, int size) {
        List<UUID> accountIds = resolveAccountIds(userId, accountId);
        if (accountIds.isEmpty()) {
            return Collections.emptyList();
        }

        int effectiveSize = size > 0 ? Math.min(size, MAX_LIMIT) : (limit > 0 ? Math.min(limit, MAX_LIMIT) : DEFAULT_LIMIT);
        int offset = page * effectiveSize;

        List<Trades> trades;
        if (status != null && !status.isBlank()) {
            trades = tradesRepository.findByAccountIdsAndStatus(accountIds, status.toUpperCase(), effectiveSize, offset);
        } else {
            trades = tradesRepository.findByAccountIds(accountIds, effectiveSize, offset);
        }

        return trades.stream()
                .map(t -> toTradeResponse(t, false))
                .collect(Collectors.toList());
    }

    public TradeResponse getTradeById(UUID userId, UUID tradeId) {
        Trades trade = tradesRepository.findByTradeId(tradeId)
                .orElseThrow(() -> new IllegalArgumentException("Trade not found: " + tradeId));

        List<UUID> accountIds = resolveAccountIds(userId, null);
        if (!accountIds.contains(trade.getAccountId())) {
            throw new IllegalArgumentException("Trade not found: " + tradeId);
        }

        return toTradeResponse(trade, true);
    }

    private List<UUID> resolveAccountIds(UUID userId, UUID accountId) {
        if (accountId != null) {
            return List.of(accountId);
        }
        return accountRepository.findByUserId(userId).stream()
                .map(Account::getAccountId)
                .collect(Collectors.toList());
    }

    private TradeResponse toTradeResponse(Trades t, boolean includePositions) {
        BigDecimal unrealizedPnl = null;
        BigDecimal markPrice = null;
        BigDecimal unrealizedPnlPct = null;

        boolean isOpen = "OPEN".equalsIgnoreCase(t.getStatus()) || "PARTIALLY_CLOSED".equalsIgnoreCase(t.getStatus());
        if (isOpen) {
            try {
                markPrice = cacheService.getLatestPrice(t.getAsset());
                if (markPrice != null && t.getAvgEntryPrice() != null && t.getTotalRemainingQty() != null) {
                    unrealizedPnl = computeUnrealizedPnl(t.getAvgEntryPrice(), markPrice, t.getTotalRemainingQty(), t.getSide());
                    BigDecimal costBasis = t.getAvgEntryPrice().multiply(t.getTotalRemainingQty());
                    if (costBasis.compareTo(BigDecimal.ZERO) > 0) {
                        unrealizedPnlPct = unrealizedPnl.divide(costBasis, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
                    }
                }
            } catch (Exception e) {
                log.warn("Could not enrich trade {} with live price", t.getTradeId());
            }
        }

        List<TradePosition> positions = includePositions
                ? tradePositionRepository.findAllByTradeId(t.getTradeId())
                : Collections.emptyList();

        BigDecimal stopLoss = null;
        BigDecimal tp1 = null;
        BigDecimal tp2 = null;
        if (!positions.isEmpty()) {
            stopLoss = positions.stream()
                    .map(TradePosition::getCurrentStopLossPrice)
                    .filter(p -> p != null && p.compareTo(BigDecimal.ZERO) > 0)
                    .findFirst().orElse(null);
            tp1 = positionTp(positions, "TP1");
            tp2 = positionTp(positions, "TP2");
            if (tp1 == null) tp1 = positionTp(positions, "SINGLE");
        }

        return TradeResponse.builder()
                .id(t.getTradeId())
                .accountId(t.getAccountId())
                .accountStrategyId(t.getAccountStrategyId())
                .strategyCode(t.getStrategyName())
                .symbol(t.getAsset())
                .direction(t.getSide())
                .status(t.getStatus())
                .entryTime(toEpochMs(t.getEntryTime()))
                .entryPrice(t.getAvgEntryPrice())
                .exitTime(toEpochMs(t.getExitTime()))
                .exitAvgPrice(t.getAvgExitPrice())
                .stopLossPrice(stopLoss)
                .tp1Price(tp1)
                .tp2Price(tp2)
                .quantity(t.getTotalEntryQty())
                .realizedPnl(t.getRealizedPnlAmount())
                .unrealizedPnl(unrealizedPnl)
                .feeUsdt(t.getTotalFeeAmount())
                .markPrice(markPrice)
                .unrealizedPnlPct(unrealizedPnlPct)
                .positions(positions.stream().map(this::toPositionResponse).collect(Collectors.toList()))
                .build();
    }

    private BigDecimal positionTp(List<TradePosition> positions, String role) {
        return positions.stream()
                .filter(p -> role.equalsIgnoreCase(p.getPositionRole()))
                .map(TradePosition::getTakeProfitPrice)
                .filter(p -> p != null && p.compareTo(BigDecimal.ZERO) > 0)
                .findFirst().orElse(null);
    }

    private TradePositionResponse toPositionResponse(TradePosition p) {
        BigDecimal fee = BigDecimal.ZERO;
        if (p.getEntryFee() != null) fee = fee.add(p.getEntryFee());
        if (p.getExitFee() != null) fee = fee.add(p.getExitFee());
        return TradePositionResponse.builder()
                .id(p.getTradePositionId())
                .tradeId(p.getTradeId())
                .type(p.getPositionRole())
                .quantity(p.getEntryQty())
                .entryPrice(p.getEntryPrice())
                .exitTime(toEpochMs(p.getExitTime()))
                .exitPrice(p.getExitPrice())
                .exitReason(p.getExitReason())
                .feeUsdt(fee)
                .realizedPnl(p.getRealizedPnlAmount())
                .build();
    }

    private BigDecimal computeUnrealizedPnl(BigDecimal entry, BigDecimal mark, BigDecimal qty, String side) {
        if ("SHORT".equalsIgnoreCase(side)) {
            return entry.subtract(mark).multiply(qty).setScale(8, RoundingMode.HALF_UP);
        }
        return mark.subtract(entry).multiply(qty).setScale(8, RoundingMode.HALF_UP);
    }

    private Long toEpochMs(LocalDateTime ldt) {
        if (ldt == null) return null;
        return ldt.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}

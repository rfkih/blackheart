package id.co.blackheart.service.tradequery;

import id.co.blackheart.dto.response.ActiveTradePnlItemResponse;
import id.co.blackheart.dto.response.ActiveTradePnlResponse;
import id.co.blackheart.model.Trades;
import id.co.blackheart.service.cache.CacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TradePnlQueryService {

    private static final int SCALE_AMOUNT = 8;
    private static final int SCALE_PERCENT = 4;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final CacheService cacheService;


    public ActiveTradePnlResponse getCurrentActiveTradePnl(UUID userId) {
        List<ActiveTradePnlItemResponse> items = new ArrayList<>();
        BigDecimal totalUnrealizedPnlAmount = BigDecimal.ZERO;

        for (UUID tradeId : cacheService.getUserActiveTradeIds(userId)) {
            try {
                Trades trade = cacheService.getTrade(tradeId);
                if (trade == null) {
                    continue;
                }

                if (!isTradeActive(trade)) {
                    continue;
                }

                String symbol = trade.getAsset();
                BigDecimal currentPrice = cacheService.getLatestPrice(symbol);

                if (currentPrice == null) {
                    log.warn("Latest price not found in cache for symbol={} tradeId={}", symbol, tradeId);
                    continue;
                }

                BigDecimal entryPrice = defaultIfNull(trade.getAvgEntryPrice());
                BigDecimal remainingQty = defaultIfNull(trade.getTotalRemainingQty());

                if (entryPrice.compareTo(BigDecimal.ZERO) <= 0 || remainingQty.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                BigDecimal unrealizedPnlAmount = calculatePnlAmount(
                        entryPrice,
                        currentPrice,
                        remainingQty,
                        trade.getSide()
                );

                BigDecimal unrealizedPnlPercent = calculatePnlPercent(
                        unrealizedPnlAmount,
                        entryPrice,
                        remainingQty
                );

                items.add(
                        ActiveTradePnlItemResponse.builder()
                                .tradeId(trade.getTradeId())
                                .asset(trade.getAsset())
                                .side(trade.getSide())
                                .status(trade.getStatus())
                                .avgEntryPrice(format3(entryPrice))
                                .currentPrice(format3(currentPrice))
                                .totalRemainingQty(format3(remainingQty))
                                .unrealizedPnlAmount(format3(unrealizedPnlAmount))
                                .unrealizedPnlPercent(format3(unrealizedPnlPercent))
                                .build()
                );

                totalUnrealizedPnlAmount = totalUnrealizedPnlAmount.add(unrealizedPnlAmount);

            } catch (Exception e) {
                log.error("Failed to calculate active trade pnl for userId={} tradeId={}", userId, tradeId, e);
            }
        }

        items.sort(Comparator.comparing(ActiveTradePnlItemResponse::getTradeId));

        return ActiveTradePnlResponse.builder()
                .userId(userId)
                .totalUnrealizedPnlAmount(format3(totalUnrealizedPnlAmount))
                .trades(items)
                .build();
    }


    private String format3(BigDecimal value) {
        if (value == null) {
            return "0.000";
        }
        return value.setScale(3, RoundingMode.HALF_UP).toPlainString();
    }

    private boolean isTradeActive(Trades trade) {
        if (trade.getStatus() == null) {
            return false;
        }

        return "OPEN".equalsIgnoreCase(trade.getStatus())
                || "PARTIALLY_CLOSED".equalsIgnoreCase(trade.getStatus());
    }

    private BigDecimal calculatePnlAmount(
            BigDecimal entryPrice,
            BigDecimal currentPrice,
            BigDecimal qty,
            String side
    ) {
        BigDecimal pnl;

        if ("SHORT".equalsIgnoreCase(side)) {
            pnl = entryPrice.subtract(currentPrice).multiply(qty);
        } else {
            pnl = currentPrice.subtract(entryPrice).multiply(qty);
        }

        return pnl.setScale(SCALE_AMOUNT, RoundingMode.HALF_UP);
    }

    private BigDecimal calculatePnlPercent(
            BigDecimal pnlAmount,
            BigDecimal entryPrice,
            BigDecimal qty
    ) {
        BigDecimal costBasis = entryPrice.multiply(qty);

        if (costBasis.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(SCALE_PERCENT, RoundingMode.HALF_UP);
        }

        return pnlAmount.divide(costBasis, SCALE_PERCENT + 4, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(SCALE_PERCENT, RoundingMode.HALF_UP);
    }

    private BigDecimal defaultIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
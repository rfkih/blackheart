package id.co.blackheart.service.cache;

import id.co.blackheart.model.TradePosition;
import id.co.blackheart.model.Trades;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CacheService {

    private static final String TRADE_KEY_PREFIX = "trade:";
    private static final String TRADE_POSITIONS_KEY_PREFIX = "tradePositions:";
    private static final String USER_ACTIVE_TRADES_KEY_PREFIX = "userActiveTrades:";

    private final RedisTemplate<String, Object> redisTemplate;

    public CacheService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void cacheActiveTrade(UUID tradeId, Trades trade) {
        String tradeKey = TRADE_KEY_PREFIX + tradeId;

        Map<String, Object> tradeMap = new HashMap<>();
        tradeMap.put("tradeId", trade.getTradeId());
        tradeMap.put("userId", trade.getUserId());
        tradeMap.put("status", trade.getStatus());
        tradeMap.put("asset", trade.getAsset());
        tradeMap.put("side", trade.getSide());
        tradeMap.put("avgEntryPrice", trade.getAvgEntryPrice());
        tradeMap.put("avgExitPrice", trade.getAvgExitPrice());
        tradeMap.put("totalEntryQty", trade.getTotalEntryQty());
        tradeMap.put("totalEntryQuoteQty", trade.getTotalEntryQuoteQty());
        tradeMap.put("totalRemainingQty", trade.getTotalRemainingQty());
        tradeMap.put("realizedPnlAmount", trade.getRealizedPnlAmount());
        tradeMap.put("realizedPnlPercent", trade.getRealizedPnlPercent());
        tradeMap.put("entryTime", trade.getEntryTime());
        tradeMap.put("exitTime", trade.getExitTime());
        tradeMap.put("strategyName", trade.getStrategyName());
        tradeMap.put("interval", trade.getInterval());
        tradeMap.put("exchange", trade.getExchange());

        redisTemplate.delete(tradeKey);
        redisTemplate.opsForHash().putAll(tradeKey, tradeMap);
    }

    public void cacheTradePositions(UUID tradeId, List<TradePosition> tradePositions) {
        String tradePositionsKey = TRADE_POSITIONS_KEY_PREFIX + tradeId;

        redisTemplate.delete(tradePositionsKey);

        if (tradePositions == null || tradePositions.isEmpty()) {
            return;
        }

        redisTemplate.opsForList().rightPushAll(tradePositionsKey, new ArrayList<>(tradePositions));
    }

    public Trades getTrade(UUID tradeId) {
        String tradeKey = TRADE_KEY_PREFIX + tradeId;
        Map<Object, Object> tradeData = redisTemplate.opsForHash().entries(tradeKey);

        if (tradeData == null || tradeData.isEmpty()) {
            return null;
        }

        Trades trade = new Trades();
        trade.setTradeId(asUuid(tradeData.get("tradeId"), tradeId));
        trade.setUserId(asUuid(tradeData.get("userId"), null));
        trade.setStatus(asString(tradeData.get("status")));
        trade.setAsset(asString(tradeData.get("asset")));
        trade.setSide(asString(tradeData.get("side")));
        trade.setAvgEntryPrice(asBigDecimal(tradeData.get("avgEntryPrice")));
        trade.setAvgExitPrice(asBigDecimal(tradeData.get("avgExitPrice")));
        trade.setTotalEntryQty(asBigDecimal(tradeData.get("totalEntryQty")));
        trade.setTotalEntryQuoteQty(asBigDecimal(tradeData.get("totalEntryQuoteQty")));
        trade.setTotalRemainingQty(asBigDecimal(tradeData.get("totalRemainingQty")));
        trade.setRealizedPnlAmount(asBigDecimal(tradeData.get("realizedPnlAmount")));
        trade.setRealizedPnlPercent(asBigDecimal(tradeData.get("realizedPnlPercent")));
        trade.setStrategyName(asString(tradeData.get("strategyName")));
        trade.setInterval(asString(tradeData.get("interval")));
        trade.setExchange(asString(tradeData.get("exchange")));

        return trade;
    }

    public List<TradePosition> getTradePositions(UUID tradeId) {
        String tradePositionsKey = TRADE_POSITIONS_KEY_PREFIX + tradeId;

        List<Object> positions = redisTemplate.opsForList().range(tradePositionsKey, 0, -1);

        if (positions == null || positions.isEmpty()) {
            return List.of();
        }

        return positions.stream()
                .filter(Objects::nonNull)
                .map(position -> (TradePosition) position)
                .collect(Collectors.toList());
    }

    public void addUserActiveTrade(UUID userId, UUID tradeId) {
        String userActiveTradesKey = USER_ACTIVE_TRADES_KEY_PREFIX + userId;
        redisTemplate.opsForSet().add(userActiveTradesKey, tradeId.toString());
    }

    public void removeUserActiveTrade(UUID userId, UUID tradeId) {
        String userActiveTradesKey = USER_ACTIVE_TRADES_KEY_PREFIX + userId;
        redisTemplate.opsForSet().remove(userActiveTradesKey, tradeId.toString());
    }

    public Set<UUID> getUserActiveTradeIds(UUID userId) {
        String userActiveTradesKey = USER_ACTIVE_TRADES_KEY_PREFIX + userId;
        Set<Object> members = redisTemplate.opsForSet().members(userActiveTradesKey);

        if (members == null || members.isEmpty()) {
            return Set.of();
        }

        return members.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .map(UUID::fromString)
                .collect(Collectors.toSet());
    }

    public List<Trades> getUserActiveTrades(UUID userId) {
        Set<UUID> tradeIds = getUserActiveTradeIds(userId);

        if (tradeIds.isEmpty()) {
            return List.of();
        }

        return tradeIds.stream()
                .map(this::getTrade)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public void cacheUserActiveTrade(UUID userId, UUID tradeId, Trades trade, List<TradePosition> tradePositions) {
        cacheActiveTrade(tradeId, trade);
        cacheTradePositions(tradeId, tradePositions);
        addUserActiveTrade(userId, tradeId);
    }

    public void removeClosedTrade(UUID tradeId) {
        Trades cachedTrade = getTrade(tradeId);

        redisTemplate.delete(TRADE_KEY_PREFIX + tradeId);
        redisTemplate.delete(TRADE_POSITIONS_KEY_PREFIX + tradeId);

        if (cachedTrade != null && cachedTrade.getUserId() != null) {
            removeUserActiveTrade(cachedTrade.getUserId(), tradeId);
        }
    }

    public void removeClosedTrade(UUID userId, UUID tradeId) {
        redisTemplate.delete(TRADE_KEY_PREFIX + tradeId);
        redisTemplate.delete(TRADE_POSITIONS_KEY_PREFIX + tradeId);
        removeUserActiveTrade(userId, tradeId);
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private BigDecimal asBigDecimal(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }

        return new BigDecimal(String.valueOf(value));
    }

    private UUID asUuid(Object value, UUID fallback) {
        if (value == null) {
            return fallback;
        }

        if (value instanceof UUID uuid) {
            return uuid;
        }

        return UUID.fromString(String.valueOf(value));
    }
}
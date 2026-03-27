package id.co.blackheart.service.cache;

import id.co.blackheart.model.TradePosition;
import id.co.blackheart.model.Trades;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class CacheService {

    private static final String TRADE_KEY_PREFIX = "trade:";
    private static final String TRADE_POSITIONS_KEY_PREFIX = "tradePositions:";
    private static final String USER_ACTIVE_TRADES_KEY_PREFIX = "userActiveTrades:";

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    private static final ZoneId DEFAULT_ZONE_ID = ZoneId.systemDefault();

    private final RedisTemplate<String, Object> redisTemplate;

    public CacheService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void saveLatestPrice(String symbol, BigDecimal price, LocalDateTime updatedAt) {
        if (symbol == null || price == null) {
            return;
        }

        String key = "latestPrice:" + symbol;

        Map<String, Object> value = new HashMap<>();
        value.put("symbol", symbol);
        value.put("price", price.toPlainString());
        value.put("updatedAt", updatedAt == null ? null : updatedAt.toString());

        redisTemplate.delete(key);
        redisTemplate.opsForHash().putAll(key, value);
    }

    public BigDecimal getLatestPrice(String symbol) {
        if (symbol == null) {
            return null;
        }

        String key = "latestPrice:" + symbol;
        Object value = redisTemplate.opsForHash().get(key, "price");

        if (value == null) {
            return null;
        }

        try {
            return new BigDecimal(String.valueOf(value));
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse latest price for symbol: " + symbol, e);
        }
    }

    public LocalDateTime getLatestPriceUpdatedAt(String symbol) {
        if (symbol == null) {
            return null;
        }

        String key = "latestPrice:" + symbol;
        Object value = redisTemplate.opsForHash().get(key, "updatedAt");

        if (value == null) {
            return null;
        }

        return LocalDateTime.parse(String.valueOf(value));
    }

    public void cacheActiveTrade(UUID tradeId, Trades trade) {
        String tradeKey = TRADE_KEY_PREFIX + tradeId;

        Map<String, Object> tradeMap = new HashMap<>();
        tradeMap.put("tradeId", toStringValue(trade.getTradeId()));
        tradeMap.put("userId", toStringValue(trade.getUserId()));
        tradeMap.put("userStrategyId", toStringValue(trade.getUserStrategyId()));

        tradeMap.put("status", trade.getStatus());
        tradeMap.put("asset", trade.getAsset());
        tradeMap.put("side", trade.getSide());
        tradeMap.put("strategyName", trade.getStrategyName());
        tradeMap.put("interval", trade.getInterval());
        tradeMap.put("exchange", trade.getExchange());

        tradeMap.put("avgEntryPrice", toStringValue(trade.getAvgEntryPrice()));
        tradeMap.put("avgExitPrice", toStringValue(trade.getAvgExitPrice()));
        tradeMap.put("totalEntryQty", toStringValue(trade.getTotalEntryQty()));
        tradeMap.put("totalEntryQuoteQty", toStringValue(trade.getTotalEntryQuoteQty()));
        tradeMap.put("totalRemainingQty", toStringValue(trade.getTotalRemainingQty()));
        tradeMap.put("realizedPnlAmount", toStringValue(trade.getRealizedPnlAmount()));
        tradeMap.put("realizedPnlPercent", toStringValue(trade.getRealizedPnlPercent()));

        tradeMap.put("entryTime", toDateTimeString(trade.getEntryTime()));
        tradeMap.put("exitTime", toDateTimeString(trade.getExitTime()));

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

        if (tradeData.isEmpty()) {
            return null;
        }

        Trades trade = new Trades();
        trade.setTradeId(asUuid(tradeData.get("tradeId"), tradeId));
        trade.setUserId(asUuid(tradeData.get("userId"), null));
        trade.setUserStrategyId(asUuid(tradeData.get("userStrategyId"), null));

        trade.setStatus(asString(tradeData.get("status")));
        trade.setAsset(asString(tradeData.get("asset")));
        trade.setSide(asString(tradeData.get("side")));
        trade.setStrategyName(asString(tradeData.get("strategyName")));
        trade.setInterval(asString(tradeData.get("interval")));
        trade.setExchange(asString(tradeData.get("exchange")));

        trade.setAvgEntryPrice(asBigDecimal(tradeData.get("avgEntryPrice")));
        trade.setAvgExitPrice(asBigDecimal(tradeData.get("avgExitPrice")));
        trade.setTotalEntryQty(asBigDecimal(tradeData.get("totalEntryQty")));
        trade.setTotalEntryQuoteQty(asBigDecimal(tradeData.get("totalEntryQuoteQty")));
        trade.setTotalRemainingQty(asBigDecimal(tradeData.get("totalRemainingQty")));
        trade.setRealizedPnlAmount(asBigDecimal(tradeData.get("realizedPnlAmount")));
        trade.setRealizedPnlPercent(asBigDecimal(tradeData.get("realizedPnlPercent")));

        trade.setEntryTime(asLocalDateTime(tradeData.get("entryTime")));
        trade.setExitTime(asLocalDateTime(tradeData.get("exitTime")));

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
                .filter(TradePosition.class::isInstance)
                .map(TradePosition.class::cast)
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
                .map(this::safeUuid)
                .filter(Objects::nonNull)
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

    private String toStringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String toDateTimeString(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }

    private BigDecimal asBigDecimal(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof BigDecimal bigDecimal) {
            return bigDecimal;
        }

        String raw = String.valueOf(value).trim();
        if (raw.isEmpty() || "null".equalsIgnoreCase(raw)) {
            return null;
        }

        return new BigDecimal(raw);
    }

    private UUID asUuid(Object value, UUID fallback) {
        if (value == null) {
            return fallback;
        }

        if (value instanceof UUID uuid) {
            return uuid;
        }

        try {
            return UUID.fromString(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
        }
    }

    private UUID safeUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDateTime asLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }

        if (value instanceof Date date) {
            return LocalDateTime.ofInstant(date.toInstant(), DEFAULT_ZONE_ID);
        }

        if (value instanceof Long epochMillis) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), DEFAULT_ZONE_ID);
        }

        String raw = String.valueOf(value).trim();
        if (raw.isEmpty() || "null".equalsIgnoreCase(raw)) {
            return null;
        }

        try {
            return LocalDateTime.parse(raw, DATE_TIME_FORMATTER);
        } catch (Exception ignored) {
        }

        try {
            return LocalDateTime.parse(raw);
        } catch (Exception ignored) {
        }

        try {
            long epochMillis = Long.parseLong(raw);
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), DEFAULT_ZONE_ID);
        } catch (Exception ignored) {
        }

        throw new IllegalArgumentException("Failed to parse LocalDateTime from value: " + raw);
    }
}
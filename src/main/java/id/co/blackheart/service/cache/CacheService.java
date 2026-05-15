package id.co.blackheart.service.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.model.TradePosition;
import id.co.blackheart.model.Trades;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class CacheService {

    private static final String TRADE_KEY_PREFIX              = "trade:";
    private static final String TRADE_POSITIONS_KEY_PREFIX    = "tradePositions:";
    private static final String USER_ACTIVE_TRADES_KEY_PREFIX = "accountActiveTrades:";
    private static final String LATEST_PRICE_KEY_PREFIX       = "latestPrice:";
    private static final String MISSING_PRICE_KEY_PREFIX      = "latestPrice:missing:";

    private static final Duration LATEST_PRICE_TTL       = Duration.ofMinutes(5);
    private static final Duration MISSING_PRICE_TTL      = Duration.ofMinutes(1);
    private static final Duration TRADE_TTL              = Duration.ofHours(24);
    private static final Duration TRADE_POSITIONS_TTL    = Duration.ofHours(24);
    private static final Duration ACCOUNT_TRADE_SET_TTL  = Duration.ofHours(48);

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private static final ZoneOffset ZONE = ZoneOffset.UTC;

    // Redis hash field names — kept in one place so the write/read sites stay in sync.
    private static final String FIELD_TRADE_ID = "tradeId";
    private static final String FIELD_ACCOUNT_ID = "accountId";
    private static final String FIELD_ACCOUNT_STRATEGY_ID = "accountStrategyId";
    private static final String FIELD_STRATEGY_NAME = "strategyName";
    private static final String FIELD_INTERVAL = "interval";
    private static final String FIELD_EXCHANGE = "exchange";
    private static final String FIELD_ASSET = "asset";
    private static final String FIELD_STATUS = "status";
    private static final String FIELD_TRADE_MODE = "tradeMode";
    private static final String FIELD_AVG_ENTRY_PRICE = "avgEntryPrice";
    private static final String FIELD_AVG_EXIT_PRICE = "avgExitPrice";
    private static final String FIELD_TOTAL_ENTRY_QTY = "totalEntryQty";
    private static final String FIELD_TOTAL_ENTRY_QUOTE_QTY = "totalEntryQuoteQty";
    private static final String FIELD_TOTAL_REMAINING_QTY = "totalRemainingQty";
    private static final String FIELD_REALIZED_PNL_AMOUNT = "realizedPnlAmount";
    private static final String FIELD_REALIZED_PNL_PERCENT = "realizedPnlPercent";
    private static final String FIELD_TOTAL_FEE_AMOUNT = "totalFeeAmount";
    private static final String FIELD_TOTAL_FEE_CURRENCY = "totalFeeCurrency";
    private static final String FIELD_EXIT_REASON = "exitReason";
    private static final String FIELD_ENTRY_TREND_REGIME = "entryTrendRegime";
    private static final String FIELD_ENTRY_ADX = "entryAdx";
    private static final String FIELD_ENTRY_ATR = "entryAtr";
    private static final String FIELD_ENTRY_RSI = "entryRsi";
    private static final String FIELD_ENTRY_TIME = "entryTime";
    private static final String FIELD_EXIT_TIME = "exitTime";
    private static final String FIELD_UPDATED_AT = "updatedAt";

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public CacheService(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void saveLatestPrice(String symbol, BigDecimal price, LocalDateTime updatedAt) {
        if (!StringUtils.hasText(symbol) || ObjectUtils.isEmpty(price)) {
            return;
        }

        String key = LATEST_PRICE_KEY_PREFIX + symbol;

        Map<String, String> value = new HashMap<>();
        value.put("symbol", symbol);
        value.put("price", price.toPlainString());
        if (ObjectUtils.isNotEmpty(updatedAt)) {
            value.put(FIELD_UPDATED_AT, updatedAt.format(DATE_TIME_FORMATTER));
        }

        try {
            redisTemplate.executePipelined((RedisCallback<?>) connection -> {
                redisTemplate.opsForHash().putAll(key, value);
                redisTemplate.expire(key, LATEST_PRICE_TTL);
                return null;
            });
        } catch (Exception e) {
            log.error("Failed to save latest price | symbol={} price={}", symbol, price, e);
        }
    }

    public BigDecimal getLatestPrice(String symbol) {
        if (ObjectUtils.isEmpty(symbol)) {
            return null;
        }

        String key = LATEST_PRICE_KEY_PREFIX + symbol;
        Object value = redisTemplate.opsForHash().get(key, "price");

        if (ObjectUtils.isEmpty(value)) {
            return null;
        }

        try {
            return new BigDecimal(String.valueOf(value));
        } catch (Exception e) {
            // Do not throw — a parse error must not crash strategy execution.
            log.warn("Failed to parse latest price from cache | symbol={} raw={}", symbol, value);
            return null;
        }
    }

    /**
     * Negative cache for symbols Binance has no quote for (e.g. an asset
     * without a USDT pair). Stops the portfolio read path from re-hitting
     * Binance on every refresh for the same dud symbol. Short TTL so a
     * newly-listed pair recovers quickly.
     */
    public void markPriceUnavailable(String symbol) {
        if (!StringUtils.hasText(symbol)) {
            return;
        }
        String key = MISSING_PRICE_KEY_PREFIX + symbol;
        try {
            redisTemplate.opsForValue().set(key, "1", MISSING_PRICE_TTL);
        } catch (Exception e) {
            log.warn("Failed to mark price unavailable | symbol={}", symbol, e);
        }
    }

    public boolean isPriceUnavailable(String symbol) {
        if (ObjectUtils.isEmpty(symbol)) {
            return false;
        }
        String key = MISSING_PRICE_KEY_PREFIX + symbol;
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.warn("Failed to check price unavailable | symbol={}", symbol, e);
            return false;
        }
    }

    public LocalDateTime getLatestPriceUpdatedAt(String symbol) {
        if (ObjectUtils.isEmpty(symbol)) {
            return null;
        }

        String key = LATEST_PRICE_KEY_PREFIX + symbol;
        Object value = redisTemplate.opsForHash().get(key, FIELD_UPDATED_AT);

        if (ObjectUtils.isEmpty(value)) {
            return null;
        }

        return asLocalDateTime(value);
    }

    // ── Trade caching ─────────────────────────────────────────────────────────

    /**
     * Writes the full trade hash to Redis.
     * Uses putAll directly (no prior delete) — Redis hash putAll is effectively
     * a field-level overwrite, which is atomic per field. Deleting first would
     * create a window where the key does not exist.
     */
    public void cacheActiveTrade(UUID tradeId, Trades trade) {
        String tradeKey = TRADE_KEY_PREFIX + tradeId;

        Map<String, Object> tradeMap = new HashMap<>();
        tradeMap.put(FIELD_TRADE_ID,              asString(trade.getTradeId()));
        tradeMap.put(FIELD_ACCOUNT_ID,            asString(trade.getAccountId()));
        tradeMap.put(FIELD_ACCOUNT_STRATEGY_ID,   asString(trade.getAccountStrategyId()));
        tradeMap.put(FIELD_STRATEGY_NAME,         asString(trade.getStrategyName()));
        tradeMap.put(FIELD_INTERVAL,              asString(trade.getInterval()));
        tradeMap.put(FIELD_EXCHANGE,              asString(trade.getExchange()));
        tradeMap.put(FIELD_ASSET,                 asString(trade.getAsset()));
        tradeMap.put("side",                      asString(trade.getSide()));
        tradeMap.put(FIELD_STATUS,                asString(trade.getStatus()));
        tradeMap.put(FIELD_TRADE_MODE,            asString(trade.getTradeMode()));
        tradeMap.put(FIELD_AVG_ENTRY_PRICE,       asString(trade.getAvgEntryPrice()));
        tradeMap.put(FIELD_AVG_EXIT_PRICE,        asString(trade.getAvgExitPrice()));
        tradeMap.put(FIELD_TOTAL_ENTRY_QTY,       asString(trade.getTotalEntryQty()));
        tradeMap.put(FIELD_TOTAL_ENTRY_QUOTE_QTY, asString(trade.getTotalEntryQuoteQty()));
        tradeMap.put(FIELD_TOTAL_REMAINING_QTY,   asString(trade.getTotalRemainingQty()));
        tradeMap.put(FIELD_REALIZED_PNL_AMOUNT,   asString(trade.getRealizedPnlAmount()));
        tradeMap.put(FIELD_REALIZED_PNL_PERCENT,  asString(trade.getRealizedPnlPercent()));
        tradeMap.put(FIELD_TOTAL_FEE_AMOUNT,      asString(trade.getTotalFeeAmount()));
        tradeMap.put(FIELD_TOTAL_FEE_CURRENCY,    asString(trade.getTotalFeeCurrency()));
        tradeMap.put(FIELD_EXIT_REASON,           asString(trade.getExitReason()));
        tradeMap.put(FIELD_ENTRY_TREND_REGIME,    asString(trade.getEntryTrendRegime()));
        tradeMap.put(FIELD_ENTRY_ADX,             asString(trade.getEntryAdx()));
        tradeMap.put(FIELD_ENTRY_ATR,             asString(trade.getEntryAtr()));
        tradeMap.put(FIELD_ENTRY_RSI,             asString(trade.getEntryRsi()));
        tradeMap.put(FIELD_ENTRY_TIME,            toDateTimeString(trade.getEntryTime()));
        tradeMap.put(FIELD_EXIT_TIME,             toDateTimeString(trade.getExitTime()));
        tradeMap.put("createdTime",               toDateTimeString(trade.getCreatedTime()));
        tradeMap.put("updatedTime",               toDateTimeString(trade.getUpdatedTime()));

        try {
            redisTemplate.executePipelined((RedisCallback<?>) connection -> {
                redisTemplate.opsForHash().putAll(tradeKey, tradeMap);
                redisTemplate.expire(tradeKey, TRADE_TTL);
                return null;
            });
        } catch (Exception e) {
            log.error("Failed to cache active trade | tradeId={}", tradeId, e);
        }
    }

    /**
     * Replaces the positions list atomically using a pipeline.
     * DELETE + RPUSH are pipelined to minimise the window where the key is empty,
     * but they are not a single atomic MULTI/EXEC transaction. For the live trading
     * use case (single writer per trade), this is safe in practice.
     */
    public void cacheTradePositions(UUID tradeId, List<TradePosition> tradePositions) {
        String key = TRADE_POSITIONS_KEY_PREFIX + tradeId;

        try {
            List<String> serialized = serializePositions(tradePositions);
            redisTemplate.executePipelined((RedisCallback<?>) connection -> {
                redisTemplate.delete(key);
                if (!serialized.isEmpty()) {
                    redisTemplate.opsForList().rightPushAll(key, new ArrayList<>(serialized));
                    redisTemplate.expire(key, TRADE_POSITIONS_TTL);
                }
                return null;
            });
        } catch (Exception e) {
            log.error("Failed to cache trade positions | tradeId={}", tradeId, e);
        }
    }

    /**
     * Caches all three pieces of trade state in a single pipeline to minimise
     * partial-failure windows.
     */
    public void cacheUserActiveTrade(UUID accountId, UUID tradeId, Trades trade, List<TradePosition> tradePositions) {
        String tradeKey     = TRADE_KEY_PREFIX + tradeId;
        String positionsKey = TRADE_POSITIONS_KEY_PREFIX + tradeId;
        String accountKey   = USER_ACTIVE_TRADES_KEY_PREFIX + accountId;

        Map<String, Object> tradeMap = buildTradeMap(trade);
        List<String> serializedPositions;
        try {
            serializedPositions = serializePositions(tradePositions);
        } catch (Exception e) {
            log.error("Failed to serialize trade positions | tradeId={}", tradeId, e);
            serializedPositions = List.of();
        }
        final List<String> positionsToStore = serializedPositions;

        try {
            redisTemplate.executePipelined((RedisCallback<?>) connection -> {
                redisTemplate.opsForHash().putAll(tradeKey, tradeMap);
                redisTemplate.expire(tradeKey, TRADE_TTL);

                redisTemplate.delete(positionsKey);
                if (!positionsToStore.isEmpty()) {
                    redisTemplate.opsForList().rightPushAll(positionsKey, new ArrayList<>(positionsToStore));
                    redisTemplate.expire(positionsKey, TRADE_POSITIONS_TTL);
                }

                redisTemplate.opsForSet().add(accountKey, tradeId.toString());
                redisTemplate.expire(accountKey, ACCOUNT_TRADE_SET_TTL);
                return null;
            });
        } catch (Exception e) {
            log.error("Failed to cache user active trade | accountId={} tradeId={}", accountId, tradeId, e);
        }
    }

    // ── Trade reads ───────────────────────────────────────────────────────────

    public Trades getTrade(UUID tradeId) {
        String tradeKey = TRADE_KEY_PREFIX + tradeId;
        Map<Object, Object> d = redisTemplate.opsForHash().entries(tradeKey);

        if (d.isEmpty()) {
            return null;
        }

        Trades trade = new Trades();
        trade.setTradeId(asUuid(d.get(FIELD_TRADE_ID), tradeId));
        trade.setAccountId(asUuid(d.get(FIELD_ACCOUNT_ID), null));
        trade.setAccountStrategyId(asUuid(d.get(FIELD_ACCOUNT_STRATEGY_ID), null));
        trade.setStrategyName(asString(d.get(FIELD_STRATEGY_NAME)));
        trade.setInterval(asString(d.get(FIELD_INTERVAL)));
        trade.setExchange(asString(d.get(FIELD_EXCHANGE)));
        trade.setAsset(asString(d.get(FIELD_ASSET)));
        trade.setSide(asString(d.get("side")));
        trade.setStatus(asString(d.get(FIELD_STATUS)));
        trade.setTradeMode(asString(d.get(FIELD_TRADE_MODE)));
        trade.setAvgEntryPrice(asBigDecimal(d.get(FIELD_AVG_ENTRY_PRICE)));
        trade.setAvgExitPrice(asBigDecimal(d.get(FIELD_AVG_EXIT_PRICE)));
        trade.setTotalEntryQty(asBigDecimal(d.get(FIELD_TOTAL_ENTRY_QTY)));
        trade.setTotalEntryQuoteQty(asBigDecimal(d.get(FIELD_TOTAL_ENTRY_QUOTE_QTY)));
        trade.setTotalRemainingQty(asBigDecimal(d.get(FIELD_TOTAL_REMAINING_QTY)));
        trade.setRealizedPnlAmount(asBigDecimal(d.get(FIELD_REALIZED_PNL_AMOUNT)));
        trade.setRealizedPnlPercent(asBigDecimal(d.get(FIELD_REALIZED_PNL_PERCENT)));
        trade.setTotalFeeAmount(asBigDecimal(d.get(FIELD_TOTAL_FEE_AMOUNT)));
        trade.setTotalFeeCurrency(asString(d.get(FIELD_TOTAL_FEE_CURRENCY)));
        trade.setExitReason(asString(d.get(FIELD_EXIT_REASON)));
        trade.setEntryTrendRegime(asString(d.get(FIELD_ENTRY_TREND_REGIME)));
        trade.setEntryAdx(asBigDecimal(d.get(FIELD_ENTRY_ADX)));
        trade.setEntryAtr(asBigDecimal(d.get(FIELD_ENTRY_ATR)));
        trade.setEntryRsi(asBigDecimal(d.get(FIELD_ENTRY_RSI)));
        trade.setEntryTime(asLocalDateTime(d.get(FIELD_ENTRY_TIME)));
        trade.setExitTime(asLocalDateTime(d.get(FIELD_EXIT_TIME)));
        trade.setCreatedTime(asLocalDateTime(d.get("createdAt")));
        trade.setUpdatedTime(asLocalDateTime(d.get(FIELD_UPDATED_AT)));

        return trade;
    }

    public List<TradePosition> getTradePositions(UUID tradeId) {
        String key = TRADE_POSITIONS_KEY_PREFIX + tradeId;
        List<Object> raw = redisTemplate.opsForList().range(key, 0, -1);

        if (CollectionUtils.isEmpty(raw)) {
            return List.of();
        }

        return raw.stream()
                .filter(Objects::nonNull)
                .map(item -> {
                    try {
                        return objectMapper.readValue(String.valueOf(item), TradePosition.class);
                    } catch (JsonProcessingException e) {
                        log.warn("Failed to deserialize TradePosition from cache | tradeId={}", tradeId);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    // ── Account active trade set ──────────────────────────────────────────────

    public void addAccountActiveTrade(UUID accountId, UUID tradeId) {
        String key = USER_ACTIVE_TRADES_KEY_PREFIX + accountId;
        redisTemplate.executePipelined((RedisCallback<?>) connection -> {
            redisTemplate.opsForSet().add(key, tradeId.toString());
            redisTemplate.expire(key, ACCOUNT_TRADE_SET_TTL);
            return null;
        });
    }

    public void removeAccountActiveTrade(UUID accountId, UUID tradeId) {
        String key = USER_ACTIVE_TRADES_KEY_PREFIX + accountId;
        redisTemplate.opsForSet().remove(key, tradeId.toString());
    }

    public Set<UUID> getAccountActiveTradeIds(UUID accountId) {
        String key = USER_ACTIVE_TRADES_KEY_PREFIX + accountId;
        Set<Object> members = redisTemplate.opsForSet().members(key);

        if (CollectionUtils.isEmpty(members)) {
            return Set.of();
        }

        return members.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .map(this::safeUuid)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    public List<Trades> getUserActiveTrades(UUID accountId) {
        Set<UUID> tradeIds = getAccountActiveTradeIds(accountId);

        if (tradeIds.isEmpty()) {
            return List.of();
        }

        return tradeIds.stream()
                .map(this::getTrade)
                .filter(Objects::nonNull)
                .toList();
    }

    // ── Trade removal ─────────────────────────────────────────────────────────

    /**
     * Removes all cache entries for a closed trade.
     * Callers must supply accountId directly to guarantee the account active-trade
     * set is always cleaned — do not rely on reading accountId back from Redis,
     * which can silently fail if the key is partially evicted.
     */
    public void removeClosedTrade(UUID accountId, UUID tradeId) {
        if (ObjectUtils.isEmpty(accountId) || ObjectUtils.isEmpty(tradeId)) {
            log.warn("[Cache] removeClosedTrade called with null | accountId={} tradeId={}", accountId, tradeId);
            return;
        }
        redisTemplate.delete(TRADE_KEY_PREFIX + tradeId);
        redisTemplate.delete(TRADE_POSITIONS_KEY_PREFIX + tradeId);
        removeAccountActiveTrade(accountId, tradeId);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private Map<String, Object> buildTradeMap(Trades trade) {
        Map<String, Object> m = new HashMap<>();
        m.put(FIELD_TRADE_ID,              asString(trade.getTradeId()));
        m.put(FIELD_ACCOUNT_ID,            asString(trade.getAccountId()));
        m.put(FIELD_ACCOUNT_STRATEGY_ID,   asString(trade.getAccountStrategyId()));
        m.put(FIELD_STRATEGY_NAME,         asString(trade.getStrategyName()));
        m.put(FIELD_INTERVAL,              asString(trade.getInterval()));
        m.put(FIELD_EXCHANGE,              asString(trade.getExchange()));
        m.put(FIELD_ASSET,                 asString(trade.getAsset()));
        m.put("side",                      asString(trade.getSide()));
        m.put(FIELD_STATUS,                asString(trade.getStatus()));
        m.put(FIELD_TRADE_MODE,            asString(trade.getTradeMode()));
        m.put(FIELD_AVG_ENTRY_PRICE,       asString(trade.getAvgEntryPrice()));
        m.put(FIELD_AVG_EXIT_PRICE,        asString(trade.getAvgExitPrice()));
        m.put(FIELD_TOTAL_ENTRY_QTY,       asString(trade.getTotalEntryQty()));
        m.put(FIELD_TOTAL_ENTRY_QUOTE_QTY, asString(trade.getTotalEntryQuoteQty()));
        m.put(FIELD_TOTAL_REMAINING_QTY,   asString(trade.getTotalRemainingQty()));
        m.put(FIELD_REALIZED_PNL_AMOUNT,   asString(trade.getRealizedPnlAmount()));
        m.put(FIELD_REALIZED_PNL_PERCENT,  asString(trade.getRealizedPnlPercent()));
        m.put(FIELD_TOTAL_FEE_AMOUNT,      asString(trade.getTotalFeeAmount()));
        m.put(FIELD_TOTAL_FEE_CURRENCY,    asString(trade.getTotalFeeCurrency()));
        m.put(FIELD_EXIT_REASON,           asString(trade.getExitReason()));
        m.put(FIELD_ENTRY_TREND_REGIME,    asString(trade.getEntryTrendRegime()));
        m.put(FIELD_ENTRY_ADX,             asString(trade.getEntryAdx()));
        m.put(FIELD_ENTRY_ATR,             asString(trade.getEntryAtr()));
        m.put(FIELD_ENTRY_RSI,             asString(trade.getEntryRsi()));
        m.put(FIELD_ENTRY_TIME,            toDateTimeString(trade.getEntryTime()));
        m.put(FIELD_EXIT_TIME,             toDateTimeString(trade.getExitTime()));
        m.put("createdTime",               toDateTimeString(trade.getCreatedTime()));
        m.put("updatedTime",               toDateTimeString(trade.getUpdatedTime()));
        return m;
    }

    private List<String> serializePositions(List<TradePosition> positions) {
        if (CollectionUtils.isEmpty(positions)) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (TradePosition position : positions) {
            try {
                result.add(objectMapper.writeValueAsString(position));
            } catch (JsonProcessingException e) {
                log.warn("Failed to serialize TradePosition | tradePositionId={}",
                        position.getTradePositionId(), e);
            }
        }
        return result;
    }

    private String asString(Object value) {
        return ObjectUtils.isEmpty(value) ? null : String.valueOf(value);
    }

    private String toDateTimeString(LocalDateTime value) {
        return ObjectUtils.isEmpty(value) ? null : value.format(DATE_TIME_FORMATTER);
    }

    private BigDecimal asBigDecimal(Object value) {
        if (ObjectUtils.isEmpty(value)) return null;
        if (value instanceof BigDecimal bd) return bd;
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty() || "null".equalsIgnoreCase(raw)) return null;
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException e) {
            log.warn("Failed to parse BigDecimal from cache value: {}", raw);
            return null;
        }
    }

    private UUID asUuid(Object value, UUID fallback) {
        if (ObjectUtils.isEmpty(value)) return fallback;
        if (value instanceof UUID uuid) return uuid;
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

    /**
     * Parses a LocalDateTime from various formats that may come out of Redis.
     * Returns null (with a warning) rather than throwing, so a bad cache value
     * never crashes the live trading path.
     */
    private LocalDateTime asLocalDateTime(Object value) {
        if (ObjectUtils.isEmpty(value)) return null;
        if (value instanceof LocalDateTime ldt) return ldt;

        if (value instanceof Long epochMillis) {
            return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), ZONE);
        }

        String raw = String.valueOf(value).trim();
        if (raw.isEmpty() || "null".equalsIgnoreCase(raw)) return null;

        try {
            return LocalDateTime.parse(raw, DATE_TIME_FORMATTER);
        } catch (Exception ignored) {
            // Fall through to the next parse strategy.
        }

        try {
            return LocalDateTime.parse(raw);
        } catch (Exception ignored) {
            // Fall through to the next parse strategy.
        }

        try {
            return LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(Long.parseLong(raw)), ZONE);
        } catch (Exception ignored) {
            // Fall through to the warn-and-return-null path.
        }

        // Do not throw — log the problem and return null so callers stay alive.
        log.warn("Failed to parse LocalDateTime from cache value: {}", raw);
        return null;
    }
}

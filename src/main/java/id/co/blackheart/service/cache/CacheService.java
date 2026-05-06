package id.co.blackheart.service.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.model.TradePosition;
import id.co.blackheart.model.Trades;
import lombok.extern.slf4j.Slf4j;
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

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    public CacheService(RedisTemplate<String, Object> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public void saveLatestPrice(String symbol, BigDecimal price, LocalDateTime updatedAt) {
        if (!StringUtils.hasText(symbol) || price == null) {
            return;
        }

        String key = LATEST_PRICE_KEY_PREFIX + symbol;

        Map<String, String> value = new HashMap<>();
        value.put("symbol", symbol);
        value.put("price", price.toPlainString());
        if (updatedAt != null) {
            value.put("updatedAt", updatedAt.format(DATE_TIME_FORMATTER));
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
        if (symbol == null) {
            return null;
        }

        String key = LATEST_PRICE_KEY_PREFIX + symbol;
        Object value = redisTemplate.opsForHash().get(key, "price");

        if (value == null) {
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
        if (symbol == null) {
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
        if (symbol == null) {
            return null;
        }

        String key = LATEST_PRICE_KEY_PREFIX + symbol;
        Object value = redisTemplate.opsForHash().get(key, "updatedAt");

        if (value == null) {
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
        tradeMap.put("tradeId",             asString(trade.getTradeId()));
        tradeMap.put("accountId",           asString(trade.getAccountId()));
        tradeMap.put("accountStrategyId",   asString(trade.getAccountStrategyId()));
        tradeMap.put("strategyName",        asString(trade.getStrategyName()));
        tradeMap.put("interval",            asString(trade.getInterval()));
        tradeMap.put("exchange",            asString(trade.getExchange()));
        tradeMap.put("asset",               asString(trade.getAsset()));
        tradeMap.put("side",                asString(trade.getSide()));
        tradeMap.put("status",              asString(trade.getStatus()));
        tradeMap.put("tradeMode",           asString(trade.getTradeMode()));
        tradeMap.put("avgEntryPrice",       asString(trade.getAvgEntryPrice()));
        tradeMap.put("avgExitPrice",        asString(trade.getAvgExitPrice()));
        tradeMap.put("totalEntryQty",       asString(trade.getTotalEntryQty()));
        tradeMap.put("totalEntryQuoteQty",  asString(trade.getTotalEntryQuoteQty()));
        tradeMap.put("totalRemainingQty",   asString(trade.getTotalRemainingQty()));
        tradeMap.put("realizedPnlAmount",   asString(trade.getRealizedPnlAmount()));
        tradeMap.put("realizedPnlPercent",  asString(trade.getRealizedPnlPercent()));
        tradeMap.put("totalFeeAmount",      asString(trade.getTotalFeeAmount()));
        tradeMap.put("totalFeeCurrency",    asString(trade.getTotalFeeCurrency()));
        tradeMap.put("exitReason",          asString(trade.getExitReason()));
        tradeMap.put("entryTrendRegime",    asString(trade.getEntryTrendRegime()));
        tradeMap.put("entryAdx",            asString(trade.getEntryAdx()));
        tradeMap.put("entryAtr",            asString(trade.getEntryAtr()));
        tradeMap.put("entryRsi",            asString(trade.getEntryRsi()));
        tradeMap.put("entryTime",           toDateTimeString(trade.getEntryTime()));
        tradeMap.put("exitTime",            toDateTimeString(trade.getExitTime()));
        tradeMap.put("createdTime",           toDateTimeString(trade.getCreatedTime()));
        tradeMap.put("updatedTime",           toDateTimeString(trade.getUpdatedTime()));

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
        trade.setTradeId(asUuid(d.get("tradeId"), tradeId));
        trade.setAccountId(asUuid(d.get("accountId"), null));
        trade.setAccountStrategyId(asUuid(d.get("accountStrategyId"), null));
        trade.setStrategyName(asString(d.get("strategyName")));
        trade.setInterval(asString(d.get("interval")));
        trade.setExchange(asString(d.get("exchange")));
        trade.setAsset(asString(d.get("asset")));
        trade.setSide(asString(d.get("side")));
        trade.setStatus(asString(d.get("status")));
        trade.setTradeMode(asString(d.get("tradeMode")));
        trade.setAvgEntryPrice(asBigDecimal(d.get("avgEntryPrice")));
        trade.setAvgExitPrice(asBigDecimal(d.get("avgExitPrice")));
        trade.setTotalEntryQty(asBigDecimal(d.get("totalEntryQty")));
        trade.setTotalEntryQuoteQty(asBigDecimal(d.get("totalEntryQuoteQty")));
        trade.setTotalRemainingQty(asBigDecimal(d.get("totalRemainingQty")));
        trade.setRealizedPnlAmount(asBigDecimal(d.get("realizedPnlAmount")));
        trade.setRealizedPnlPercent(asBigDecimal(d.get("realizedPnlPercent")));
        trade.setTotalFeeAmount(asBigDecimal(d.get("totalFeeAmount")));
        trade.setTotalFeeCurrency(asString(d.get("totalFeeCurrency")));
        trade.setExitReason(asString(d.get("exitReason")));
        trade.setEntryTrendRegime(asString(d.get("entryTrendRegime")));
        trade.setEntryAdx(asBigDecimal(d.get("entryAdx")));
        trade.setEntryAtr(asBigDecimal(d.get("entryAtr")));
        trade.setEntryRsi(asBigDecimal(d.get("entryRsi")));
        trade.setEntryTime(asLocalDateTime(d.get("entryTime")));
        trade.setExitTime(asLocalDateTime(d.get("exitTime")));
        trade.setCreatedTime(asLocalDateTime(d.get("createdAt")));
        trade.setUpdatedTime(asLocalDateTime(d.get("updatedAt")));

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
                .collect(Collectors.toList());
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
                .collect(Collectors.toList());
    }

    // ── Trade removal ─────────────────────────────────────────────────────────

    /**
     * Removes all cache entries for a closed trade.
     * Callers must supply accountId directly to guarantee the account active-trade
     * set is always cleaned — do not rely on reading accountId back from Redis,
     * which can silently fail if the key is partially evicted.
     */
    public void removeClosedTrade(UUID accountId, UUID tradeId) {
        if (accountId == null || tradeId == null) {
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
        m.put("tradeId",            asString(trade.getTradeId()));
        m.put("accountId",          asString(trade.getAccountId()));
        m.put("accountStrategyId",  asString(trade.getAccountStrategyId()));
        m.put("strategyName",       asString(trade.getStrategyName()));
        m.put("interval",           asString(trade.getInterval()));
        m.put("exchange",           asString(trade.getExchange()));
        m.put("asset",              asString(trade.getAsset()));
        m.put("side",               asString(trade.getSide()));
        m.put("status",             asString(trade.getStatus()));
        m.put("tradeMode",          asString(trade.getTradeMode()));
        m.put("avgEntryPrice",      asString(trade.getAvgEntryPrice()));
        m.put("avgExitPrice",       asString(trade.getAvgExitPrice()));
        m.put("totalEntryQty",      asString(trade.getTotalEntryQty()));
        m.put("totalEntryQuoteQty", asString(trade.getTotalEntryQuoteQty()));
        m.put("totalRemainingQty",  asString(trade.getTotalRemainingQty()));
        m.put("realizedPnlAmount",  asString(trade.getRealizedPnlAmount()));
        m.put("realizedPnlPercent", asString(trade.getRealizedPnlPercent()));
        m.put("totalFeeAmount",     asString(trade.getTotalFeeAmount()));
        m.put("totalFeeCurrency",   asString(trade.getTotalFeeCurrency()));
        m.put("exitReason",         asString(trade.getExitReason()));
        m.put("entryTrendRegime",   asString(trade.getEntryTrendRegime()));
        m.put("entryAdx",           asString(trade.getEntryAdx()));
        m.put("entryAtr",           asString(trade.getEntryAtr()));
        m.put("entryRsi",           asString(trade.getEntryRsi()));
        m.put("entryTime",          toDateTimeString(trade.getEntryTime()));
        m.put("exitTime",           toDateTimeString(trade.getExitTime()));
        m.put("createdTime",          toDateTimeString(trade.getCreatedTime()));
        m.put("updatedTime",          toDateTimeString(trade.getUpdatedTime()));
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
        return value == null ? null : String.valueOf(value);
    }

    private String toDateTimeString(LocalDateTime value) {
        return value == null ? null : value.format(DATE_TIME_FORMATTER);
    }

    private BigDecimal asBigDecimal(Object value) {
        if (value == null) return null;
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
        if (value == null) return fallback;
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
        if (value == null) return null;
        if (value instanceof LocalDateTime ldt) return ldt;

        if (value instanceof Long epochMillis) {
            return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(epochMillis), ZONE);
        }

        String raw = String.valueOf(value).trim();
        if (raw.isEmpty() || "null".equalsIgnoreCase(raw)) return null;

        try {
            return LocalDateTime.parse(raw, DATE_TIME_FORMATTER);
        } catch (Exception ignored) { }

        try {
            return LocalDateTime.parse(raw);
        } catch (Exception ignored) { }

        try {
            return LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(Long.parseLong(raw)), ZONE);
        } catch (Exception ignored) { }

        // Do not throw — log the problem and return null so callers stay alive.
        log.warn("Failed to parse LocalDateTime from cache value: {}", raw);
        return null;
    }
}

package id.co.blackheart.controller;

import id.co.blackheart.dto.response.CurrencyRatesResponse;
import id.co.blackheart.dto.response.IndicatorDataResponse;
import id.co.blackheart.dto.response.LatestPriceResponse;
import id.co.blackheart.dto.response.MarketDataResponse;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.repository.FeatureStoreRepository;
import id.co.blackheart.repository.MarketDataRepository;
import id.co.blackheart.service.marketquery.CurrencyRateService;
import id.co.blackheart.service.marketquery.MarketQueryService;
import id.co.blackheart.util.ResponseCode;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/market")
@RequiredArgsConstructor
public class MarketQueryController {

    private final MarketQueryService marketQueryService;
    private final MarketDataRepository marketDataRepository;
    private final FeatureStoreRepository featureStoreRepository;
    private final CurrencyRateService currencyRateService;

    /**
     * Rates for the frontend's display-currency toggle. Returned values are
     * cached server-side so the external APIs aren't hit on every request.
     */
    @GetMapping("/rates")
    public ResponseEntity<ResponseDto> getRates() {
        CurrencyRatesResponse data = currencyRateService.getRates();
        return ResponseEntity.ok().body(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(data)
                .build());
    }

    @GetMapping("/latest-price/{symbol}")
    public ResponseEntity<ResponseDto> getLatestPrice(@PathVariable String symbol) {
        LatestPriceResponse data = marketQueryService.getLatestPrice(symbol.toUpperCase());
        return ResponseEntity.ok().body(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(data)
                .build());
    }

    @GetMapping
    public ResponseEntity<ResponseDto> getCandles(
            @RequestParam String symbol,
            @RequestParam String interval,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "200") int limit) {

        List<MarketData> candles;
        if (from != null && to != null) {
            LocalDateTime fromDt = parseDateTime(from);
            LocalDateTime toDt = parseDateTime(to);
            candles = marketDataRepository.findBySymbolIntervalAndRange(symbol.toUpperCase(), interval, fromDt, toDt);
        } else {
            candles = marketDataRepository.findLatestCandles(symbol.toUpperCase(), interval, limit);
        }

        List<MarketDataResponse> result = candles.stream()
                .map(m -> MarketDataResponse.builder()
                        .symbol(m.getSymbol())
                        .interval(m.getInterval())
                        .openTime(toEpochMs(m.getStartTime()))
                        .open(m.getOpenPrice())
                        .high(m.getHighPrice())
                        .low(m.getLowPrice())
                        .close(m.getClosePrice())
                        .volume(m.getVolume())
                        .closeTime(toEpochMs(m.getEndTime()))
                        .build())
                .collect(Collectors.toList());

        return ResponseEntity.ok().body(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(result)
                .build());
    }

    /**
     * Per-candle technical indicators for the market chart overlays. Backed by
     * the FeatureStore table, whose rows are precomputed by the indicator
     * service. Rows that haven't been backfilled (legacy candles) appear with
     * null fields, which the chart skips rather than drawing as zero.
     *
     * <p>{@code from}/{@code to} accept either epoch milliseconds (13 digits)
     * or ISO-8601 date-times. When either is omitted we fall back to the last
     * {@code limit} rows so the UI has something to render on first mount.
     */
    @GetMapping("/indicators")
    public ResponseEntity<ResponseDto> getIndicators(
            @RequestParam String symbol,
            @RequestParam String interval,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "500") int limit) {

        List<FeatureStore> rows;
        if (from != null && to != null) {
            LocalDateTime fromDt = parseDateTime(from);
            LocalDateTime toDt = parseDateTime(to);
            rows = featureStoreRepository.findBySymbolIntervalAndRange(
                    symbol.toUpperCase(), interval, fromDt, toDt);
        } else {
            // Without a range we derive the window from the latest N candles so
            // /indicators stays aligned with GET /api/v1/market on first mount.
            List<MarketData> recent = marketDataRepository.findLatestCandles(
                    symbol.toUpperCase(), interval, limit);
            if (recent.isEmpty()) {
                rows = List.of();
            } else {
                // findLatestCandles returns newest-first; flip the bounds.
                LocalDateTime endTime = recent.get(0).getStartTime();
                LocalDateTime startTime = recent.get(recent.size() - 1).getStartTime();
                rows = featureStoreRepository.findBySymbolIntervalAndRange(
                        symbol.toUpperCase(), interval, startTime, endTime);
            }
        }

        List<IndicatorDataResponse> result = rows.stream()
                .map(this::toIndicatorResponse)
                .collect(Collectors.toList());

        return ResponseEntity.ok().body(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(result)
                .build());
    }

    private IndicatorDataResponse toIndicatorResponse(FeatureStore f) {
        // BB / KC middles aren't stored — approximate as the midpoint of the
        // bands. Good enough for visualisation; caller never needs the exact
        // SMA / EMA basis for an overlay.
        BigDecimal bbMiddle = midpoint(f.getBbUpperBand(), f.getBbLowerBand());
        BigDecimal kcMiddle = midpoint(f.getKcUpperBand(), f.getKcLowerBand());

        return IndicatorDataResponse.builder()
                .time(toEpochMs(f.getStartTime()))
                .ema20(f.getEma20())
                .ema50(f.getEma50())
                .ema200(f.getEma200())
                .bbUpper(f.getBbUpperBand())
                .bbMiddle(bbMiddle)
                .bbLower(f.getBbLowerBand())
                .kcUpper(f.getKcUpperBand())
                .kcMiddle(kcMiddle)
                .kcLower(f.getKcLowerBand())
                .rsi(f.getRsi())
                .macd(f.getMacd())
                .macdSignal(f.getMacdSignal())
                .macdHistogram(f.getMacdHistogram())
                .atr(f.getAtr())
                .adx(f.getAdx())
                .build();
    }

    private BigDecimal midpoint(BigDecimal upper, BigDecimal lower) {
        if (upper == null || lower == null) return null;
        return upper.add(lower).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
    }

    private LocalDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("Date string is empty");
        }

        if (s.matches("\\d{13}")) {
            // Candle start_time/end_time columns are stored as UTC LocalDateTimes
            // by the ingestion path. Pair-wise with toEpochMs() (which also uses
            // UTC), this keeps the round-trip stable regardless of the server's
            // default timezone — previously this used ZoneId.systemDefault()
            // and round-tripping drifted by the offset on non-UTC hosts.
            return LocalDateTime.ofInstant(
                    Instant.ofEpochMilli(Long.parseLong(s)),
                    ZoneOffset.UTC
            );
        }

        try {
            return LocalDateTime.parse(s);
        } catch (DateTimeParseException ignored) {}

        try {
            return LocalDate.parse(s).atStartOfDay();
        } catch (DateTimeParseException ignored) {}

        throw new IllegalArgumentException("Unsupported date format: " + s);
    }

    private Long toEpochMs(LocalDateTime ldt) {
        if (ldt == null) return null;
        return ldt.toInstant(ZoneOffset.UTC).toEpochMilli();
    }
}

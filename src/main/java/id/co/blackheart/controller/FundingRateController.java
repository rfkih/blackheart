package id.co.blackheart.controller;

import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.model.FundingRate;
import id.co.blackheart.repository.FundingRateRepository;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.CollectionUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read access to V34 {@code funding_rate_history} — Binance perpetual-futures
 * 8h funding events. Drives the funding-rate panel on the backtest result
 * page so operators can see what funding rate was actually realized over
 * the run window (vs the flat stub on {@code BacktestRun.fundingRateBpsPer8h}).
 *
 * <p>Read-only. Ingestion is owned by the 8-hourly scheduler + backfill job.
 */
@RestController
@RequestMapping("/api/v1/funding-rate")
@RequiredArgsConstructor
@Tag(name = "FundingRateController", description = "Read funding-rate history (V34, read-only)")
public class FundingRateController {

    private static final int MAX_RECENT = 200;

    private final FundingRateRepository repository;

    @GetMapping("/summary")
    @Operation(
            summary = "Funding-rate summary for a (symbol, [startTime, endTime]) window",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ResponseDto> summary(
            @RequestParam String symbol,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(defaultValue = "200") int limit
    ) {
        int safeLimit = Math.clamp(limit, 1, MAX_RECENT);
        // findInWindow is (lower-exclusive, upper-inclusive]; nudging the lower
        // bound back by a microsecond so the caller's [startTime, endTime] is
        // treated inclusively without changing the underlying repo contract.
        LocalDateTime lowerExclusive = startTime.minusNanos(1);
        List<FundingRate> rows = repository.findInWindow(symbol, lowerExclusive, endTime);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("symbol", symbol);
        data.put("startTime", startTime.toString());
        data.put("endTime", endTime.toString());
        data.put("count", rows.size());

        if (CollectionUtils.isEmpty(rows)) {
            data.put("mean", null);
            data.put("min", null);
            data.put("max", null);
            data.put("annualizedPct", null);
            data.put("latestRate", null);
            data.put("latestTime", null);
            data.put("recent", List.of());
        } else {
            BigDecimal sum = BigDecimal.ZERO;
            BigDecimal min = rows.get(0).getFundingRate();
            BigDecimal max = rows.get(0).getFundingRate();
            for (FundingRate r : rows) {
                BigDecimal rate = r.getFundingRate();
                sum = sum.add(rate);
                if (rate.compareTo(min) < 0) min = rate;
                if (rate.compareTo(max) > 0) max = rate;
            }
            BigDecimal mean = sum.divide(BigDecimal.valueOf(rows.size()), 12, RoundingMode.HALF_UP);
            // Three settlements per day, 365 days/yr — informational only,
            // the operator already knows it's a rough projection.
            BigDecimal annualized = mean.multiply(BigDecimal.valueOf(3L * 365L));

            FundingRate latest = rows.get(rows.size() - 1);
            data.put("mean", mean);
            data.put("min", min);
            data.put("max", max);
            data.put("annualizedPct", annualized.multiply(BigDecimal.valueOf(100)));
            data.put("latestRate", latest.getFundingRate());
            data.put("latestTime", latest.getFundingTime().toString());

            int from = Math.max(0, rows.size() - safeLimit);
            List<Map<String, Object>> recent = rows.subList(from, rows.size()).stream()
                    .map(FundingRateController::toRow)
                    .toList();
            data.put("recent", recent);
        }

        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(data)
                .build());
    }

    private static Map<String, Object> toRow(FundingRate r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fundingTime", r.getFundingTime().toString());
        m.put("fundingRate", r.getFundingRate());
        return m;
    }
}

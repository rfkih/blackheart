package id.co.blackheart.controller;

import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.marketdata.HistoricalDataService;
import id.co.blackheart.service.technicalindicator.TechnicalIndicatorService;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Historical data backfill is a privileged, CPU/IO-heavy operation that affects
 * shared market-data tables. Admin-only. Restricted to the research JVM
 * (Phase 1 decoupling) since it loads heavy historical data.
 */
@RestController
@RequestMapping("/api/v1/historical")
@RequiredArgsConstructor
@Profile("research")
@Tag(name = "HistoricalBackfillController", description = "Controller for Historical Data Warmup")
@PreAuthorize("hasRole('ADMIN')")
public class HistoricalBackfillController {

    private final HistoricalDataService historicalDataService;
    private final TechnicalIndicatorService technicalIndicatorService;

    @PostMapping({"/backfill", "/backill"})
    public ResponseEntity<ResponseDto> backfillHistoricalData(
            @RequestParam String symbol,
            @RequestParam String interval
    ) {
        historicalDataService.backfillLastCandlesAndFeatures(symbol, interval);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(Map.of(
                        "symbol", symbol,
                        "interval", interval,
                        "message", "Historical warmup completed successfully"
                ))
                .build());
    }

    /**
     * General feature backfill across a date range. Recomputes ALL FeatureStore
     * indicator columns the live ingestion path produces — works for every
     * strategy, not just VCB.
     *
     * <p>Modes:
     * <ul>
     *   <li>{@code recompute=false} (default) — fill candles in the range that
     *       lack a FeatureStore row. Idempotent.</li>
     *   <li>{@code recompute=true} — delete existing rows in the range first,
     *       then recompute. Use when indicator code/params changed.</li>
     * </ul>
     */
    @PostMapping("/backfill-features")
    public ResponseEntity<ResponseDto> backfillFeatures(
            @RequestParam String symbol,
            @RequestParam String interval,
            @RequestParam LocalDateTime from,
            @RequestParam LocalDateTime to,
            @RequestParam(defaultValue = "false") boolean recompute
    ) {
        int updated = technicalIndicatorService.backfillFeaturesInRange(
                symbol, interval, from, to, recompute);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(Map.of(
                        "symbol", symbol,
                        "interval", interval,
                        "from", from.toString(),
                        "to", to.toString(),
                        "recompute", recompute,
                        "recordsUpdated", updated
                ))
                .build());
    }

    /**
     * @deprecated Use {@code /backfill-features} instead. This endpoint only
     * patched VCB-specific columns; the new endpoint recomputes the full
     * feature set used by every strategy. Kept for back-compat — delegates
     * to the general backfill so callers see equivalent (or better) results.
     */
    @Deprecated(since = "general-backfill")
    @PostMapping("/backfill-vcb")
    public ResponseEntity<ResponseDto> backfillVcbIndicators(
            @RequestParam String symbol,
            @RequestParam String interval,
            @RequestParam LocalDateTime from,
            @RequestParam LocalDateTime to
    ) {
        int updated = technicalIndicatorService.backfillFeaturesInRange(
                symbol, interval, from, to, false);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(Map.of(
                        "symbol", symbol,
                        "interval", interval,
                        "from", from.toString(),
                        "to", to.toString(),
                        "recordsUpdated", updated,
                        "deprecated", "Use /api/v1/historical/backfill-features instead"
                ))
                .build());
    }
}

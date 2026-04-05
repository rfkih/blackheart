package id.co.blackheart.controller;

import id.co.blackheart.service.marketdata.HistoricalDataService;
import id.co.blackheart.service.technicalindicator.TechnicalIndicatorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/historical")
@RequiredArgsConstructor
public class HistoricalWarmupController {

    private final HistoricalDataService historicalDataService;
    private final TechnicalIndicatorService technicalIndicatorService;

    @PostMapping("/warmup")
    public Map<String, Object> warmupHistoricalData(
            @RequestParam String symbol,
            @RequestParam String interval
    ) {
        historicalDataService.backfillLastCandlesAndFeatures(symbol, interval);

        return Map.of(
                "success", true,
                "message", "Historical warmup completed successfully",
                "symbol", symbol,
                "interval", interval
        );
    }

    /**
     * Patches existing FeatureStore records that are missing VCB indicator fields
     * (bb_width, kc_width, atr_ratio, signed_er_20 and band values).
     * Run this once after deploying the VCB strategy for the first time.
     *
     * Example: POST /api/historical/backfill-vcb?symbol=BTCUSDT&interval=1h&from=2025-01-01T00:00:00&to=2026-04-04T00:00:00
     */
    @PostMapping("/backfill-vcb")
    public Map<String, Object> backfillVcbIndicators(
            @RequestParam String symbol,
            @RequestParam String interval,
            @RequestParam LocalDateTime from,
            @RequestParam LocalDateTime to
    ) {
        int updated = technicalIndicatorService.backfillVcbIndicators(symbol, interval, from, to);

        return Map.of(
                "success", true,
                "symbol", symbol,
                "interval", interval,
                "from", from.toString(),
                "to", to.toString(),
                "recordsUpdated", updated
        );
    }
}
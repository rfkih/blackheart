package id.co.blackheart.controller;

import id.co.blackheart.service.marketdata.HistoricalWarmupService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/historical")
@RequiredArgsConstructor
public class HistoricalWarmupController {

    private final HistoricalWarmupService historicalWarmupService;

    @PostMapping("/warmup")
    public Map<String, Object> warmupHistoricalData(
            @RequestParam String symbol,
            @RequestParam String interval
    ) {
        historicalWarmupService.backfillLastCandlesAndFeatures(symbol, interval);

        return Map.of(
                "success", true,
                "message", "Historical warmup completed successfully",
                "symbol", symbol,
                "interval", interval
        );
    }
}
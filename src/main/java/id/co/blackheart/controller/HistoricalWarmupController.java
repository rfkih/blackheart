package id.co.blackheart.controller;

import id.co.blackheart.service.marketdata.HistoricalDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/historical")
@RequiredArgsConstructor
public class HistoricalWarmupController {

    private final HistoricalDataService historicalDataService;

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
}
package id.co.blackheart.controller;

import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.marketdata.HistoricalDataService;
import id.co.blackheart.service.technicalindicator.TechnicalIndicatorService;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/historical")
@RequiredArgsConstructor
@Tag(name = "HistoricalWarmupController", description = "Controller for Historical Data Warmup")
public class HistoricalWarmupController {

    private final HistoricalDataService historicalDataService;
    private final TechnicalIndicatorService technicalIndicatorService;

    @PostMapping("/warmup")
    public ResponseEntity<ResponseDto> warmupHistoricalData(
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

    @PostMapping("/backfill-vcb")
    public ResponseEntity<ResponseDto> backfillVcbIndicators(
            @RequestParam String symbol,
            @RequestParam String interval,
            @RequestParam LocalDateTime from,
            @RequestParam LocalDateTime to
    ) {
        int updated = technicalIndicatorService.backfillVcbIndicators(symbol, interval, from, to);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(Map.of(
                        "symbol", symbol,
                        "interval", interval,
                        "from", from.toString(),
                        "to", to.toString(),
                        "recordsUpdated", updated
                ))
                .build());
    }
}

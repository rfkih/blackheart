package id.co.blackheart.controller;

import id.co.blackheart.dto.request.BacktestRunRequest;
import id.co.blackheart.dto.response.BacktestRunDetailResponse;
import id.co.blackheart.dto.response.BacktestRunResponse;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.backtest.BacktestQueryService;
import id.co.blackheart.service.backtest.BacktestService;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/backtest")
@RequiredArgsConstructor
@Tag(name = "BacktestV1Controller", description = "Backtest API v1")
public class BacktestV1Controller {

    private final BacktestService backtestService;
    private final BacktestQueryService backtestQueryService;

    /**
     * Submit a new backtest run. Returns the same {@link BacktestRunDetailResponse}
     * shape as {@code GET /api/v1/backtest/:id} so clients can use one mapper
     * for both write and read paths — earlier versions returned the legacy
     * {@code BacktestRunResponse} shape and callers ended up with undefined
     * ids when navigating to the result page.
     */
    @PostMapping
    public ResponseEntity<ResponseDto> runBacktest(@RequestBody BacktestRunRequest request) {
        BacktestRunResponse submitted = backtestService.runBacktest(request);
        BacktestRunDetailResponse detail = backtestQueryService.getRun(submitted.getBacktestRunId());
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(detail)
                .build());
    }

    @GetMapping
    public ResponseEntity<ResponseDto> listRuns(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(backtestQueryService.listRuns(page, size))
                .build());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResponseDto> getRun(@PathVariable UUID id) {
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(backtestQueryService.getRun(id))
                .build());
    }

    @GetMapping("/{id}/equity-points")
    public ResponseEntity<ResponseDto> getEquityPoints(@PathVariable UUID id) {
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(backtestQueryService.getEquityPoints(id))
                .build());
    }

    @GetMapping("/{id}/trades")
    public ResponseEntity<ResponseDto> getTrades(@PathVariable UUID id) {
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(backtestQueryService.getTrades(id))
                .build());
    }

    @GetMapping("/{id}/candles")
    public ResponseEntity<ResponseDto> getCandles(@PathVariable UUID id) {
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(backtestQueryService.getCandles(id))
                .build());
    }
}

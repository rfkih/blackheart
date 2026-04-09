package id.co.blackheart.controller;

import id.co.blackheart.dto.request.BacktestRunRequest;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.backtest.BacktestService;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
@Tag(name = "BacktestController", description = "Controller for Backtest Execution")
public class BacktestController {

    private final BacktestService backtestService;

    @PostMapping("/run")
    public ResponseEntity<ResponseDto> runBacktest(@RequestBody BacktestRunRequest request) {
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(backtestService.runBacktest(request))
                .build());
    }
}

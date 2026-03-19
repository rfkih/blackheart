package id.co.blackheart.controller;

import id.co.blackheart.dto.request.BacktestRunRequest;
import id.co.blackheart.dto.response.BacktestRunResponse;
import id.co.blackheart.service.backtest.BacktestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/backtest")
@RequiredArgsConstructor
public class BacktestController {

    private final BacktestService backtestService;

    @PostMapping("/run")
    public BacktestRunResponse runBacktest(@RequestBody BacktestRunRequest request) {
        return backtestService.runBacktest(request);
    }
}
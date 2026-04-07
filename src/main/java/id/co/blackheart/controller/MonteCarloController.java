package id.co.blackheart.controller;

import id.co.blackheart.dto.montecarlo.MonteCarloResponse;
import id.co.blackheart.dto.request.MonteCarloRequest;
import id.co.blackheart.service.montecarlo.MonteCarloService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/monte-carlo")
@RequiredArgsConstructor
public class MonteCarloController {

    private final MonteCarloService monteCarloService;

    @PostMapping("/run")
    public MonteCarloResponse run(@RequestBody MonteCarloRequest request) {
        return monteCarloService.run(request);
    }
}
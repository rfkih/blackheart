package id.co.blackheart.controller;

import id.co.blackheart.dto.request.MonteCarloRequest;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.montecarlo.MonteCarloService;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/monte-carlo")
@RequiredArgsConstructor
@Tag(name = "MonteCarloController", description = "Controller for Monte Carlo Simulation")
public class MonteCarloController {

    private final MonteCarloService monteCarloService;

    @PostMapping("/run")
    public ResponseEntity<ResponseDto> run(@RequestBody MonteCarloRequest request) {
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(monteCarloService.run(request))
                .build());
    }
}

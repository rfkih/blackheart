package id.co.blackheart.controller;

import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.engine.EngineMetrics;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Operator endpoint for spec-driven engine health (parametric blueprint M4.2).
 *
 * <p>Returns per-strategy-code eval / error counters and an error-rate
 * percentage drawn from {@link EngineMetrics}. This is a placeholder until
 * Phase 7 ships proper Actuator + Prometheus scraping; the shape here is
 * intentionally close to the metric set Phase 7 will publish so dashboards
 * built against this can migrate by swapping the source URL.
 *
 * <p>Counters are process-local — both JVMs (trading, research) maintain
 * independent counts. Restarting either JVM zeroes its counters; only the
 * underlying {@code spec_trace} table provides historical depth.
 */
@RestController
@RequestMapping("/api/v1/engine/metrics")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "EngineMetricsController",
        description = "Spec-driven engine health metrics (M4.2 — Phase 7 will replace with Actuator/Prometheus)")
public class EngineMetricsController {

    private final EngineMetrics engineMetrics;

    /**
     * Snapshot of evaluation counters for every strategy code seen since the
     * JVM started. Output shape:
     * <pre>{@code
     * {
     *   "MMR": { "evalCount": 1820, "errorCount": 0, "errorRatePct": 0.0 },
     *   "DCB": { "evalCount":  912, "errorCount": 3, "errorRatePct": 0.329 }
     * }
     * }</pre>
     */
    @GetMapping
    public ResponseEntity<ResponseDto> getMetrics() {
        // Snapshot errors BEFORE evals: recordError increments evalCount then
        // errorCount, so reading errors first guarantees every counted error
        // is already reflected in the subsequent eval read (errors ≤ evals).
        // Reversing this order can produce errorRatePct > 100%.
        Map<String, AtomicLong> errorSnapshot = engineMetrics.errorCountSnapshot();
        Map<String, AtomicLong> evalSnapshot = engineMetrics.evalCountSnapshot();

        // Deterministic ordering helps when an operator diffs two snapshots.
        TreeSet<String> codes = new TreeSet<>();
        codes.addAll(evalSnapshot.keySet());
        codes.addAll(errorSnapshot.keySet());

        Map<String, Map<String, Object>> body = new LinkedHashMap<>();
        for (String code : codes) {
            long evals = evalSnapshot.getOrDefault(code, new AtomicLong()).get();
            long errors = errorSnapshot.getOrDefault(code, new AtomicLong()).get();
            double rate = evals == 0L ? 0.0 : (errors * 100.0 / evals);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("evalCount", evals);
            entry.put("errorCount", errors);
            entry.put("errorRatePct", round3(rate));
            body.put(code, entry);
        }

        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(body)
                .build());
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}

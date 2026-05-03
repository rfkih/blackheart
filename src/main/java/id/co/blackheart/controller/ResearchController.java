package id.co.blackheart.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.repository.BacktestRunRepository;
import id.co.blackheart.service.research.AnalysisReport;
import id.co.blackheart.service.research.BacktestAnalysisService;
import id.co.blackheart.service.strategy.ResearchParamService;
import id.co.blackheart.service.research.ResearchSweepService;
import id.co.blackheart.service.research.SweepSpec;
import id.co.blackheart.service.research.SweepState;
import id.co.blackheart.service.strategy.TrendPullbackStrategyService.Params;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Research-loop endpoints: on-demand analysis, hot-reloadable TPR params,
 * a research-log view, and the parameter-sweep driver.
 *
 * <p>Access split:
 *   <ul>
 *     <li>Sweeps endpoints are user-accessible — every sweep is created with
 *     the caller's {@code userId} and read/cancel/delete check ownership at
 *     the service layer, so each user only sees their own.</li>
 *     <li>{@code GET /tpr/params} is read-only and exposed to all users
 *     because the sweep wizard uses it to seed the TPR baseline params.</li>
 *     <li>Everything else (analysis on arbitrary run ids, mutating TPR
 *     params, the global research log) is admin-only via method-level
 *     {@link PreAuthorize}.</li>
 *   </ul>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/research")
@Profile("research")
@RequiredArgsConstructor
@Tag(name = "ResearchController",
     description = "Research-mode diagnostics + hot-reloadable strategy params")
public class ResearchController {

    private final BacktestAnalysisService analysisService;
    private final ResearchParamService researchParamService;
    private final ResearchSweepService sweepService;
    private final BacktestRunRepository runRepository;
    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    // ── Per-run analysis ─────────────────────────────────────────────────────

    @GetMapping("/backtest/{runId}/analysis")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Fetch the diagnostic report for a completed backtest run",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> getAnalysis(
            @PathVariable UUID runId,
            @RequestParam(name = "recompute", defaultValue = "false") boolean recompute) {
        BacktestRun run = runRepository.findById(runId)
                .orElseThrow(() -> new EntityNotFoundException("Run not found: " + runId));

        // If the caller explicitly asked for a fresh computation, or the row
        // has no cached snapshot yet (older runs, or completed-before-analyzer),
        // compute now. Otherwise return the cached payload verbatim.
        AnalysisReport report;
        if (recompute || run.getAnalysisSnapshot() == null || run.getAnalysisSnapshot().isBlank()) {
            report = analysisService.analyze(runId);
        } else {
            try {
                report = objectMapper.readValue(run.getAnalysisSnapshot(), AnalysisReport.class);
            } catch (Exception e) {
                // Malformed snapshot → fall back to recomputation so the
                // caller still gets useful data.
                log.warn("Snapshot unreadable for run {}, recomputing", runId, e);
                report = analysisService.analyze(runId);
            }
        }

        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(report)
                .build());
    }

    // ── TPR hot-reloadable params ────────────────────────────────────────────

    @GetMapping("/tpr/params")
    @Operation(summary = "Current live TPR research params",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> getTprParams() {
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(researchParamService.getTprParams())
                .build());
    }

    @PutMapping("/tpr/params")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Replace the live TPR research params (hot reload, no restart)",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> setTprParams(@RequestBody Params params) {
        Params persisted = researchParamService.setTprParams(params);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(persisted)
                .build());
    }

    @PostMapping("/tpr/params/reset")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Reset TPR params to the baked-in defaults",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> resetTprParams() {
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(researchParamService.resetTprToDefaults())
                .build());
    }

    // ── Research log ─────────────────────────────────────────────────────────

    /**
     * Compact progression view — one row per completed backtest with the
     * headline metrics flattened out. Lets us see version-over-version deltas
     * at a glance (v0.1 → v0.2b → v0.3 → v0.4).
     */
    @GetMapping("/log")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Research log — paginated/filterable feed of completed runs, newest first",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> getResearchLog(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(name = "strategyCode", required = false) String strategyCode,
            @RequestParam(name = "asset", required = false) String asset,
            @RequestParam(name = "interval", required = false) String interval,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        UUID userId = jwtService.extractUserId(authHeader.substring(7));
        // Normalize blanks → null so the repo's `IS NULL` short-circuits engage
        // when the user clears a filter (the form sends an empty string).
        String code = (strategyCode == null || strategyCode.isBlank()) ? null : strategyCode.trim();
        String assetFilter = (asset == null || asset.isBlank()) ? null : asset.trim();
        String intervalFilter = (interval == null || interval.isBlank()) ? null : interval.trim();
        int cappedSize = Math.max(1, Math.min(size, 200));
        Pageable pageable = PageRequest.of(Math.max(0, page), cappedSize);

        Page<BacktestRun> runs = runRepository.findResearchLog(
                userId, code, assetFilter, intervalFilter, pageable);
        Page<Map<String, Object>> rows = runs.map(this::toLogRow);

        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(rows)
                .build());
    }

    // ── Sweep driver ─────────────────────────────────────────────────────────

    @PostMapping("/sweeps")
    @Operation(summary = "Start a parameter-grid sweep for a research strategy",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> createSweep(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody SweepSpec spec) {
        UUID userId = jwtService.extractUserId(authHeader.substring(7));
        SweepState state = sweepService.startSweep(userId, spec);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ResponseDto.builder()
                .responseCode(HttpStatus.ACCEPTED.value() + ResponseCode.SUCCESS.getCode())
                .data(state)
                .build());
    }

    @GetMapping("/sweeps/{sweepId}")
    @Operation(summary = "Fetch a sweep's current state + partial leaderboard",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> getSweep(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID sweepId) {
        UUID userId = jwtService.extractUserId(authHeader.substring(7));
        SweepState state = sweepService.getSweep(sweepId);
        if (state == null
                || (state.getUserId() != null && !state.getUserId().equals(userId))) {
            // Use a 404 for "not yours" as well — mirrors the IDOR-safe pattern.
            throw new jakarta.persistence.EntityNotFoundException("Sweep not found: " + sweepId);
        }
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(state)
                .build());
    }

    @GetMapping("/sweeps")
    @Operation(summary = "List sweeps the caller owns — paginated, filterable, sortable",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> listSweeps(
            @RequestHeader("Authorization") String authHeader,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "sort", required = false) String sort,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "25") int size) {
        UUID userId = jwtService.extractUserId(authHeader.substring(7));
        Set<String> statusFilter = parseCsvUpper(status);
        // Cap size to keep dashboards from accidentally loading thousands of rows
        // through the in-memory filter (the same cap RecentPromotions uses).
        int cappedSize = Math.max(1, Math.min(size, 100));
        Pageable pageable = PageRequest.of(Math.max(0, page), cappedSize);
        Page<SweepState> result = sweepService.listSweepsPaged(userId, statusFilter, sort, pageable);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(result)
                .build());
    }

    /** "RUNNING,PENDING" → {RUNNING, PENDING}. Empty/null → empty set (= no filter). */
    private static Set<String> parseCsvUpper(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        Set<String> out = new HashSet<>();
        for (String token : csv.split(",")) {
            String t = token.trim().toUpperCase(Locale.ROOT);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    @PostMapping("/sweeps/{sweepId}/cancel")
    @Operation(summary = "Cancel a running sweep — finishes the in-flight combo, then stops",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> cancelSweep(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID sweepId) {
        UUID userId = jwtService.extractUserId(authHeader.substring(7));
        SweepState state = sweepService.getSweep(sweepId);
        if (state == null
                || (state.getUserId() != null && !state.getUserId().equals(userId))) {
            throw new jakarta.persistence.EntityNotFoundException("Sweep not found: " + sweepId);
        }
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(sweepService.cancelSweep(sweepId))
                .build());
    }

    @PostMapping("/sweeps/{sweepId}/evaluate-holdout")
    @Operation(summary = "One-shot unbiased evaluation on the locked holdout slice. "
            + "Server rejects second attempts; pick your winner carefully.",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> evaluateHoldout(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID sweepId,
            @RequestBody Map<String, Object> body) {
        UUID userId = jwtService.extractUserId(authHeader.substring(7));
        @SuppressWarnings("unchecked")
        Map<String, Object> paramSet = (Map<String, Object>) body.get("paramSet");
        BacktestRun run = sweepService.evaluateHoldout(userId, sweepId, paramSet);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ResponseDto.builder()
                .responseCode(HttpStatus.ACCEPTED.value() + ResponseCode.SUCCESS.getCode())
                .data(Map.of(
                        "backtestRunId", run.getBacktestRunId(),
                        "sweepId", sweepId
                ))
                .build());
    }

    @DeleteMapping("/sweeps/{sweepId}")
    @Operation(summary = "Delete a finished sweep — removes both memory record and disk file",
               security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<ResponseDto> deleteSweep(
            @RequestHeader("Authorization") String authHeader,
            @PathVariable UUID sweepId) {
        UUID userId = jwtService.extractUserId(authHeader.substring(7));
        SweepState state = sweepService.getSweep(sweepId);
        if (state == null
                || (state.getUserId() != null && !state.getUserId().equals(userId))) {
            throw new jakarta.persistence.EntityNotFoundException("Sweep not found: " + sweepId);
        }
        sweepService.deleteSweep(sweepId);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(Map.of("deleted", true))
                .build());
    }

    /** Flatten the analysis snapshot's headline metrics into a single row. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> toLogRow(BacktestRun r) {
        Map<String, Object> row = new HashMap<>();
        row.put("runId", r.getBacktestRunId());
        row.put("strategyCode", r.getStrategyCode());
        row.put("strategyVersion", r.getStrategyVersion());
        row.put("asset", r.getAsset());
        row.put("interval", r.getInterval());
        row.put("createdAt", r.getCreatedTime());

        try {
            Map<String, Object> snap = objectMapper.readValue(
                    r.getAnalysisSnapshot(), new TypeReference<Map<String, Object>>() {});
            Object headline = snap.get("headline");
            if (headline instanceof Map<?, ?> h) {
                row.put("tradeCount", h.get("tradeCount"));
                row.put("winRate", h.get("winRate"));
                row.put("profitFactor", h.get("profitFactor"));
                row.put("avgR", h.get("avgR"));
                row.put("netPnl", h.get("netPnl"));
                row.put("maxDrawdown", h.get("maxDrawdown"));
                row.put("maxConsecutiveLosses", h.get("maxConsecutiveLosses"));
            }
        } catch (Exception e) {
            log.warn("Could not flatten analysis for run {}", r.getBacktestRunId(), e);
        }
        return row;
    }
}

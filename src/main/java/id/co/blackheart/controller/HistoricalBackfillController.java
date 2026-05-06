package id.co.blackheart.controller;

import id.co.blackheart.dto.request.SubmitHistoricalJobRequest;
import id.co.blackheart.dto.response.CoverageReport;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.model.HistoricalBackfillJob;
import id.co.blackheart.model.JobStatus;
import id.co.blackheart.repository.UserRepository;
import id.co.blackheart.service.marketdata.MarketDataIntegrityService;
import id.co.blackheart.service.marketdata.job.HistoricalBackfillJobService;
import id.co.blackheart.service.technicalindicator.patcher.FeaturePatcher;
import id.co.blackheart.service.technicalindicator.patcher.FeaturePatcherRegistry;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Historical data backfill is a privileged, CPU/IO-heavy operation that affects
 * shared market-data tables. Admin-only. Restricted to the research JVM
 * (Phase 1 decoupling) since it loads heavy historical data.
 */
@RestController
@RequestMapping("/api/v1/historical")
@RequiredArgsConstructor
@Profile("research")
@Tag(name = "HistoricalBackfillController", description = "Controller for Historical Data Warmup")
@PreAuthorize("hasRole('ADMIN')")
public class HistoricalBackfillController {

    private final MarketDataIntegrityService marketDataIntegrityService;
    private final HistoricalBackfillJobService historicalBackfillJobService;
    private final FeaturePatcherRegistry featurePatcherRegistry;
    private final UserRepository userRepository;

    /**
     * Resolve the UUID of the admin who triggered the request. Used as the
     * audit trail on {@link HistoricalBackfillJob#createdByUserId}. Returns
     * {@code null} only when the SecurityContext is empty (shouldn't happen
     * given the @PreAuthorize gate), or when the authenticated email no
     * longer maps to a row in {@code users} (deleted account, edge case).
     */
    private UUID resolveCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            return null;
        }
        Object principal = auth.getPrincipal();
        String email = (principal instanceof UserDetails ud) ? ud.getUsername() : auth.getName();
        if (!StringUtils.hasText(email)) return null;
        return userRepository.findByEmail(email).map(u -> u.getUserId()).orElse(null);
    }

    /**
     * Discovery endpoint — lists every column that has a registered
     * {@code FeaturePatcher}. The UI cross-references this with the
     * {@code nullColumns} map from {@code /coverage} to render only the
     * patches actually available.
     */
    @GetMapping("/patchable-columns")
    public ResponseEntity<ResponseDto> patchableColumns() {
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (String col : featurePatcherRegistry.registeredColumns()) {
            FeaturePatcher<?> patcher = featurePatcherRegistry.find(col).orElseThrow();
            rows.add(Map.of(
                    "column", col,
                    "writtenColumns", patcher.writtenColumns()
            ));
        }
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(Map.of("columns", rows))
                .build());
    }

    // ── Async job endpoints ───────────────────────────────────────────────
    // The unified UI submits work through these and polls for status. Each
    // job is one (symbol, interval) repair operation; multi-pair work is
    // multiple jobs.

    /**
     * Submit a new historical backfill job. Returns the persisted PENDING
     * row immediately — execution happens on {@code taskExecutor}. Submitting
     * a {@code jobType} for which no handler is registered returns 400.
     */
    @PostMapping("/jobs")
    public ResponseEntity<ResponseDto> submitJob(@RequestBody SubmitHistoricalJobRequest req) {
        HistoricalBackfillJob job = historicalBackfillJobService.submit(
                req.jobType(),
                req.symbol(),
                req.interval(),
                req.params(),
                resolveCurrentUserId()
        );
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(job)
                .build());
    }

    /** Get a single job by ID — used for polling status + progress. */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<ResponseDto> getJob(@PathVariable UUID jobId) {
        HistoricalBackfillJob job = historicalBackfillJobService.get(jobId);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(job)
                .build());
    }

    /**
     * Cooperative cancel — sets {@code cancel_requested}. The handler exits
     * at its next polling point and the runner marks the job CANCELLED.
     * Already-finished jobs are a no-op.
     */
    @PostMapping("/jobs/{jobId}/cancel")
    public ResponseEntity<ResponseDto> cancelJob(@PathVariable UUID jobId) {
        HistoricalBackfillJob job = historicalBackfillJobService.requestCancel(jobId);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(job)
                .build());
    }

    /**
     * List recent jobs. Optional {@code status} filter; default ordering is
     * newest-first. {@code limit} is capped server-side at 500.
     */
    @GetMapping("/jobs")
    public ResponseEntity<ResponseDto> listJobs(
            @RequestParam(required = false) JobStatus status,
            @RequestParam(required = false) Integer limit
    ) {
        List<HistoricalBackfillJob> jobs = historicalBackfillJobService.list(status, limit);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(jobs)
                .build());
    }

    /**
     * Read-only coverage report for the unified data integrity console.
     * The {@code /admin/historical} UI calls this first to populate the
     * "what's broken?" view before the operator picks repair actions.
     *
     * <p>{@code from}/{@code to} are optional — when omitted the service
     * defaults to the full available range for the (symbol, interval).
     * Returns gaps, NULL counts per indicator column, and sanity counters
     * — all bounded queries, safe to run on demand.
     */
    @GetMapping("/coverage")
    public ResponseEntity<ResponseDto> coverage(
            @RequestParam String symbol,
            @RequestParam String interval,
            @RequestParam(required = false) LocalDateTime from,
            @RequestParam(required = false) LocalDateTime to
    ) {
        CoverageReport report = marketDataIntegrityService.report(symbol, interval, from, to);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(report)
                .build());
    }

}

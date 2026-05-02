package id.co.blackheart.controller;

import com.fasterxml.jackson.databind.JsonNode;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.model.WalkForwardRun;
import id.co.blackheart.repository.WalkForwardRunRepository;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only viewer for {@code walk_forward_run}. The orchestrator owns
 * write authority via {@code POST /walk-forward}; this controller exists
 * only so the operator UI can browse stability verdicts without shelling
 * into psql or the orchestrator API.
 *
 * <p>Triggering a walk-forward from the dashboard is intentionally NOT
 * exposed — the run is up to ~3h synchronous and the existing reverse
 * proxy ({@code /api/v1/research-orch}) caps non-GET timeouts at 35min.
 * Operators kick off walk-forward via {@code POST /walk-forward} on the
 * orchestrator (see CLAUDE.md "Research operations") or wait for the
 * autonomous research loop to schedule one after a SIGNIFICANT_EDGE tick.
 */
@RestController
@RequestMapping("/api/v1/walk-forward")
@RequiredArgsConstructor
@Profile("!research")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "WalkForwardController", description = "Admin viewer for walk_forward_run")
public class WalkForwardController {

    private static final int MAX_PAGE_SIZE = 100;

    private final WalkForwardRunRepository repository;

    @GetMapping
    @Operation(
            summary = "List walk-forward runs, newest first, with optional filters",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ResponseDto> list(
            @RequestParam(required = false) String strategyCode,
            @RequestParam(required = false) String instrument,
            @RequestParam(required = false) String intervalName,
            @RequestParam(required = false) String verdict,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size
    ) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize);

        Page<WalkForwardRun> rows = repository.findFiltered(
                blankToNull(strategyCode),
                blankToNull(instrument),
                blankToNull(intervalName),
                blankToNull(verdict == null ? null : verdict.trim().toUpperCase()),
                pageable);

        List<Map<String, Object>> content = rows.getContent().stream()
                .map(WalkForwardController::toListRow)
                .toList();

        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(Map.of(
                        "content", content,
                        "page", rows.getNumber(),
                        "size", rows.getSize(),
                        "totalElements", rows.getTotalElements(),
                        "totalPages", rows.getTotalPages()
                ))
                .build());
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Single walk-forward run including fold breakdown",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ResponseDto> get(@PathVariable("id") UUID id) {
        WalkForwardRun row = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("walk_forward_run row not found"));
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(toDetailRow(row))
                .build());
    }

    @GetMapping("/recent")
    @Operation(
            summary = "Recent walk-forward runs scoped to a (strategy, instrument, interval)",
            description = "Used by the strategy detail page to surface the latest stability verdict inline. Returns up to {limit} rows newest-first.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ResponseDto> recent(
            @RequestParam String strategyCode,
            @RequestParam String instrument,
            @RequestParam String intervalName,
            @RequestParam(defaultValue = "5") int limit
    ) {
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        List<WalkForwardRun> rows = repository.findRecentForStrategy(
                strategyCode, instrument, intervalName, PageRequest.of(0, safeLimit));
        List<Map<String, Object>> content = rows.stream()
                .map(WalkForwardController::toListRow)
                .toList();
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(Map.of(
                        "content", content,
                        "strategyCode", strategyCode,
                        "instrument", instrument,
                        "intervalName", intervalName
                ))
                .build());
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private static Map<String, Object> toListRow(WalkForwardRun w) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("walkForwardId", w.getWalkForwardId());
        row.put("strategyCode", w.getStrategyCode());
        row.put("instrument", w.getInstrument());
        row.put("intervalName", w.getIntervalName());
        row.put("stabilityVerdict", w.getStabilityVerdict());
        row.put("nFolds", w.getNFolds());
        row.put("trainMonths", w.getTrainMonths());
        row.put("testMonths", w.getTestMonths());
        row.put("fullWindowStart", w.getFullWindowStart() == null ? null : w.getFullWindowStart().toString());
        row.put("fullWindowEnd", w.getFullWindowEnd() == null ? null : w.getFullWindowEnd().toString());
        row.put("foldPfMean", asNullable(w.getFoldPfMean()));
        row.put("foldPfStd", asNullable(w.getFoldPfStd()));
        row.put("foldPfMin", asNullable(w.getFoldPfMin()));
        row.put("foldPfMax", asNullable(w.getFoldPfMax()));
        row.put("foldPfPositivePct", asNullable(w.getFoldPfPositivePct()));
        row.put("foldReturnMean", asNullable(w.getFoldReturnMean()));
        row.put("foldReturnStd", asNullable(w.getFoldReturnStd()));
        row.put("foldSharpeMean", asNullable(w.getFoldSharpeMean()));
        row.put("foldSharpeStd", asNullable(w.getFoldSharpeStd()));
        row.put("totalTradesAcrossFolds", w.getTotalTradesAcrossFolds());
        row.put("motivatingIterationId", w.getMotivatingIterationId());
        row.put("createdTime", w.getCreatedTime() == null ? null : w.getCreatedTime().toString());
        row.put("createdBy", w.getCreatedBy());
        return row;
    }

    private static Map<String, Object> toDetailRow(WalkForwardRun w) {
        Map<String, Object> row = toListRow(w);
        JsonNode folds = w.getFoldResults();
        row.put("foldResults", folds);
        row.put("gitCommitHash", w.getGitCommitHash());
        row.put("notes", w.getNotes());
        return row;
    }

    private static Object asNullable(BigDecimal v) {
        return v == null ? null : v;
    }
}

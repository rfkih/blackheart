package id.co.blackheart.controller;

import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.model.SpecTrace;
import id.co.blackheart.repository.SpecTraceRepository;
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
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only viewer for {@code spec_trace} (V19). Drives the
 * {@code SpecTraceViewer} UI used by operators to inspect engine decision
 * trees — rule-by-rule evaluation, spec snapshot at decision time, latency,
 * and any caught engine errors.
 *
 * <p>Writes are owned by {@code SpecTraceLogger} on the engine path; this
 * controller never mutates rows.
 *
 * <p>List endpoint emits a list-row projection (omits the heavyweight
 * {@code specSnapshot} JSONB to keep paged responses small); the detail
 * endpoint returns the full row including spec snapshot and per-rule trace.
 */
@RestController
@RequestMapping("/api/v1/spec-trace")
@RequiredArgsConstructor
@Profile("!research")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "SpecTraceController", description = "Admin viewer for spec_trace")
public class SpecTraceController {

    private static final int MAX_PAGE_SIZE = 200;

    private final SpecTraceRepository repository;

    @GetMapping
    @Operation(
            summary = "List spec traces, newest first, with optional filters",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ResponseDto> list(
            @RequestParam(required = false) UUID backtestRunId,
            @RequestParam(required = false) UUID accountStrategyId,
            @RequestParam(required = false) String strategyCode,
            @RequestParam(required = false) String phase,
            @RequestParam(required = false) String decision,
            @RequestParam(defaultValue = "false") boolean errorsOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        // Require at least one scope dimension. Without it, the JPQL filter
        // degenerates into a full-table scan + count(*) over a table that hits
        // hundreds of thousands of rows per backtest. The two indexed access
        // paths are (backtest_run_id, bar_time) and (account_strategy_id,
        // bar_time DESC); pick at least one.
        if (backtestRunId == null && accountStrategyId == null) {
            throw new IllegalArgumentException(
                    "Either backtestRunId or accountStrategyId is required");
        }
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize);

        Page<SpecTrace> rows = repository.findFiltered(
                backtestRunId,
                accountStrategyId,
                blankToNull(strategyCode == null ? null : strategyCode.trim().toUpperCase()),
                blankToNull(phase == null ? null : phase.trim().toUpperCase()),
                blankToNull(decision == null ? null : decision.trim().toUpperCase()),
                errorsOnly,
                pageable);

        List<Map<String, Object>> content = rows.getContent().stream()
                .map(SpecTraceController::toListRow)
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
            summary = "Single spec trace including spec_snapshot and per-rule trace",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ResponseDto> get(@PathVariable("id") UUID id) {
        SpecTrace row = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("spec_trace row not found"));
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(toDetailRow(row))
                .build());
    }

    private static String blankToNull(String s) {
        return StringUtils.hasText(s) ? s : null;
    }

    private static Map<String, Object> toListRow(SpecTrace t) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("traceId", t.getTraceId());
        row.put("backtestRunId", t.getBacktestRunId());
        row.put("accountStrategyId", t.getAccountStrategyId());
        row.put("strategyCode", t.getStrategyCode());
        row.put("barTime", t.getBarTime() == null ? null : t.getBarTime().toString());
        row.put("phase", t.getPhase());
        row.put("decision", t.getDecision());
        row.put("decisionReason", t.getDecisionReason());
        row.put("evalLatencyUs", t.getEvalLatencyUs());
        row.put("errorClass", t.getErrorClass());
        row.put("createdTime", t.getCreatedTime() == null ? null : t.getCreatedTime().toString());
        return row;
    }

    private static Map<String, Object> toDetailRow(SpecTrace t) {
        Map<String, Object> row = toListRow(t);
        row.put("specSnapshot", t.getSpecSnapshot());
        row.put("rules", t.getRules());
        row.put("errorMessage", t.getErrorMessage());
        return row;
    }
}

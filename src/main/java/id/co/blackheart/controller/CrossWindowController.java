package id.co.blackheart.controller;

import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.model.CrossWindowRun;
import id.co.blackheart.repository.CrossWindowRunRepository;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read access to {@code cross_window_run} (V38) — the regime-stratified
 * validation results written by the research orchestrator. Surfaces:
 *
 * <ul>
 *   <li>Strategy detail page — current verdict + per-window breakdown.</li>
 *   <li>Promotion dialog — gate-warning if the latest verdict is not
 *       {@code ROBUST_CROSS_WINDOW}.</li>
 * </ul>
 *
 * <p>No mutation endpoints — the orchestrator is the sole writer.
 */
@RestController
@RequestMapping("/api/v1/cross-window")
@RequiredArgsConstructor
@Tag(name = "CrossWindowController", description = "Cross-window validation verdicts (read-only)")
public class CrossWindowController {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 50;

    private final CrossWindowRunRepository repository;

    @GetMapping
    @Operation(
            summary = "List cross-window runs for a (strategy, interval, instrument), newest first",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ResponseDto> list(
            @RequestParam String strategyCode,
            @RequestParam String intervalName,
            @RequestParam String instrument,
            @RequestParam(defaultValue = "5") int limit
    ) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<CrossWindowRun> rows = repository
                .findByStrategyCodeAndIntervalNameAndInstrumentOrderByCreatedTimeDesc(
                        strategyCode, intervalName, instrument, PageRequest.of(0, safeLimit));

        List<Map<String, Object>> mapped = rows.stream()
                .map(CrossWindowController::toRow)
                .toList();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("strategyCode", strategyCode);
        data.put("intervalName", intervalName);
        data.put("instrument", instrument);
        data.put("runs", mapped);
        // Convenience: the head row is what every caller actually cares
        // about. Returning it explicitly saves the client a `[0]` access
        // and makes the empty-state branch explicit.
        data.put("latest", mapped.isEmpty() ? null : mapped.get(0));

        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(data)
                .build());
    }

    private static Map<String, Object> toRow(CrossWindowRun r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("crossWindowId", r.getCrossWindowId());
        m.put("strategyCode", r.getStrategyCode());
        m.put("intervalName", r.getIntervalName());
        m.put("instrument", r.getInstrument());
        m.put("windowsCatalogVersion", r.getWindowsCatalogVersion());
        m.put("nWindows", r.getNWindows());
        m.put("nWindowsCompleted", r.getNWindowsCompleted());
        m.put("windowPfMean", r.getWindowPfMean());
        m.put("windowPfStd", r.getWindowPfStd());
        m.put("windowPfMin", r.getWindowPfMin());
        m.put("windowPfMax", r.getWindowPfMax());
        m.put("pctWindowsNetPositive", r.getPctWindowsNetPositive());
        m.put("windowReturnMean", r.getWindowReturnMean());
        m.put("windowReturnStd", r.getWindowReturnStd());
        m.put("totalTradesAcrossWindows", r.getTotalTradesAcrossWindows());
        m.put("crossWindowVerdict", r.getCrossWindowVerdict());
        m.put("motivatingIterationId", r.getMotivatingIterationId());
        m.put("motivatingWalkForwardId", r.getMotivatingWalkForwardId());
        m.put("windowResults", r.getWindowResults());
        m.put("gitCommitHash", r.getGitCommitHash());
        m.put("notes", r.getNotes());
        m.put("createdTime", r.getCreatedTime() == null ? null : r.getCreatedTime().toString());
        m.put("createdBy", r.getCreatedBy());
        return m;
    }
}

package id.co.blackheart.controller;

import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.model.StrategyDefinitionHistory;
import id.co.blackheart.repository.StrategyDefinitionHistoryRepository;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * Read-only viewer for {@code strategy_definition_history} (V18). Drives the
 * admin spec-revision browser used to diff one revision of a spec against
 * the next, replay forensic decisions ("what was active at time T?"), and
 * audit who changed what.
 *
 * <p>Writes are owned by {@code StrategyDefinitionHistoryService} on the
 * mutation path; this controller never writes.
 */
@RestController
@RequestMapping("/api/v1/strategy-definition-history")
@RequiredArgsConstructor
@Profile("!research")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "StrategyDefinitionHistoryController",
        description = "Admin viewer for strategy_definition_history")
public class StrategyDefinitionHistoryController {

    private static final int MAX_PAGE_SIZE = 100;

    private final StrategyDefinitionHistoryRepository repository;

    @GetMapping
    @Operation(
            summary = "List spec revisions for a strategy, newest first",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ResponseDto> list(
            @RequestParam String strategyCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size
    ) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize,
                Sort.by(Sort.Direction.DESC, "changedAt"));

        Page<StrategyDefinitionHistory> rows =
                repository.findByStrategyCodeOrderByChangedAtDesc(strategyCode, pageable);

        // Attach priorHistoryId per row so the frontend's "diff vs prev" works
        // across pagination boundaries. Within-page lookup is O(1) via the
        // adjacent index; the last row on the page falls back to a single
        // indexed query for the most-recent revision before its changedAt.
        List<StrategyDefinitionHistory> pageList = rows.getContent();
        List<Map<String, Object>> content = new java.util.ArrayList<>(pageList.size());
        for (int i = 0; i < pageList.size(); i++) {
            StrategyDefinitionHistory current = pageList.get(i);
            UUID priorId = null;
            if (i + 1 < pageList.size()) {
                priorId = pageList.get(i + 1).getHistoryId();
            } else if (current.getChangedAt() != null) {
                priorId = repository
                        .findFirstByStrategyCodeAndChangedAtLessThanOrderByChangedAtDesc(
                                strategyCode, current.getChangedAt())
                        .map(StrategyDefinitionHistory::getHistoryId)
                        .orElse(null);
            }
            content.add(toListRow(current, priorId));
        }

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
            summary = "Single revision including full spec_jsonb snapshot",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    public ResponseEntity<ResponseDto> get(@PathVariable("id") UUID id) {
        StrategyDefinitionHistory row = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(
                        "strategy_definition_history row not found"));
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(toDetailRow(row))
                .build());
    }

    private static Map<String, Object> toListRow(StrategyDefinitionHistory h, UUID priorHistoryId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("historyId", h.getHistoryId());
        row.put("priorHistoryId", priorHistoryId);
        row.put("strategyCode", h.getStrategyCode());
        row.put("strategyDefinitionId", h.getStrategyDefinitionId());
        row.put("archetype", h.getArchetype());
        row.put("archetypeVersion", h.getArchetypeVersion());
        row.put("specSchemaVersion", h.getSpecSchemaVersion());
        row.put("operation", h.getOperation());
        row.put("changedByUserId", h.getChangedByUserId());
        row.put("changedAt", h.getChangedAt() == null ? null : h.getChangedAt().toString());
        row.put("changeReason", h.getChangeReason());
        return row;
    }

    private static Map<String, Object> toDetailRow(StrategyDefinitionHistory h) {
        Map<String, Object> row = toListRow(h, null);
        row.put("specJsonb", h.getSpecJsonb());
        return row;
    }
}

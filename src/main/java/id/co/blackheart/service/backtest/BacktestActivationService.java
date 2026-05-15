package id.co.blackheart.service.backtest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.request.ActivateBacktestStrategyRequest;
import id.co.blackheart.dto.response.AccountStrategyResponse;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.repository.BacktestRunRepository;
import id.co.blackheart.service.strategy.AccountStrategyOwnershipGuard;
import id.co.blackheart.service.strategy.AccountStrategyService;
import id.co.blackheart.service.strategy.StrategyParamService;
import id.co.blackheart.util.AppConstant;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * Promotes a completed backtest run's parameter snapshot into the user's live
 * strategy configuration. The target account strategy must be owned by the
 * caller; the source backtest run may belong to anyone (researcher or user) —
 * consistent with the backtest submission ownership model.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestActivationService {

    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final BacktestRunRepository backtestRunRepository;
    private final AccountStrategyOwnershipGuard ownershipGuard;
    private final AccountStrategyService accountStrategyService;
    private final StrategyParamService strategyParamService;
    private final ObjectMapper objectMapper;

    /**
     * Apply a completed backtest run's parameter snapshot to the user's own
     * account strategy, then enable it for paper trading.
     *
     * <ol>
     *   <li>Verifies the run is COMPLETED.</li>
     *   <li>Asserts the caller owns {@code request.accountStrategyId}.</li>
     *   <li>Extracts overrides for {@code request.strategyCode} from the run's
     *       {@code config_snapshot} JSON — empty map when no overrides recorded.</li>
     *   <li>Creates a new strategy_param preset (atomically activated, tagged with
     *       the run id for traceability).</li>
     *   <li>Enables the account strategy (sets {@code enabled=true}), starting
     *       paper trading if {@code simulated=true}.</li>
     * </ol>
     */
    @Transactional
    public AccountStrategyResponse activate(UUID userId, UUID runId,
                                            ActivateBacktestStrategyRequest request) {
        BacktestRun run = backtestRunRepository.findById(runId)
                .orElseThrow(() -> new EntityNotFoundException(AppConstant.NOT_FOUND));

        if (!STATUS_COMPLETED.equalsIgnoreCase(run.getStatus())) {
            throw new IllegalStateException(
                    "Backtest run must be COMPLETED before activating as a strategy (current status: "
                            + run.getStatus() + ")");
        }

        ownershipGuard.assertOwned(userId, request.getAccountStrategyId());

        // Validate the requested strategy code is actually part of this run.
        // resolveOverrides() would silently return empty overrides for a wrong
        // code, creating a blank preset and activating the strategy with no params.
        String runCodes = run.getStrategyCode();
        boolean codeInRun = runCodes != null &&
                java.util.Arrays.stream(runCodes.split(","))
                        .map(String::trim)
                        .anyMatch(c -> c.equalsIgnoreCase(request.getStrategyCode()));
        if (!codeInRun) {
            throw new IllegalArgumentException(
                    "strategyCode '" + request.getStrategyCode()
                            + "' is not part of backtest run " + runId
                            + " (run codes: " + runCodes + ")");
        }

        Map<String, Object> overrides = resolveOverrides(run, request.getStrategyCode());
        String presetName = buildPresetName(request.getPresetName(), run);

        strategyParamService.create(
                request.getAccountStrategyId(),
                presetName,
                overrides,
                true,   // activate — atomically replaces any existing active preset
                runId,
                userId.toString()
        );

        AccountStrategyResponse result =
                accountStrategyService.activateStrategy(userId, request.getAccountStrategyId());

        log.info("Backtest {} promoted to strategy {}: preset='{}' overrides.keys={}",
                runId, request.getAccountStrategyId(), presetName, overrides.keySet());
        return result;
    }

    /**
     * Extract the override map for {@code strategyCode} from the run's JSON
     * config_snapshot. The snapshot shape is {@code { "LSR": { overrides }, … }}.
     * Falls back to an empty map when the snapshot is absent, unparseable, or the
     * code was not present (pure-defaults run — the preset is still created so the
     * run is traceable via {@code source_backtest_run_id}).
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveOverrides(BacktestRun run, String strategyCode) {
        if (run.getConfigSnapshot() == null) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Map<String, Object>> snapshot = objectMapper.readValue(
                    run.getConfigSnapshot(),
                    new TypeReference<Map<String, Map<String, Object>>>() {});
            // Exact match first, then case-insensitive fallback (codes are
            // stored uppercase in the snapshot but the request may differ).
            Map<String, Object> exact = snapshot.get(strategyCode);
            if (exact != null) return exact;
            for (Map.Entry<String, Map<String, Object>> e : snapshot.entrySet()) {
                if (e.getKey().equalsIgnoreCase(strategyCode)) {
                    return e.getValue();
                }
            }
            return Collections.emptyMap();
        } catch (Exception e) {
            log.warn("Failed to parse configSnapshot for run={}: {}", run.getBacktestRunId(), e.getMessage());
            return Collections.emptyMap();
        }
    }

    private String buildPresetName(String requested, BacktestRun run) {
        if (StringUtils.hasText(requested)) {
            return requested.trim();
        }
        String date = run.getCreatedTime() != null
                ? run.getCreatedTime().format(DATE_FMT)
                : "N/A";
        String shortId = run.getBacktestRunId().toString().substring(0, 8);
        return "Backtest " + shortId + " · " + date;
    }
}

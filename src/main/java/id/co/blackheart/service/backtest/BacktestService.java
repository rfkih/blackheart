package id.co.blackheart.service.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.request.BacktestRunRequest;
import id.co.blackheart.dto.response.BacktestRunResponse;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.repository.BacktestRunRepository;
import id.co.blackheart.service.build.BuildInfoService;
import id.co.blackheart.service.risk.SlippageCalibrationService;
import id.co.blackheart.service.strategy.AccountStrategyOwnershipGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestService {

    private static final String STATUS_PENDING = "PENDING";

    /** Binance USD-M futures default taker fee (0.04%). Used when the request omits feeRate. */
    private static final BigDecimal DEFAULT_FEE_RATE = new BigDecimal("0.0004");
    /** Conservative slippage default (0.05%) — applied when the request omits slippageRate. */
    private static final BigDecimal DEFAULT_SLIPPAGE_RATE = new BigDecimal("0.0005");

    private final BacktestRunRepository backtestRunRepository;
    private final BacktestResponseMapper backtestMapperService;
    private final AccountStrategyOwnershipGuard ownershipGuard;
    private final BacktestAsyncRunner backtestAsyncRunner;
    private final ObjectMapper objectMapper;
    private final BuildInfoService buildInfoService;
    private final SlippageCalibrationService slippageCalibrationService;
    private final BacktestDataValidatorService backtestDataValidatorService;

    /**
     * Persist the backtest as PENDING, hand it off to the dedicated backtest
     * executor, and return immediately. The HTTP request is done in
     * milliseconds regardless of how long the actual backtest takes.
     *
     * <p>Clients poll {@code GET /api/v1/backtest/:id} on the returned id to
     * watch {@code status} + {@code progressPercent} evolve.
     */
    public BacktestRunResponse runBacktest(UUID userId, BacktestRunRequest request) {
        validateRequest(request);
        backtestDataValidatorService.validate(
                request.getAsset(), request.getInterval(),
                request.getStartTime(), request.getEndTime());

        ownershipGuard.assertOwned(userId, request.getAccountStrategyId());
        if (request.getStrategyAccountStrategyIds() != null) {
            for (UUID perStrategyId : request.getStrategyAccountStrategyIds().values()) {
                if (perStrategyId != null) {
                    ownershipGuard.assertOwned(userId, perStrategyId);
                }
            }
        }

        String resolvedStrategyCode = resolveStrategyCode(request);
        String resolvedStrategyName = request.getStrategyName() != null ? request.getStrategyName() : resolvedStrategyCode;
        BigDecimal resolvedFee = request.getFeeRate() != null ? request.getFeeRate() : DEFAULT_FEE_RATE;
        // Slippage resolution order: explicit request → calibrated from
        // user's own fills → hardcoded default. Calibration only kicks in
        // when the symbol has enough closed trades with intent recorded
        // (see SlippageCalibrationService.MIN_SAMPLE_FOR_TRUST). Below
        // that threshold we'd be picking a number from coin-flip data.
        BigDecimal resolvedSlippage;
        if (request.getSlippageRate() != null) {
            resolvedSlippage = request.getSlippageRate();
        } else {
            resolvedSlippage = slippageCalibrationService
                    .calibratedRateAsFraction(request.getAsset())
                    .orElse(DEFAULT_SLIPPAGE_RATE);
        }

        // Persist the wizard's per-strategy param overrides on the run itself
        // (config_snapshot JSON). BacktestAsyncRunner reads it back, installs
        // a thread-local via BacktestParamOverrideContext, and the Lsr/Vcb
        // param services layer the overrides on top of stored params (wizard
        // wins on key collisions). Stashing on the row also powers the result
        // page's "Re-run with these params" — tunings survive a page refresh.
        Map<String, Map<String, Object>> overrides = request.getStrategyParamOverrides();
        String configSnapshot = serialiseOverrides(overrides);
        if (!CollectionUtils.isEmpty(overrides)) {
            log.info("Backtest submitted with param overrides across {} strateg(ies): {}",
                    overrides.size(), overrides.keySet());
        }

        BacktestRun backtestRun = BacktestRun.builder()
                .userId(userId)
                .accountStrategyId(request.getAccountStrategyId())
                .strategyAccountStrategyIds(request.getStrategyAccountStrategyIds())
                .strategyParamIds(request.getStrategyParamIds())
                .strategyName(resolvedStrategyName)
                .strategyCode(resolvedStrategyCode)
                .asset(request.getAsset())
                .interval(request.getInterval())
                .status(STATUS_PENDING)
                .progressPercent(0)
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .initialCapital(request.getInitialCapital())
                .riskPerTradePct(request.getRiskPerTradePct())
                .feePct(resolvedFee)
                .slippagePct(resolvedSlippage)
                .fundingRateBpsPer8h(request.getFundingRateBpsPer8h())
                .minNotional(request.getMinNotional())
                .minQty(request.getMinQty())
                .qtyStep(request.getQtyStep())
                .allowLong(request.getAllowLong())
                .allowShort(request.getAllowShort())
                .maxOpenPositions(request.getMaxOpenPositions())
                .totalTrades(0)
                .totalWins(0)
                .totalLosses(0)
                .winRate(BigDecimal.ZERO)
                .grossProfit(BigDecimal.ZERO)
                .grossLoss(BigDecimal.ZERO)
                .netProfit(BigDecimal.ZERO)
                .maxDrawdownPct(BigDecimal.ZERO)
                .endingBalance(request.getInitialCapital())
                .configSnapshot(configSnapshot)
                // Reproducibility manifest — stamp the running app's git SHA
                // and version so the result page can later cross-reference
                // exactly which strategy code + defaults produced this run.
                .gitCommitSha(buildInfoService.getGitCommitSha())
                .appVersion(buildInfoService.getAppVersion())
                // Phase A — multi-strategy controls. Both fields are
                // optional; null means "no run-level cap" / "fall back to
                // account_strategy.capital_allocation_pct" respectively.
                .maxConcurrentStrategies(request.getMaxConcurrentStrategies())
                .strategyAllocations(nullIfEmpty(canonicaliseAllocations(request.getStrategyAllocations())))
                .strategyIntervals(nullIfEmpty(canonicaliseIntervals(request.getStrategyIntervals())))
                // Origin tag — RESEARCHER when the autonomous orchestrator
                // submits, USER for everything else (wizard, scripts). This
                // is a UI/operational label not a security gate, so the V32
                // CHECK constraint is what actually bounds the value space.
                .triggeredBy(resolveTriggeredBy(request))
                .build();

        backtestRun = backtestRunRepository.save(backtestRun);

        try {
            backtestAsyncRunner.runAsync(backtestRun.getBacktestRunId());
        } catch (RejectedExecutionException e) {
            // Backtest pool is saturated — mark the row FAILED immediately so
            // the user sees the reason instead of a "stuck PENDING forever".
            log.warn("Backtest executor rejected submission | runId={}",
                    backtestRun.getBacktestRunId());
            backtestRun.setStatus("FAILED");
            backtestRun.setNotes("Server is at backtest capacity. Try again in a few minutes.");
            backtestRunRepository.save(backtestRun);
        }

        return backtestMapperService.toRunResponse(backtestRun);
    }

    /**
     * Resolves the {@code triggered_by} tag for a new run. Accepts only the
     * two whitelisted values defined by the V32 CHECK constraint and falls
     * back to {@code USER} for null/blank/unknown — keeping the contract
     * additive so existing clients that never set the field still work.
     */
    private String resolveTriggeredBy(BacktestRunRequest request) {
        String raw = request.getTriggeredBy();
        if (raw == null) return "USER";
        String upper = raw.trim().toUpperCase();
        if ("RESEARCHER".equals(upper) || "USER".equals(upper)) {
            return upper;
        }
        return "USER";
    }

    /**
     * Serialises the wizard's override map into the JSON shape the async
     * runner parses back out. Null/empty overrides → {@code null} so we don't
     * litter the DB with empty {@code {}} blobs that force the query service
     * to do unnecessary JSON parsing on every detail read.
     */
    private String serialiseOverrides(Map<String, Map<String, Object>> overrides) {
        if (CollectionUtils.isEmpty(overrides)) return null;
        try {
            return objectMapper.writeValueAsString(overrides);
        } catch (Exception e) {
            // Non-fatal — the run will still execute, just without overrides.
            log.warn("Failed to serialise strategyParamOverrides, dropping: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Validate + uppercase-normalize the per-strategy allocation map. Each
     * value must be in (0, 100]. Strategy codes are stored uppercase so
     * lookups inside the executor don't need case-insensitive matching.
     * Returns an empty map for null/empty/all-blank input — the call site
     * passes the result through {@link #nullIfEmpty} so the JSONB column
     * stays SQL NULL (not {@code '{}'}) when nothing was supplied.
     */
    private Map<String, BigDecimal> canonicaliseAllocations(Map<String, BigDecimal> raw) {
        Map<String, BigDecimal> out = new java.util.LinkedHashMap<>();
        if (CollectionUtils.isEmpty(raw)) return out;
        for (Map.Entry<String, BigDecimal> e : raw.entrySet()) {
            BigDecimal v = e.getValue();
            if (StringUtils.hasText(e.getKey()) && v != null) {
                if (v.signum() <= 0 || v.compareTo(new BigDecimal("100")) > 0) {
                    throw new IllegalArgumentException(
                            "strategyAllocations[" + e.getKey() + "] must be in (0, 100], got " + v);
                }
                out.put(e.getKey().toUpperCase().trim(), v);
            }
        }
        return out;
    }

    /**
     * Validate + uppercase-normalize the per-strategy interval map. The
     * @Pattern annotation on the DTO already validates each value matches
     * a known interval; here we only canonicalize keys. Returns an empty
     * map for null/empty input — see {@link #canonicaliseAllocations} for
     * the rationale on the empty-vs-null contract.
     */
    private Map<String, String> canonicaliseIntervals(Map<String, String> raw) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        if (CollectionUtils.isEmpty(raw)) return out;
        for (Map.Entry<String, String> e : raw.entrySet()) {
            String v = e.getValue();
            if (StringUtils.hasText(e.getKey()) && StringUtils.hasText(v)) {
                out.put(e.getKey().toUpperCase().trim(), v.trim());
            }
        }
        return out;
    }

    /**
     * Builder-side bridge between the canonicalise helpers (which return
     * empty maps to satisfy Sonar S1168) and the JSONB columns (which want
     * SQL NULL — not {@code '{}'} — when nothing was supplied, so the
     * detail-read path can skip JSON parsing on absent values).
     */
    private static <K, V> Map<K, V> nullIfEmpty(Map<K, V> m) {
        return CollectionUtils.isEmpty(m) ? null : m;
    }

    /**
     * Resolves the strategy code(s) to store on BacktestRun.
     * Multi-strategy: codes joined as comma-separated string, e.g. "LSR_V2,VCB".
     * Single-strategy: the single code, falling back to strategyName.
     */
    private String resolveStrategyCode(BacktestRunRequest request) {
        List<String> codes = request.getStrategyCodes();
        if (!CollectionUtils.isEmpty(codes)) {
            List<String> valid = codes.stream()
                    .filter(StringUtils::hasText)
                    .toList();
            if (!CollectionUtils.isEmpty(valid)) {
                return String.join(",", valid);
            }
        }
        if (StringUtils.hasText(request.getStrategyCode())) {
            return request.getStrategyCode();
        }
        return request.getStrategyName();
    }

    private void validateRequest(BacktestRunRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("BacktestRunRequest cannot be null");
        }

        boolean hasStrategyCodes = !CollectionUtils.isEmpty(request.getStrategyCodes())
                && request.getStrategyCodes().stream().anyMatch(StringUtils::hasText);
        boolean hasStrategyCode = StringUtils.hasText(request.getStrategyCode());
        boolean hasStrategyName = StringUtils.hasText(request.getStrategyName());

        if (!hasStrategyCodes && !hasStrategyCode && !hasStrategyName) {
            throw new IllegalArgumentException("At least one of strategyCodes, strategyCode, or strategyName must be provided");
        }
        if (request.getAccountStrategyId() == null) {
            throw new IllegalArgumentException("accountStrategyId is required (backtest_run.account_strategy_id is NOT NULL)");
        }
        if (!StringUtils.hasText(request.getAsset())) {
            throw new IllegalArgumentException("asset cannot be blank");
        }
        if (!StringUtils.hasText(request.getInterval())) {
            throw new IllegalArgumentException("interval cannot be blank");
        }
        if (request.getStartTime() == null || request.getEndTime() == null) {
            throw new IllegalArgumentException("startTime and endTime cannot be null");
        }
        if (request.getStartTime().isAfter(request.getEndTime())) {
            throw new IllegalArgumentException("startTime cannot be after endTime");
        }
        if (request.getInitialCapital() == null || request.getInitialCapital().signum() <= 0) {
            throw new IllegalArgumentException("initialCapital must be greater than zero");
        }

        // Cap backtest range at 5 years of 1m candles (~2.6M bars) to prevent
        // a single request from pegging CPU + pulling millions of rows out of
        // Postgres. Longer ranges on coarser intervals (e.g. 1h) pass through.
        Duration span = Duration.between(request.getStartTime(), request.getEndTime());
        if (span.toDays() > 365L * 5) {
            throw new IllegalArgumentException(
                    "Backtest range exceeds the 5-year limit. Split the run into smaller windows.");
        }
        // feeRate and slippageRate are optional — defaults kick in at build time.
    }
}

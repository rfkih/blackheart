package id.co.blackheart.service.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.request.BacktestRunRequest;
import id.co.blackheart.dto.response.BacktestRunResponse;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.repository.BacktestRunRepository;
import id.co.blackheart.service.backtest.BacktestService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates multi-run parameter sweeps. Given a {@link SweepSpec} with a
 * cross-product grid, submits one backtest per combo, waits for each to
 * complete, and records the resulting metrics into a {@link SweepState} that
 * can be polled via the REST endpoint.
 *
 * <p>Design choices:
 * <ul>
 *   <li><b>Single-thread sweep executor.</b> Only one sweep runs at a time
 *     system-wide. Prevents accidental fan-out of a 50-combo × 2-minute-each
 *     sweep times three users all hitting POST simultaneously.</li>
 *   <li><b>Backtest workload still runs on the existing {@code backtestExecutor}.</b>
 *     The sweep thread submits one backtest, then polls the DB for completion
 *     — so sweeps cost one extra thread, not N.</li>
 *   <li><b>In-memory state + JSON persistence.</b> Sweep records live in a
 *     concurrent map; each state change writes a JSON snapshot to disk so a
 *     restart doesn't lose in-progress leaderboards.</li>
 *   <li><b>Combo cap.</b> Hard limit of {@link #MAX_COMBOS} combinations to
 *     prevent 10×10×10 = 1000 submissions that would wedge the worker pool.</li>
 * </ul>
 */
@Slf4j
@Service
public class ResearchSweepService {

    /** Constructor with explicit @Lazy on the analyzer to break a potential
     *  circular dependency (analyzer is part of the same package, but only
     *  invoked from the sweep thread, well after context startup). */
    public ResearchSweepService(
            BacktestService backtestService,
            BacktestRunRepository runRepository,
            @Lazy BacktestAnalysisService analysisService,
            ObjectMapper objectMapper
    ) {
        this.backtestService = backtestService;
        this.runRepository = runRepository;
        this.analysisService = analysisService;
        this.objectMapper = objectMapper;
    }

    private static final int MAX_COMBOS = 64;
    /** Per-round cap for research-mode sweeps — higher than the flat cap
     *  because rounds 2+ fan out from elites and can balloon with loose ranges.
     *  At 30s/backtest this is ~2h/round worst-case, still reasonable background
     *  work; the 256 number exists to block 1000-combo accidents, not narrow
     *  research grids. */
    private static final int MAX_COMBOS_PER_ROUND = 256;
    private static final int MAX_ROUNDS = 5;
    private static final BigDecimal DEFAULT_ELITE_PCT = new BigDecimal("0.25");
    /** Combos with fewer than this many trades are excluded from elite
     *  selection. Prevents successive halving from chasing 1-trade outliers
     *  whose +R is pure noise. Falls back to the full pool when no combo
     *  meets the floor. */
    private static final int MIN_TRADES_FOR_ELITE = 5;
    /** Sweep thread polls the underlying backtest_run for status + progress
     *  at this cadence. Matches BacktestProgressTracker's 500ms throttle —
     *  any faster and the DB read sees no new data; any slower and short
     *  backtests appear to jump 0→100 because intermediate progress writes
     *  happen between polls. */
    private static final long POLL_INTERVAL_MS = 500L;
    private static final long PER_RUN_TIMEOUT_MS = 20 * 60 * 1000L; // 20 min per run — generous.

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private final BacktestService backtestService;
    private final BacktestRunRepository runRepository;
    private final BacktestAnalysisService analysisService;
    private final ObjectMapper objectMapper;

    /** Per-sweep cancel flag. Checked between combos by the sweep thread. */
    private final Map<UUID, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    @Value("${app.research.sweeps-dir:./research/sweeps}")
    private String sweepsDir;

    private final Map<UUID, SweepState> sweeps = new ConcurrentHashMap<>();

    /**
     * One sweep at a time across the whole system. Backtests still run on
     * the shared backtest executor — this just gates orchestration.
     */
    private final ExecutorService sweepExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "research-sweep");
        t.setDaemon(true);
        return t;
    });

    @PostConstruct
    public void init() {
        loadPersistedSweeps();
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Enqueue a new sweep. Returns the initial PENDING state immediately;
     * the actual backtests run on the single-thread executor in the background.
     *
     * <p>Single-round (flat) sweeps pre-expand every combo here. Multi-round
     * research sweeps plan round 1 only; rounds 2+ are planned as they start,
     * seeded by the previous round's elites.
     */
    public SweepState startSweep(UUID userId, SweepSpec spec) {
        validate(spec);

        int rounds = resolveRounds(spec);
        boolean researchMode = rounds > 1;

        UUID sweepId = UUID.randomUUID();
        // CopyOnWriteArrayList — sweep thread mutates while the HTTP poll
        // reads. CoW gives us a stable snapshot per read; mutation is O(N) but
        // N is bounded by MAX_COMBOS_PER_ROUND × MAX_ROUNDS = small.
        SweepState.SweepStateBuilder builder = SweepState.builder()
                .sweepId(sweepId)
                .userId(userId)
                .spec(spec)
                .status(STATUS_PENDING)
                .createdAt(LocalDateTime.now())
                .finishedCombos(0)
                .roundsTruncated(0)
                .results(new CopyOnWriteArrayList<>());

        if (researchMode) {
            // Plan round 1 only — further rounds seed from elites, can't plan ahead.
            List<Map<String, Object>> round1 = expandFromRanges(spec.getParamRanges());
            if (round1.isEmpty()) {
                throw new IllegalArgumentException("Param ranges yielded zero combos");
            }
            if (round1.size() > MAX_COMBOS_PER_ROUND) {
                throw new IllegalArgumentException(
                        "Round-1 grid would generate " + round1.size()
                                + " combos; per-round cap is " + MAX_COMBOS_PER_ROUND);
            }
            List<SweepResult> initial = new CopyOnWriteArrayList<>();
            for (Map<String, Object> combo : round1) {
                initial.add(SweepResult.builder()
                        .round(1)
                        .paramSet(combo)
                        .status(STATUS_PENDING)
                        .build());
            }
            builder.totalCombos(round1.size())
                    .currentRound(1)
                    .totalRounds(rounds)
                    .results(initial);
        } else {
            // Legacy flat-grid behaviour.
            List<Map<String, Object>> combos = expandGrid(spec.getParamGrid());
            if (combos.size() > MAX_COMBOS) {
                throw new IllegalArgumentException(
                        "Sweep would generate " + combos.size() + " combos; cap is " + MAX_COMBOS);
            }
            List<SweepResult> initial = new CopyOnWriteArrayList<>();
            for (Map<String, Object> combo : combos) {
                initial.add(SweepResult.builder()
                        .round(1)
                        .paramSet(combo)
                        .status(STATUS_PENDING)
                        .build());
            }
            builder.totalCombos(combos.size()).results(initial);
        }

        SweepState state = builder.build();
        sweeps.put(sweepId, state);
        cancelFlags.put(sweepId, new AtomicBoolean(false));
        persist(state);

        sweepExecutor.execute(() -> run(state));
        return state;
    }

    /**
     * Sets the sweep's cancel flag. The orchestrator checks the flag between
     * combos and stops cleanly with status {@code CANCELLED}. The combo
     * currently in flight runs to completion — we don't kill in-flight
     * backtest workers because they don't expose a cooperative interrupt.
     */
    public SweepState cancelSweep(UUID sweepId) {
        SweepState state = sweeps.get(sweepId);
        if (state == null) throw new jakarta.persistence.EntityNotFoundException("Sweep not found: " + sweepId);
        AtomicBoolean flag = cancelFlags.get(sweepId);
        if (flag != null) flag.set(true);
        log.info("Sweep cancel requested | id={}", sweepId);
        return state;
    }

    /**
     * Forget a sweep — removes it from the in-memory map and deletes the
     * persisted JSON snapshot. Refuses to delete in-flight sweeps; cancel
     * first.
     */
    public void deleteSweep(UUID sweepId) {
        SweepState state = sweeps.get(sweepId);
        if (state == null) return;
        if (STATUS_RUNNING.equalsIgnoreCase(state.getStatus())
                || STATUS_PENDING.equalsIgnoreCase(state.getStatus())) {
            throw new IllegalStateException(
                    "Sweep is still " + state.getStatus() + " — cancel before deleting");
        }
        sweeps.remove(sweepId);
        cancelFlags.remove(sweepId);
        try {
            Files.deleteIfExists(Path.of(sweepsDir).resolve(sweepId + ".json"));
        } catch (IOException e) {
            log.warn("Could not delete sweep file for {}", sweepId, e);
        }
        log.info("Sweep deleted | id={}", sweepId);
    }

    public SweepState getSweep(UUID sweepId) {
        return sweeps.get(sweepId);
    }

    public List<SweepState> listSweeps(UUID userId) {
        List<SweepState> out = new ArrayList<>();
        for (SweepState s : sweeps.values()) {
            if (userId == null || s.getUserId() == null || userId.equals(s.getUserId())) {
                out.add(s);
            }
        }
        out.sort(Comparator.comparing(
                SweepState::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    // ── Orchestrator ────────────────────────────────────────────────────────

    private void run(SweepState state) {
        state.setStatus(STATUS_RUNNING);
        persist(state);
        log.info("Sweep started | id={} combos={} rounds={}",
                state.getSweepId(), state.getTotalCombos(),
                state.getTotalRounds() == null ? 1 : state.getTotalRounds());

        try {
            boolean researchMode = state.getTotalRounds() != null && state.getTotalRounds() > 1;

            if (researchMode) {
                runResearchMode(state);
            } else {
                runFlat(state);
            }
            state.setStatus(isCancelled(state) ? STATUS_CANCELLED : STATUS_COMPLETED);
        } catch (Exception e) {
            log.error("Sweep orchestrator crashed | id={}", state.getSweepId(), e);
            state.setStatus(STATUS_FAILED);
        } finally {
            state.setCompletedAt(LocalDateTime.now());
            persist(state);
            log.info("Sweep finished | id={} status={}", state.getSweepId(), state.getStatus());
        }
    }

    /** Flat single-round — runs every entry in {@link SweepState#getResults()}. */
    private void runFlat(SweepState state) {
        List<SweepResult> results = state.getResults();
        for (int i = 0; i < results.size(); i++) {
            if (isCancelled(state)) {
                log.info("Sweep {} cancelled at combo {}/{}", state.getSweepId(), i, results.size());
                return;
            }
            SweepResult r = results.get(i);
            executeOneSafe(state, r);
            state.setFinishedCombos(i + 1);
            persist(state);
        }
    }

    private boolean isCancelled(SweepState state) {
        AtomicBoolean flag = cancelFlags.get(state.getSweepId());
        return flag != null && flag.get();
    }

    /**
     * Research-mode successive halving:
     * <ul>
     *   <li>Round 1: run pre-planned round-1 combos (already in state).</li>
     *   <li>Round 2..N: pick top-{@code elitePct} by rank metric → refine each
     *     elite's params ±1 step → dedupe against every combo already seen in
     *     the sweep → run the new batch.</li>
     * </ul>
     *
     * <p>Rounds stop early when refinement produces no new combos (search has
     * converged on the existing grid) or when the per-round cap would be
     * exceeded after dedupe.
     */
    private void runResearchMode(SweepState state) {
        int totalRounds = state.getTotalRounds();
        String rankMetric = resolveRankMetric(state.getSpec());

        for (int r = 1; r <= totalRounds; r++) {
            if (isCancelled(state)) {
                log.info("Sweep {} cancelled before round {}", state.getSweepId(), r);
                return;
            }
            final int round = r;
            state.setCurrentRound(round);
            persist(state);

            int startIdx = indexOfFirstInRound(state.getResults(), round);
            if (startIdx < 0) {
                log.info("Sweep {} round {} had no queued combos — stopping early",
                        state.getSweepId(), round);
                break;
            }

            List<SweepResult> results = state.getResults();
            for (int i = startIdx; i < results.size(); i++) {
                if (isCancelled(state)) {
                    log.info("Sweep {} cancelled mid-round {}", state.getSweepId(), round);
                    return;
                }
                SweepResult cur = results.get(i);
                if (cur.getRound() == null || cur.getRound() != round) break;
                executeOneSafe(state, cur);
                state.setFinishedCombos(state.getFinishedCombos() + 1);
                persist(state);
            }

            if (round == totalRounds) break;

            // Plan the next round from this round's elites. Filter to combos
            // with at least MIN_TRADES_FOR_ELITE trades so a 1-trade outlier
            // doesn't seed the entire next round.
            List<SweepResult> roundResults = results.stream()
                    .filter(x -> x.getRound() != null && x.getRound() == round)
                    .filter(x -> STATUS_COMPLETED.equalsIgnoreCase(x.getStatus()))
                    .toList();
            if (roundResults.isEmpty()) {
                log.info("Sweep {} round {} produced no completed results — stopping",
                        state.getSweepId(), round);
                break;
            }

            List<SweepResult> elitePool = roundResults.stream()
                    .filter(x -> x.getTradeCount() != null && x.getTradeCount() >= MIN_TRADES_FOR_ELITE)
                    .toList();
            if (elitePool.isEmpty()) {
                // Fallback: nothing met the trade-count floor; use the full
                // pool so the sweep doesn't dead-end on a noisy first round.
                log.info("Sweep {} round {} — no combos with >= {} trades, using full pool",
                        state.getSweepId(), round, MIN_TRADES_FOR_ELITE);
                elitePool = roundResults;
            }

            BigDecimal elitePct = state.getSpec().getElitePct();
            if (elitePct == null || elitePct.signum() <= 0) elitePct = DEFAULT_ELITE_PCT;
            int eliteCount = Math.max(1, (int) Math.ceil(elitePool.size() * elitePct.doubleValue()));

            List<SweepResult> elites = new ArrayList<>(elitePool);
            elites.sort(rankComparator(rankMetric));
            elites = elites.subList(0, Math.min(eliteCount, elites.size()));

            // Dedup uses canonicalised paramSet (BigDecimal scales stripped)
            // so 0.6 and 0.60 don't both land in the next round.
            Set<Map<String, Object>> alreadyPlanned = new HashSet<>();
            for (SweepResult seen : results) alreadyPlanned.add(canonicalise(seen.getParamSet()));

            List<Map<String, Object>> nextCombos = new ArrayList<>();
            int producedCandidates = 0;
            for (SweepResult elite : elites) {
                for (Map<String, Object> refined : refineAroundElite(
                        state.getSpec().getParamRanges(), elite.getParamSet())) {
                    producedCandidates++;
                    Map<String, Object> canonical = canonicalise(refined);
                    if (alreadyPlanned.add(canonical)) {
                        nextCombos.add(refined);
                        if (nextCombos.size() >= MAX_COMBOS_PER_ROUND) break;
                    }
                }
                if (nextCombos.size() >= MAX_COMBOS_PER_ROUND) break;
            }

            if (producedCandidates > nextCombos.size()) {
                int truncated = (state.getRoundsTruncated() == null ? 0 : state.getRoundsTruncated())
                        + (producedCandidates - nextCombos.size());
                state.setRoundsTruncated(truncated);
                log.info("Sweep {} round {} produced {} refined candidates, {} kept after dedup+cap",
                        state.getSweepId(), round, producedCandidates, nextCombos.size());
            }

            if (nextCombos.isEmpty()) {
                log.info("Sweep {} converged at round {} — no new combos to explore",
                        state.getSweepId(), round);
                break;
            }

            int nextRound = round + 1;
            for (Map<String, Object> combo : nextCombos) {
                results.add(SweepResult.builder()
                        .round(nextRound)
                        .paramSet(combo)
                        .status(STATUS_PENDING)
                        .build());
            }
            state.setTotalCombos(results.size());
            persist(state);
            log.info("Sweep {} planned round {} with {} combos",
                    state.getSweepId(), nextRound, nextCombos.size());
        }
    }

    /** Strips trailing zeros from every BigDecimal in the combo so equality
     *  works across different scales (0.6 vs 0.60). Non-decimal values pass
     *  through unchanged. */
    static Map<String, Object> canonicalise(Map<String, Object> combo) {
        if (combo == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : combo.entrySet()) {
            Object v = e.getValue();
            if (v instanceof BigDecimal bd) {
                out.put(e.getKey(), bd.stripTrailingZeros());
            } else {
                out.put(e.getKey(), v);
            }
        }
        return out;
    }

    /** Wrapper around {@link #executeOne} that turns exceptions into FAILED rows. */
    private void executeOneSafe(SweepState state, SweepResult r) {
        try {
            executeOne(state, r);
        } catch (Exception e) {
            log.error("Sweep combo failed | sweepId={} combo={}",
                    state.getSweepId(), r.getParamSet(), e);
            r.setStatus(STATUS_FAILED);
            r.setErrorMessage(e.getMessage());
        }
    }

    private int indexOfFirstInRound(List<SweepResult> results, int round) {
        for (int i = 0; i < results.size(); i++) {
            if (results.get(i).getRound() != null && results.get(i).getRound() == round) {
                return i;
            }
        }
        return -1;
    }

    private void executeOne(SweepState state, SweepResult result) throws Exception {
        SweepSpec spec = state.getSpec();
        BacktestRunRequest req = buildRequest(spec, result.getParamSet());

        long startNanos = System.nanoTime();
        result.setStatus(STATUS_RUNNING);
        persist(state);

        BacktestRunResponse submitted = backtestService.runBacktest(state.getUserId(), req);
        UUID runId = submitted.getBacktestRunId();
        result.setBacktestRunId(runId);
        result.setProgressPercent(0);
        persist(state);

        BacktestRun completed = waitForRun(runId, state, result);
        if (completed == null) {
            result.setStatus(STATUS_FAILED);
            result.setErrorMessage("Run did not complete in " + (PER_RUN_TIMEOUT_MS / 1000) + "s");
            result.setElapsedMs(Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
            return;
        }

        result.setStatus(STATUS_COMPLETED.equalsIgnoreCase(completed.getStatus())
                ? STATUS_COMPLETED : STATUS_FAILED);
        result.setElapsedMs(Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
        // Mirror the run's final progress; the BacktestRun row already
        // guarantees COMPLETED == 100, FAILED keeps last reported value.
        result.setProgressPercent(completed.getProgressPercent());

        populateMetrics(result, completed);
    }

    /**
     * Polls the backtest_run row until it reaches a terminal status. Each
     * poll also mirrors the run's {@code progressPercent} onto the matching
     * SweepResult so the leaderboard row's progress bar reflects the actual
     * backtest progress instead of a client-side guess.
     */
    private BacktestRun waitForRun(UUID runId, SweepState state, SweepResult result)
            throws InterruptedException {
        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() - start < PER_RUN_TIMEOUT_MS) {
            BacktestRun run = runRepository.findById(runId).orElse(null);
            if (run != null) {
                Integer pct = run.getProgressPercent();
                if (pct != null && !pct.equals(result.getProgressPercent())) {
                    result.setProgressPercent(pct);
                    persist(state);
                }
                String status = run.getStatus();
                if (STATUS_COMPLETED.equalsIgnoreCase(status)
                        || STATUS_FAILED.equalsIgnoreCase(status)) {
                    return run;
                }
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return null;
    }

    /**
     * Flatten the analyzer's headline metrics onto the sweep result row.
     * If the snapshot is missing or unreadable, kicks the analyzer once to
     * regenerate it before giving up — covers the case where the analyzer
     * crashed mid-run or the column was never written.
     */
    private void populateMetrics(SweepResult result, BacktestRun run) {
        String snap = run.getAnalysisSnapshot();
        if (snap == null || snap.isBlank()) {
            log.info("No analysis snapshot for run {} — re-invoking analyzer",
                    run.getBacktestRunId());
            try {
                analysisService.analyze(run.getBacktestRunId());
                BacktestRun refreshed = runRepository.findById(run.getBacktestRunId()).orElse(run);
                snap = refreshed.getAnalysisSnapshot();
            } catch (Exception e) {
                log.warn("Analyzer retry failed for run {}", run.getBacktestRunId(), e);
            }
            if (snap == null || snap.isBlank()) return;
        }
        try {
            Map<String, Object> root = objectMapper.readValue(
                    snap, new TypeReference<Map<String, Object>>() {});
            Object headline = root.get("headline");
            if (!(headline instanceof Map<?, ?> h)) return;
            result.setTradeCount(asInt(h.get("tradeCount")));
            result.setWinRate(asDecimal(h.get("winRate")));
            result.setProfitFactor(asDecimal(h.get("profitFactor")));
            result.setAvgR(asDecimal(h.get("avgR")));
            result.setNetPnl(asDecimal(h.get("netPnl")));
            result.setMaxDrawdown(asDecimal(h.get("maxDrawdown")));
            result.setMaxConsecutiveLosses(asInt(h.get("maxConsecutiveLosses")));
        } catch (Exception e) {
            log.warn("Failed to parse analysis snapshot for run {}", run.getBacktestRunId(), e);
        }
    }

    // ── Request building ────────────────────────────────────────────────────

    private BacktestRunRequest buildRequest(SweepSpec spec, Map<String, Object> paramSet) {
        BacktestRunRequest req = new BacktestRunRequest();
        req.setAccountStrategyId(spec.getAccountStrategyId());
        req.setAsset(spec.getAsset());
        req.setInterval(spec.getInterval());
        req.setStartTime(spec.getFromDate());
        req.setEndTime(spec.getToDate());
        req.setInitialCapital(spec.getInitialCapital());
        req.setStrategyCode(spec.getStrategyCode());
        req.setStrategyCodes(List.of(spec.getStrategyCode()));
        req.setStrategyAccountStrategyIds(Map.of(spec.getStrategyCode(), spec.getAccountStrategyId()));

        // Trade-sizing defaults — MUST be set. Without these
        // StrategyHelper.resolveRiskPct returns null, sizing bails to zero,
        // and every sweep combo reports zero trades (caught the hard way).
        // Values mirror the frontend's buildBacktestPayload.DEFAULT_SIZING so
        // sweep-submitted runs behave the same as wizard-submitted ones.
        req.setRiskPerTradePct(new BigDecimal("0.9"));
        req.setFeeRate(new BigDecimal("0.00075"));
        req.setSlippageRate(BigDecimal.ZERO);
        req.setMinNotional(new BigDecimal("7"));
        req.setMinQty(new BigDecimal("0.000001"));
        req.setQtyStep(new BigDecimal("0.000001"));
        req.setMaxOpenPositions(1);
        req.setAllowLong(Boolean.TRUE);
        req.setAllowShort(Boolean.FALSE);

        // Merge order: fixedParams as base, swept paramSet on top. Validation
        // already ensured the keys don't overlap, so the order is purely
        // defensive — if they did, the swept value wins.
        Map<String, Object> merged = new LinkedHashMap<>();
        if (spec.getFixedParams() != null) merged.putAll(spec.getFixedParams());
        merged.putAll(paramSet);

        Map<String, Map<String, Object>> overrides = new LinkedHashMap<>();
        overrides.put(spec.getStrategyCode(), merged);
        req.setStrategyParamOverrides(overrides);
        return req;
    }

    // ── Research-mode helpers ───────────────────────────────────────────────

    private int resolveRounds(SweepSpec spec) {
        Integer r = spec.getRounds();
        if (r == null || r <= 1) return 1;
        if (r > MAX_ROUNDS) {
            throw new IllegalArgumentException("rounds capped at " + MAX_ROUNDS);
        }
        return r;
    }

    private String resolveRankMetric(SweepSpec spec) {
        String m = spec.getRankMetric();
        return (m == null || m.isBlank()) ? "avgR" : m;
    }

    /** Expands paramRanges into the round-1 cross-product. */
    static List<Map<String, Object>> expandFromRanges(Map<String, ParamRange> ranges) {
        if (ranges == null || ranges.isEmpty()) return List.of();
        Map<String, List<Object>> grid = new LinkedHashMap<>();
        for (Map.Entry<String, ParamRange> e : ranges.entrySet()) {
            List<BigDecimal> vals = e.getValue().expand();
            if (vals.isEmpty()) continue;
            grid.put(e.getKey(), new ArrayList<>(vals));
        }
        return expandGrid(grid);
    }

    /**
     * Refine-around-elite: for each key in the elite's paramSet, pull the ±1
     * step neighbourhood from {@link ParamRange#refineAround}. Cross-product
     * the per-key neighbourhoods so refined combos vary on every dimension
     * simultaneously — a pure single-dim sweep would miss interactions.
     */
    static List<Map<String, Object>> refineAroundElite(
            Map<String, ParamRange> ranges, Map<String, Object> elite) {
        if (ranges == null || elite == null) return List.of();
        Map<String, List<Object>> perKey = new LinkedHashMap<>();
        for (Map.Entry<String, ParamRange> e : ranges.entrySet()) {
            Object seedRaw = elite.get(e.getKey());
            BigDecimal seed = toDecimal(seedRaw);
            if (seed == null) continue;
            List<BigDecimal> vals = e.getValue().refineAround(seed, 1);
            if (vals.isEmpty()) continue;
            perKey.put(e.getKey(), new ArrayList<>(vals));
        }
        return expandGrid(perKey);
    }

    private static BigDecimal toDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    /** Descending sort on the selected rank metric; nulls/NaN sort last. */
    private Comparator<SweepResult> rankComparator(String metric) {
        return (a, b) -> {
            BigDecimal av = pickMetric(a, metric);
            BigDecimal bv = pickMetric(b, metric);
            if (av == null && bv == null) return 0;
            if (av == null) return 1;
            if (bv == null) return -1;
            return bv.compareTo(av); // descending
        };
    }

    private BigDecimal pickMetric(SweepResult r, String metric) {
        return switch (metric) {
            case "profitFactor" -> r.getProfitFactor();
            case "netPnl" -> r.getNetPnl();
            case "winRate" -> r.getWinRate();
            default -> r.getAvgR();
        };
    }

    // ── Grid expansion ──────────────────────────────────────────────────────

    /**
     * Cartesian product of {key -> values} → list of {key -> single-value}.
     * Insertion order of the input map drives iteration order, so the
     * leaderboard remains readable.
     */
    static List<Map<String, Object>> expandGrid(Map<String, List<Object>> grid) {
        if (grid == null || grid.isEmpty()) return List.of(Map.of());

        List<Map<String, Object>> out = new ArrayList<>();
        out.add(new LinkedHashMap<>());

        for (Map.Entry<String, List<Object>> e : grid.entrySet()) {
            String key = e.getKey();
            List<Object> values = e.getValue();
            if (values == null || values.isEmpty()) continue;

            List<Map<String, Object>> next = new ArrayList<>();
            for (Map<String, Object> existing : out) {
                for (Object v : values) {
                    Map<String, Object> copy = new LinkedHashMap<>(existing);
                    copy.put(key, v);
                    next.add(copy);
                }
            }
            out = next;
        }
        return out;
    }

    // ── Validation ──────────────────────────────────────────────────────────

    /** Strategies whose param service consumes BacktestParamOverrideContext,
     *  so per-run sweep overrides actually land. TPR goes through
     *  ResearchParamService + explicit setters; VCB/LSR route through their
     *  {@code *ParamService.getParams()} which merges overrides onto the DB
     *  row already. Add codes here as more strategies wire themselves in. */
    private static final java.util.Set<String> RESEARCH_CAPABLE_CODES =
            java.util.Set.of("TPR", "VCB", "LSR");

    private void validate(SweepSpec spec) {
        if (spec == null) throw new IllegalArgumentException("spec required");
        if (isBlank(spec.getStrategyCode())) throw new IllegalArgumentException("strategyCode required");
        if (!RESEARCH_CAPABLE_CODES.contains(spec.getStrategyCode().toUpperCase())) {
            throw new IllegalArgumentException(
                    "Research sweeps are only supported for "
                            + RESEARCH_CAPABLE_CODES
                            + ". Wire the other strategies' param service to "
                            + "BacktestParamOverrideContext before adding them here.");
        }
        if (isBlank(spec.getAsset())) throw new IllegalArgumentException("asset required");
        if (isBlank(spec.getInterval())) throw new IllegalArgumentException("interval required");
        if (spec.getFromDate() == null || spec.getToDate() == null) {
            throw new IllegalArgumentException("fromDate / toDate required");
        }
        if (!spec.getFromDate().isBefore(spec.getToDate())) {
            throw new IllegalArgumentException("fromDate must be before toDate");
        }
        if (spec.getAccountStrategyId() == null) {
            throw new IllegalArgumentException("accountStrategyId required");
        }
        if (spec.getInitialCapital() == null || spec.getInitialCapital().signum() <= 0) {
            throw new IllegalArgumentException("initialCapital must be positive");
        }
        int rounds = spec.getRounds() == null ? 1 : spec.getRounds();
        Set<String> knownKeys = knownParamKeysFor(spec.getStrategyCode());
        if (rounds > 1) {
            if (rounds > MAX_ROUNDS) {
                throw new IllegalArgumentException("rounds capped at " + MAX_ROUNDS);
            }
            if (spec.getParamRanges() == null || spec.getParamRanges().isEmpty()) {
                throw new IllegalArgumentException(
                        "paramRanges required for multi-round research sweeps");
            }
            for (Map.Entry<String, ParamRange> e : spec.getParamRanges().entrySet()) {
                ParamRange r = e.getValue();
                if (r == null || r.getMin() == null || r.getMax() == null || r.getStep() == null
                        || r.getStep().signum() <= 0
                        || r.getMin().compareTo(r.getMax()) > 0) {
                    throw new IllegalArgumentException(
                            "Invalid range for " + e.getKey() + " — need min/max/step and min≤max");
                }
                rejectUnknownKey(e.getKey(), knownKeys, spec.getStrategyCode());
            }
        } else {
            if (spec.getParamGrid() == null || spec.getParamGrid().isEmpty()) {
                throw new IllegalArgumentException("paramGrid must contain at least one varied key");
            }
            for (String key : spec.getParamGrid().keySet()) {
                rejectUnknownKey(key, knownKeys, spec.getStrategyCode());
            }
        }

        // Fixed params: every key must be known and must NOT also appear as a
        // swept key. A swept value would always win the merge anyway, but
        // accepting the collision silently would let users submit a sweep
        // that ignores half their fixed values.
        Map<String, Object> fixed = spec.getFixedParams();
        if (fixed != null && !fixed.isEmpty()) {
            Set<String> sweptKeys = new HashSet<>();
            if (spec.getParamRanges() != null) sweptKeys.addAll(spec.getParamRanges().keySet());
            if (spec.getParamGrid() != null)   sweptKeys.addAll(spec.getParamGrid().keySet());
            for (String key : fixed.keySet()) {
                rejectUnknownKey(key, knownKeys, spec.getStrategyCode());
                if (sweptKeys.contains(key)) {
                    throw new IllegalArgumentException(
                            "Param '" + key + "' is both swept and pinned — pick one.");
                }
            }
        }
    }

    private void rejectUnknownKey(String key, Set<String> knownKeys, String strategyCode) {
        if (knownKeys.isEmpty()) return; // Unknown strategy — accept anything.
        if (!knownKeys.contains(key)) {
            throw new IllegalArgumentException(
                    "Unknown param '" + key + "' for strategy " + strategyCode +
                    ". Valid keys: " + knownKeys);
        }
    }

    /** Param-key registry — used to reject typos at submit time before the
     *  sweep silently runs with the baseline for the misspelt dimension.
     *  Sources: TrendPullbackStrategyService.Params, dto/vcb/VcbParams,
     *  dto/lsr/LsrParams. Keep in sync when those classes evolve. */
    private static final Map<String, Set<String>> KNOWN_PARAM_KEYS = Map.of(
            "TPR", Set.of(
                    "ema50SlopeMin", "biasAdxMin", "biasAdxMax",
                    "adxEntryMin", "adxEntryMax", "diSpreadMin",
                    "pullbackTouchAtr",
                    "longRsiMin", "longRsiMax", "shortRsiMin", "shortRsiMax",
                    "bodyRatioMin", "clvMin", "clvMax", "rvolMin",
                    "stopAtrBuffer", "maxEntryRiskPct", "tp1R",
                    "breakEvenR", "runnerBreakEvenR",
                    "runnerPhase2R", "runnerPhase3R",
                    "runnerAtrPhase2", "runnerAtrPhase3",
                    "runnerLockPhase2R", "runnerLockPhase3R",
                    "minSignalScore"
            ),
            "VCB", Set.of(
                    "squeezeKcTolerance", "atrRatioCompressMax", "erCompressMax",
                    "relVolBreakoutMin", "relVolBreakoutMax", "bodyRatioBreakoutMin",
                    "biasErMin",
                    "adxEntryMax", "longRsiMin", "shortRsiMax",
                    "longDiSpreadMin", "shortDiSpreadMin",
                    "stopAtrBuffer", "tp1R", "maxEntryRiskPct",
                    "runnerHalfR", "runnerBreakEvenR", "runnerPhase2R", "runnerPhase3R",
                    "runnerAtrPhase2", "runnerAtrPhase3",
                    "runnerLockPhase2R", "runnerLockPhase3R",
                    "minSignalScore"
            ),
            "LSR", Set.of(
                    "adxTrendingMin", "adxCompressionMax", "adxEntryMin", "adxEntryMax",
                    "atrRatioExhaustion", "atrRatioChaotic", "atrRatioCompress",
                    "stopAtrBuffer", "maxRiskPct",
                    "tp1RLongSweep", "tp1RLongContinuation", "tp1RShort",
                    "beTriggerRLongSweep", "beTriggerRLongContinuation", "beTriggerRShort",
                    "beFeeBufferR",
                    "shortNotionalMultiplier", "longContinuationNotionalMultiplier",
                    "timeStopBarsLongSweep", "timeStopBarsLongContinuation", "timeStopBarsShort",
                    "timeStopMinRLongSweep", "timeStopMinRLongContinuation", "timeStopMinRShort",
                    "longSweepMinAtr", "longSweepMaxAtr",
                    "longSweepRsiMin", "longSweepRsiMax",
                    "longSweepRvolMin", "longSweepBodyMin", "longSweepClvMin",
                    "minSignalScoreLongSweep", "minConfidenceScoreLongSweep",
                    "longContRsiMin", "longContRsiMax",
                    "longContRvolMin", "longContBodyMin", "longContClvMin",
                    "longContDonchianBufferAtr",
                    "minSignalScoreLongCont", "minConfidenceScoreLongCont",
                    "shortSweepMinAtr", "shortSweepMaxAtr",
                    "shortRsiMin", "shortRvolMin", "shortBodyMin", "shortClvMax",
                    "minSignalScoreShort"
            )
    );

    private Set<String> knownParamKeysFor(String strategyCode) {
        if (strategyCode == null) return Set.of();
        return KNOWN_PARAM_KEYS.getOrDefault(strategyCode.toUpperCase(), Set.of());
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    private void persist(SweepState state) {
        try {
            Path dir = Path.of(sweepsDir);
            Files.createDirectories(dir);
            Path file = dir.resolve(state.getSweepId() + ".json");
            Path tmp = dir.resolve(state.getSweepId() + ".json.tmp");
            // Write to a sibling temp file then atomic-rename so a process kill
            // mid-write can never leave a partially-flushed JSON on disk that
            // would fail to parse on next boot.
            Files.writeString(tmp,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state));
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFail) {
                // ATOMIC_MOVE isn't supported on every filesystem (e.g. some
                // network mounts) — fall back to plain replace.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize sweep state", e);
        } catch (IOException e) {
            log.error("Failed to write sweep state to {}", sweepsDir, e);
        }
    }

    private void loadPersistedSweeps() {
        try {
            Path dir = Path.of(sweepsDir);
            if (!Files.exists(dir)) return;
            try (var stream = Files.list(dir)) {
                Iterator<Path> it = stream.iterator();
                while (it.hasNext()) {
                    Path p = it.next();
                    String name = p.getFileName().toString();
                    if (!name.endsWith(".json")) continue;
                    // Stale .tmp files from a kill mid-write — sweep them up.
                    if (name.endsWith(".json.tmp")) {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) { }
                        continue;
                    }
                    try {
                        byte[] bytes = Files.readAllBytes(p);
                        SweepState s = objectMapper.readValue(bytes, SweepState.class);
                        // Sweeps that were in-flight at shutdown are stuck —
                        // mark them failed so the UI doesn't spinner-forever,
                        // and FAIL the orphan backtest_run rows they had
                        // submitted (otherwise they sit at PENDING/RUNNING
                        // forever in the trades view).
                        if (STATUS_RUNNING.equalsIgnoreCase(s.getStatus())
                                || STATUS_PENDING.equalsIgnoreCase(s.getStatus())) {
                            failOrphanedRuns(s);
                            s.setStatus(STATUS_FAILED);
                            persist(s);
                        }
                        sweeps.put(s.getSweepId(), s);
                        cancelFlags.put(s.getSweepId(), new AtomicBoolean(false));
                    } catch (IOException e) {
                        log.warn("Could not read sweep snapshot {}", p, e);
                    }
                }
            }
            log.info("Loaded {} persisted sweep state(s)", sweeps.size());
        } catch (IOException e) {
            log.warn("Could not enumerate sweeps directory {}", sweepsDir, e);
        }
    }

    /**
     * For any backtest_run rows linked to this orphaned sweep that are still
     * PENDING or RUNNING in the DB, flip them to FAILED. They can't make
     * progress — the worker thread that owned them is gone with the previous
     * JVM.
     */
    private void failOrphanedRuns(SweepState s) {
        if (s.getResults() == null) return;
        for (SweepResult r : s.getResults()) {
            UUID runId = r.getBacktestRunId();
            if (runId == null) continue;
            try {
                BacktestRun run = runRepository.findById(runId).orElse(null);
                if (run == null) continue;
                String status = run.getStatus();
                if (STATUS_PENDING.equalsIgnoreCase(status)
                        || STATUS_RUNNING.equalsIgnoreCase(status)) {
                    run.setStatus(STATUS_FAILED);
                    run.setNotes("Orphaned by sweep " + s.getSweepId() + " on JVM restart");
                    runRepository.save(run);
                    log.info("Failed orphan backtest_run {} (was {})", runId, status);
                }
            } catch (Exception e) {
                log.warn("Could not fail orphan run {}", runId, e);
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    private static Integer asInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    /** Number → BigDecimal via toString to avoid double-precision rounding —
     *  e.g. 0.1 + 0.2 = 0.30000000000000004 if you go through doubleValue(). */
    private static java.math.BigDecimal asDecimal(Object v) {
        if (v == null) return null;
        if (v instanceof java.math.BigDecimal bd) return bd;
        if (v instanceof Number n) {
            try { return new java.math.BigDecimal(n.toString()); } catch (NumberFormatException ignored) { }
        }
        try { return new java.math.BigDecimal(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    /** Read-only view of all known sweep ids (used by tests / admin tools). */
    public Collection<UUID> knownIds() {
        return sweeps.keySet();
    }
}

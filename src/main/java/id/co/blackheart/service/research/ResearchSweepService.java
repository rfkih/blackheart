package id.co.blackheart.service.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.request.BacktestRunRequest;
import id.co.blackheart.dto.response.BacktestRunResponse;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.repository.BacktestRunRepository;
import id.co.blackheart.service.backtest.BacktestAnalysisService;
import id.co.blackheart.service.backtest.BacktestService;
import id.co.blackheart.service.statistics.SharpeStatistics;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

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

    public ResearchSweepService(
            BacktestService backtestService,
            BacktestRunRepository runRepository,
            BacktestAnalysisService analysisService,
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
    /** Hard cap on total backtests across all combos × all walk-forward
     *  legs. With train/OOS each combo runs 2 backtests; future K-fold WF
     *  multiplies further. 2000 keeps a single sweep under ~17 hours at
     *  30s per backtest — beyond that, the user should split the work. */
    private static final int MAX_TOTAL_RUNS = 2000;
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

        // Compute the holdout slice's dates upfront so the UI can show them
        // before any backtest runs. The holdout is the tail of the window —
        // sweep optimization stops at holdoutFromDate.
        if (spec.getHoldoutFractionPct() != null && spec.getHoldoutFractionPct().signum() > 0) {
            long total = Duration.between(spec.getFromDate(), spec.getToDate()).getSeconds();
            long holdoutSeconds = (long) (total * spec.getHoldoutFractionPct().doubleValue() / 100.0);
            LocalDateTime holdoutStart = spec.getToDate().minusSeconds(holdoutSeconds);
            builder.holdoutFromDate(holdoutStart);
            builder.holdoutToDate(spec.getToDate());
        }

        SweepState state = builder.build();
        sweeps.put(sweepId, state);
        cancelFlags.put(sweepId, new AtomicBoolean(false));
        persist(state);

        sweepExecutor.execute(() -> run(state));
        return state;
    }

    /**
     * One-shot holdout evaluation. After a sweep COMPLETES, the user picks
     * a winner from the OOS-ranked leaderboard and calls this to get the
     * unbiased estimate: a single backtest with the chosen params, run over
     * the holdout slice that the sweep was forbidden from touching.
     *
     * <p>Discipline gates (any failure aborts):
     * <ul>
     *   <li>Sweep must be COMPLETED — can't evaluate while still optimizing.</li>
     *   <li>Sweep must have had a holdout reserved — i.e. holdoutFractionPct
     *       was set at submit time. Without that the "holdout slice" is a
     *       meaningless renaming of the OOS slice.</li>
     *   <li>Caller must own the sweep.</li>
     *   <li>No prior holdout run for this sweep — once you touch holdout,
     *       it's spent. A second evaluation would defeat the entire point.
     *       The DB unique partial index enforces this even if a race got past
     *       the service-layer check.</li>
     * </ul>
     */
    @org.springframework.transaction.annotation.Transactional
    public BacktestRun evaluateHoldout(UUID userId, UUID sweepId, Map<String, Object> paramSet) {
        SweepState state = sweeps.get(sweepId);
        if (state == null
                || (state.getUserId() != null && !state.getUserId().equals(userId))) {
            throw new jakarta.persistence.EntityNotFoundException("Sweep not found: " + sweepId);
        }
        if (!STATUS_COMPLETED.equalsIgnoreCase(state.getStatus())) {
            throw new IllegalStateException(
                    "Sweep is " + state.getStatus() + " — wait for COMPLETED before evaluating holdout");
        }
        if (state.getHoldoutFromDate() == null || state.getHoldoutToDate() == null) {
            throw new IllegalStateException(
                    "Sweep was submitted without a holdout reservation — re-submit with holdoutFractionPct set");
        }
        if (state.getHoldoutBacktestRunId() != null) {
            throw new IllegalStateException(
                    "This sweep's holdout has already been evaluated (run "
                            + state.getHoldoutBacktestRunId() + "). Holdout is one-shot by design.");
        }
        if (CollectionUtils.isEmpty(paramSet)) {
            throw new IllegalArgumentException("paramSet required");
        }

        SweepSpec spec = state.getSpec();
        BacktestRunRequest req = buildRequest(spec, paramSet);
        req.setStartTime(state.getHoldoutFromDate());
        req.setEndTime(state.getHoldoutToDate());

        BacktestRunResponse submitted = backtestService.runBacktest(userId, req);
        UUID runId = submitted.getBacktestRunId();

        // Targeted UPDATE — touches only the holdout marker columns so we
        // can't race the async worker writing progress/status into the
        // same row. A full {@code save(row)} would overwrite worker writes
        // captured between our load and save. The unique partial index on
        // (holdout_for_sweep_id WHERE is_holdout_run) is the database-level
        // safety net against double-evaluation; the
        // {@code WHERE is_holdout_run = FALSE} guard makes this UPDATE
        // idempotent (re-running on an already-marked row is a no-op).
        int updated = runRepository.markAsHoldoutRun(runId, sweepId);
        if (updated == 0) {
            log.warn("Holdout marker UPDATE affected zero rows | runId={} sweepId={}", runId, sweepId);
        }

        state.setHoldoutBacktestRunId(runId);
        persist(state);

        log.info("Sweep {} holdout evaluation submitted | runId={} window={}→{}",
                sweepId, runId, state.getHoldoutFromDate(), state.getHoldoutToDate());
        return runRepository.findById(runId).orElse(null);
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
        SweepState state = sweeps.get(sweepId);
        if (state != null) enrichDsrThreshold(state);
        return state;
    }

    public List<SweepState> listSweeps(UUID userId) {
        List<SweepState> out = new ArrayList<>();
        for (SweepState s : sweeps.values()) {
            if (userId == null || s.getUserId() == null || userId.equals(s.getUserId())) {
                enrichDsrThreshold(s);
                out.add(s);
            }
        }
        out.sort(Comparator.comparing(
                SweepState::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return out;
    }

    /**
     * Filterable + paginated sweep list. Sweeps live in an in-memory map so
     * filtering/sorting/paging happens in service code rather than via SQL.
     * The page envelope mirrors Spring Data's {@link Page} so the frontend
     * sees the same shape regardless of storage.
     *
     * @param userId        owner — null returns all (used by admin tooling).
     * @param statusFilter  upper-cased status set, or empty for "any".
     *                      Honours CSV like {@code "RUNNING,PENDING"} parsed
     *                      at the controller boundary.
     * @param sort          {@code "createdAt,desc"} (default), {@code "status"},
     *                      etc. Field ∈ {createdAt, status, finishedCombos,
     *                      totalCombos}; direction ∈ {asc, desc}.
     */
    public Page<SweepState> listSweepsPaged(
            UUID userId, Set<String> statusFilter, String sort, Pageable pageable) {
        List<SweepState> filtered = new ArrayList<>();
        for (SweepState s : sweeps.values()) {
            if (userId != null && s.getUserId() != null && !userId.equals(s.getUserId())) continue;
            if (!CollectionUtils.isEmpty(statusFilter)) {
                String st = s.getStatus() == null ? "" : s.getStatus().toUpperCase(Locale.ROOT);
                if (!statusFilter.contains(st)) continue;
            }
            enrichDsrThreshold(s);
            filtered.add(s);
        }
        filtered.sort(sweepComparator(sort));

        int total = filtered.size();
        int from = (int) Math.min(pageable.getOffset(), total);
        int to = Math.min(from + pageable.getPageSize(), total);
        List<SweepState> pageRows = filtered.subList(from, to);
        return new PageImpl<>(new ArrayList<>(pageRows), pageable, total);
    }

    /** Parse {@code field,direction} (Spring's convention). Defaults: createdAt,desc. */
    private static Comparator<SweepState> sweepComparator(String sort) {
        String field = "createdAt";
        boolean desc = true;
        if (StringUtils.hasText(sort)) {
            String[] parts = sort.split(",", 2);
            field = parts[0].trim();
            if (parts.length > 1) desc = "desc".equalsIgnoreCase(parts[1].trim());
        }
        Comparator<SweepState> cmp;
        switch (field) {
            case "status":
                cmp = Comparator.comparing(
                        SweepState::getStatus,
                        Comparator.nullsLast(Comparator.naturalOrder()));
                break;
            case "finishedCombos":
                cmp = Comparator.comparingInt(SweepState::getFinishedCombos);
                break;
            case "totalCombos":
                cmp = Comparator.comparingInt(SweepState::getTotalCombos);
                break;
            case "createdAt":
            default:
                cmp = Comparator.comparing(
                        SweepState::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()));
                break;
        }
        return desc ? cmp.reversed() : cmp;
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
        if ("WALK_FORWARD_K".equalsIgnoreCase(spec.getSplitMode())) {
            executeWalkForwardK(state, result, spec);
        } else if ("TRAIN_OOS".equalsIgnoreCase(spec.getSplitMode())) {
            executeTrainOos(state, result, spec);
        } else {
            executeSingle(state, result, spec);
        }
    }

    private void executeSingle(SweepState state, SweepResult result, SweepSpec spec) throws Exception {
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
     * Train/OOS split execution: run two backtests sequentially with the
     * same params, the first over the train window and the second over the
     * out-of-sample tail. The OOS leg's metrics mirror to the legacy
     * top-level fields on {@link SweepResult} so existing leaderboard sort,
     * DSR computation, and frontend rendering automatically rank by OOS —
     * which is the whole point of the split.
     *
     * <p>Wall time per combo doubles, but in exchange the leaderboard stops
     * being a fancy in-sample overfitter.
     */
    private void executeTrainOos(SweepState state, SweepResult result, SweepSpec spec) throws Exception {
        long startNanos = System.nanoTime();
        result.setStatus(STATUS_RUNNING);
        persist(state);

        // The "sweep end" is the start of the holdout slice (or spec.toDate
        // when holdout is disabled). The holdout slice is reserved — sweep
        // optimization is forbidden from running over it. evaluateHoldout()
        // is the only entry point that touches it.
        java.time.LocalDateTime sweepEnd = state.getHoldoutFromDate() != null
                ? state.getHoldoutFromDate()
                : spec.getToDate();

        BigDecimal pct = spec.getOosFractionPct();
        if (pct == null) pct = new BigDecimal("30");
        long totalWindowSeconds = Duration.between(spec.getFromDate(), spec.getToDate()).getSeconds();
        // OOS fraction is relative to the FULL window (including holdout)
        // so 30% means 30% of the user's submitted range — not 30% of
        // whatever's left after holdout. Keeps the mental model simple.
        long oosSeconds = (long) (totalWindowSeconds * pct.doubleValue() / 100.0);
        java.time.LocalDateTime splitAt = sweepEnd.minusSeconds(oosSeconds);

        // Train leg.
        BacktestRunRequest trainReq = buildRequest(spec, result.getParamSet());
        trainReq.setStartTime(spec.getFromDate());
        trainReq.setEndTime(splitAt);
        BacktestRunResponse trainSubmit = backtestService.runBacktest(state.getUserId(), trainReq);
        UUID trainRunId = trainSubmit.getBacktestRunId();
        result.setTrainBacktestRunId(trainRunId);
        result.setBacktestRunId(trainRunId); // legacy field follows the live leg
        result.setProgressPercent(0);
        persist(state);

        BacktestRun trainCompleted = waitForRun(trainRunId, state, result);
        if (trainCompleted == null) {
            result.setStatus(STATUS_FAILED);
            result.setErrorMessage("Train leg did not complete in "
                    + (PER_RUN_TIMEOUT_MS / 1000) + "s");
            result.setElapsedMs(Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
            return;
        }
        applyTrainMetrics(result, trainCompleted);
        if (!STATUS_COMPLETED.equalsIgnoreCase(trainCompleted.getStatus())) {
            // Train failed — skip OOS, surface the train failure on the row.
            result.setStatus(STATUS_FAILED);
            result.setErrorMessage("Train leg failed: "
                    + (trainCompleted.getNotes() != null ? trainCompleted.getNotes() : "unknown"));
            result.setElapsedMs(Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
            return;
        }

        // OOS leg — same params, the OOS slice (which ends at sweepEnd, NOT
        // at spec.getToDate(), so the locked holdout stays untouched). From
        // here on the legacy top-level fields track the OOS run, so
        // leaderboard sort/DSR all operate on out-of-sample numbers.
        BacktestRunRequest oosReq = buildRequest(spec, result.getParamSet());
        oosReq.setStartTime(splitAt);
        oosReq.setEndTime(sweepEnd);
        BacktestRunResponse oosSubmit = backtestService.runBacktest(state.getUserId(), oosReq);
        UUID oosRunId = oosSubmit.getBacktestRunId();
        result.setOosBacktestRunId(oosRunId);
        result.setBacktestRunId(oosRunId);
        result.setProgressPercent(0);
        persist(state);

        BacktestRun oosCompleted = waitForRun(oosRunId, state, result);
        if (oosCompleted == null) {
            result.setStatus(STATUS_FAILED);
            result.setErrorMessage("OOS leg did not complete in "
                    + (PER_RUN_TIMEOUT_MS / 1000) + "s");
            result.setElapsedMs(Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
            return;
        }

        result.setStatus(STATUS_COMPLETED.equalsIgnoreCase(oosCompleted.getStatus())
                ? STATUS_COMPLETED : STATUS_FAILED);
        result.setElapsedMs(Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
        result.setProgressPercent(oosCompleted.getProgressPercent());
        applyOosMetrics(result, oosCompleted);
    }

    /**
     * K-fold anchored walk-forward execution. The available window (excluding
     * any locked holdout) splits into a train-head chunk followed by K
     * non-overlapping OOS slices. For each fold, train spans
     * {@code [start, OOS_i.start)} (expanding) and OOS is the i-th slice.
     *
     * <p>Per combo this runs 2K backtests. The mean OOS Sharpe mirrors to
     * {@link SweepResult#getSharpeRatio()} so the leaderboard ranks by
     * it automatically; stddev across folds is exposed separately as a
     * regime-sensitivity diagnostic.
     */
    private void executeWalkForwardK(SweepState state, SweepResult result, SweepSpec spec) throws Exception {
        long startNanos = System.nanoTime();
        result.setStatus(STATUS_RUNNING);
        persist(state);

        int k = spec.getWalkForwardWindows() == null ? 4 : spec.getWalkForwardWindows();
        java.time.LocalDateTime sweepEnd = state.getHoldoutFromDate() != null
                ? state.getHoldoutFromDate()
                : spec.getToDate();
        // Same OOS-fraction semantics as TRAIN_OOS / validation. Defaults
        // to 30% when the user didn't supply one (matches TRAIN_OOS default).
        double oosFraction = (spec.getOosFractionPct() != null
                ? spec.getOosFractionPct().doubleValue() : 30.0) / 100.0;
        java.util.List<WalkForwardWindowing.Fold> foldPlan =
                WalkForwardWindowing.buildFolds(spec.getFromDate(), sweepEnd, k, oosFraction);

        java.util.List<WindowResult> folds = new java.util.ArrayList<>(k);
        java.util.List<Double> oosSharpes = new java.util.ArrayList<>(k);

        for (int i = 0; i < k; i++) {
            WalkForwardWindowing.Fold plan = foldPlan.get(i);
            java.time.LocalDateTime trainFrom = plan.trainFromDate();
            java.time.LocalDateTime trainTo = plan.trainToDate();
            java.time.LocalDateTime oosFrom = plan.oosFromDate();
            java.time.LocalDateTime oosTo = plan.oosToDate();

            WindowResult fold = WindowResult.builder()
                    .foldIndex(i + 1)
                    .trainFromDate(trainFrom)
                    .trainToDate(trainTo)
                    .oosFromDate(oosFrom)
                    .oosToDate(oosTo)
                    .build();

            // Train leg.
            BacktestRunRequest trainReq = buildRequest(spec, result.getParamSet());
            trainReq.setStartTime(trainFrom);
            trainReq.setEndTime(trainTo);
            BacktestRunResponse trainSubmit = backtestService.runBacktest(state.getUserId(), trainReq);
            UUID trainRunId = trainSubmit.getBacktestRunId();
            fold.setTrainBacktestRunId(trainRunId);
            result.setBacktestRunId(trainRunId);
            persist(state);

            BacktestRun trainCompleted = waitForRun(trainRunId, state, result);
            if (trainCompleted == null
                    || !STATUS_COMPLETED.equalsIgnoreCase(trainCompleted.getStatus())) {
                fold.setStatus(STATUS_FAILED);
                folds.add(fold);
                continue; // bad fold doesn't poison the rest
            }
            fold.setTrainSharpeRatio(trainCompleted.getSharpeRatio());

            // OOS leg.
            BacktestRunRequest oosReq = buildRequest(spec, result.getParamSet());
            oosReq.setStartTime(oosFrom);
            oosReq.setEndTime(oosTo);
            BacktestRunResponse oosSubmit = backtestService.runBacktest(state.getUserId(), oosReq);
            UUID oosRunId = oosSubmit.getBacktestRunId();
            fold.setOosBacktestRunId(oosRunId);
            result.setBacktestRunId(oosRunId);
            persist(state);

            BacktestRun oosCompleted = waitForRun(oosRunId, state, result);
            if (oosCompleted == null
                    || !STATUS_COMPLETED.equalsIgnoreCase(oosCompleted.getStatus())) {
                fold.setStatus(STATUS_FAILED);
                folds.add(fold);
                continue;
            }
            fold.setOosSharpeRatio(oosCompleted.getSharpeRatio());
            fold.setOosPsr(oosCompleted.getPsr());
            fold.setOosNetPnl(oosCompleted.getNetProfit());
            fold.setOosTradeCount(oosCompleted.getTotalTrades());
            fold.setStatus(STATUS_COMPLETED);
            folds.add(fold);

            if (oosCompleted.getSharpeRatio() != null) {
                oosSharpes.add(oosCompleted.getSharpeRatio().doubleValue());
            }
            // Mirror the most-recent fold's OOS run as the "current" backtest
            // so the legacy populateMetrics() snapshot at the end has data.
        }

        result.setWindowResults(folds);
        result.setElapsedMs(Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
        result.setProgressPercent(100);

        // Aggregate across folds. Mean OOS Sharpe mirrors to legacy slots so
        // leaderboard sort + DSR threshold operate on it; stddev is the
        // regime-sensitivity diagnostic surfaced alongside.
        if (oosSharpes.isEmpty()) {
            result.setStatus(STATUS_FAILED);
            result.setErrorMessage("All " + k + " walk-forward folds failed");
            return;
        }
        double[] arr = oosSharpes.stream().mapToDouble(Double::doubleValue).toArray();
        BigDecimal mean = BigDecimal.valueOf(SharpeStatistics.mean(arr))
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal sd = arr.length >= 2
                ? BigDecimal.valueOf(SharpeStatistics.stddev(arr)).setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        result.setMeanOosSharpe(mean);
        result.setStddevOosSharpe(sd);
        // Mirror to the legacy fields the leaderboard sort + DSR cohort use.
        result.setSharpeRatio(mean);

        // Pull the rest of the headline metrics from the last successful OOS
        // run so non-Sharpe columns (winRate, profitFactor, avgR, etc.) still
        // populate. The leaderboard already shows averages where it matters
        // (Sharpe); the rest are illustrative for the most-recent fold.
        WindowResult lastSuccessful = null;
        for (int i = folds.size() - 1; i >= 0; i--) {
            if (STATUS_COMPLETED.equalsIgnoreCase(folds.get(i).getStatus())) {
                lastSuccessful = folds.get(i);
                break;
            }
        }
        if (lastSuccessful != null && lastSuccessful.getOosBacktestRunId() != null) {
            BacktestRun lastRun = runRepository.findById(lastSuccessful.getOosBacktestRunId()).orElse(null);
            if (lastRun != null) {
                populateMetrics(result, lastRun);
                // populateMetrics overwrites sharpeRatio with the single-fold
                // value — restore the cohort mean.
                result.setSharpeRatio(mean);
                result.setPsr(lastRun.getPsr());
            }
        }

        result.setStatus(STATUS_COMPLETED);
    }

    private void applyTrainMetrics(SweepResult result, BacktestRun run) {
        result.setTrainTradeCount(run.getTotalTrades());
        result.setTrainNetPnl(run.getNetProfit());
        result.setTrainSharpeRatio(run.getSharpeRatio());
        result.setTrainPsr(run.getPsr());
    }

    private void applyOosMetrics(SweepResult result, BacktestRun run) {
        result.setOosTradeCount(run.getTotalTrades());
        result.setOosNetPnl(run.getNetProfit());
        result.setOosSharpeRatio(run.getSharpeRatio());
        result.setOosPsr(run.getPsr());
        // Mirror to legacy top-level fields so leaderboard, DSR threshold,
        // and existing UI sort all operate on OOS automatically.
        populateMetrics(result, run);
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
        if (!StringUtils.hasText(snap)) {
            log.info("No analysis snapshot for run {} — re-invoking analyzer",
                    run.getBacktestRunId());
            try {
                analysisService.analyze(run.getBacktestRunId());
                BacktestRun refreshed = runRepository.findById(run.getBacktestRunId()).orElse(run);
                snap = refreshed.getAnalysisSnapshot();
            } catch (Exception e) {
                log.warn("Analyzer retry failed for run {}", run.getBacktestRunId(), e);
            }
            if (!StringUtils.hasText(snap)) return;
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
            // Sharpe + PSR aren't in the analyzer snapshot — they live on
            // backtest_run directly (written by BacktestPersistenceService).
            // Pull the freshest copy of the row so newer runs surface them.
            result.setSharpeRatio(run.getSharpeRatio());
            result.setPsr(run.getPsr());
        } catch (Exception e) {
            log.warn("Failed to parse analysis snapshot for run {}", run.getBacktestRunId(), e);
        }
    }

    /**
     * Compute the cohort's expected-max-Sharpe under N null trials and stash
     * it on the {@link SweepState} for the controller to return alongside
     * the leaderboard. Idempotent — same inputs always produce the same
     * value, so it's safe to invoke on every read without locking.
     *
     * <p>The threshold answers the multiple-comparisons question every sweep
     * implicitly raises: "given I just ran N param combos, how high a
     * Sharpe should I expect to see <i>by chance</i> even if none of them
     * had real edge?" Combos exceeding the threshold are evidence beyond
     * selection bias; combos at or below are statistically indistinguishable
     * from the best of N coin flips.
     *
     * <p>Uses annualized Sharpes throughout — same units as the leaderboard
     * already displays, so users can compare a combo's Sharpe directly to
     * the threshold by eye.
     */
    private void enrichDsrThreshold(SweepState state) {
        if (state == null || state.getResults() == null) return;
        double[] sharpes = state.getResults().stream()
                .filter(r -> "COMPLETED".equalsIgnoreCase(r.getStatus()))
                .map(SweepResult::getSharpeRatio)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(BigDecimal::doubleValue)
                .toArray();
        if (sharpes.length < 2) {
            state.setDsrThresholdSharpe(null);
            state.setDsrCohortStddev(null);
            return;
        }
        double sigma = SharpeStatistics.stddev(sharpes);
        double srStar = SharpeStatistics.expectedMaxSharpe(sigma, sharpes.length);
        state.setDsrCohortStddev(BigDecimal.valueOf(sigma).setScale(4, RoundingMode.HALF_UP));
        state.setDsrThresholdSharpe(BigDecimal.valueOf(srStar).setScale(4, RoundingMode.HALF_UP));
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
        return StringUtils.hasText(m) ? m : "avgR";
    }

    /** Expands paramRanges into the round-1 cross-product. */
    static List<Map<String, Object>> expandFromRanges(Map<String, ParamRange> ranges) {
        if (CollectionUtils.isEmpty(ranges)) return List.of();
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
        if (CollectionUtils.isEmpty(grid)) return List.of(Map.of());

        List<Map<String, Object>> out = new ArrayList<>();
        out.add(new LinkedHashMap<>());

        for (Map.Entry<String, List<Object>> e : grid.entrySet()) {
            String key = e.getKey();
            List<Object> values = e.getValue();
            if (CollectionUtils.isEmpty(values)) continue;

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

    /**
     * Best-effort estimate of how many combos the sweep will produce. For
     * flat grids this is exact (cross product); for research-mode (rounds
     * with refinement) it's the round-1 size × the maximum theoretical
     * fan-out, which is a conservative upper bound. Used purely to enforce
     * {@link #MAX_TOTAL_RUNS} at submit time.
     */
    private int estimateTotalCombos(SweepSpec spec) {
        int rounds = spec.getRounds() == null ? 1 : spec.getRounds();
        if (rounds > 1 && spec.getParamRanges() != null) {
            // Round 1 from ranges, then up to MAX_COMBOS_PER_ROUND per
            // refinement round. Realistic upper bound on what we'd run.
            int round1 = expandFromRanges(spec.getParamRanges()).size();
            return round1 + (rounds - 1) * MAX_COMBOS_PER_ROUND;
        }
        if (!CollectionUtils.isEmpty(spec.getParamGrid())) {
            int total = 1;
            for (List<Object> values : spec.getParamGrid().values()) {
                total *= Math.max(1, values.size());
                if (total > MAX_TOTAL_RUNS) return total; // saturate to short-circuit
            }
            return total;
        }
        return 0;
    }

    private void validate(SweepSpec spec) {
        if (spec == null) throw new IllegalArgumentException("spec required");
        if (!StringUtils.hasText(spec.getStrategyCode())) throw new IllegalArgumentException("strategyCode required");
        if (!RESEARCH_CAPABLE_CODES.contains(spec.getStrategyCode().toUpperCase())) {
            throw new IllegalArgumentException(
                    "Research sweeps are only supported for "
                            + RESEARCH_CAPABLE_CODES
                            + ". Wire the other strategies' param service to "
                            + "BacktestParamOverrideContext before adding them here.");
        }
        if (!StringUtils.hasText(spec.getAsset())) throw new IllegalArgumentException("asset required");
        if (!StringUtils.hasText(spec.getInterval())) throw new IllegalArgumentException("interval required");
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
        // Train/OOS split validation. The fraction is bounded so neither leg
        // ends up degenerate — at 5% the OOS sample is too short to score, at
        // 60% the train sample is too short to fit. We also require each
        // window to be at least 7-30 days so the strategy has a chance to
        // open positions; otherwise the metrics are mostly noise.
        boolean trainOosMode = "TRAIN_OOS".equalsIgnoreCase(spec.getSplitMode());
        boolean walkForwardMode = "WALK_FORWARD_K".equalsIgnoreCase(spec.getSplitMode());
        BigDecimal holdoutPct = spec.getHoldoutFractionPct();
        if (holdoutPct != null && !trainOosMode && !walkForwardMode) {
            throw new IllegalArgumentException(
                    "holdoutFractionPct only applies with splitMode TRAIN_OOS or WALK_FORWARD_K");
        }
        if (trainOosMode) {
            BigDecimal oosPct = spec.getOosFractionPct();
            if (oosPct == null) oosPct = new BigDecimal("30");
            if (oosPct.compareTo(new BigDecimal("10")) < 0
                    || oosPct.compareTo(new BigDecimal("50")) > 0) {
                throw new IllegalArgumentException(
                        "oosFractionPct must be between 10 and 50, got " + oosPct);
            }
            BigDecimal hPct = holdoutPct != null ? holdoutPct : BigDecimal.ZERO;
            if (hPct.signum() < 0
                    || hPct.compareTo(new BigDecimal("40")) > 0) {
                throw new IllegalArgumentException(
                        "holdoutFractionPct must be between 0 and 40, got " + hPct);
            }
            // Combined sanity: train slice must be at least 30% of the window
            // so the optimization has something to fit on.
            BigDecimal trainPct = new BigDecimal("100").subtract(oosPct).subtract(hPct);
            if (trainPct.compareTo(new BigDecimal("30")) < 0) {
                throw new IllegalArgumentException(
                        "Train slice would be only " + trainPct + "% of the window — "
                                + "lower oosFractionPct or holdoutFractionPct so train >= 30%");
            }

            long totalSeconds = Duration.between(spec.getFromDate(), spec.getToDate()).getSeconds();
            long holdoutSeconds = (long) (totalSeconds * hPct.doubleValue() / 100.0);
            long oosSeconds = (long) (totalSeconds * oosPct.doubleValue() / 100.0);
            long trainSeconds = totalSeconds - oosSeconds - holdoutSeconds;

            if (oosSeconds < Duration.ofDays(7).getSeconds()) {
                throw new IllegalArgumentException(
                        "OOS window is shorter than 7 days — widen the date range or lower oosFractionPct");
            }
            if (trainSeconds < Duration.ofDays(30).getSeconds()) {
                throw new IllegalArgumentException(
                        "Train window is shorter than 30 days — widen the date range");
            }
            if (hPct.signum() > 0 && holdoutSeconds < Duration.ofDays(7).getSeconds()) {
                throw new IllegalArgumentException(
                        "Holdout window is shorter than 7 days — widen the date range or lower holdoutFractionPct");
            }
        }
        // K-fold walk-forward validation. Train head + K OOS slices tile the
        // available (non-holdout) window. The OOS coverage is controlled by
        // oosFractionPct (default 30%) — train head is whatever remains.
        if (walkForwardMode) {
            int k = spec.getWalkForwardWindows() == null ? 4 : spec.getWalkForwardWindows();
            if (k < 2 || k > 8) {
                throw new IllegalArgumentException(
                        "walkForwardWindows must be between 2 and 8, got " + k);
            }
            BigDecimal hPct = holdoutPct != null ? holdoutPct : BigDecimal.ZERO;
            if (hPct.signum() < 0 || hPct.compareTo(new BigDecimal("40")) > 0) {
                throw new IllegalArgumentException(
                        "holdoutFractionPct must be between 0 and 40, got " + hPct);
            }
            // OOS fraction shares semantics with TRAIN_OOS mode: % of the
            // available (non-holdout) window covered by OOS evaluation.
            // Default 30% when not provided.
            BigDecimal oosPctRaw = spec.getOosFractionPct() != null
                    ? spec.getOosFractionPct() : new BigDecimal("30");
            if (oosPctRaw.compareTo(new BigDecimal("10")) < 0
                    || oosPctRaw.compareTo(new BigDecimal("70")) > 0) {
                throw new IllegalArgumentException(
                        "oosFractionPct must be between 10 and 70 for K-fold, got " + oosPctRaw);
            }
            long totalSeconds = Duration.between(spec.getFromDate(), spec.getToDate()).getSeconds();
            long holdoutSeconds = (long) (totalSeconds * hPct.doubleValue() / 100.0);
            long availableSeconds = totalSeconds - holdoutSeconds;
            double oosFraction = oosPctRaw.doubleValue() / 100.0;

            WalkForwardWindowing.SliceSizing sizing =
                    WalkForwardWindowing.computeSliceSizing(availableSeconds, k, oosFraction);
            WalkForwardWindowing.validateSliceSizes(sizing);

            if (hPct.signum() > 0 && holdoutSeconds < Duration.ofDays(7).getSeconds()) {
                throw new IllegalArgumentException(
                        "Holdout window is shorter than 7 days — lower holdoutFractionPct or widen the date range");
            }
        }
        // Total-runs hard cap. Walk-forward modes multiply run count per
        // combo, so we estimate before submission and reject grids that
        // would queue runaway-cluster work.
        int runsPerCombo = walkForwardMode
                ? 2 * (spec.getWalkForwardWindows() == null ? 4 : spec.getWalkForwardWindows())
                : (trainOosMode ? 2 : 1);
        int estimatedCombos = estimateTotalCombos(spec);
        if ((long) estimatedCombos * runsPerCombo > MAX_TOTAL_RUNS) {
            throw new IllegalArgumentException(
                    "Sweep would queue " + ((long) estimatedCombos * runsPerCombo)
                            + " backtests (cap " + MAX_TOTAL_RUNS + "). Lower the grid, lower K, or split into smaller sweeps.");
        }
        int rounds = spec.getRounds() == null ? 1 : spec.getRounds();
        Set<String> knownKeys = knownParamKeysFor(spec.getStrategyCode());
        if (rounds > 1) {
            if (rounds > MAX_ROUNDS) {
                throw new IllegalArgumentException("rounds capped at " + MAX_ROUNDS);
            }
            if (CollectionUtils.isEmpty(spec.getParamRanges())) {
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
            if (CollectionUtils.isEmpty(spec.getParamGrid())) {
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
        if (!CollectionUtils.isEmpty(fixed)) {
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

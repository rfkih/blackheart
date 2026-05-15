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
import org.apache.commons.lang3.ObjectUtils;
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

    /** On-disk snapshot file extensions. {@link #EXTENSION_JSON_TMP} is the
     *  sibling-write target for atomic-rename, scanned + cleaned up at boot. */
    private static final String EXTENSION_JSON     = ".json";
    private static final String EXTENSION_JSON_TMP = ".json.tmp";

    /** Strategy-param keys repeated across {@link #KNOWN_PARAM_KEYS} entries.
     *  Kept as constants so a rename in one strategy's param schema is a
     *  one-line edit here, not a grep across three Set.of literals. */
    private static final String KEY_STOP_ATR_BUFFER = "stopAtrBuffer";
    private static final String KEY_ADX_ENTRY_MAX   = "adxEntryMax";

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
        if (ObjectUtils.isNotEmpty(spec.getHoldoutFractionPct()) && spec.getHoldoutFractionPct().signum() > 0) {
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
        if (ObjectUtils.isEmpty(state)
                || (ObjectUtils.isNotEmpty(state.getUserId()) && !state.getUserId().equals(userId))) {
            throw new jakarta.persistence.EntityNotFoundException("Sweep not found: " + sweepId);
        }
        if (!STATUS_COMPLETED.equalsIgnoreCase(state.getStatus())) {
            throw new IllegalStateException(
                    "Sweep is " + state.getStatus() + " — wait for COMPLETED before evaluating holdout");
        }
        if (ObjectUtils.isEmpty(state.getHoldoutFromDate()) || ObjectUtils.isEmpty(state.getHoldoutToDate())) {
            throw new IllegalStateException(
                    "Sweep was submitted without a holdout reservation — re-submit with holdoutFractionPct set");
        }
        if (ObjectUtils.isNotEmpty(state.getHoldoutBacktestRunId())) {
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
        if (ObjectUtils.isEmpty(state)) throw new jakarta.persistence.EntityNotFoundException("Sweep not found: " + sweepId);
        AtomicBoolean flag = cancelFlags.get(sweepId);
        if (ObjectUtils.isNotEmpty(flag)) flag.set(true);
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
        if (ObjectUtils.isEmpty(state)) return;
        if (STATUS_RUNNING.equalsIgnoreCase(state.getStatus())
                || STATUS_PENDING.equalsIgnoreCase(state.getStatus())) {
            throw new IllegalStateException(
                    "Sweep is still " + state.getStatus() + " — cancel before deleting");
        }
        sweeps.remove(sweepId);
        cancelFlags.remove(sweepId);
        try {
            Files.deleteIfExists(Path.of(sweepsDir).resolve(sweepId + EXTENSION_JSON));
        } catch (IOException e) {
            log.warn("Could not delete sweep file for {}", sweepId, e);
        }
        log.info("Sweep deleted | id={}", sweepId);
    }

    public SweepState getSweep(UUID sweepId) {
        SweepState state = sweeps.get(sweepId);
        if (ObjectUtils.isNotEmpty(state)) enrichDsrThreshold(state);
        return state;
    }

    public List<SweepState> listSweeps(UUID userId) {
        List<SweepState> out = new ArrayList<>();
        for (SweepState s : sweeps.values()) {
            if (ObjectUtils.isEmpty(userId) || ObjectUtils.isEmpty(s.getUserId()) || userId.equals(s.getUserId())) {
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
            if (matchesPagedFilter(s, userId, statusFilter)) {
                enrichDsrThreshold(s);
                filtered.add(s);
            }
        }
        filtered.sort(sweepComparator(sort));

        int total = filtered.size();
        int from = (int) Math.min(pageable.getOffset(), total);
        int to = Math.min(from + pageable.getPageSize(), total);
        List<SweepState> pageRows = filtered.subList(from, to);
        return new PageImpl<>(new ArrayList<>(pageRows), pageable, total);
    }

    private static boolean matchesPagedFilter(SweepState s, UUID userId, Set<String> statusFilter) {
        if (ObjectUtils.isNotEmpty(userId) && ObjectUtils.isNotEmpty(s.getUserId()) && !userId.equals(s.getUserId())) return false;
        if (CollectionUtils.isEmpty(statusFilter)) return true;
        String st = ObjectUtils.isEmpty(s.getStatus()) ? "" : s.getStatus().toUpperCase(Locale.ROOT);
        return statusFilter.contains(st);
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
                ObjectUtils.isEmpty(state.getTotalRounds()) ? 1 : state.getTotalRounds());

        try {
            boolean researchMode = ObjectUtils.isNotEmpty(state.getTotalRounds()) && state.getTotalRounds() > 1;

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
        return ObjectUtils.isNotEmpty(flag) && flag.get();
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
            RoundOutcome outcome = runOneRound(state, r, rankMetric, totalRounds);
            if (outcome == RoundOutcome.STOP_SWEEP) return;
            if (outcome == RoundOutcome.DONE) return;     // last/converged/no-data
        }
    }

    /** Outcome of a single research-mode round. STOP_SWEEP = caller cancelled
     *  during execution; DONE = orderly stop (last round, converged, no data);
     *  CONTINUE = next round was planned, keep iterating. */
    private enum RoundOutcome { CONTINUE, DONE, STOP_SWEEP }

    private RoundOutcome runOneRound(SweepState state, int round, String rankMetric, int totalRounds) {
        state.setCurrentRound(round);
        persist(state);

        int startIdx = indexOfFirstInRound(state.getResults(), round);
        if (startIdx < 0) {
            log.info("Sweep {} round {} had no queued combos — stopping early",
                    state.getSweepId(), round);
            return RoundOutcome.DONE;
        }

        if (!executeRoundCombos(state, startIdx, round)) {
            return RoundOutcome.STOP_SWEEP;          // cancelled mid-round
        }
        if (round == totalRounds) return RoundOutcome.DONE;

        return planNextRound(state, round, rankMetric)
                ? RoundOutcome.CONTINUE
                : RoundOutcome.DONE;
    }

    /** Run every queued combo for {@code round}. Returns false when cancelled. */
    private boolean executeRoundCombos(SweepState state, int startIdx, int round) {
        List<SweepResult> results = state.getResults();
        for (int i = startIdx; i < results.size(); i++) {
            if (isCancelled(state)) {
                log.info("Sweep {} cancelled mid-round {}", state.getSweepId(), round);
                return false;
            }
            SweepResult cur = results.get(i);
            if (ObjectUtils.isEmpty(cur.getRound()) || cur.getRound() != round) break;
            executeOneSafe(state, cur);
            state.setFinishedCombos(state.getFinishedCombos() + 1);
            persist(state);
        }
        return true;
    }

    /**
     * Pick this round's elites, refine around them, dedupe, append to results.
     * Returns false when nothing new was planned (search converged or no
     * completed combos to seed from) — caller stops the sweep.
     */
    private boolean planNextRound(SweepState state, int round, String rankMetric) {
        List<SweepResult> results = state.getResults();
        List<SweepResult> roundResults = results.stream()
                .filter(x -> ObjectUtils.isNotEmpty(x.getRound()) && x.getRound() == round)
                .filter(x -> STATUS_COMPLETED.equalsIgnoreCase(x.getStatus()))
                .toList();
        if (roundResults.isEmpty()) {
            log.info("Sweep {} round {} produced no completed results — stopping",
                    state.getSweepId(), round);
            return false;
        }

        List<SweepResult> elites = pickElites(state, roundResults, rankMetric, round);

        // Dedup uses canonicalised paramSet (BigDecimal scales stripped)
        // so 0.6 and 0.60 don't both land in the next round.
        Set<Map<String, Object>> alreadyPlanned = new HashSet<>();
        for (SweepResult seen : results) alreadyPlanned.add(canonicalise(seen.getParamSet()));

        List<Map<String, Object>> nextCombos = new ArrayList<>();
        int producedCandidates = collectRefinedCombos(state, elites, alreadyPlanned, nextCombos);

        if (producedCandidates > nextCombos.size()) {
            int truncated = (ObjectUtils.isEmpty(state.getRoundsTruncated()) ? 0 : state.getRoundsTruncated())
                    + (producedCandidates - nextCombos.size());
            state.setRoundsTruncated(truncated);
            log.info("Sweep {} round {} produced {} refined candidates, {} kept after dedup+cap",
                    state.getSweepId(), round, producedCandidates, nextCombos.size());
        }
        if (nextCombos.isEmpty()) {
            log.info("Sweep {} converged at round {} — no new combos to explore",
                    state.getSweepId(), round);
            return false;
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
        return true;
    }

    /** Filter to combos with enough trades, sort by rank metric, take the top
     *  {@code elitePct} (defaulting to {@link #DEFAULT_ELITE_PCT}). Falls back
     *  to the full pool when no combo meets the trade-count floor. */
    private List<SweepResult> pickElites(
            SweepState state, List<SweepResult> roundResults, String rankMetric, int round) {
        List<SweepResult> elitePool = roundResults.stream()
                .filter(x -> ObjectUtils.isNotEmpty(x.getTradeCount()) && x.getTradeCount() >= MIN_TRADES_FOR_ELITE)
                .toList();
        if (elitePool.isEmpty()) {
            log.info("Sweep {} round {} — no combos with >= {} trades, using full pool",
                    state.getSweepId(), round, MIN_TRADES_FOR_ELITE);
            elitePool = roundResults;
        }
        BigDecimal elitePct = state.getSpec().getElitePct();
        if (ObjectUtils.isEmpty(elitePct) || elitePct.signum() <= 0) elitePct = DEFAULT_ELITE_PCT;
        int eliteCount = Math.max(1, (int) Math.ceil(elitePool.size() * elitePct.doubleValue()));

        List<SweepResult> elites = new ArrayList<>(elitePool);
        elites.sort(rankComparator(rankMetric));
        return elites.subList(0, Math.min(eliteCount, elites.size()));
    }

    /** Cross-product refine each elite, dedupe via {@code alreadyPlanned},
     *  cap at {@link #MAX_COMBOS_PER_ROUND}. Mutates {@code nextCombos} +
     *  {@code alreadyPlanned} in place. Returns the total candidate count
     *  (some may have been deduped or capped out) so the caller can record
     *  truncation. */
    private int collectRefinedCombos(SweepState state, List<SweepResult> elites,
                                     Set<Map<String, Object>> alreadyPlanned,
                                     List<Map<String, Object>> nextCombos) {
        int producedCandidates = 0;
        for (SweepResult elite : elites) {
            for (Map<String, Object> refined : refineAroundElite(
                    state.getSpec().getParamRanges(), elite.getParamSet())) {
                producedCandidates++;
                Map<String, Object> canonical = canonicalise(refined);
                if (alreadyPlanned.add(canonical)) {
                    nextCombos.add(refined);
                    if (nextCombos.size() >= MAX_COMBOS_PER_ROUND) return producedCandidates;
                }
            }
            if (nextCombos.size() >= MAX_COMBOS_PER_ROUND) return producedCandidates;
        }
        return producedCandidates;
    }

    /** Strips trailing zeros from every BigDecimal in the combo so equality
     *  works across different scales (0.6 vs 0.60). Non-decimal values pass
     *  through unchanged. Returns an empty map for a null input — the only
     *  call sites add the result to a dedupe set, so an empty placeholder
     *  is harmless and keeps the helper null-free for Sonar S1168. */
    static Map<String, Object> canonicalise(Map<String, Object> combo) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (ObjectUtils.isEmpty(combo)) return out;
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
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.error("Sweep combo interrupted | sweepId={} combo={}",
                    state.getSweepId(), r.getParamSet(), ie);
            r.setStatus(STATUS_FAILED);
            r.setErrorMessage(ie.getMessage());
        } catch (Exception e) {
            log.error("Sweep combo failed | sweepId={} combo={}",
                    state.getSweepId(), r.getParamSet(), e);
            r.setStatus(STATUS_FAILED);
            r.setErrorMessage(e.getMessage());
        }
    }

    private int indexOfFirstInRound(List<SweepResult> results, int round) {
        for (int i = 0; i < results.size(); i++) {
            if (ObjectUtils.isNotEmpty(results.get(i).getRound()) && results.get(i).getRound() == round) {
                return i;
            }
        }
        return -1;
    }

    private void executeOne(SweepState state, SweepResult result) throws InterruptedException {
        SweepSpec spec = state.getSpec();
        if ("WALK_FORWARD_K".equalsIgnoreCase(spec.getSplitMode())) {
            executeWalkForwardK(state, result, spec);
        } else if ("TRAIN_OOS".equalsIgnoreCase(spec.getSplitMode())) {
            executeTrainOos(state, result, spec);
        } else {
            executeSingle(state, result, spec);
        }
    }

    private void executeSingle(SweepState state, SweepResult result, SweepSpec spec) throws InterruptedException {
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
        if (ObjectUtils.isEmpty(completed)) {
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
    private void executeTrainOos(SweepState state, SweepResult result, SweepSpec spec) throws InterruptedException {
        long startNanos = System.nanoTime();
        result.setStatus(STATUS_RUNNING);
        persist(state);

        // The "sweep end" is the start of the holdout slice (or spec.toDate
        // when holdout is disabled). The holdout slice is reserved — sweep
        // optimization is forbidden from running over it. evaluateHoldout()
        // is the only entry point that touches it.
        java.time.LocalDateTime sweepEnd = ObjectUtils.isNotEmpty(state.getHoldoutFromDate())
                ? state.getHoldoutFromDate()
                : spec.getToDate();

        BigDecimal pct = spec.getOosFractionPct();
        if (ObjectUtils.isEmpty(pct)) pct = new BigDecimal("30");
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
        if (ObjectUtils.isEmpty(trainCompleted)) {
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
                    + (ObjectUtils.isNotEmpty(trainCompleted.getNotes()) ? trainCompleted.getNotes() : "unknown"));
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
        if (ObjectUtils.isEmpty(oosCompleted)) {
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
    private void executeWalkForwardK(SweepState state, SweepResult result, SweepSpec spec) throws InterruptedException {
        long startNanos = System.nanoTime();
        result.setStatus(STATUS_RUNNING);
        persist(state);

        int k = ObjectUtils.isEmpty(spec.getWalkForwardWindows()) ? 4 : spec.getWalkForwardWindows();
        java.time.LocalDateTime sweepEnd = ObjectUtils.isNotEmpty(state.getHoldoutFromDate())
                ? state.getHoldoutFromDate()
                : spec.getToDate();
        // Same OOS-fraction semantics as TRAIN_OOS / validation. Defaults
        // to 30% when the user didn't supply one (matches TRAIN_OOS default).
        double oosFraction = (ObjectUtils.isNotEmpty(spec.getOosFractionPct())
                ? spec.getOosFractionPct().doubleValue() : 30.0) / 100.0;
        java.util.List<WalkForwardWindowing.Fold> foldPlan =
                WalkForwardWindowing.buildFolds(spec.getFromDate(), sweepEnd, k, oosFraction);

        java.util.List<WindowResult> folds = new java.util.ArrayList<>(k);
        java.util.List<Double> oosSharpes = new java.util.ArrayList<>(k);

        for (int i = 0; i < k; i++) {
            WindowResult fold = runOneFold(state, result, spec, foldPlan.get(i), i + 1);
            folds.add(fold);
            if (ObjectUtils.isNotEmpty(fold.getOosSharpeRatio())) {
                oosSharpes.add(fold.getOosSharpeRatio().doubleValue());
            }
        }

        result.setWindowResults(folds);
        result.setElapsedMs(Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
        result.setProgressPercent(100);

        if (oosSharpes.isEmpty()) {
            result.setStatus(STATUS_FAILED);
            result.setErrorMessage("All " + k + " walk-forward folds failed");
            return;
        }

        aggregateFoldStatistics(result, folds, oosSharpes);
        result.setStatus(STATUS_COMPLETED);
    }

    /** Run one walk-forward fold (train + OOS). Returns a populated {@link WindowResult}
     *  whose status is COMPLETED on success or FAILED when either leg failed —
     *  bad folds don't poison the rest of the sweep. */
    private WindowResult runOneFold(SweepState state, SweepResult result, SweepSpec spec,
                                    WalkForwardWindowing.Fold plan, int foldIndex)
            throws InterruptedException {
        WindowResult fold = WindowResult.builder()
                .foldIndex(foldIndex)
                .trainFromDate(plan.trainFromDate())
                .trainToDate(plan.trainToDate())
                .oosFromDate(plan.oosFromDate())
                .oosToDate(plan.oosToDate())
                .build();

        FoldLegOutcome train = runFoldLeg(state, result, spec, plan.trainFromDate(), plan.trainToDate());
        // Stamp the runId regardless of completion so the operator can navigate
        // to the (timed-out / failed) run from the fold UI.
        fold.setTrainBacktestRunId(train.runId());
        if (ObjectUtils.isEmpty(train.completed())
                || !STATUS_COMPLETED.equalsIgnoreCase(train.completed().getStatus())) {
            fold.setStatus(STATUS_FAILED);
            return fold;
        }
        fold.setTrainSharpeRatio(train.completed().getSharpeRatio());

        FoldLegOutcome oos = runFoldLeg(state, result, spec, plan.oosFromDate(), plan.oosToDate());
        fold.setOosBacktestRunId(oos.runId());
        if (ObjectUtils.isEmpty(oos.completed())
                || !STATUS_COMPLETED.equalsIgnoreCase(oos.completed().getStatus())) {
            fold.setStatus(STATUS_FAILED);
            return fold;
        }
        BacktestRun oosCompleted = oos.completed();
        fold.setOosSharpeRatio(oosCompleted.getSharpeRatio());
        fold.setOosPsr(oosCompleted.getPsr());
        fold.setOosNetPnl(oosCompleted.getNetProfit());
        fold.setOosTradeCount(oosCompleted.getTotalTrades());
        fold.setStatus(STATUS_COMPLETED);
        return fold;
    }

    /** Submitted runId paired with the wait outcome. {@code completed} is
     *  null when {@link #waitForRun} timed out — the {@code runId} is still
     *  populated so callers can record it on the fold for navigation. */
    private record FoldLegOutcome(UUID runId, BacktestRun completed) {}

    /** Submit + wait for one backtest leg of a walk-forward fold. Mirrors
     *  the run id onto {@code result} so the live progress bar tracks it. */
    private FoldLegOutcome runFoldLeg(SweepState state, SweepResult result, SweepSpec spec,
                                      java.time.LocalDateTime from, java.time.LocalDateTime to)
            throws InterruptedException {
        BacktestRunRequest req = buildRequest(spec, result.getParamSet());
        req.setStartTime(from);
        req.setEndTime(to);
        BacktestRunResponse submit = backtestService.runBacktest(state.getUserId(), req);
        UUID runId = submit.getBacktestRunId();
        result.setBacktestRunId(runId);
        persist(state);
        return new FoldLegOutcome(runId, waitForRun(runId, state, result));
    }

    /** Aggregate across folds. Mean OOS Sharpe mirrors to legacy slots so
     *  leaderboard sort + DSR threshold operate on it; stddev is the
     *  regime-sensitivity diagnostic surfaced alongside. Pulls the rest of
     *  the headline metrics from the last successful OOS run so non-Sharpe
     *  columns (winRate, profitFactor, avgR, etc.) still populate. */
    private void aggregateFoldStatistics(SweepResult result, List<WindowResult> folds, List<Double> oosSharpes) {
        double[] arr = oosSharpes.stream().mapToDouble(Double::doubleValue).toArray();
        BigDecimal mean = BigDecimal.valueOf(SharpeStatistics.mean(arr))
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal sd = arr.length >= 2
                ? BigDecimal.valueOf(SharpeStatistics.stddev(arr)).setScale(4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        result.setMeanOosSharpe(mean);
        result.setStddevOosSharpe(sd);
        result.setSharpeRatio(mean);

        WindowResult lastSuccessful = findLastSuccessfulFold(folds);
        if (ObjectUtils.isNotEmpty(lastSuccessful) && ObjectUtils.isNotEmpty(lastSuccessful.getOosBacktestRunId())) {
            BacktestRun lastRun = runRepository.findById(lastSuccessful.getOosBacktestRunId()).orElse(null);
            if (ObjectUtils.isNotEmpty(lastRun)) {
                populateMetrics(result, lastRun);
                // populateMetrics overwrites sharpeRatio with the single-fold
                // value — restore the cohort mean.
                result.setSharpeRatio(mean);
                result.setPsr(lastRun.getPsr());
            }
        }
    }

    private static WindowResult findLastSuccessfulFold(List<WindowResult> folds) {
        for (int i = folds.size() - 1; i >= 0; i--) {
            if (STATUS_COMPLETED.equalsIgnoreCase(folds.get(i).getStatus())) {
                return folds.get(i);
            }
        }
        return null;
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
            if (ObjectUtils.isNotEmpty(run)) {
                Integer pct = run.getProgressPercent();
                if (ObjectUtils.isNotEmpty(pct) && !pct.equals(result.getProgressPercent())) {
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
        if (ObjectUtils.isEmpty(state) || CollectionUtils.isEmpty(state.getResults())) return;
        double[] sharpes = state.getResults().stream()
                .filter(r -> STATUS_COMPLETED.equalsIgnoreCase(r.getStatus()))
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
        if (ObjectUtils.isNotEmpty(spec.getFixedParams())) merged.putAll(spec.getFixedParams());
        merged.putAll(paramSet);

        Map<String, Map<String, Object>> overrides = new LinkedHashMap<>();
        overrides.put(spec.getStrategyCode(), merged);
        req.setStrategyParamOverrides(overrides);
        return req;
    }

    // ── Research-mode helpers ───────────────────────────────────────────────

    private int resolveRounds(SweepSpec spec) {
        Integer r = spec.getRounds();
        if (ObjectUtils.isEmpty(r) || r <= 1) return 1;
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
        if (ObjectUtils.isEmpty(ranges) || ObjectUtils.isEmpty(elite)) return List.of();
        Map<String, List<Object>> perKey = new LinkedHashMap<>();
        for (Map.Entry<String, ParamRange> e : ranges.entrySet()) {
            List<Object> refined = refinedValuesForKey(e.getValue(), elite.get(e.getKey()));
            if (!refined.isEmpty()) {
                perKey.put(e.getKey(), refined);
            }
        }
        return expandGrid(perKey);
    }

    private static List<Object> refinedValuesForKey(ParamRange range, Object seedRaw) {
        BigDecimal seed = toDecimal(seedRaw);
        if (ObjectUtils.isEmpty(seed)) return List.of();
        List<BigDecimal> vals = range.refineAround(seed, 1);
        return vals.isEmpty() ? List.of() : new ArrayList<>(vals);
    }

    private static BigDecimal toDecimal(Object v) {
        if (ObjectUtils.isEmpty(v)) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        try { return new BigDecimal(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    /** Descending sort on the selected rank metric; nulls/NaN sort last. */
    private Comparator<SweepResult> rankComparator(String metric) {
        return (a, b) -> {
            BigDecimal av = pickMetric(a, metric);
            BigDecimal bv = pickMetric(b, metric);
            if (ObjectUtils.isEmpty(av) && ObjectUtils.isEmpty(bv)) return 0;
            if (ObjectUtils.isEmpty(av)) return 1;
            if (ObjectUtils.isEmpty(bv)) return -1;
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
        int rounds = ObjectUtils.isEmpty(spec.getRounds()) ? 1 : spec.getRounds();
        if (rounds > 1 && ObjectUtils.isNotEmpty(spec.getParamRanges())) {
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
        validateRequiredFields(spec);

        boolean trainOosMode = "TRAIN_OOS".equalsIgnoreCase(spec.getSplitMode());
        boolean walkForwardMode = "WALK_FORWARD_K".equalsIgnoreCase(spec.getSplitMode());
        BigDecimal holdoutPct = spec.getHoldoutFractionPct();
        if (ObjectUtils.isNotEmpty(holdoutPct) && !trainOosMode && !walkForwardMode) {
            throw new IllegalArgumentException(
                    "holdoutFractionPct only applies with splitMode TRAIN_OOS or WALK_FORWARD_K");
        }

        if (trainOosMode)    validateTrainOosMode(spec, holdoutPct);
        if (walkForwardMode) validateWalkForwardMode(spec, holdoutPct);

        validateTotalRunsCap(spec, trainOosMode, walkForwardMode);
        validateRoundsAndKeys(spec);
        validateFixedParamsKeys(spec);
    }

    private static void validateRequiredFields(SweepSpec spec) {
        if (ObjectUtils.isEmpty(spec)) throw new IllegalArgumentException("spec required");
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
        if (ObjectUtils.isEmpty(spec.getFromDate()) || ObjectUtils.isEmpty(spec.getToDate())) {
            throw new IllegalArgumentException("fromDate / toDate required");
        }
        if (!spec.getFromDate().isBefore(spec.getToDate())) {
            throw new IllegalArgumentException("fromDate must be before toDate");
        }
        if (ObjectUtils.isEmpty(spec.getAccountStrategyId())) {
            throw new IllegalArgumentException("accountStrategyId required");
        }
        if (ObjectUtils.isEmpty(spec.getInitialCapital()) || spec.getInitialCapital().signum() <= 0) {
            throw new IllegalArgumentException("initialCapital must be positive");
        }
    }

    /** Train/OOS split validation. The fraction is bounded so neither leg
     *  ends up degenerate — at 5% the OOS sample is too short to score, at
     *  60% the train sample is too short to fit. We also require each
     *  window to be at least 7-30 days so the strategy has a chance to
     *  open positions; otherwise the metrics are mostly noise. */
    private static void validateTrainOosMode(SweepSpec spec, BigDecimal holdoutPct) {
        BigDecimal oosPct = spec.getOosFractionPct();
        if (ObjectUtils.isEmpty(oosPct)) oosPct = new BigDecimal("30");
        if (oosPct.compareTo(new BigDecimal("10")) < 0
                || oosPct.compareTo(new BigDecimal("50")) > 0) {
            throw new IllegalArgumentException(
                    "oosFractionPct must be between 10 and 50, got " + oosPct);
        }
        BigDecimal hPct = ObjectUtils.isNotEmpty(holdoutPct) ? holdoutPct : BigDecimal.ZERO;
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

    /** K-fold walk-forward validation. Train head + K OOS slices tile the
     *  available (non-holdout) window. The OOS coverage is controlled by
     *  oosFractionPct (default 30%) — train head is whatever remains. */
    private static void validateWalkForwardMode(SweepSpec spec, BigDecimal holdoutPct) {
        int k = ObjectUtils.isEmpty(spec.getWalkForwardWindows()) ? 4 : spec.getWalkForwardWindows();
        if (k < 2 || k > 8) {
            throw new IllegalArgumentException(
                    "walkForwardWindows must be between 2 and 8, got " + k);
        }
        BigDecimal hPct = ObjectUtils.isNotEmpty(holdoutPct) ? holdoutPct : BigDecimal.ZERO;
        if (hPct.signum() < 0 || hPct.compareTo(new BigDecimal("40")) > 0) {
            throw new IllegalArgumentException(
                    "holdoutFractionPct must be between 0 and 40, got " + hPct);
        }
        BigDecimal oosPctRaw = ObjectUtils.isNotEmpty(spec.getOosFractionPct())
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

    /** Total-runs hard cap. Walk-forward modes multiply run count per combo,
     *  so we estimate before submission and reject grids that would queue
     *  runaway-cluster work. */
    private void validateTotalRunsCap(SweepSpec spec, boolean trainOosMode, boolean walkForwardMode) {
        int runsPerCombo = runsPerCombo(spec, trainOosMode, walkForwardMode);
        int estimatedCombos = estimateTotalCombos(spec);
        if ((long) estimatedCombos * runsPerCombo > MAX_TOTAL_RUNS) {
            throw new IllegalArgumentException(
                    "Sweep would queue " + ((long) estimatedCombos * runsPerCombo)
                            + " backtests (cap " + MAX_TOTAL_RUNS + "). Lower the grid, lower K, or split into smaller sweeps.");
        }
    }

    private void validateRoundsAndKeys(SweepSpec spec) {
        int rounds = ObjectUtils.isEmpty(spec.getRounds()) ? 1 : spec.getRounds();
        Set<String> knownKeys = knownParamKeysFor(spec.getStrategyCode());
        if (rounds > 1) {
            validateMultiRoundRanges(spec, rounds, knownKeys);
        } else {
            validateFlatGrid(spec, knownKeys);
        }
    }

    private void validateMultiRoundRanges(SweepSpec spec, int rounds, Set<String> knownKeys) {
        if (rounds > MAX_ROUNDS) {
            throw new IllegalArgumentException("rounds capped at " + MAX_ROUNDS);
        }
        if (CollectionUtils.isEmpty(spec.getParamRanges())) {
            throw new IllegalArgumentException(
                    "paramRanges required for multi-round research sweeps");
        }
        for (Map.Entry<String, ParamRange> e : spec.getParamRanges().entrySet()) {
            ParamRange r = e.getValue();
            if (ObjectUtils.isEmpty(r) || ObjectUtils.isEmpty(r.getMin()) || ObjectUtils.isEmpty(r.getMax()) || ObjectUtils.isEmpty(r.getStep())
                    || r.getStep().signum() <= 0
                    || r.getMin().compareTo(r.getMax()) > 0) {
                throw new IllegalArgumentException(
                        "Invalid range for " + e.getKey() + " — need min/max/step and min≤max");
            }
            rejectUnknownKey(e.getKey(), knownKeys, spec.getStrategyCode());
        }
    }

    private void validateFlatGrid(SweepSpec spec, Set<String> knownKeys) {
        if (CollectionUtils.isEmpty(spec.getParamGrid())) {
            throw new IllegalArgumentException("paramGrid must contain at least one varied key");
        }
        for (String key : spec.getParamGrid().keySet()) {
            rejectUnknownKey(key, knownKeys, spec.getStrategyCode());
        }
    }

    /** Fixed params: every key must be known and must NOT also appear as a
     *  swept key. A swept value would always win the merge anyway, but
     *  accepting the collision silently would let users submit a sweep
     *  that ignores half their fixed values. */
    private void validateFixedParamsKeys(SweepSpec spec) {
        Map<String, Object> fixed = spec.getFixedParams();
        if (CollectionUtils.isEmpty(fixed)) return;

        Set<String> knownKeys = knownParamKeysFor(spec.getStrategyCode());
        Set<String> sweptKeys = new HashSet<>();
        if (ObjectUtils.isNotEmpty(spec.getParamRanges())) sweptKeys.addAll(spec.getParamRanges().keySet());
        if (ObjectUtils.isNotEmpty(spec.getParamGrid()))   sweptKeys.addAll(spec.getParamGrid().keySet());
        for (String key : fixed.keySet()) {
            rejectUnknownKey(key, knownKeys, spec.getStrategyCode());
            if (sweptKeys.contains(key)) {
                throw new IllegalArgumentException(
                        "Param '" + key + "' is both swept and pinned — pick one.");
            }
        }
    }

    /** Per-combo backtest count for the {@link #MAX_TOTAL_RUNS} estimate.
     *  WALK_FORWARD_K runs 2K backtests (train + OOS per fold); TRAIN_OOS
     *  runs 2 (train + OOS); single mode runs 1. K defaults to 4 when not
     *  set. Pulled out of {@link #validate} so the ternaries flatten. */
    private static int runsPerCombo(SweepSpec spec, boolean trainOosMode, boolean walkForwardMode) {
        if (walkForwardMode) {
            int k = ObjectUtils.isEmpty(spec.getWalkForwardWindows()) ? 4 : spec.getWalkForwardWindows();
            return 2 * k;
        }
        return trainOosMode ? 2 : 1;
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
                    "adxEntryMin", KEY_ADX_ENTRY_MAX, "diSpreadMin",
                    "pullbackTouchAtr",
                    "longRsiMin", "longRsiMax", "shortRsiMin", "shortRsiMax",
                    "bodyRatioMin", "clvMin", "clvMax", "rvolMin",
                    KEY_STOP_ATR_BUFFER, "maxEntryRiskPct", "tp1R",
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
                    KEY_ADX_ENTRY_MAX, "longRsiMin", "shortRsiMax",
                    "longDiSpreadMin", "shortDiSpreadMin",
                    KEY_STOP_ATR_BUFFER, "tp1R", "maxEntryRiskPct",
                    "runnerHalfR", "runnerBreakEvenR", "runnerPhase2R", "runnerPhase3R",
                    "runnerAtrPhase2", "runnerAtrPhase3",
                    "runnerLockPhase2R", "runnerLockPhase3R",
                    "minSignalScore"
            ),
            "LSR", Set.of(
                    "adxTrendingMin", "adxCompressionMax", "adxEntryMin", KEY_ADX_ENTRY_MAX,
                    "atrRatioExhaustion", "atrRatioChaotic", "atrRatioCompress",
                    KEY_STOP_ATR_BUFFER, "maxRiskPct",
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
        if (ObjectUtils.isEmpty(strategyCode)) return Set.of();
        return KNOWN_PARAM_KEYS.getOrDefault(strategyCode.toUpperCase(), Set.of());
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    private void persist(SweepState state) {
        try {
            Path dir = Path.of(sweepsDir);
            Files.createDirectories(dir);
            Path file = dir.resolve(state.getSweepId() + EXTENSION_JSON);
            Path tmp = dir.resolve(state.getSweepId() + EXTENSION_JSON_TMP);
            // Write to a sibling temp file then atomic-rename so a process kill
            // mid-write can never leave a partially-flushed JSON on disk that
            // would fail to parse on next boot.
            Files.writeString(tmp,
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(state));
            atomicReplace(tmp, file);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize sweep state", e);
        } catch (IOException e) {
            log.error("Failed to write sweep state to {}", sweepsDir, e);
        }
    }

    /** Atomic-rename {@code tmp} onto {@code dest}, falling back to plain
     *  replace on filesystems that reject {@link StandardCopyOption#ATOMIC_MOVE}
     *  (some network mounts). Pulled out so {@link #persist}'s try-block has
     *  no nested try (Sonar S1141). */
    private static void atomicReplace(Path tmp, Path dest) throws IOException {
        try {
            Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFail) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void loadPersistedSweeps() {
        try {
            Path dir = Path.of(sweepsDir);
            if (!Files.exists(dir)) return;
            try (var stream = Files.list(dir)) {
                Iterator<Path> it = stream.iterator();
                while (it.hasNext()) {
                    handleSnapshotPath(it.next());
                }
            }
            log.info("Loaded {} persisted sweep state(s)", sweeps.size());
        } catch (IOException e) {
            log.warn("Could not enumerate sweeps directory {}", sweepsDir, e);
        }
    }

    /**
     * Per-file dispatch from {@link #loadPersistedSweeps}: clean stale
     * {@code .json.tmp} fragments first, skip non-JSON, otherwise rehydrate
     * the sweep snapshot. Combined into one method so the enclosing loop
     * body has no nested try and no chain of {@code continue}s.
     *
     * <p>The {@code .json.tmp} branch must precede the {@code .json} filter:
     * {@code "foo.json.tmp".endsWith(".json")} is false, so checking the
     * .json suffix first would silently drop tmp files into the unhandled
     * bucket and let them accumulate forever.
     */
    private void handleSnapshotPath(Path p) {
        String name = p.getFileName().toString();

        // Stale .tmp left by a kill mid-write — best-effort cleanup, retried
        // next boot if delete fails.
        if (name.endsWith(EXTENSION_JSON_TMP)) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignored) {
                // Tmp deletion is non-critical — retried on next boot.
            }
            return;
        }

        if (!name.endsWith(EXTENSION_JSON)) return;

        loadSweepSnapshot(p);
    }

    /** Read + register a single sweep snapshot, recovering orphaned runs
     *  if the JVM died while the sweep was in flight. Pulled out of
     *  {@link #loadPersistedSweeps} so its outer try has no nested try. */
    private void loadSweepSnapshot(Path p) {
        try {
            byte[] bytes = Files.readAllBytes(p);
            SweepState s = objectMapper.readValue(bytes, SweepState.class);
            // Sweeps that were in-flight at shutdown are stuck — mark them
            // failed so the UI doesn't spinner-forever, and FAIL the orphan
            // backtest_run rows they had submitted (otherwise they sit at
            // PENDING/RUNNING forever in the trades view).
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

    /**
     * For any backtest_run rows linked to this orphaned sweep that are still
     * PENDING or RUNNING in the DB, flip them to FAILED. They can't make
     * progress — the worker thread that owned them is gone with the previous
     * JVM.
     */
    private void failOrphanedRuns(SweepState s) {
        if (ObjectUtils.isEmpty(s.getResults())) return;
        for (SweepResult r : s.getResults()) {
            failOrphanedRun(s.getSweepId(), r.getBacktestRunId());
        }
    }

    /** Best-effort orphan-run cleanup for a single result. Pulled out so the
     *  caller's loop has zero {@code continue} statements (Sonar S135). */
    private void failOrphanedRun(UUID sweepId, UUID runId) {
        if (ObjectUtils.isEmpty(runId)) return;
        try {
            BacktestRun run = runRepository.findById(runId).orElse(null);
            if (ObjectUtils.isEmpty(run)) return;
            String status = run.getStatus();
            if (STATUS_PENDING.equalsIgnoreCase(status)
                    || STATUS_RUNNING.equalsIgnoreCase(status)) {
                run.setStatus(STATUS_FAILED);
                run.setNotes("Orphaned by sweep " + sweepId + " on JVM restart");
                runRepository.save(run);
                log.info("Failed orphan backtest_run {} (was {})", runId, status);
            }
        } catch (Exception e) {
            log.warn("Could not fail orphan run {}", runId, e);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static Integer asInt(Object v) {
        if (ObjectUtils.isEmpty(v)) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    /** Number → BigDecimal via toString to avoid double-precision rounding —
     *  e.g. 0.1 + 0.2 = 0.30000000000000004 if you go through doubleValue(). */
    private static java.math.BigDecimal asDecimal(Object v) {
        if (ObjectUtils.isEmpty(v)) return null;
        if (v instanceof java.math.BigDecimal bd) return bd;
        if (v instanceof Number n) {
            try {
                return new java.math.BigDecimal(n.toString());
            } catch (NumberFormatException ignored) {
                // Number → toString() should always parse, but Number is an
                // open hierarchy and a custom subclass could return junk.
                // Fall through to the generic toString attempt below.
            }
        }
        try { return new java.math.BigDecimal(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    /** Read-only view of all known sweep ids (used by tests / admin tools). */
    public Collection<UUID> knownIds() {
        return sweeps.keySet();
    }
}

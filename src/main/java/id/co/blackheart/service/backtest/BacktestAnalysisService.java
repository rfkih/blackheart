package id.co.blackheart.service.backtest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.model.BacktestRun;
import id.co.blackheart.model.BacktestTrade;
import id.co.blackheart.repository.BacktestRunRepository;
import id.co.blackheart.repository.BacktestTradeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Computes the diagnostic report we've been generating by hand after every
 * backtest run — headline metrics, feature-bucket winrates, MFE-capture stats,
 * best / worst trades. Output is a JSON blob stored on
 * {@link BacktestRun#getAnalysisSnapshot()}.
 *
 * <p>Invoked from {@code BacktestAsyncRunner.runAsync} on completion so every
 * run picks this up automatically; also exposed via
 * {@code GET /api/v1/backtest/:id/analysis} for on-demand recomputation if the
 * bucket definitions or scoring change.
 *
 * <p>Feature-bucket ranges are deliberately hard-coded to match the research
 * buckets we've been using ([18,25,30,35,45] for ADX, etc.). When a different
 * set of buckets becomes relevant, update {@link BucketConfig} — no schema
 * change needed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BacktestAnalysisService {

    private final BacktestRunRepository runRepository;
    private final BacktestTradeRepository tradeRepository;
    private final ObjectMapper objectMapper;

    /**
     * Compute + persist + return the analysis for a run. Idempotent: running
     * twice on the same run just overwrites the snapshot column. Failures
     * (e.g. run still in progress, no trades) are logged and return an
     * "empty" report rather than throwing — the caller chain
     * (BacktestAsyncRunner → markCompleted) shouldn't cascade-fail on
     * analysis problems.
     */
    @Transactional
    public AnalysisReport analyze(UUID backtestRunId) {
        BacktestRun run = runRepository.findById(backtestRunId).orElse(null);
        if (run == null) {
            log.warn("Analysis skipped — run not found: {}", backtestRunId);
            return AnalysisReport.empty(backtestRunId);
        }

        List<BacktestTrade> trades = tradeRepository.findAllByBacktestRunId(backtestRunId);
        AnalysisReport report = compute(run, trades);

        try {
            run.setAnalysisSnapshot(objectMapper.writeValueAsString(report));
            runRepository.save(run);
        } catch (JsonProcessingException e) {
            // Don't fail the entire backtest completion path over a serialization bug.
            log.error("Failed to serialize analysis for run {}", backtestRunId, e);
        }
        return report;
    }

    private AnalysisReport compute(BacktestRun run, List<BacktestTrade> trades) {
        AnalysisReport report = new AnalysisReport();
        report.setBacktestRunId(run.getBacktestRunId());
        report.setStrategyCode(run.getStrategyCode());
        report.setStrategyVersion(run.getStrategyVersion());
        report.setAsset(run.getAsset());
        report.setInterval(run.getInterval());
        report.setTradeCount(trades.size());

        if (trades.isEmpty()) {
            report.setHeadline(Headline.zero());
            return report;
        }

        report.setHeadline(computeHeadline(run, trades));
        report.setBuckets(computeBuckets(trades));
        report.setMfeCapture(computeMfeCapture(trades));
        report.setBestTrades(topTrades(trades, /*descending*/ true, 5));
        report.setWorstTrades(topTrades(trades, false, 5));
        return report;
    }

    // ── Headline ─────────────────────────────────────────────────────────────

    private Headline computeHeadline(BacktestRun run, List<BacktestTrade> trades) {
        int wins = 0;
        int losses = 0;
        BigDecimal grossProfit = BigDecimal.ZERO;
        BigDecimal grossLoss = BigDecimal.ZERO;
        BigDecimal totalR = BigDecimal.ZERO;
        int rCount = 0;

        BigDecimal eq = BigDecimal.ZERO;
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDd = BigDecimal.ZERO;
        int maxConsecLosses = 0;
        int cur = 0;

        List<BacktestTrade> chrono = new ArrayList<>(trades);
        chrono.sort(Comparator.comparing(BacktestTrade::getEntryTime,
                Comparator.nullsLast(Comparator.naturalOrder())));

        for (BacktestTrade t : chrono) {
            BigDecimal pnl = safe(t.getRealizedPnlAmount());
            if (pnl.signum() > 0) {
                wins++;
                grossProfit = grossProfit.add(pnl);
                cur = 0;
            } else if (pnl.signum() < 0) {
                losses++;
                grossLoss = grossLoss.add(pnl);
                cur++;
                if (cur > maxConsecLosses) maxConsecLosses = cur;
            }

            if (t.getRealizedRMultiple() != null) {
                totalR = totalR.add(t.getRealizedRMultiple());
                rCount++;
            }

            eq = eq.add(pnl);
            if (eq.compareTo(peak) > 0) peak = eq;
            BigDecimal dd = eq.subtract(peak);
            if (dd.compareTo(maxDd) < 0) maxDd = dd;
        }

        int total = trades.size();
        BigDecimal winRate = total > 0
                ? new BigDecimal(wins).divide(new BigDecimal(total), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal pf = grossLoss.signum() < 0
                ? grossProfit.divide(grossLoss.abs(), 4, RoundingMode.HALF_UP)
                : null; // undefined when there are no losses — caller renders "∞"
        BigDecimal avgR = rCount > 0
                ? totalR.divide(new BigDecimal(rCount), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal avgWin = wins > 0
                ? grossProfit.divide(new BigDecimal(wins), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal avgLoss = losses > 0
                ? grossLoss.divide(new BigDecimal(losses), 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        Headline h = new Headline();
        h.setTradeCount(total);
        h.setWins(wins);
        h.setLosses(losses);
        h.setWinRate(winRate);
        h.setProfitFactor(pf);
        h.setAvgR(avgR);
        h.setAvgWin(avgWin);
        h.setAvgLoss(avgLoss);
        h.setGrossProfit(grossProfit);
        h.setGrossLoss(grossLoss);
        h.setNetPnl(eq);
        h.setPeakEquity(peak);
        h.setMaxDrawdown(maxDd);
        h.setMaxConsecutiveLosses(maxConsecLosses);
        h.setInitialCapital(safe(run.getInitialCapital()));
        return h;
    }

    // ── Feature buckets ──────────────────────────────────────────────────────

    /** (lower inclusive, upper exclusive) — the ranges we've been eyeballing
     *  manually. Keep these in sync with what the analyses in chat use. */
    private static final List<BucketConfig> BUCKETS = List.of(
            new BucketConfig("entry_adx", "Entry ADX", BacktestTrade::getEntryAdx,
                    new double[][]{{18,25},{25,30},{30,35},{35,45}}),
            new BucketConfig("bias_adx", "Bias (4H) ADX", BacktestTrade::getBiasAdx,
                    new double[][]{{0,20},{20,25},{25,30},{30,35},{35,40},{40,60}}),
            new BucketConfig("entry_rsi", "Entry RSI", BacktestTrade::getEntryRsi,
                    new double[][]{{30,38},{38,45},{45,50},{50,55},{55,60},{60,70}}),
            new BucketConfig("entry_clv", "Close-Location-Value",
                    BacktestTrade::getEntryCloseLocationValue,
                    new double[][]{{0.0,0.6},{0.6,0.7},{0.7,0.8},{0.8,0.9},{0.9,1.01}}),
            new BucketConfig("entry_rvol", "Relative Volume",
                    BacktestTrade::getEntryRelativeVolume20,
                    new double[][]{{0.0,1.0},{1.0,1.3},{1.3,1.8},{1.8,3.0},{3.0,10.0}})
    );

    private Map<String, List<BucketRow>> computeBuckets(List<BacktestTrade> trades) {
        Map<String, List<BucketRow>> out = new LinkedHashMap<>();
        for (BucketConfig cfg : BUCKETS) {
            List<BucketRow> rows = new ArrayList<>(cfg.ranges().length);
            for (double[] range : cfg.ranges()) {
                double lo = range[0];
                double hi = range[1];
                int n = 0;
                int wn = 0;
                BigDecimal total = BigDecimal.ZERO;
                for (BacktestTrade t : trades) {
                    BigDecimal v = cfg.extractor().apply(t);
                    if (v == null) continue;
                    double d = v.doubleValue();
                    if (d < lo || d >= hi) continue;
                    n++;
                    BigDecimal pnl = safe(t.getRealizedPnlAmount());
                    if (pnl.signum() > 0) wn++;
                    total = total.add(pnl);
                }
                if (n == 0) continue;
                BucketRow row = new BucketRow();
                row.setLow(lo);
                row.setHigh(hi);
                row.setCount(n);
                row.setWins(wn);
                row.setWinRate(BigDecimal.valueOf(wn)
                        .divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP));
                row.setTotalPnl(total);
                rows.add(row);
            }
            out.put(cfg.key(), rows);
        }
        return out;
    }

    // ── MFE capture ──────────────────────────────────────────────────────────

    private MfeCapture computeMfeCapture(List<BacktestTrade> trades) {
        List<BigDecimal> captures = new ArrayList<>();
        List<BigDecimal> winnerMfes = new ArrayList<>();
        List<BigDecimal> loserMaes = new ArrayList<>();
        for (BacktestTrade t : trades) {
            BigDecimal pnl = safe(t.getRealizedPnlAmount());
            BigDecimal mfe = t.getMaxFavorableExcursionR();
            BigDecimal mae = t.getMaxAdverseExcursionR();
            BigDecimal rmul = t.getRealizedRMultiple();
            if (pnl.signum() > 0 && mfe != null && mfe.signum() > 0 && rmul != null) {
                BigDecimal cap = rmul.divide(mfe, 4, RoundingMode.HALF_UP);
                captures.add(cap);
                winnerMfes.add(mfe);
            }
            if (pnl.signum() < 0 && mae != null) {
                loserMaes.add(mae);
            }
        }
        MfeCapture m = new MfeCapture();
        m.setWinnerCaptureAvg(avg(captures));
        m.setWinnerCaptureMin(min(captures));
        m.setWinnerCaptureMax(max(captures));
        m.setWinnerMfeAvg(avg(winnerMfes));
        m.setLoserMaeAvg(avg(loserMaes));
        m.setLoserMaeMedian(median(loserMaes));
        return m;
    }

    // ── Top trades ───────────────────────────────────────────────────────────

    private List<TradeSnapshot> topTrades(List<BacktestTrade> trades, boolean descending, int limit) {
        List<BacktestTrade> sorted = new ArrayList<>(trades);
        Comparator<BacktestTrade> cmp = Comparator.comparing(
                t -> safe(t.getRealizedPnlAmount()),
                Comparator.nullsLast(Comparator.naturalOrder()));
        if (descending) cmp = cmp.reversed();
        sorted.sort(cmp);

        List<TradeSnapshot> out = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, sorted.size()); i++) {
            BacktestTrade t = sorted.get(i);
            TradeSnapshot s = new TradeSnapshot();
            s.setTradeId(t.getBacktestTradeId());
            s.setEntryTime(t.getEntryTime() != null ? t.getEntryTime().toString() : null);
            s.setSide(t.getSide());
            s.setPnl(safe(t.getRealizedPnlAmount()));
            s.setR(t.getRealizedRMultiple());
            s.setEntryAdx(t.getEntryAdx());
            s.setEntryRsi(t.getEntryRsi());
            s.setEntryClv(t.getEntryCloseLocationValue());
            s.setEntryRvol(t.getEntryRelativeVolume20());
            s.setBiasAdx(t.getBiasAdx());
            s.setMfeR(t.getMaxFavorableExcursionR());
            s.setMaeR(t.getMaxAdverseExcursionR());
            s.setExitReason(t.getExitReason());
            out.add(s);
        }
        return out;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static BigDecimal safe(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private static BigDecimal avg(List<BigDecimal> xs) {
        if (xs.isEmpty()) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal x : xs) sum = sum.add(x);
        return sum.divide(new BigDecimal(xs.size()), 4, RoundingMode.HALF_UP);
    }

    private static BigDecimal min(List<BigDecimal> xs) {
        if (xs.isEmpty()) return null;
        BigDecimal m = xs.get(0);
        for (BigDecimal x : xs) if (x.compareTo(m) < 0) m = x;
        return m;
    }

    private static BigDecimal max(List<BigDecimal> xs) {
        if (xs.isEmpty()) return null;
        BigDecimal m = xs.get(0);
        for (BigDecimal x : xs) if (x.compareTo(m) > 0) m = x;
        return m;
    }

    private static BigDecimal median(List<BigDecimal> xs) {
        if (xs.isEmpty()) return null;
        List<BigDecimal> s = new ArrayList<>(xs);
        s.sort(Comparator.naturalOrder());
        return s.get(s.size() / 2);
    }

    // ── Config record for bucket ranges ──────────────────────────────────────

    private record BucketConfig(
            String key,
            String label,
            Function<BacktestTrade, BigDecimal> extractor,
            double[][] ranges
    ) {
        public BucketConfig {
            ranges = Arrays.copyOf(ranges, ranges.length);
        }
    }
}

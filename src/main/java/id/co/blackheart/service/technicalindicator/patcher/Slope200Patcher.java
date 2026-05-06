package id.co.blackheart.service.technicalindicator.patcher;

import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.MarketData;
import id.co.blackheart.repository.MarketDataRepository;
import id.co.blackheart.service.technicalindicator.BarSeriesUtil;
import id.co.blackheart.util.MapperUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Patches {@code slope_200} — the 200-bar EMA200 slope, used by every
 * trend-aware strategy. The canonical patcher implementation, used as the
 * shape template for any future TA4j-driven {@link FeaturePatcher}.
 *
 * <p>Loads market_data with a generous 40-day calendar warmup before each
 * window — enough for EMA200 saturation at any interval (5m → ~11k bars,
 * 1d → 40 bars). The aux holds the precomputed EMA200 series + an index
 * lookup so per-row patching is O(1).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Slope200Patcher extends FeaturePatcher<Slope200Patcher.Aux> {

    private static final int WARMUP_CALENDAR_DAYS = 40;
    private static final int EMA_PERIOD = 200;

    private final MarketDataRepository marketDataRepository;
    private final MapperUtil mapperUtil;

    @Override
    public String primaryColumn() {
        return "slope_200";
    }

    @Override
    public Aux buildAux(String symbol, String interval,
                        LocalDateTime windowStart, LocalDateTime windowEnd) {
        // Use the start_time-range query so the boundary bar (start_time ==
        // windowEnd) is included — otherwise it falls between two windows
        // and never gets patched.
        List<MarketData> data = marketDataRepository.findBySymbolIntervalAndStartTimeRange(
                symbol, interval, windowStart.minusDays(WARMUP_CALENDAR_DAYS), windowEnd);
        if (data == null || data.size() < 2) {
            log.debug("Slope200Patcher: insufficient market_data for {}/{} window [{} → {}]",
                    symbol, interval, windowStart, windowEnd);
            return null;
        }
        data.sort(Comparator.comparing(MarketData::getStartTime));

        BarSeries series = BarSeriesUtil.toBarSeries(data, interval, mapperUtil);
        EMAIndicator ema200 = new EMAIndicator(new ClosePriceIndicator(series), EMA_PERIOD);

        Map<LocalDateTime, Integer> idxByTime = new HashMap<>(data.size() * 2);
        for (int i = 0; i < data.size(); i++) {
            idxByTime.put(data.get(i).getStartTime(), i);
        }
        return new Aux(ema200, idxByTime);
    }

    @Override
    public void patchRow(FeatureStore row, Aux aux) {
        Integer idx = aux.idxByTime.get(row.getStartTime());
        if (idx == null) {
            // Row's start_time isn't in the loaded window — could happen at
            // a boundary if market_data has a gap exactly at the patch row.
            // Skip rather than throw; the framework will report it as
            // unpatched and the operator can re-fetch market_data first.
            return;
        }

        BigDecimal slope200;
        if (idx >= EMA_PERIOD) {
            slope200 = BarSeriesUtil.toBigDecimal(aux.ema200.getValue(idx).minus(aux.ema200.getValue(idx - EMA_PERIOD)))
                    .divide(BigDecimal.valueOf(EMA_PERIOD), BarSeriesUtil.DEFAULT_SCALE, RoundingMode.HALF_UP);
        } else if (idx > 0) {
            slope200 = BarSeriesUtil.toBigDecimal(aux.ema200.getValue(idx).minus(aux.ema200.getValue(0)))
                    .divide(BigDecimal.valueOf(idx), BarSeriesUtil.DEFAULT_SCALE, RoundingMode.HALF_UP);
        } else {
            slope200 = BigDecimal.ZERO;
        }
        row.setSlope200(slope200);
    }

    /** Per-window auxiliary state — carries EMA200 + a start_time → bar-index map. */
    public record Aux(EMAIndicator ema200, Map<LocalDateTime, Integer> idxByTime) {
    }
}

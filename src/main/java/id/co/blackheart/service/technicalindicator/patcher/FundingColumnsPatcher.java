package id.co.blackheart.service.technicalindicator.patcher;

import id.co.blackheart.model.FeatureStore;
import id.co.blackheart.model.FundingRate;
import id.co.blackheart.repository.FundingRateRepository;
import id.co.blackheart.service.funding.FundingRateService;
import id.co.blackheart.service.funding.FundingRateService.FundingFeatureSnapshot;
import id.co.blackheart.util.MapperUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Patches the three funding-rate columns ({@code funding_rate_8h},
 * {@code funding_rate_7d_avg}, {@code funding_rate_z}) from
 * {@code funding_rate_history}. Demonstrates the {@link FeaturePatcher}
 * pattern for non-OHLC indicators — the aux holds a {@link FundingRate}
 * series rather than a {@link org.ta4j.core.BarSeries}.
 *
 * <p>Discovery keys on {@code funding_rate_8h} as the primary column. All
 * three columns are written together from the same FundingRate series, so
 * the framework finds rows missing the primary and patches the bundle.
 *
 * <p>No market_data warmup needed — the funding service computes its 8h /
 * 7d / z-score features from a full prior FundingRate series loaded once
 * per window via {@link FundingRateRepository#findAllUpTo}.
 *
 * <p>Cold-start safe: returns null aux when no funding history exists for
 * the symbol (e.g. spot pair, or perp before its first scrape). The
 * orchestrator skips the window without error.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FundingColumnsPatcher extends FeaturePatcher<FundingColumnsPatcher.Aux> {

    private final FundingRateRepository fundingRateRepository;
    private final FundingRateService fundingRateService;
    private final MapperUtil mapperUtil;

    @Override
    public String primaryColumn() {
        return "funding_rate_8h";
    }

    @Override
    public List<String> writtenColumns() {
        return List.of("funding_rate_8h", "funding_rate_7d_avg", "funding_rate_z");
    }

    @Override
    public Aux buildAux(String symbol, String interval,
                        LocalDateTime windowStart, LocalDateTime windowEnd) {
        // Look one bar past windowEnd so funding events landing inside
        // (windowEnd, windowEnd + interval] are visible to the last bar in
        // this window. The bar at start_time = windowEnd has end_time =
        // windowEnd + interval and the live path uses findLatest(symbol,
        // end_time) with <= semantics; without this overshoot the bulk
        // patch would miss a fresh funding event right at the boundary
        // and diverge from the live computation.
        long intervalMinutes = mapperUtil.getIntervalMinutes(interval);
        LocalDateTime auxBoundary = windowEnd.plusMinutes(intervalMinutes);
        List<FundingRate> series = fundingRateRepository.findAllUpTo(symbol, auxBoundary);
        if (series.isEmpty()) {
            log.warn("FundingColumnsPatcher: no funding_rate_history for {} (cold-start?). Skipping window.",
                    symbol);
            return null;
        }
        return new Aux(series);
    }

    @Override
    public PatchOutcome patchRow(FeatureStore row, Aux aux) {
        FundingFeatureSnapshot snap = fundingRateService.computeFundingFeaturesFromSeries(
                aux.series, row.getEndTime());
        if (snap.rate8h() == null) {
            // Row's end_time predates the first funding event in the series.
            // Leaving the columns NULL is the correct outcome — overwriting
            // existing nulls with new nulls would still register as "patched"
            // in the metrics and obscure that real source data is missing.
            return PatchOutcome.NOT_FILLED;
        }
        row.setFundingRate8h(snap.rate8h());
        row.setFundingRate7dAvg(snap.rate7dAvg());
        row.setFundingRateZ(snap.rateZ());
        return PatchOutcome.FILLED;
    }

    /** Per-window funding-rate series snapshot, frozen at windowEnd. */
    public record Aux(List<FundingRate> series) {
    }
}

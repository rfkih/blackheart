package id.co.blackheart.service.marketdata;

import id.co.blackheart.model.FundingRate;
import id.co.blackheart.service.marketdata.FundingRateService.FundingFeatureSnapshot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 4.8 audit fixes — verify {@link FundingFeatureSnapshot#compute} math.
 *
 * <p>The compute method is package-private; reflection unwraps it so we don't
 * have to widen visibility for the test. Each test feeds a hand-rolled window
 * with a known mean/stddev so the assertion is independent of any production
 * data dependency.
 */
class FundingFeatureSnapshotTest {

    private static FundingFeatureSnapshot invoke(BigDecimal rate8h, List<FundingRate> window) {
        try {
            Method m = FundingFeatureSnapshot.class.getDeclaredMethod(
                    "compute", BigDecimal.class, List.class);
            m.setAccessible(true);
            return (FundingFeatureSnapshot) m.invoke(null, rate8h, window);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static FundingRate fr(double rate, int hourOffset) {
        return FundingRate.builder()
                .symbol("BTCUSDT")
                .fundingTime(LocalDateTime.of(2026, 5, 1, 0, 0).plusHours(hourOffset))
                .fundingRate(BigDecimal.valueOf(rate))
                .build();
    }

    @Test
    void emptyWindowReturnsRate8hOnly() {
        FundingFeatureSnapshot s = invoke(BigDecimal.valueOf(0.0001), List.of());
        assertEquals(BigDecimal.valueOf(0.0001), s.rate8h());
        assertNull(s.rate7dAvg());
        assertNull(s.rateZ());
    }

    @Test
    void nullRate8hReturnsAllNull() {
        FundingFeatureSnapshot s = invoke(null, List.of(fr(0.0001, 0)));
        assertNull(s.rate8h());
        assertNull(s.rate7dAvg());
        assertNull(s.rateZ());
    }

    @Test
    void nLessThanFourLeavesZNull() {
        // n=3 was OK before M1, but post-fix we need n>=4 so peer-set has >=3.
        List<FundingRate> w = List.of(fr(0.0001, 0), fr(0.0002, 8), fr(0.0003, 16));
        FundingFeatureSnapshot s = invoke(BigDecimal.valueOf(0.0003), w);
        assertNotNull(s.rate7dAvg());
        assertNull(s.rateZ(), "z must be null with peer-set < 3");
    }

    @Test
    void constantPeersGiveNullZ() {
        // All peers identical → peer stddev = 0 → guard returns null.
        List<FundingRate> w = List.of(
                fr(0.0001, 0), fr(0.0001, 8), fr(0.0001, 16),
                fr(0.0001, 24), fr(0.0050, 32));
        FundingFeatureSnapshot s = invoke(BigDecimal.valueOf(0.0050), w);
        assertNotNull(s.rate7dAvg());
        assertNull(s.rateZ());
    }

    @Test
    void zScoreExcludesSelfFromStats() {
        // Peer set: [0.0001, 0.0001, 0.0002, 0.0002] → mean=0.00015, stddev≈5.77e-5.
        // Sample: 0.0050 (extreme outlier). Without self-exclusion, the sample
        // would dominate the variance and pull z toward ~1; with self-exclusion
        // the |z| should land in the tens.
        List<FundingRate> peers = new ArrayList<>(List.of(
                fr(0.0001, 0), fr(0.0001, 8), fr(0.0002, 16), fr(0.0002, 24)));
        BigDecimal sample = BigDecimal.valueOf(0.0050);
        peers.add(fr(0.0050, 32)); // sample as last element of window
        FundingFeatureSnapshot s = invoke(sample, peers);

        assertNotNull(s.rateZ());
        double absZ = s.rateZ().abs().doubleValue();
        assertTrue(absZ > 10.0,
                "Self-exclusion should yield extreme |z|; got " + absZ);
    }

    @Test
    void rate7dAvgIncludesAllSamplesIncludingCurrent() {
        // The 7-day mean is meant as a real average — current event included.
        List<FundingRate> w = List.of(
                fr(0.0001, 0), fr(0.0001, 8), fr(0.0001, 16), fr(0.0007, 24));
        FundingFeatureSnapshot s = invoke(BigDecimal.valueOf(0.0007), w);
        // mean = (0.0001 + 0.0001 + 0.0001 + 0.0007) / 4 = 0.00025
        assertEquals(0, s.rate7dAvg().compareTo(new BigDecimal("0.000250000000")),
                "rate7dAvg should include current sample; got " + s.rate7dAvg());
    }
}

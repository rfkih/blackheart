package id.co.blackheart.service.risk;

import id.co.blackheart.model.Trades;
import id.co.blackheart.repository.TradesRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlippageCalibrationServiceTest {

    @Mock private TradesRepository tradesRepository;
    @InjectMocks private SlippageCalibrationService service;

    @Test
    void emptyWhenNoSampleAvailable() {
        when(tradesRepository.findRecentWithIntent(anyString(), anyInt())).thenReturn(List.of());
        assertEquals(Optional.empty(), service.calibrate("BTCUSDT"));
    }

    @Test
    void longSlippageMeasuresFillAboveIntended() {
        // Intended 100000, filled at 100050 → 5 bps slippage.
        when(tradesRepository.findRecentWithIntent(anyString(), anyInt()))
                .thenReturn(List.of(longFill("100000", "100050")));

        SlippageCalibrationService.SlippageStats s = service.calibrate("BTCUSDT").orElseThrow();

        // (100050 - 100000) / 100000 × 10000 = 5 bps.
        assertTrue(s.meanBps().subtract(new BigDecimal("5.000")).abs()
                .compareTo(new BigDecimal("0.01")) <= 0);
        assertEquals(1, s.sampleSize());
        assertFalse(s.trustworthy(), "1 sample is below the trust threshold");
    }

    @Test
    void shortSlippageFlipsSign() {
        // SHORT intended 100000, filled at 99950 — that's a WORSE short fill
        // (entered low). Signed slippage = (100000 - 99950) / 100000 × 10000 = 5 bps.
        when(tradesRepository.findRecentWithIntent(anyString(), anyInt()))
                .thenReturn(List.of(shortFill("100000", "99950")));

        SlippageCalibrationService.SlippageStats s = service.calibrate("BTCUSDT").orElseThrow();
        assertTrue(s.meanBps().compareTo(BigDecimal.ZERO) > 0,
                "SHORT filled below intent should yield positive (bad) slippage, got " + s.meanBps());
    }

    @Test
    void trustworthyOnlyAboveSampleThreshold() {
        // 25 fills of identical 7-bps slippage → trustworthy and stable.
        java.util.ArrayList<Trades> fills = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) fills.add(longFill("100000", "100070"));
        when(tradesRepository.findRecentWithIntent(anyString(), anyInt())).thenReturn(fills);

        SlippageCalibrationService.SlippageStats s = service.calibrate("BTCUSDT").orElseThrow();

        assertTrue(s.trustworthy());
        assertTrue(s.meanBps().subtract(new BigDecimal("7.000")).abs()
                .compareTo(new BigDecimal("0.01")) <= 0);
        assertEquals(0, s.stddevBps().compareTo(BigDecimal.ZERO),
                "no variance in the sample → stddev 0");
    }

    @Test
    void calibratedRateAsFractionGivesUsableBacktestInput() {
        // 30 fills, mean 8 bps → usable as backtest slippage.
        java.util.ArrayList<Trades> fills = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) fills.add(longFill("100000", "100080"));
        when(tradesRepository.findRecentWithIntent(anyString(), anyInt())).thenReturn(fills);

        BigDecimal rate = service.calibratedRateAsFraction("BTCUSDT").orElseThrow();
        // 8 bps = 0.0008 fraction. Allow a hair of rounding.
        assertTrue(rate.subtract(new BigDecimal("0.0008")).abs()
                .compareTo(new BigDecimal("0.00001")) <= 0);
    }

    @Test
    void calibratedRateEmptyWhenSampleTooThin() {
        when(tradesRepository.findRecentWithIntent(anyString(), anyInt()))
                .thenReturn(List.of(longFill("100000", "100050")));
        assertEquals(Optional.empty(), service.calibratedRateAsFraction("BTCUSDT"));
    }

    @Test
    void priceImprovementFillsContributeNegativeSlippage() {
        // LONG intended 100000, filled at 99970 — that's a 3 bps PRICE
        // IMPROVEMENT. Signed slippage = (99970 - 100000) / 100000 × 10000
        // = -3 bps. The mean across mixed fills should reflect the sign.
        java.util.ArrayList<Trades> fills = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            // Half +5 bps (worse), half -3 bps (better) → mean 1 bps.
            fills.add(longFill("100000", i % 2 == 0 ? "100050" : "99970"));
        }
        when(tradesRepository.findRecentWithIntent(anyString(), anyInt())).thenReturn(fills);

        SlippageCalibrationService.SlippageStats s = service.calibrate("BTCUSDT").orElseThrow();
        assertTrue(s.meanBps().subtract(new BigDecimal("1.000")).abs()
                .compareTo(new BigDecimal("0.01")) <= 0);
    }

    private static Trades longFill(String intended, String actual) {
        Trades t = new Trades();
        t.setSide("LONG");
        t.setIntendedEntryPrice(new BigDecimal(intended));
        t.setAvgEntryPrice(new BigDecimal(actual));
        return t;
    }

    private static Trades shortFill(String intended, String actual) {
        Trades t = new Trades();
        t.setSide("SHORT");
        t.setIntendedEntryPrice(new BigDecimal(intended));
        t.setAvgEntryPrice(new BigDecimal(actual));
        return t;
    }
}

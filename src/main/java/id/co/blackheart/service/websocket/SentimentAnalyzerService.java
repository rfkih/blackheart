package id.co.blackheart.service.websocket;

import id.co.blackheart.dto.response.SentimentResponse;
import id.co.blackheart.model.FeatureStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Derives a combined directional sentiment from a 1h and a 4h {@link FeatureStore} snapshot.
 *
 * <h3>Score weights</h3>
 * <pre>
 *   combined = (score4h × 0.60) + (score1h × 0.40)
 * </pre>
 * The 4h frame carries more weight because it reflects macro directional bias;
 * the 1h frame captures short-term momentum and entry timing signals.
 *
 * <h3>Combined label thresholds</h3>
 * <pre>
 *   >= 0.65  → STRONG_BUY
 *   >= 0.30  → BUY
 *   (-0.30, 0.30) → NEUTRAL
 *   <= -0.30 → SELL
 *   <= -0.65 → STRONG_SELL
 * </pre>
 */
@Slf4j
@Service
public class SentimentAnalyzerService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private static final String STRONG_BUY  = "STRONG_BUY";
    private static final String BUY         = "BUY";
    private static final String NEUTRAL     = "NEUTRAL";
    private static final String SELL        = "SELL";
    private static final String STRONG_SELL = "STRONG_SELL";

    private static final BigDecimal WEIGHT_4H = new BigDecimal("0.60");
    private static final BigDecimal WEIGHT_1H = new BigDecimal("0.40");

    /**
     * Computes combined sentiment from 1h and 4h feature snapshots.
     * Either can be null; the available frame(s) will be used with their full weight normalised.
     */
    public SentimentResponse analyze(FeatureStore fs1h, FeatureStore fs4h, String symbol) {
        List<String> signals1h = new ArrayList<>();
        List<String> signals4h = new ArrayList<>();

        BigDecimal raw1h = fs1h != null ? scoreFrame(fs1h, signals1h) : null;
        BigDecimal raw4h = fs4h != null ? scoreFrame(fs4h, signals4h) : null;

        String label1h = raw1h != null ? toLabel(raw1h) : NEUTRAL;
        String label4h = raw4h != null ? toLabel(raw4h) : NEUTRAL;

        // Weighted combination — fall back to whichever frame is available
        BigDecimal combined;
        if (raw1h != null && raw4h != null) {
            combined = raw4h.multiply(WEIGHT_4H).add(raw1h.multiply(WEIGHT_1H));
        } else if (raw4h != null) {
            combined = raw4h;
        } else if (raw1h != null) {
            combined = raw1h;
        } else {
            combined = ZERO;
        }

        combined = combined.max(new BigDecimal("-1.00")).min(new BigDecimal("1.00"))
                .setScale(4, RoundingMode.HALF_UP);

        if (signals1h.isEmpty()) signals1h.add("Insufficient 1h data");
        if (signals4h.isEmpty()) signals4h.add("Insufficient 4h data");

        String combinedLabel = toLabel(combined);

        log.debug("Sentiment | symbol={} score1h={} score4h={} combined={} label={}",
                symbol, raw1h, raw4h, combined, combinedLabel);

        return SentimentResponse.builder()
                .symbol(symbol)
                .sentiment(combinedLabel)
                .score(combined)
                .score1h(raw1h != null ? raw1h.setScale(4, RoundingMode.HALF_UP) : null)
                .score4h(raw4h != null ? raw4h.setScale(4, RoundingMode.HALF_UP) : null)
                .sentiment1h(label1h)
                .sentiment4h(label4h)
                .signals1h(signals1h)
                .signals4h(signals4h)
                // 1h snapshot
                .price(fs1h != null ? fs1h.getPrice() : null)
                .rsi1h(fs1h != null ? fs1h.getRsi() : null)
                .adx1h(fs1h != null ? fs1h.getAdx() : null)
                .macdHistogram1h(fs1h != null ? fs1h.getMacdHistogram() : null)
                .relativeVolume1h(fs1h != null ? fs1h.getRelativeVolume20() : null)
                .trendRegime1h(fs1h != null ? fs1h.getTrendRegime() : null)
                .isBullishBreakout1h(fs1h != null ? fs1h.getIsBullishBreakout() : null)
                .isBearishBreakout1h(fs1h != null ? fs1h.getIsBearishBreakout() : null)
                // 4h snapshot
                .rsi4h(fs4h != null ? fs4h.getRsi() : null)
                .adx4h(fs4h != null ? fs4h.getAdx() : null)
                .macdHistogram4h(fs4h != null ? fs4h.getMacdHistogram() : null)
                .trendRegime4h(fs4h != null ? fs4h.getTrendRegime() : null)
                .entryBias4h(fs4h != null ? fs4h.getEntryBias() : null)
                // timestamps
                .featureTime1h(fs1h != null ? fs1h.getStartTime() : null)
                .featureTime4h(fs4h != null ? fs4h.getStartTime() : null)
                .publishedAt(LocalDateTime.now())
                .build();
    }

    // ── Per-frame scoring ─────────────────────────────────────────────────────

    /**
     * Scores a single FeatureStore frame. Returns a raw score in [-1.0, 1.0] before clamping.
     * Signals list is populated in-place for transparency.
     */
    private BigDecimal scoreFrame(FeatureStore fs, List<String> signals) {
        BigDecimal score = ZERO;

        // 1. Trend regime (+0.20 / -0.20)
        if ("BULL".equalsIgnoreCase(fs.getTrendRegime())) {
            score = score.add(new BigDecimal("0.20"));
            signals.add("Trend regime: BULL");
        } else if ("BEAR".equalsIgnoreCase(fs.getTrendRegime())) {
            score = score.subtract(new BigDecimal("0.20"));
            signals.add("Trend regime: BEAR");
        }

        // 2. EMA stack alignment (+0.15 / -0.15)
        if (hasValue(fs.getPrice()) && hasValue(fs.getEma20())
                && hasValue(fs.getEma50()) && hasValue(fs.getEma200())) {
            boolean bullStack = fs.getPrice().compareTo(fs.getEma20()) > 0
                    && fs.getEma20().compareTo(fs.getEma50()) > 0
                    && fs.getEma50().compareTo(fs.getEma200()) > 0;
            boolean bearStack = fs.getPrice().compareTo(fs.getEma20()) < 0
                    && fs.getEma20().compareTo(fs.getEma50()) < 0
                    && fs.getEma50().compareTo(fs.getEma200()) < 0;
            if (bullStack) {
                score = score.add(new BigDecimal("0.15"));
                signals.add("EMA stack: bullish");
            } else if (bearStack) {
                score = score.subtract(new BigDecimal("0.15"));
                signals.add("EMA stack: bearish");
            }
        }

        // 3. EMA50 slope (+0.10 / -0.10)
        if (hasValue(fs.getEma50Slope())) {
            if (fs.getEma50Slope().compareTo(ZERO) > 0) {
                score = score.add(new BigDecimal("0.10"));
                signals.add("EMA50 slope: rising");
            } else if (fs.getEma50Slope().compareTo(ZERO) < 0) {
                score = score.subtract(new BigDecimal("0.10"));
                signals.add("EMA50 slope: falling");
            }
        }

        // 4. Directional index spread (+0.10 / -0.10)
        if (hasValue(fs.getPlusDI()) && hasValue(fs.getMinusDI())) {
            BigDecimal diSpread = fs.getPlusDI().subtract(fs.getMinusDI());
            if (diSpread.compareTo(new BigDecimal("5")) > 0) {
                score = score.add(new BigDecimal("0.10"));
                signals.add("+DI > -DI: bullish directional pressure");
            } else if (diSpread.compareTo(new BigDecimal("-5")) < 0) {
                score = score.subtract(new BigDecimal("0.10"));
                signals.add("-DI > +DI: bearish directional pressure");
            }
        }

        // 5. ADX strength bonus (±0.05 in dominant direction)
        if (hasValue(fs.getAdx()) && fs.getAdx().compareTo(new BigDecimal("25")) >= 0) {
            if (score.compareTo(ZERO) > 0) {
                score = score.add(new BigDecimal("0.05"));
                signals.add("ADX " + fmt(fs.getAdx()) + ": strong trend confirms bull");
            } else if (score.compareTo(ZERO) < 0) {
                score = score.subtract(new BigDecimal("0.05"));
                signals.add("ADX " + fmt(fs.getAdx()) + ": strong trend confirms bear");
            }
        }

        // 6. MACD histogram (+0.10 / -0.10)
        if (hasValue(fs.getMacdHistogram())) {
            if (fs.getMacdHistogram().compareTo(ZERO) > 0) {
                score = score.add(new BigDecimal("0.10"));
                signals.add("MACD histogram: positive momentum");
            } else if (fs.getMacdHistogram().compareTo(ZERO) < 0) {
                score = score.subtract(new BigDecimal("0.10"));
                signals.add("MACD histogram: negative momentum");
            }
        }

        // 7. RSI zone (±0.05 overbought/oversold, ±0.10 directional)
        if (hasValue(fs.getRsi())) {
            BigDecimal rsi = fs.getRsi();
            if (rsi.compareTo(new BigDecimal("70")) >= 0) {
                score = score.add(new BigDecimal("0.05"));
                signals.add("RSI " + fmt(rsi) + ": overbought — momentum high, caution");
            } else if (rsi.compareTo(new BigDecimal("55")) >= 0) {
                score = score.add(new BigDecimal("0.10"));
                signals.add("RSI " + fmt(rsi) + ": bullish zone");
            } else if (rsi.compareTo(new BigDecimal("30")) <= 0) {
                score = score.subtract(new BigDecimal("0.05"));
                signals.add("RSI " + fmt(rsi) + ": oversold — momentum low, caution");
            } else if (rsi.compareTo(new BigDecimal("45")) <= 0) {
                score = score.subtract(new BigDecimal("0.10"));
                signals.add("RSI " + fmt(rsi) + ": bearish zone");
            }
        }

        // 8. Breakout flags (+0.10 / -0.10)
        if (Boolean.TRUE.equals(fs.getIsBullishBreakout())) {
            score = score.add(new BigDecimal("0.10"));
            signals.add("Bullish breakout detected");
        }
        if (Boolean.TRUE.equals(fs.getIsBearishBreakout())) {
            score = score.subtract(new BigDecimal("0.10"));
            signals.add("Bearish breakout detected");
        }

        // 9. Signed efficiency ratio (+0.10 / -0.10)
        if (hasValue(fs.getSignedEr20())) {
            if (fs.getSignedEr20().compareTo(new BigDecimal("0.30")) >= 0) {
                score = score.add(new BigDecimal("0.10"));
                signals.add("Signed ER: efficiently trending up");
            } else if (fs.getSignedEr20().compareTo(new BigDecimal("-0.30")) <= 0) {
                score = score.subtract(new BigDecimal("0.10"));
                signals.add("Signed ER: efficiently trending down");
            }
        }

        // 10. Relative volume amplifier (±0.05)
        if (hasValue(fs.getRelativeVolume20())
                && fs.getRelativeVolume20().compareTo(new BigDecimal("1.50")) >= 0) {
            if (score.compareTo(ZERO) > 0) {
                score = score.add(new BigDecimal("0.05"));
                signals.add("Above-average volume confirms bullish move");
            } else if (score.compareTo(ZERO) < 0) {
                score = score.subtract(new BigDecimal("0.05"));
                signals.add("Above-average volume confirms bearish move");
            }
        }

        return score.max(new BigDecimal("-1.00")).min(new BigDecimal("1.00"));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String toLabel(BigDecimal score) {
        if (score.compareTo(new BigDecimal("0.65")) >= 0)  return STRONG_BUY;
        if (score.compareTo(new BigDecimal("0.30")) >= 0)  return BUY;
        if (score.compareTo(new BigDecimal("-0.30")) > 0)  return NEUTRAL;
        if (score.compareTo(new BigDecimal("-0.65")) > 0)  return SELL;
        return STRONG_SELL;
    }

    private boolean hasValue(BigDecimal v) {
        return v != null;
    }

    private String fmt(BigDecimal v) {
        return v.setScale(1, RoundingMode.HALF_UP).toPlainString();
    }
}

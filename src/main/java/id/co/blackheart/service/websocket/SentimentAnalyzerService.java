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
import org.apache.commons.lang3.ObjectUtils;

/**
 * Derives a combined directional sentiment from a 1h and a 4h {@link FeatureStore} snapshot.
 *
 * <h3>Score weights</h3>
 * <pre>
 *   combined = (score4h Ã— 0.60) + (score1h Ã— 0.40)
 * </pre>
 * The 4h frame carries more weight because it reflects macro directional bias;
 * the 1h frame captures short-term momentum and entry timing signals.
 *
 * <h3>Combined label thresholds</h3>
 * <pre>
 *   &gt;= 0.65  â†’ STRONG_BUY
 *   &gt;= 0.30  â†’ BUY
 *   (-0.30, 0.30) â†’ NEUTRAL
 *   &lt;= -0.30 â†’ SELL
 *   &lt;= -0.65 â†’ STRONG_SELL
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

    private static final BigDecimal LOWER_BOUND = new BigDecimal("-1.00");
    private static final BigDecimal UPPER_BOUND = new BigDecimal("1.00");

    private static final BigDecimal D_020 = new BigDecimal("0.20");
    private static final BigDecimal D_015 = new BigDecimal("0.15");
    private static final BigDecimal D_010 = new BigDecimal("0.10");
    private static final BigDecimal D_005 = new BigDecimal("0.05");

    /**
     * Computes combined sentiment from 1h and 4h feature snapshots.
     * Either can be null; the available frame(s) will be used with their full weight normalised.
     */
    public SentimentResponse analyze(FeatureStore fs1h, FeatureStore fs4h, String symbol) {
        List<String> signals1h = new ArrayList<>();
        List<String> signals4h = new ArrayList<>();

        BigDecimal raw1h = fs1h != null ? scoreFrame(fs1h, signals1h) : null;
        BigDecimal raw4h = fs4h != null ? scoreFrame(fs4h, signals4h) : null;

        BigDecimal combined = clamp(combineScores(raw1h, raw4h))
                .setScale(4, RoundingMode.HALF_UP);

        if (signals1h.isEmpty()) signals1h.add("Insufficient 1h data");
        if (signals4h.isEmpty()) signals4h.add("Insufficient 4h data");

        String combinedLabel = toLabel(combined);

        log.debug("Sentiment | symbol={} score1h={} score4h={} combined={} label={}",
                symbol, raw1h, raw4h, combined, combinedLabel);

        FrameScores scores = new FrameScores(raw1h, raw4h, combined, combinedLabel);
        return buildResponse(symbol, fs1h, fs4h, signals1h, signals4h, scores);
    }

    private record FrameScores(BigDecimal raw1h, BigDecimal raw4h, BigDecimal combined, String combinedLabel) {}

    private BigDecimal combineScores(BigDecimal raw1h, BigDecimal raw4h) {
        if (raw1h != null && raw4h != null) {
            return raw4h.multiply(WEIGHT_4H).add(raw1h.multiply(WEIGHT_1H));
        }
        if (raw4h != null) return raw4h;
        if (raw1h != null) return raw1h;
        return ZERO;
    }

    private SentimentResponse buildResponse(String symbol, FeatureStore fs1h, FeatureStore fs4h,
                                            List<String> signals1h, List<String> signals4h,
                                            FrameScores scores) {
        String lbl1h = scores.raw1h() != null ? toLabel(scores.raw1h()) : NEUTRAL;
        String lbl4h = scores.raw4h() != null ? toLabel(scores.raw4h()) : NEUTRAL;
        SentimentResponse.SentimentResponseBuilder b = SentimentResponse.builder()
                .symbol(symbol)
                .sentiment(scores.combinedLabel())
                .score(scores.combined())
                .score1h(scaleOrNull(scores.raw1h()))
                .score4h(scaleOrNull(scores.raw4h()))
                .sentiment1h(lbl1h)
                .sentiment4h(lbl4h)
                .signals1h(signals1h)
                .signals4h(signals4h)
                .publishedAt(LocalDateTime.now());
        apply1hFields(b, fs1h);
        apply4hFields(b, fs4h);
        return b.build();
    }

    private void apply1hFields(SentimentResponse.SentimentResponseBuilder b, FeatureStore fs) {
        if (fs == null) return;
        b.price(fs.getPrice()).rsi1h(fs.getRsi()).adx1h(fs.getAdx())
                .macdHistogram1h(fs.getMacdHistogram()).relativeVolume1h(fs.getRelativeVolume20())
                .trendRegime1h(fs.getTrendRegime()).isBullishBreakout1h(fs.getIsBullishBreakout())
                .isBearishBreakout1h(fs.getIsBearishBreakout()).featureTime1h(fs.getStartTime());
    }

    private void apply4hFields(SentimentResponse.SentimentResponseBuilder b, FeatureStore fs) {
        if (fs == null) return;
        b.rsi4h(fs.getRsi()).adx4h(fs.getAdx()).macdHistogram4h(fs.getMacdHistogram())
                .trendRegime4h(fs.getTrendRegime()).entryBias4h(fs.getEntryBias())
                .featureTime4h(fs.getStartTime());
    }

    private BigDecimal scaleOrNull(BigDecimal v) {
        return v != null ? v.setScale(4, RoundingMode.HALF_UP) : null;
    }

    private BigDecimal clamp(BigDecimal v) {
        return v.max(LOWER_BOUND).min(UPPER_BOUND);
    }

    // â”€â”€ Per-frame scoring â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Scores a single FeatureStore frame. Returns a raw score in [-1.0, 1.0] before clamping.
     * Signals list is populated in-place for transparency.
     */
    private BigDecimal scoreFrame(FeatureStore fs, List<String> signals) {
        BigDecimal score = ZERO;
        score = score.add(scoreTrendRegime(fs, signals));
        score = score.add(scoreEmaStack(fs, signals));
        score = score.add(scoreEma50Slope(fs, signals));
        score = score.add(scoreDirectionalIndex(fs, signals));
        score = score.add(scoreAdxBonus(fs, signals, score));
        score = score.add(scoreMacd(fs, signals));
        score = score.add(scoreRsi(fs, signals));
        score = score.add(scoreBreakouts(fs, signals));
        score = score.add(scoreSignedEr(fs, signals));
        score = score.add(scoreVolumeAmplifier(fs, signals, score));
        return clamp(score);
    }

    private BigDecimal scoreTrendRegime(FeatureStore fs, List<String> signals) {
        if ("BULL".equalsIgnoreCase(fs.getTrendRegime())) {
            signals.add("Trend regime: BULL");
            return D_020;
        }
        if ("BEAR".equalsIgnoreCase(fs.getTrendRegime())) {
            signals.add("Trend regime: BEAR");
            return D_020.negate();
        }
        return ZERO;
    }

    private BigDecimal scoreEmaStack(FeatureStore fs, List<String> signals) {
        if (!hasValue(fs.getPrice()) || !hasValue(fs.getEma20())
                || !hasValue(fs.getEma50()) || !hasValue(fs.getEma200())) {
            return ZERO;
        }
        boolean bullStack = fs.getPrice().compareTo(fs.getEma20()) > 0
                && fs.getEma20().compareTo(fs.getEma50()) > 0
                && fs.getEma50().compareTo(fs.getEma200()) > 0;
        if (bullStack) {
            signals.add("EMA stack: bullish");
            return D_015;
        }
        boolean bearStack = fs.getPrice().compareTo(fs.getEma20()) < 0
                && fs.getEma20().compareTo(fs.getEma50()) < 0
                && fs.getEma50().compareTo(fs.getEma200()) < 0;
        if (bearStack) {
            signals.add("EMA stack: bearish");
            return D_015.negate();
        }
        return ZERO;
    }

    private BigDecimal scoreEma50Slope(FeatureStore fs, List<String> signals) {
        if (!hasValue(fs.getEma50Slope())) return ZERO;
        int sign = fs.getEma50Slope().compareTo(ZERO);
        if (sign > 0) {
            signals.add("EMA50 slope: rising");
            return D_010;
        }
        if (sign < 0) {
            signals.add("EMA50 slope: falling");
            return D_010.negate();
        }
        return ZERO;
    }

    private BigDecimal scoreDirectionalIndex(FeatureStore fs, List<String> signals) {
        if (!hasValue(fs.getPlusDI()) || !hasValue(fs.getMinusDI())) return ZERO;
        BigDecimal diSpread = fs.getPlusDI().subtract(fs.getMinusDI());
        if (diSpread.compareTo(new BigDecimal("5")) > 0) {
            signals.add("+DI > -DI: bullish directional pressure");
            return D_010;
        }
        if (diSpread.compareTo(new BigDecimal("-5")) < 0) {
            signals.add("-DI > +DI: bearish directional pressure");
            return D_010.negate();
        }
        return ZERO;
    }

    private BigDecimal scoreAdxBonus(FeatureStore fs, List<String> signals, BigDecimal currentScore) {
        if (!hasValue(fs.getAdx()) || fs.getAdx().compareTo(new BigDecimal("25")) < 0) return ZERO;
        int dir = currentScore.compareTo(ZERO);
        if (dir > 0) {
            signals.add("ADX " + fmt(fs.getAdx()) + ": strong trend confirms bull");
            return D_005;
        }
        if (dir < 0) {
            signals.add("ADX " + fmt(fs.getAdx()) + ": strong trend confirms bear");
            return D_005.negate();
        }
        return ZERO;
    }

    private BigDecimal scoreMacd(FeatureStore fs, List<String> signals) {
        if (!hasValue(fs.getMacdHistogram())) return ZERO;
        int sign = fs.getMacdHistogram().compareTo(ZERO);
        if (sign > 0) {
            signals.add("MACD histogram: positive momentum");
            return D_010;
        }
        if (sign < 0) {
            signals.add("MACD histogram: negative momentum");
            return D_010.negate();
        }
        return ZERO;
    }

    private BigDecimal scoreRsi(FeatureStore fs, List<String> signals) {
        if (!hasValue(fs.getRsi())) return ZERO;
        BigDecimal rsi = fs.getRsi();
        if (rsi.compareTo(new BigDecimal("70")) >= 0) {
            signals.add("RSI " + fmt(rsi) + ": overbought â€” momentum high, caution");
            return D_005;
        }
        if (rsi.compareTo(new BigDecimal("55")) >= 0) {
            signals.add("RSI " + fmt(rsi) + ": bullish zone");
            return D_010;
        }
        if (rsi.compareTo(new BigDecimal("30")) <= 0) {
            signals.add("RSI " + fmt(rsi) + ": oversold â€” momentum low, caution");
            return D_005.negate();
        }
        if (rsi.compareTo(new BigDecimal("45")) <= 0) {
            signals.add("RSI " + fmt(rsi) + ": bearish zone");
            return D_010.negate();
        }
        return ZERO;
    }

    private BigDecimal scoreBreakouts(FeatureStore fs, List<String> signals) {
        BigDecimal delta = ZERO;
        if (Boolean.TRUE.equals(fs.getIsBullishBreakout())) {
            signals.add("Bullish breakout detected");
            delta = delta.add(D_010);
        }
        if (Boolean.TRUE.equals(fs.getIsBearishBreakout())) {
            signals.add("Bearish breakout detected");
            delta = delta.subtract(D_010);
        }
        return delta;
    }

    private BigDecimal scoreSignedEr(FeatureStore fs, List<String> signals) {
        if (!hasValue(fs.getSignedEr20())) return ZERO;
        if (fs.getSignedEr20().compareTo(new BigDecimal("0.30")) >= 0) {
            signals.add("Signed ER: efficiently trending up");
            return D_010;
        }
        if (fs.getSignedEr20().compareTo(new BigDecimal("-0.30")) <= 0) {
            signals.add("Signed ER: efficiently trending down");
            return D_010.negate();
        }
        return ZERO;
    }

    private BigDecimal scoreVolumeAmplifier(FeatureStore fs, List<String> signals, BigDecimal currentScore) {
        if (!hasValue(fs.getRelativeVolume20())
                || fs.getRelativeVolume20().compareTo(new BigDecimal("1.50")) < 0) {
            return ZERO;
        }
        int dir = currentScore.compareTo(ZERO);
        if (dir > 0) {
            signals.add("Above-average volume confirms bullish move");
            return D_005;
        }
        if (dir < 0) {
            signals.add("Above-average volume confirms bearish move");
            return D_005.negate();
        }
        return ZERO;
    }

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

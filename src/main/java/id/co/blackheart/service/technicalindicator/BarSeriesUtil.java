package id.co.blackheart.service.technicalindicator;

import id.co.blackheart.model.MarketData;
import id.co.blackheart.util.MapperUtil;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeries;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * Pure helpers for converting between this project's persistence types
 * ({@link MarketData}, {@link BigDecimal}) and TA4j's runtime types
 * ({@link BarSeries}, {@link Num}). Extracted from
 * {@code TechnicalIndicatorService} so feature patchers and any future
 * indicator-driven code can share one implementation — same precision,
 * same UTC handling, same rounding.
 *
 * <p>All methods are stateless static utilities. The {@code MapperUtil}
 * dependency is passed in by the caller rather than injected because this
 * class isn't a Spring bean.
 */
public final class BarSeriesUtil {

    /** Decimal scale for indicator-derived values persisted to feature_store. */
    public static final int DEFAULT_SCALE = 8;

    private static final ZoneId UTC = ZoneId.of("UTC");

    private BarSeriesUtil() {
    }

    /**
     * Build a TA4j {@link BarSeries} from a list of {@link MarketData} bars
     * sorted ascending by start_time. The bar's {@code endTime} is set to
     * the candle's end_time in UTC so TA4j's time-based queries align with
     * the persistence layer's semantics.
     */
    public static BarSeries toBarSeries(List<MarketData> data, String interval, MapperUtil mapperUtil) {
        BarSeries series = new BaseBarSeries();
        Duration barDuration = Duration.ofMinutes(mapperUtil.getIntervalMinutes(interval));
        for (MarketData md : data) {
            Instant barTimestamp = md.getEndTime().atZone(UTC).toInstant();
            Num openPrice = series.numOf(md.getOpenPrice());
            Num highPrice = series.numOf(md.getHighPrice());
            Num lowPrice = series.numOf(md.getLowPrice());
            Num closePrice = series.numOf(md.getClosePrice());
            Num volume = series.numOf(md.getVolume());
            BaseBar bar = BaseBar.builder()
                    .timePeriod(barDuration)
                    .endTime(barTimestamp.atZone(UTC))
                    .openPrice(openPrice)
                    .highPrice(highPrice)
                    .lowPrice(lowPrice)
                    .closePrice(closePrice)
                    .volume(volume)
                    .build();
            series.addBar(bar);
        }
        return series;
    }

    /**
     * Convert a TA4j {@link Num} to a {@link BigDecimal} with the
     * project-standard 8-decimal scale and HALF_UP rounding. Matches the
     * precision used by the {@code feature_store} columns.
     */
    public static BigDecimal toBigDecimal(Num value) {
        return new BigDecimal(value.toString()).setScale(DEFAULT_SCALE, RoundingMode.HALF_UP);
    }
}

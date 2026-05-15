package id.co.blackheart.service.mlingest.job.handler;

import id.co.blackheart.model.JobType;
import org.springframework.stereotype.Component;

/**
 * Binance public macro feeds (funding rate, OI, top L/S, taker volume) →
 * {@code macro_raw}. Stub until Phase 1 M2.
 *
 * <p>Distinct from {@link id.co.blackheart.model.JobType#BACKFILL_FUNDING_HISTORY}
 * which writes the {@code funding_rate_history} table used by feature_store
 * funding columns. This one writes raw ingestion rows for ML features.
 */
@Component
public class BackfillMlBinanceMacroHandler extends MlBackfillStubHandler {
    @Override public JobType jobType() { return JobType.BACKFILL_ML_BINANCE_MACRO; }
    @Override protected String sourceName() { return "binance_macro"; }
}

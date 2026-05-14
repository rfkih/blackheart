package id.co.blackheart.service.mlingest.job.handler;

import id.co.blackheart.model.JobType;
import org.springframework.stereotype.Component;

/**
 * CoinGecko free-tier global metrics (BTC dominance, total market caps) →
 * {@code macro_raw}. Stub until Phase 1 M2.
 */
@Component
public class BackfillMlCoinGeckoHandler extends MlBackfillStubHandler {
    @Override public JobType jobType() { return JobType.BACKFILL_ML_COINGECKO; }
    @Override protected String sourceName() { return "coingecko"; }
}

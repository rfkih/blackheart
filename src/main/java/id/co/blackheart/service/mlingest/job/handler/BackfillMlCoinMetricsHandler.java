package id.co.blackheart.service.mlingest.job.handler;

import id.co.blackheart.model.JobType;
import org.springframework.stereotype.Component;

/**
 * CoinMetrics community-tier on-chain metrics (exchange netflow, active
 * addresses, realized cap) → {@code onchain_raw}. Stub until Phase 1 M2.
 */
@Component
public class BackfillMlCoinMetricsHandler extends MlBackfillStubHandler {
    @Override public JobType jobType() { return JobType.BACKFILL_ML_COINMETRICS; }
    @Override protected String sourceName() { return "coinmetrics"; }
}

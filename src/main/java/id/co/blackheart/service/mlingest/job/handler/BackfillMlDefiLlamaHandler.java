package id.co.blackheart.service.mlingest.job.handler;

import id.co.blackheart.model.JobType;
import org.springframework.stereotype.Component;

/**
 * DefiLlama stablecoin supply + chain TVL → {@code onchain_raw}.
 * Stub until Phase 1 M2.
 */
@Component
public class BackfillMlDefiLlamaHandler extends MlBackfillStubHandler {
    @Override public JobType jobType() { return JobType.BACKFILL_ML_DEFILLAMA; }
    @Override protected String sourceName() { return "defillama"; }
}

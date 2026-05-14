package id.co.blackheart.service.mlingest.job.handler;

import id.co.blackheart.model.JobType;
import org.springframework.stereotype.Component;

/**
 * alternative.me Fear & Greed Index history → {@code macro_raw}.
 * Simplest source — single endpoint, no auth. Stub until Phase 1 M2.
 */
@Component
public class BackfillMlAlternativeMeHandler extends MlBackfillStubHandler {
    @Override public JobType jobType() { return JobType.BACKFILL_ML_ALTERNATIVE_ME; }
    @Override protected String sourceName() { return "alternative_me"; }
}

package id.co.blackheart.service.mlingest.job.handler;

import id.co.blackheart.model.JobType;
import org.springframework.stereotype.Component;

/**
 * ForexFactory economic calendar scrape (FOMC, CPI, NFP, Unemployment) →
 * {@code macro_raw}. Stub until Phase 1 M2.
 *
 * <p>Most fragile source — HTML structure changes periodically. Real impl
 * must wrap in robust retry + parse-error tolerance.
 */
@Component
public class BackfillMlForexFactoryHandler extends MlBackfillStubHandler {
    @Override public JobType jobType() { return JobType.BACKFILL_ML_FOREXFACTORY; }
    @Override protected String sourceName() { return "forexfactory"; }
}

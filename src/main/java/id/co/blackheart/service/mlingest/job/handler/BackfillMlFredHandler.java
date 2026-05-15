package id.co.blackheart.service.mlingest.job.handler;

import id.co.blackheart.model.JobType;
import org.springframework.stereotype.Component;

/**
 * FRED + ALFRED macro series → {@code macro_raw}. Stub until Phase 1 M2.
 * Real impl will POST to Python ingest service at
 * {@code POST /pull/fred {start, end, series_ids[], use_alfred_vintage}}.
 */
@Component
public class BackfillMlFredHandler extends MlBackfillStubHandler {
    @Override public JobType jobType() { return JobType.BACKFILL_ML_FRED; }
    @Override protected String sourceName() { return "fred"; }
}

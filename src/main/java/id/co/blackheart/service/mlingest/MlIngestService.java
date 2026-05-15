package id.co.blackheart.service.mlingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.co.blackheart.dto.request.TriggerMlBackfillRequest;
import id.co.blackheart.dto.request.UpdateMlScheduleRequest;
import id.co.blackheart.model.HistoricalBackfillJob;
import id.co.blackheart.model.JobType;
import id.co.blackheart.model.MlIngestSchedule;
import id.co.blackheart.model.MlSourceHealth;
import id.co.blackheart.repository.MlIngestScheduleRepository;
import id.co.blackheart.repository.MlSourceHealthRepository;
import id.co.blackheart.service.marketdata.job.HistoricalBackfillJobService;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestration layer for the ML ingest control plane. Three responsibilities:
 *
 * <ol>
 *   <li>CRUD on {@link MlIngestSchedule} (admin edits cron/lookback/enabled
 *       via Blackridge). The dynamic Spring TaskScheduler reads these rows on
 *       its refresh tick.</li>
 *   <li>Read-only access to {@link MlSourceHealth} for the dashboard.</li>
 *   <li>Triggering manual backfills — routes through the existing
 *       {@link HistoricalBackfillJobService}, mapping source string to the
 *       matching {@code BACKFILL_ML_*} JobType.</li>
 * </ol>
 *
 * <p><b>Source name validation</b>: every API call that takes a source string
 * is checked against the {@link #SOURCE_TO_JOB_TYPE} map, which is the single
 * source of truth for what sources the system knows about. Adding a new source
 * is a two-step change: append a JobType enum value + add a mapping here.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MlIngestService {

    /**
     * Canonical mapping of source identifier → backfill JobType. Drives both
     * the trigger endpoint and the source-name validator.
     */
    private static final Map<String, JobType> SOURCE_TO_JOB_TYPE = Map.of(
            "fred",           JobType.BACKFILL_ML_FRED,
            "binance_macro",  JobType.BACKFILL_ML_BINANCE_MACRO,
            "defillama",      JobType.BACKFILL_ML_DEFILLAMA,
            "coinmetrics",    JobType.BACKFILL_ML_COINMETRICS,
            "coingecko",      JobType.BACKFILL_ML_COINGECKO,
            "alternative_me", JobType.BACKFILL_ML_ALTERNATIVE_ME,
            "forexfactory",   JobType.BACKFILL_ML_FOREXFACTORY
    );

    private final MlIngestScheduleRepository scheduleRepository;
    private final MlSourceHealthRepository healthRepository;
    private final HistoricalBackfillJobService backfillJobService;

    // ── Schedules ───────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<MlIngestSchedule> listSchedules() {
        return scheduleRepository.findAllByOrderBySourceAscSymbolAsc();
    }

    @Transactional(readOnly = true)
    public MlIngestSchedule getSchedule(Long id) {
        return scheduleRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "ml_ingest_schedule not found: id=" + id));
    }

    /**
     * Partial update. Any null field in the request is left unchanged. The
     * cron expression is validated via {@link CronExpression#parse} so a
     * malformed string surfaces as a 400 instead of silently breaking the
     * scheduler tick.
     */
    @Transactional
    public MlIngestSchedule updateSchedule(Long id, UpdateMlScheduleRequest req, String updatedBy) {
        if (req == null) {
            throw new IllegalArgumentException("Update request must not be null");
        }
        MlIngestSchedule row = scheduleRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException(
                        "ml_ingest_schedule not found: id=" + id));

        if (req.cronExpression() != null) {
            // Throws IllegalArgumentException on parse failure — controller
            // maps to 400.
            CronExpression.parse(req.cronExpression());
            row.setCronExpression(req.cronExpression());
        }
        if (req.lookbackHours() != null) {
            if (req.lookbackHours() <= 0 || req.lookbackHours() > 720) {
                throw new IllegalArgumentException(
                        "lookbackHours must be in [1, 720], got " + req.lookbackHours());
            }
            row.setLookbackHours(req.lookbackHours());
        }
        if (req.enabled() != null) {
            row.setEnabled(req.enabled());
        }
        if (req.config() != null) {
            row.setConfig(req.config());
        }
        row.setUpdatedBy(updatedBy);

        MlIngestSchedule saved = scheduleRepository.save(row);
        log.info("ml_ingest_schedule updated | id={} source={} symbol={} enabled={} cron={}",
                saved.getId(), saved.getSource(), saved.getSymbol(),
                saved.isEnabled(), saved.getCronExpression());
        return saved;
    }

    // ── Source health ───────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<MlSourceHealth> listSourceHealth() {
        return healthRepository.findAllByOrderBySourceAsc();
    }

    // ── Manual backfill trigger ─────────────────────────────────────────────

    /**
     * Maps a source name → {@code BACKFILL_ML_*} JobType and submits through
     * the existing job system. The frontend gets back a {@link HistoricalBackfillJob}
     * and polls {@code /api/v1/historical/jobs/{id}} the same way it does for
     * existing backfills — no separate polling endpoint needed.
     *
     * <p>Validates source name aggressively (whitelist) — rejects unknown
     * sources with 400 rather than crashing the handler dispatch.
     */
    public HistoricalBackfillJob triggerBackfill(TriggerMlBackfillRequest req, UUID userId) {
        if (req == null || req.source() == null) {
            throw new IllegalArgumentException("source is required");
        }
        String normalizedSource = req.source().toLowerCase(Locale.ROOT).trim();
        JobType jobType = SOURCE_TO_JOB_TYPE.get(normalizedSource);
        if (jobType == null) {
            throw new IllegalArgumentException("Unknown ML ingest source: " + req.source()
                    + ". Known sources: " + SOURCE_TO_JOB_TYPE.keySet());
        }

        String symbol = (req.symbol() == null || req.symbol().isBlank()) ? null : req.symbol();
        JsonNode params = mergeScheduleDefaults(normalizedSource, symbol, req.params());

        HistoricalBackfillJob job = backfillJobService.submit(
                jobType, symbol, null, params, userId);
        log.info("ML backfill triggered | jobId={} source={} symbol={} jobType={}",
                job.getJobId(), normalizedSource, symbol, jobType);
        return job;
    }

    /**
     * Fill ``params.config`` from the matching {@link MlIngestSchedule} row
     * when the caller didn't supply one. The frontend's backfill dialog only
     * collects start/end (and symbol where applicable) — source-specific
     * params (FRED series ids, Binance feeds, etc.) live in the schedule row
     * as the canonical default. Without this merge, Python source modules
     * that require non-empty config (e.g. binance_macro, fred, coinmetrics)
     * reject the request with 502.
     *
     * <p>User-supplied {@code config} always wins — only fills in when the
     * caller's params is missing the key (or null).
     */
    private JsonNode mergeScheduleDefaults(String source, String symbol, JsonNode params) {
        ObjectNode merged = (params != null && params.isObject())
                ? params.deepCopy()
                : JsonNodeFactory.instance.objectNode();
        JsonNode existing = merged.get("config");
        if (existing != null && !existing.isNull() && existing.isObject()) {
            return merged;
        }
        scheduleRepository.findBySourceAndSymbol(source, symbol)
                .map(MlIngestSchedule::getConfig)
                .ifPresentOrElse(
                        cfg -> {
                            merged.set("config", cfg);
                            log.info(
                                    "ML backfill | merged config from schedule | source={} symbol={}",
                                    source, symbol
                            );
                        },
                        () -> log.warn(
                                "ML backfill | no schedule found for source={} symbol={} — "
                                        + "Python module may reject for missing config",
                                source, symbol
                        )
                );
        return merged;
    }

    /**
     * Read-only view of the source whitelist — controller exposes this so the
     * frontend can populate dropdowns without hardcoding.
     */
    public List<String> knownSources() {
        return SOURCE_TO_JOB_TYPE.keySet().stream().sorted().toList();
    }
}

package id.co.blackheart.service.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Hot-reloadable parameter store for research strategies (TPR).
 * Replaces the "edit Java → gradle compile → restart" loop with a simple PUT
 * request that the strategy picks up on its next evaluation.
 *
 * <p>Params are held per-strategy in {@link AtomicReference}s so reads are
 * lock-free on the hot path. A JSON file backs each in-memory copy so changes
 * survive application restarts — paths are configurable, defaulting to
 * {@code ./research/<strategy>_params.json} relative to the Spring Boot
 * working dir.
 *
 * <p>Not wired through Spring's config prop system on purpose: the point is
 * that users/Claude update params at runtime without touching application
 * properties or redeploying.
 *
 * <p><b>Package note (V14+):</b> moved from {@code service.research} to
 * {@code service.strategy} so the trading-service JAR can physically
 * exclude {@code service/research/**} without breaking
 * {@link TrendPullbackStrategyService}, which depends on this class to
 * resolve TPR params at runtime. The bean still serves the research-mode
 * TPR strategy; it just lives next to its peers (LSR/VCB/VBO param services).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ResearchParamService {

    private final ObjectMapper objectMapper;

    @Value("${app.research.tpr-params-path:./research/tpr_params.json}")
    private String tprParamsPath;

    private final AtomicReference<TrendPullbackStrategyService.Params> tprParams = new AtomicReference<>();

    /**
     * Load persisted params on boot. If a file doesn't exist or is unreadable,
     * fall through to the baked-in {@code defaults()} so the strategy still
     * runs correctly from a cold install.
     *
     * <p>When a disk file IS present, we merge it OVER {@code defaults()}
     * rather than replacing them — so JSON files written before a new param
     * was added still pick up the new field's default value. Without the
     * merge, every schema-evolution adds a silent regression for existing
     * installs (e.g. V2 risk-based sizing fields would deserialize to null
     * and the strategy would fall back to legacy notional sizing).
     */
    @PostConstruct
    public void init() {
        TrendPullbackStrategyService.Params merged = mergeOverDefaults(
                tprParamsPath, TrendPullbackStrategyService.Params.defaults());
        tprParams.set(merged);
        boolean fromDisk = java.nio.file.Files.exists(java.nio.file.Path.of(tprParamsPath));
        log.info("TPR research params initialised (source={})", fromDisk ? "disk+defaults" : "defaults");
    }

    // ── TPR ──────────────────────────────────────────────────────────────────

    public TrendPullbackStrategyService.Params getTprParams() {
        TrendPullbackStrategyService.Params p = tprParams.get();
        return p != null ? p : TrendPullbackStrategyService.Params.defaults();
    }

    /**
     * Overwrite the live TPR params and persist to disk. Returns the value
     * actually set. Not atomic wrt disk — if disk write fails we log but
     * still update the in-memory reference; a restart would then reset to
     * the previous on-disk value.
     */
    public TrendPullbackStrategyService.Params setTprParams(TrendPullbackStrategyService.Params next) {
        if (next == null) throw new IllegalArgumentException("params must not be null");
        tprParams.set(next);
        persistToDisk(tprParamsPath, next);
        log.info("TPR research params updated | adxMin={} clvMin={} stopAtr={} runnerAtrPhase2={}",
                next.getAdxEntryMin(), next.getClvMin(),
                next.getStopAtrBuffer(), next.getRunnerAtrPhase2());
        return next;
    }

    public TrendPullbackStrategyService.Params resetTprToDefaults() {
        return setTprParams(TrendPullbackStrategyService.Params.defaults());
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private <T> T loadFromDisk(String pathStr, Class<T> type) {
        try {
            Path path = Path.of(pathStr);
            if (!Files.exists(path)) return null;
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0) return null;
            return objectMapper.readValue(bytes, type);
        } catch (IOException e) {
            log.warn("Could not read params from {} — falling back to defaults", pathStr, e);
            return null;
        }
    }

    /**
     * Update {@code defaults} with any non-null fields present in the on-disk
     * JSON, returning the merged value. Keys absent from the JSON keep their
     * default — the schema-evolution-friendly behaviour. If the file is
     * missing or unreadable, returns {@code defaults} unchanged.
     *
     * <p>Implementation: {@code ObjectMapper.readerForUpdating(defaults)}
     * mutates the seed instance in place with non-null values from the JSON.
     * Jackson treats absent keys as no-op and explicit nulls as no-op too
     * (default {@code SetterInfo} behaviour), so a partial JSON cannot
     * accidentally erase a default with a null overlay.
     */
    private <T> T mergeOverDefaults(String pathStr, T defaults) {
        try {
            Path path = Path.of(pathStr);
            if (!Files.exists(path)) return defaults;
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0) return defaults;
            return objectMapper.readerForUpdating(defaults).readValue(bytes);
        } catch (IOException e) {
            log.warn("Could not merge params from {} — keeping defaults", pathStr, e);
            return defaults;
        }
    }

    private void persistToDisk(String pathStr, Object params) {
        try {
            Path path = Path.of(pathStr);
            if (path.getParent() != null) Files.createDirectories(path.getParent());
            Files.writeString(path, objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(params));
        } catch (IOException e) {
            log.error("Could not persist params to {}", pathStr, e);
        }
    }
}

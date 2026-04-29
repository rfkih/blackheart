package id.co.blackheart.service.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.service.strategy.TrendPullbackStrategyService;
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
     */
    @PostConstruct
    public void init() {
        TrendPullbackStrategyService.Params tpr =
                loadFromDisk(tprParamsPath, TrendPullbackStrategyService.Params.class);
        tprParams.set(tpr != null ? tpr : TrendPullbackStrategyService.Params.defaults());
        log.info("TPR research params initialised (source={})", tpr != null ? "disk" : "defaults");
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

package id.co.blackheart.engine;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Lookup table of archetype name → {@link StrategyEngine}. Spring discovers
 * every {@code StrategyEngine} bean on startup; the registry catches
 * collisions (two engines claiming the same archetype) loudly at boot.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class StrategyEngineRegistry {

    private final List<StrategyEngine> engines;

    private final Map<String, StrategyEngine> byArchetype = new HashMap<>();

    @PostConstruct
    void index() {
        for (StrategyEngine engine : engines) {
            String key = normalize(engine.archetype());
            StrategyEngine prior = byArchetype.put(key, engine);
            if (prior != null) {
                throw new IllegalStateException(
                        "Two StrategyEngine beans claim archetype=" + key
                                + ": " + prior.getClass().getName()
                                + " and " + engine.getClass().getName());
            }
            log.info("Registered StrategyEngine archetype={} version={} bean={}",
                    key, engine.supportedVersion(), engine.getClass().getSimpleName());
        }
    }

    public Optional<StrategyEngine> find(String archetype) {
        if (archetype == null) return Optional.empty();
        return Optional.ofNullable(byArchetype.get(normalize(archetype)));
    }

    public StrategyEngine require(String archetype) {
        return find(archetype).orElseThrow(() -> new IllegalStateException(
                "No StrategyEngine registered for archetype=" + archetype
                        + " (known: " + byArchetype.keySet() + ")"));
    }

    private String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}

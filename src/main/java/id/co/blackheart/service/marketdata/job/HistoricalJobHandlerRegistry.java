package id.co.blackheart.service.marketdata.job;

import id.co.blackheart.model.JobType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Auto-wires all {@link HistoricalJobHandler} beans into a {@link JobType}-keyed
 * map at startup. Adding a new job type means registering a new handler
 * bean — the registry picks it up via constructor injection.
 *
 * <p>Throws on duplicate handlers for the same job type so a registration
 * collision fails fast instead of silently shadowing a handler.
 */
@Slf4j
@Component
public class HistoricalJobHandlerRegistry {

    private final Map<JobType, HistoricalJobHandler> handlers;

    public HistoricalJobHandlerRegistry(List<HistoricalJobHandler> beans) {
        Map<JobType, HistoricalJobHandler> map = new EnumMap<>(JobType.class);
        for (HistoricalJobHandler h : beans) {
            HistoricalJobHandler previous = map.put(h.jobType(), h);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate HistoricalJobHandler registered for " + h.jobType()
                                + ": " + previous.getClass().getName()
                                + " and " + h.getClass().getName());
            }
        }
        this.handlers = Map.copyOf(map);
        log.info("HistoricalJobHandlerRegistry initialized with {} handler(s): {}",
                handlers.size(), handlers.keySet());
    }

    public Optional<HistoricalJobHandler> find(JobType type) {
        return Optional.ofNullable(handlers.get(type));
    }

    public boolean isRegistered(JobType type) {
        return handlers.containsKey(type);
    }
}

package id.co.blackheart.service.research;

import id.co.blackheart.dto.response.ResearchControlResponse;
import id.co.blackheart.model.ResearchControl;
import id.co.blackheart.repository.ResearchControlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Reads/writes the global research-pause flag. The {@code research-tick.sh}
 * orchestrator polls this row directly via psql (not through the API) on
 * every tick to decide whether to claim work.
 *
 * Singleton row guaranteed by V23's CHECK constraint, seeded on migrate.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ResearchControlService {

    private static final Integer SINGLETON_ID = 1;

    private final ResearchControlRepository repository;

    @Transactional(readOnly = true)
    public ResearchControlResponse getStatus() {
        ResearchControl row = loadSingleton();
        return toResponse(row);
    }

    @Transactional
    public ResearchControlResponse pause(String reason, UUID actorUserId) {
        ResearchControl row = loadSingleton();
        row.setPaused(true);
        row.setReason(reason);
        row.setUpdatedAt(Instant.now());
        row.setUpdatedByUserId(actorUserId);
        repository.save(row);
        log.info("Research loop paused by user={} reason={}", actorUserId, reason);
        return toResponse(row);
    }

    @Transactional
    public ResearchControlResponse resume(UUID actorUserId) {
        ResearchControl row = loadSingleton();
        row.setPaused(false);
        row.setReason(null);
        row.setUpdatedAt(Instant.now());
        row.setUpdatedByUserId(actorUserId);
        repository.save(row);
        log.info("Research loop resumed by user={}", actorUserId);
        return toResponse(row);
    }

    private ResearchControl loadSingleton() {
        return repository.findById(SINGLETON_ID).orElseThrow(() ->
                new IllegalStateException("research_control row missing — V23 migration not applied?"));
    }

    private ResearchControlResponse toResponse(ResearchControl row) {
        return ResearchControlResponse.builder()
                .paused(row.isPaused())
                .reason(row.getReason())
                .updatedAt(row.getUpdatedAt())
                .updatedByUserId(row.getUpdatedByUserId())
                .build();
    }
}

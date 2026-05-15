package id.co.blackheart.service.research;

import id.co.blackheart.dto.request.CreateResearchQueueItemRequest;
import id.co.blackheart.dto.request.UpdateResearchQueueItemRequest;
import id.co.blackheart.dto.response.ResearchQueueItemResponse;
import id.co.blackheart.model.ResearchQueueItem;
import id.co.blackheart.repository.ResearchQueueRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Admin-facing CRUD over the research queue. Mirrors the surface of
 * {@code research/scripts/queue-strategy.sh} so the operator no longer
 * needs shell access for routine experiment scheduling.
 *
 * <p>Cancel semantics: we don't delete rows (orchestrator state machine
 * + iteration_log FKs make hard-delete fragile). Cancel transitions any
 * non-terminal status to {@code COMPLETED} with {@code finalVerdict =
 * CANCELLED} and a timestamped {@code notes} entry. The orchestrator's
 * row-claim filter (status='PENDING') skips cancelled rows naturally.
 */
@Slf4j
@Service
@Profile("research")
@RequiredArgsConstructor
public class ResearchQueueService {

    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String VERDICT_CANCELLED = "CANCELLED";
    private static final String DEFAULT_INSTRUMENT = "BTCUSDT";
    private static final int DEFAULT_PRIORITY = 100;

    private final ResearchQueueRepository repository;

    public List<ResearchQueueItemResponse> list(String strategyCode, List<String> statuses) {
        List<String> normalized = CollectionUtils.isEmpty(statuses) ? null : statuses;
        return repository.findFiltered(strategyCode, normalized).stream()
                .map(ResearchQueueItemResponse::from)
                .toList();
    }

    @Transactional
    public ResearchQueueItemResponse create(CreateResearchQueueItemRequest req, String actor) {
        ResearchQueueItem item = ResearchQueueItem.builder()
                .queueId(UUID.randomUUID())
                .priority(req.getPriority() != null ? req.getPriority() : DEFAULT_PRIORITY)
                .strategyCode(req.getStrategyCode())
                .intervalName(req.getIntervalName())
                .instrument(StringUtils.hasText(req.getInstrument())
                        ? req.getInstrument()
                        : DEFAULT_INSTRUMENT)
                .sweepConfig(req.getSweepConfig())
                .hypothesis(req.getHypothesis())
                .status(STATUS_PENDING)
                .iterationNumber(0)
                .iterBudget(req.getIterBudget())
                .earlyStopOnNoEdge(!Boolean.FALSE.equals(req.getEarlyStopOnNoEdge()))
                .requireWalkForward(!Boolean.FALSE.equals(req.getRequireWalkForward()))
                .createdTime(LocalDateTime.now())
                .createdBy(actor)
                .build();
        ResearchQueueItem saved = repository.save(item);
        log.info("Queued research item: id={} strategy={} interval={} budget={} actor={}",
                saved.getQueueId(), saved.getStrategyCode(), saved.getIntervalName(),
                saved.getIterBudget(), actor);
        return ResearchQueueItemResponse.from(saved);
    }

    @Transactional
    public ResearchQueueItemResponse updatePriority(UUID queueId, UpdateResearchQueueItemRequest req) {
        ResearchQueueItem item = repository.findById(queueId)
                .orElseThrow(() -> new EntityNotFoundException("Queue item not found: " + queueId));
        if (req.getPriority() != null) {
            item.setPriority(req.getPriority());
        }
        return ResearchQueueItemResponse.from(repository.save(item));
    }

    /**
     * Soft-cancel: mark COMPLETED with verdict=CANCELLED. Refused for
     * already-terminal rows (COMPLETED / PARKED / FAILED) so the audit
     * trail does not get rewritten by accident.
     */
    @Transactional
    public ResearchQueueItemResponse cancel(UUID queueId, String actor) {
        ResearchQueueItem item = repository.findById(queueId)
                .orElseThrow(() -> new EntityNotFoundException("Queue item not found: " + queueId));
        if (!STATUS_PENDING.equals(item.getStatus()) && !STATUS_RUNNING.equals(item.getStatus())) {
            throw new IllegalStateException(
                    "Cannot cancel queue item in terminal status: " + item.getStatus());
        }
        item.setStatus(STATUS_COMPLETED);
        item.setFinalVerdict(VERDICT_CANCELLED);
        item.setCompletedAt(LocalDateTime.now());
        String prior = item.getNotes() == null ? "" : item.getNotes() + "\n";
        item.setNotes(prior + "[" + LocalDateTime.now() + "] Cancelled via UI by " + actor);
        log.info("Cancelled research queue item: id={} actor={}", queueId, actor);
        return ResearchQueueItemResponse.from(repository.save(item));
    }
}

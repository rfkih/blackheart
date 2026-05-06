package id.co.blackheart.service.marketdata.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import id.co.blackheart.model.HistoricalBackfillJob;
import id.co.blackheart.model.JobStatus;
import id.co.blackheart.model.JobType;
import id.co.blackheart.repository.HistoricalBackfillJobRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Front door for the historical backfill job system. Submits new jobs,
 * exposes status/cancel/list operations, and hands the heavy work off to
 * {@link HistoricalBackfillJobAsyncRunner} so the controller never blocks
 * on a long backfill.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricalBackfillJobService {

    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MAX_LIST_LIMIT = 500;

    private final HistoricalBackfillJobRepository repository;
    private final HistoricalJobHandlerRegistry registry;
    private final HistoricalBackfillJobAsyncRunner asyncRunner;

    /**
     * Persist a PENDING row and dispatch async execution. Returns the job
     * with a fresh UUID — the controller relays this to the client so the
     * UI can start polling immediately.
     *
     * <p>Submission is rejected upfront if no handler is registered for the
     * requested {@link JobType} — better to surface as a 400 than to flip
     * the row to FAILED a moment later.
     */
    @Transactional
    public HistoricalBackfillJob submit(JobType jobType, String symbol, String interval,
                                        JsonNode params, UUID userId) {
        if (jobType == null) {
            throw new IllegalArgumentException("jobType cannot be null");
        }
        if (!registry.isRegistered(jobType)) {
            throw new IllegalArgumentException("No handler registered for jobType=" + jobType);
        }

        HistoricalBackfillJob job = HistoricalBackfillJob.builder()
                .jobId(UUID.randomUUID())
                .jobType(jobType)
                .symbol(symbol)
                .interval(interval)
                .params(params != null ? params : JsonNodeFactory.instance.objectNode())
                .status(JobStatus.PENDING)
                .progressDone(0)
                .progressTotal(0)
                .cancelRequested(false)
                .createdByUserId(userId)
                .createdAt(LocalDateTime.now())
                .build();

        HistoricalBackfillJob persisted = repository.save(job);
        log.info("Historical backfill job submitted | jobId={} type={} symbol={} interval={}",
                persisted.getJobId(), jobType, symbol, interval);

        // Hand off to the async runner — but only AFTER the parent transaction
        // commits. Without this, the @Async worker thread can pick up the task
        // and SELECT the job by id before the inserting transaction's commit
        // is visible, throwing EntityNotFoundException and leaving the row
        // stuck in PENDING. Registering an afterCommit synchronization closes
        // that race; if no transaction is active (e.g. tests), call directly.
        UUID jobIdForRunner = persisted.getJobId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    asyncRunner.runAsync(jobIdForRunner);
                }
            });
        } else {
            asyncRunner.runAsync(jobIdForRunner);
        }

        return persisted;
    }

    @Transactional(readOnly = true)
    public HistoricalBackfillJob get(UUID jobId) {
        return repository.findById(jobId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Historical backfill job not found: " + jobId));
    }

    /**
     * Cooperative cancel — flips {@code cancel_requested} so the running
     * handler exits at its next polling point. Already-finished jobs are
     * a no-op; PENDING jobs that haven't started yet will be skipped by
     * the runner when it sees the flag.
     */
    @Transactional
    public HistoricalBackfillJob requestCancel(UUID jobId) {
        HistoricalBackfillJob job = get(jobId);
        if (job.getStatus() == JobStatus.SUCCESS
                || job.getStatus() == JobStatus.FAILED
                || job.getStatus() == JobStatus.CANCELLED) {
            return job;
        }
        if (!job.isCancelRequested()) {
            job.setCancelRequested(true);
            repository.save(job);
            log.info("Cancel requested | jobId={} status={}", jobId, job.getStatus());
        }
        return job;
    }

    @Transactional(readOnly = true)
    public List<HistoricalBackfillJob> list(JobStatus status, Integer limit) {
        int capped = capLimit(limit);
        if (status == null) {
            return repository.findTopOrderByCreatedAtDesc(PageRequest.of(0, capped));
        }
        return repository.findTopByStatusOrderByCreatedAtDesc(status, PageRequest.of(0, capped));
    }

    @Transactional(readOnly = true)
    public List<HistoricalBackfillJob> listActive() {
        return repository.findActive();
    }

    private int capLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIST_LIMIT;
        return Math.min(limit, MAX_LIST_LIMIT);
    }
}

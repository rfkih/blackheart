package id.co.blackheart.service.marketdata.job;

import com.fasterxml.jackson.databind.JsonNode;
import id.co.blackheart.model.HistoricalBackfillJob;
import id.co.blackheart.model.JobStatus;
import id.co.blackheart.repository.HistoricalBackfillJobRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Persists job lifecycle transitions and progress ticks for the historical
 * backfill job system. Each method runs in its own
 * {@code REQUIRES_NEW} transaction so polling clients see status / phase /
 * progress flips immediately, even when a handler is mid-way through a
 * long inner transaction.
 *
 * <p><b>Why this lives in its own bean</b>: the async runner and
 * {@link JobContextImpl} call into these methods from inside a worker
 * thread that may already be in a transaction (e.g. inside a
 * {@code TransactionTemplate.execute(...)}). Calling {@code @Transactional}
 * methods on the same bean is a no-op — Spring's proxy is bypassed for
 * self-invocation, the {@code REQUIRES_NEW} advice never fires, and
 * progress writes silently participate in the caller's transaction
 * (invisible to pollers until that outer tx commits).
 *
 * <p>Putting these methods on a separate Spring bean means callers always
 * cross a proxy boundary, the {@code REQUIRES_NEW} advice fires, and each
 * persist commits independently. Polling clients see updates within ~ms.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricalBackfillJobLifecycle {

    private final HistoricalBackfillJobRepository repository;

    // ── Lifecycle transitions ────────────────────────────────────────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public HistoricalBackfillJob markRunning(UUID jobId) {
        HistoricalBackfillJob job = repository.findById(jobId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "Historical backfill job not found: " + jobId));
        // Idempotent: if the worker re-enters somehow, don't re-stamp started_at.
        if (job.getStatus() == JobStatus.PENDING) {
            job.setStatus(JobStatus.RUNNING);
            job.setStartedAt(LocalDateTime.now());
            repository.save(job);
        }
        return job;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSuccess(UUID jobId, JsonNode result) {
        HistoricalBackfillJob job = repository.findById(jobId).orElse(null);
        if (job == null) return;
        job.setStatus(JobStatus.SUCCESS);
        job.setFinishedAt(LocalDateTime.now());
        if (result != null) job.setResult(result);
        repository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID jobId, String errorClass, String errorMessage) {
        HistoricalBackfillJob job = repository.findById(jobId).orElse(null);
        if (job == null) return;
        job.setStatus(JobStatus.FAILED);
        job.setFinishedAt(LocalDateTime.now());
        job.setErrorClass(errorClass);
        job.setErrorMessage(errorMessage);
        repository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCancelled(UUID jobId, JsonNode partialResult) {
        HistoricalBackfillJob job = repository.findById(jobId).orElse(null);
        if (job == null) return;
        job.setStatus(JobStatus.CANCELLED);
        job.setFinishedAt(LocalDateTime.now());
        if (partialResult != null) job.setResult(partialResult);
        repository.save(job);
    }

    // ── Progress + phase + cancel-flag (called by JobContextImpl) ────────────

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistPhase(UUID jobId, String phase) {
        HistoricalBackfillJob job = repository.findById(jobId).orElse(null);
        if (job == null) return;
        job.setPhase(phase);
        repository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void persistProgress(UUID jobId, int done, int total) {
        HistoricalBackfillJob job = repository.findById(jobId).orElse(null);
        if (job == null) return;
        job.setProgressDone(Math.max(0, done));
        job.setProgressTotal(Math.max(0, total));
        repository.save(job);
    }

    /**
     * Reads the latest {@code cancel_requested} value in a fresh transaction
     * so the handler observes any cancel that another transaction
     * committed between chunks.
     */
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public boolean readCancelFlag(UUID jobId) {
        return repository.findById(jobId)
                .map(HistoricalBackfillJob::isCancelRequested)
                .orElse(false);
    }
}
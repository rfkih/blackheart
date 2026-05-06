package id.co.blackheart.service.marketdata.job;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.co.blackheart.model.HistoricalBackfillJob;
import id.co.blackheart.model.JobStatus;
import id.co.blackheart.model.JobType;
import id.co.blackheart.repository.HistoricalBackfillJobRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lifecycle tests for the job service. Focuses on:
 * <ul>
 *   <li><b>submit()</b>: rejects unknown JobTypes upfront, persists PENDING,
 *       and dispatches to the async runner exactly once.</li>
 *   <li><b>requestCancel()</b>: idempotent — flips the flag once, ignores
 *       already-terminal jobs, never errors on double-cancel.</li>
 *   <li><b>get()</b>: delegates to repository, surfaces missing jobs as
 *       {@link EntityNotFoundException}.</li>
 * </ul>
 *
 * <p>The afterCommit synchronization in submit() is exercised indirectly:
 * when no transaction is active (this test runs without Spring context),
 * the runner is invoked directly.
 */
@ExtendWith(MockitoExtension.class)
class HistoricalBackfillJobServiceTest {

    @Mock private HistoricalBackfillJobRepository repository;
    @Mock private HistoricalJobHandlerRegistry registry;
    @Mock private HistoricalBackfillJobAsyncRunner asyncRunner;

    private HistoricalBackfillJobService service;

    @BeforeEach
    void setUp() {
        service = new HistoricalBackfillJobService(repository, registry, asyncRunner);
        // save() is invoked with a freshly-built entity in submit-style tests
        // — return the arg verbatim so the test sees the same instance.
        // lenient() because the cancel/get/list tests don't exercise save().
        lenient().when(repository.save(any(HistoricalBackfillJob.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ── submit ───────────────────────────────────────────────────────────────

    @Test
    void submit_unregisteredJobType_throwsBeforePersist() {
        when(registry.isRegistered(JobType.COVERAGE_REPAIR)).thenReturn(false);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.submit(JobType.COVERAGE_REPAIR, "BTCUSDT", "1h", null, null)
        );
        assertTrue(ex.getMessage().contains("No handler registered"));
        verify(repository, never()).save(any());
        verify(asyncRunner, never()).runAsync(any());
    }

    @Test
    void submit_nullJobType_throwsBeforePersist() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> service.submit(null, "BTCUSDT", "1h", null, null)
        );
        assertTrue(ex.getMessage().contains("jobType"));
        verify(repository, never()).save(any());
    }

    @Test
    void submit_validJobType_persistsPendingAndDispatches() {
        when(registry.isRegistered(JobType.COVERAGE_REPAIR)).thenReturn(true);
        ObjectNode params = JsonNodeFactory.instance.objectNode().put("mode", "warmup");

        HistoricalBackfillJob result = service.submit(
                JobType.COVERAGE_REPAIR, "BTCUSDT", "1h", params, null);

        assertNotNull(result);
        assertEquals(JobStatus.PENDING, result.getStatus());
        assertEquals(JobType.COVERAGE_REPAIR, result.getJobType());
        assertEquals("BTCUSDT", result.getSymbol());
        assertEquals("1h", result.getInterval());
        assertNotNull(result.getJobId());
        assertNotNull(result.getCreatedAt());
        assertSame(params, result.getParams());
        assertFalse(result.isCancelRequested());
        assertEquals(0, result.getProgressDone());
        assertEquals(0, result.getProgressTotal());

        verify(repository).save(any(HistoricalBackfillJob.class));
        // Without an active transaction, runAsync fires synchronously.
        verify(asyncRunner).runAsync(result.getJobId());
    }

    @Test
    void submit_nullParams_substitutesEmptyObject() {
        when(registry.isRegistered(JobType.BACKFILL_FUNDING_HISTORY)).thenReturn(true);

        HistoricalBackfillJob result = service.submit(
                JobType.BACKFILL_FUNDING_HISTORY, "BTCUSDT", null, null, null);

        assertNotNull(result.getParams());
        assertEquals(0, result.getParams().size(),
                "null params should normalize to an empty JSON object");
    }

    @Test
    void submit_capturesUserId() {
        when(registry.isRegistered(JobType.RECOMPUTE_RANGE)).thenReturn(true);
        UUID userId = UUID.randomUUID();

        HistoricalBackfillJob result = service.submit(
                JobType.RECOMPUTE_RANGE, "BTCUSDT", "1h", null, userId);

        assertEquals(userId, result.getCreatedByUserId());
    }

    // ── get ──────────────────────────────────────────────────────────────────

    @Test
    void get_existingJob_returnsIt() {
        UUID id = UUID.randomUUID();
        HistoricalBackfillJob job = HistoricalBackfillJob.builder()
                .jobId(id).status(JobStatus.RUNNING).build();
        when(repository.findById(id)).thenReturn(Optional.of(job));

        assertSame(job, service.get(id));
    }

    @Test
    void get_missingJob_throws() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.get(id));
    }

    // ── requestCancel ────────────────────────────────────────────────────────

    @Test
    void requestCancel_pendingJob_setsFlagAndSaves() {
        UUID id = UUID.randomUUID();
        HistoricalBackfillJob job = HistoricalBackfillJob.builder()
                .jobId(id).status(JobStatus.PENDING).cancelRequested(false).build();
        when(repository.findById(id)).thenReturn(Optional.of(job));

        HistoricalBackfillJob result = service.requestCancel(id);

        assertTrue(result.isCancelRequested());
        ArgumentCaptor<HistoricalBackfillJob> saved = ArgumentCaptor.forClass(HistoricalBackfillJob.class);
        verify(repository).save(saved.capture());
        assertTrue(saved.getValue().isCancelRequested());
    }

    @Test
    void requestCancel_runningJob_setsFlagAndSaves() {
        UUID id = UUID.randomUUID();
        HistoricalBackfillJob job = HistoricalBackfillJob.builder()
                .jobId(id).status(JobStatus.RUNNING).cancelRequested(false).build();
        when(repository.findById(id)).thenReturn(Optional.of(job));

        service.requestCancel(id);

        verify(repository).save(any(HistoricalBackfillJob.class));
    }

    @Test
    void requestCancel_alreadyCancelRequested_isIdempotent() {
        UUID id = UUID.randomUUID();
        HistoricalBackfillJob job = HistoricalBackfillJob.builder()
                .jobId(id).status(JobStatus.RUNNING).cancelRequested(true).build();
        when(repository.findById(id)).thenReturn(Optional.of(job));

        service.requestCancel(id);

        // Already requested — no second save.
        verify(repository, never()).save(any());
    }

    @Test
    void requestCancel_terminalSuccess_isNoOp() {
        UUID id = UUID.randomUUID();
        HistoricalBackfillJob job = HistoricalBackfillJob.builder()
                .jobId(id).status(JobStatus.SUCCESS).cancelRequested(false).build();
        when(repository.findById(id)).thenReturn(Optional.of(job));

        HistoricalBackfillJob result = service.requestCancel(id);

        assertFalse(result.isCancelRequested(),
                "Cancel on a SUCCESS job should not flip the flag");
        verify(repository, never()).save(any());
    }

    @Test
    void requestCancel_terminalFailed_isNoOp() {
        UUID id = UUID.randomUUID();
        HistoricalBackfillJob job = HistoricalBackfillJob.builder()
                .jobId(id).status(JobStatus.FAILED).cancelRequested(false).build();
        when(repository.findById(id)).thenReturn(Optional.of(job));

        service.requestCancel(id);
        verify(repository, never()).save(any());
    }

    @Test
    void requestCancel_terminalCancelled_isNoOp() {
        UUID id = UUID.randomUUID();
        HistoricalBackfillJob job = HistoricalBackfillJob.builder()
                .jobId(id).status(JobStatus.CANCELLED).cancelRequested(true).build();
        when(repository.findById(id)).thenReturn(Optional.of(job));

        service.requestCancel(id);
        verify(repository, never()).save(any());
    }

    @Test
    void requestCancel_missingJob_throws() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThrows(EntityNotFoundException.class, () -> service.requestCancel(id));
    }

    // ── list ─────────────────────────────────────────────────────────────────

    @Test
    void list_capsLimitToDefault_whenNullOrZero() {
        // Just verify it doesn't crash + makes one repo call. Limit-cap math
        // is internal detail of capLimit().
        when(repository.findTopOrderByCreatedAtDesc(any())).thenReturn(java.util.List.of());

        service.list(null, null);
        service.list(null, 0);
        service.list(null, -5);

        verify(repository, times(3)).findTopOrderByCreatedAtDesc(any());
    }

    @Test
    void list_byStatus_dispatchesToStatusQuery() {
        when(repository.findTopByStatusOrderByCreatedAtDesc(eq(JobStatus.RUNNING), any()))
                .thenReturn(java.util.List.of());

        service.list(JobStatus.RUNNING, 50);

        verify(repository).findTopByStatusOrderByCreatedAtDesc(eq(JobStatus.RUNNING), any());
        verify(repository, never()).findTopOrderByCreatedAtDesc(any());
    }
}

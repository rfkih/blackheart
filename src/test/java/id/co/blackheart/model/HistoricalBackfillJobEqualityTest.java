package id.co.blackheart.model;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks in the id-keyed equality contract for {@link HistoricalBackfillJob}.
 *
 * <p>Lombok's {@code @Data} would generate all-fields equals/hashCode,
 * which is incorrect for JPA managed entities (mutable rows compared by
 * pre-flush values). The custom override on the entity should:
 * <ul>
 *   <li>Treat two managed rows with the same {@code jobId} as equal,
 *       regardless of mutated state.</li>
 *   <li>Treat rows with different {@code jobId} as unequal.</li>
 *   <li>Use a constant {@code hashCode} so the value is stable across
 *       pre-/post-persist lifecycle transitions.</li>
 * </ul>
 */
class HistoricalBackfillJobEqualityTest {

    private HistoricalBackfillJob job(UUID id, JobStatus status) {
        return HistoricalBackfillJob.builder()
                .jobId(id)
                .status(status)
                .jobType(JobType.COVERAGE_REPAIR)
                .build();
    }

    @Test
    void sameId_differentStatus_areEqual() {
        UUID id = UUID.randomUUID();
        HistoricalBackfillJob a = job(id, JobStatus.PENDING);
        HistoricalBackfillJob b = job(id, JobStatus.RUNNING);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void differentIds_areUnequal() {
        HistoricalBackfillJob a = job(UUID.randomUUID(), JobStatus.PENDING);
        HistoricalBackfillJob b = job(UUID.randomUUID(), JobStatus.PENDING);

        assertNotEquals(a, b);
    }

    @Test
    void mutatingNonIdField_doesNotChangeEquality() {
        UUID id = UUID.randomUUID();
        HistoricalBackfillJob a = job(id, JobStatus.PENDING);
        HistoricalBackfillJob b = job(id, JobStatus.PENDING);

        b.setProgressDone(42);
        b.setProgressTotal(100);
        b.setPhase("bulk:write_rows");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void hashCode_isStableAcrossMutation() {
        HistoricalBackfillJob a = job(UUID.randomUUID(), JobStatus.PENDING);
        int before = a.hashCode();

        a.setStatus(JobStatus.RUNNING);
        a.setProgressDone(500);
        a.setPhase("recompute:delete_then_insert");

        assertEquals(before, a.hashCode());
    }

    @Test
    void worksInHashSet_dedupesByIdNotByMutation() {
        UUID id = UUID.randomUUID();
        Set<HistoricalBackfillJob> seen = new HashSet<>();

        seen.add(job(id, JobStatus.PENDING));
        // Same id, different status — should NOT add a second copy.
        seen.add(job(id, JobStatus.RUNNING));
        // New id — should add.
        seen.add(job(UUID.randomUUID(), JobStatus.PENDING));

        assertEquals(2, seen.size());
    }

    @Test
    void notEquals_whenIdNull() {
        // Newly-built entities before id assignment shouldn't collide.
        HistoricalBackfillJob a = HistoricalBackfillJob.builder().build();
        HistoricalBackfillJob b = HistoricalBackfillJob.builder().build();

        assertFalse(a.equals(b));
    }

    @Test
    void notEquals_toUnrelatedType() {
        HistoricalBackfillJob a = job(UUID.randomUUID(), JobStatus.PENDING);
        assertNotEquals(a, "not a job");
        assertNotEquals(a, null);
    }

    @Test
    void equals_isReflexive() {
        HistoricalBackfillJob a = job(UUID.randomUUID(), JobStatus.PENDING);
        assertTrue(a.equals(a));
    }
}

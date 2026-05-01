package id.co.blackheart.repository;


import id.co.blackheart.model.SchedulerJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SchedulerJobRepository extends JpaRepository<SchedulerJob, Long> {
    List<SchedulerJob> findByStatus(String status);

    /**
     * Stamps last_run_at without loading + saving the entity. Called by the
     * router on every fire so the dashboard has a heartbeat for every job
     * type, not just IP_MONITOR.
     */
    @Modifying
    @Transactional
    @Query("UPDATE SchedulerJob j SET j.lastRunAt = :ts WHERE j.id = :id")
    void touchLastRun(@Param("id") Long id, @Param("ts") LocalDateTime ts);
}


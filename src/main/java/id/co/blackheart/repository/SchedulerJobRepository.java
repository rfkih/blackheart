package id.co.blackheart.repository;


import id.co.blackheart.model.SchedulerJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SchedulerJobRepository extends JpaRepository<SchedulerJob, Long> {
    List<SchedulerJob> findByStatus(String status);
    SchedulerJob findByJobName(String jobName);
}


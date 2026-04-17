package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "scheduler_jobs")
@Getter
@Setter
public class SchedulerJob extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_name", nullable = false, unique = true)
    private String jobName;

    @Column(name = "job_type", nullable = false)
    private String jobType;

    @Column(name = "cron_expression", nullable = false)
    private String cronExpression;

    @Column(name = "status", nullable = false)
    private String status;


}


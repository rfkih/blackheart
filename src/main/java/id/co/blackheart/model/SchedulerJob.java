package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "scheduler_jobs")
@Getter
@Setter
public class SchedulerJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_name", nullable = false, unique = true)
    private String jobName; // Unique job name (e.g., "train-model-job")

    @Column(name = "job_type", nullable = false)
    private String jobType; // e.g., "TRAIN_MODEL", "UPDATE_BALANCE"

    @Column(name = "cron_expression", nullable = false)
    private String cronExpression; // e.g., "0 0 * * * *"

    @Column(name = "status", nullable = false)
    private String status; // e.g., "0", "1"

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}


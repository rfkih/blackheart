package id.co.blackheart.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "training_jobs")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class TrainingJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "model", nullable = false)
    private String model;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "training_duration")
    private String trainingDuration;

    @Column(name = "training_accuracy")
    private Double trainingAccuracy;

    @Column(name = "validation_accuracy")
    private Double validationAccuracy;

    @Column(name = "label_buy_count")
    private Integer labelBuyCount;

    @Column(name = "label_hold_count")
    private Integer labelHoldCount;

    @Column(name = "label_sell_count")
    private Integer labelSellCount;

    @Column(name = "training_data_count")
    private Integer trainingDataCount;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @CreationTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
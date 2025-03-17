package id.co.blackheart.repository;

import id.co.blackheart.model.TrainingJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TrainingJobRepository extends JpaRepository<TrainingJob, Long> {
    // Optional: Add custom queries here if needed
    List<TrainingJob> findByModelAndStatus(String model, String status);
}

package id.co.blackheart.repository;

import id.co.blackheart.model.MlSourceHealth;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MlSourceHealthRepository extends JpaRepository<MlSourceHealth, String> {

    List<MlSourceHealth> findAllByOrderBySourceAsc();
}

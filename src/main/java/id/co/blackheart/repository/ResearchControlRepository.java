package id.co.blackheart.repository;

import id.co.blackheart.model.ResearchControl;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ResearchControlRepository extends JpaRepository<ResearchControl, Integer> {
}

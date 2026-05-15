package id.co.blackheart.repository;

import id.co.blackheart.model.CrossWindowRun;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CrossWindowRunRepository extends JpaRepository<CrossWindowRun, UUID> {

    /**
     * Most-recent cross-window runs for a (strategy, interval, instrument)
     * tuple. Caller usually wants the head row (latest verdict) plus a
     * couple of priors for the operator to eyeball drift.
     */
    List<CrossWindowRun> findByStrategyCodeAndIntervalNameAndInstrumentOrderByCreatedTimeDesc(
            String strategyCode,
            String intervalName,
            String instrument,
            Pageable pageable);
}

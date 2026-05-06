package id.co.blackheart.repository;

import id.co.blackheart.model.ServerIpLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ServerIpLogRepository extends JpaRepository<ServerIpLog, Long> {
    List<ServerIpLog> findAllByOrderByRecordedAtDesc();
    List<ServerIpLog> findTop100ByOrderByRecordedAtDesc();
    Optional<ServerIpLog> findTopByOrderByRecordedAtDesc();
}
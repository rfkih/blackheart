package id.co.blackheart.repository;

import id.co.blackheart.model.TradeExecutionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TradeExecutionLogRepository extends JpaRepository<TradeExecutionLog, UUID> {
}

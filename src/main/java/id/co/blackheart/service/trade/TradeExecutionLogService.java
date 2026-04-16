package id.co.blackheart.service.trade;

import id.co.blackheart.model.Account;
import id.co.blackheart.model.TradeExecutionLog;
import id.co.blackheart.repository.TradeExecutionLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class TradeExecutionLogService {

    private static final String SUCCESS = "SUCCESS";
    private static final String FAILED  = "FAILED";

    private final TradeExecutionLogRepository tradeExecutionLogRepository;

    public void logOpenSuccess(
            Account account,
            String asset,
            String strategyName,
            String side,
            String entryReason,
            UUID tradeId
    ) {
        record("OPEN", side, SUCCESS, account, asset, strategyName, entryReason, tradeId, null);
    }

    public void logOpenFailure(
            Account account,
            String asset,
            String strategyName,
            String side,
            String entryReason,
            UUID tradeId,
            String errorMessage
    ) {
        record("OPEN", side, FAILED, account, asset, strategyName, entryReason, tradeId, truncate(errorMessage));
    }

    public void logCloseSuccess(
            Account account,
            String asset,
            String side,
            String exitReason,
            UUID tradeId
    ) {
        record("CLOSE", side, SUCCESS, account, asset, null, exitReason, tradeId, null);
    }

    public void logCloseFailure(
            Account account,
            String asset,
            String side,
            String exitReason,
            UUID tradeId,
            String errorMessage
    ) {
        record("CLOSE", side, FAILED, account, asset, null, exitReason, tradeId, truncate(errorMessage));
    }

    private void record(
            String executionType,
            String side,
            String status,
            Account account,
            String asset,
            String strategyName,
            String executionReason,
            UUID tradeId,
            String errorMessage
    ) {
        try {
            TradeExecutionLog entry = TradeExecutionLog.builder()
                    .executionType(executionType)
                    .side(side)
                    .status(status)
                    .accountId(account != null ? account.getAccountId() : null)
                    .username(account != null ? account.getUsername() : null)
                    .asset(asset)
                    .strategyName(strategyName)
                    .executionReason(executionReason)
                    .tradeId(tradeId)
                    .errorMessage(errorMessage)
                    .executedAt(LocalDateTime.now())
                    .build();

            tradeExecutionLogRepository.save(entry);
        } catch (Exception e) {
            log.error("[TradeExecutionLog] Failed to persist execution log | type={} status={} account={} asset={}",
                    executionType, status, account != null ? account.getUsername() : "null", asset, e);
        }
    }

    private String truncate(String msg) {
        if (msg == null) return null;
        return msg.length() > 2000 ? msg.substring(0, 2000) : msg;
    }
}

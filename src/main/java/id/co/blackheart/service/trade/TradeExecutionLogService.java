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
        persistEntry(account,
                baseBuilder("OPEN", side, SUCCESS, account, asset, entryReason, tradeId)
                        .strategyName(strategyName)
                        .build());
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
        persistEntry(account,
                baseBuilder("OPEN", side, FAILED, account, asset, entryReason, tradeId)
                        .strategyName(strategyName)
                        .errorMessage(truncate(errorMessage))
                        .build());
    }

    public void logCloseSuccess(
            Account account,
            String asset,
            String side,
            String exitReason,
            UUID tradeId
    ) {
        persistEntry(account,
                baseBuilder("CLOSE", side, SUCCESS, account, asset, exitReason, tradeId)
                        .build());
    }

    public void logCloseFailure(
            Account account,
            String asset,
            String side,
            String exitReason,
            UUID tradeId,
            String errorMessage
    ) {
        persistEntry(account,
                baseBuilder("CLOSE", side, FAILED, account, asset, exitReason, tradeId)
                        .errorMessage(truncate(errorMessage))
                        .build());
    }

    private static TradeExecutionLog.TradeExecutionLogBuilder baseBuilder(
            String executionType,
            String side,
            String status,
            Account account,
            String asset,
            String executionReason,
            UUID tradeId
    ) {
        return TradeExecutionLog.builder()
                .executionType(executionType)
                .side(side)
                .status(status)
                .accountId(account != null ? account.getAccountId() : null)
                .username(account != null ? account.getUsername() : null)
                .asset(asset)
                .executionReason(executionReason)
                .tradeId(tradeId)
                .executedAt(LocalDateTime.now());
    }

    private void persistEntry(Account account, TradeExecutionLog entry) {
        try {
            tradeExecutionLogRepository.save(entry);
        } catch (Exception e) {
            log.error("[TradeExecutionLog] Failed to persist execution log | type={} status={} account={} asset={}",
                    entry.getExecutionType(), entry.getStatus(),
                    account != null ? account.getUsername() : "null", entry.getAsset(), e);
        }
    }

    private String truncate(String msg) {
        if (msg == null) return null;
        return msg.length() > 2000 ? msg.substring(0, 2000) : msg;
    }
}

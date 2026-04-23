package id.co.blackheart.service.websocket;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which account ids currently want live-PnL frames pushed to them.
 *
 * <p>Entries are added when a client sends {@code /app/pnl.subscribe} and
 * removed when that STOMP session disconnects (see
 * {@link LivePnlSessionLifecycleListener}). Without the cleanup path the set
 * grew monotonically for the process lifetime and the publisher burned a DB
 * query per second per stale id.
 *
 * <p>We key by STOMP session id too so a disconnect can know which account
 * ids to drop without the client having to send an unsubscribe frame.
 */
@Component
public class LivePnlSubscriptionRegistry {

    /** accountId → count of STOMP sessions currently subscribed. */
    private final Map<UUID, Integer> refCountByAccount = new ConcurrentHashMap<>();

    /** sessionId → set of account ids that session registered for. */
    private final Map<String, Set<UUID>> accountsBySession = new ConcurrentHashMap<>();

    public void addAccount(String sessionId, UUID accountId) {
        if (sessionId == null || accountId == null) return;
        accountsBySession
                .computeIfAbsent(sessionId, s -> ConcurrentHashMap.newKeySet())
                .add(accountId);
        refCountByAccount.merge(accountId, 1, Integer::sum);
    }

    /** Drops every account id this session registered. Safe to call repeatedly. */
    public void removeSession(String sessionId) {
        if (sessionId == null) return;
        Set<UUID> accounts = accountsBySession.remove(sessionId);
        if (accounts == null) return;
        for (UUID accountId : accounts) {
            refCountByAccount.computeIfPresent(accountId, (k, count) ->
                    count <= 1 ? null : count - 1);
        }
    }

    /** Currently-subscribed account ids. Returned view is a live snapshot. */
    public Set<UUID> getSubscribedAccountIds() {
        return refCountByAccount.keySet();
    }
}

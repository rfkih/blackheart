package id.co.blackheart.service.websocket;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LivePnlSubscriptionRegistry {

    private final Set<UUID> subscribedAcccountId = ConcurrentHashMap.newKeySet();

    public void addAccount(UUID accountId) {
        subscribedAcccountId.add(accountId);
    }

    public void removeAccount(UUID accountId) {
        subscribedAcccountId.remove(accountId);
    }

    public Set<UUID> getSubscribedAcccountId() {
        return subscribedAcccountId;
    }
}
package id.co.blackheart.service.websocket;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LivePnlSubscriptionRegistry {

    private final Set<UUID> subscribedUserIds = ConcurrentHashMap.newKeySet();

    public void addUser(UUID userId) {
        subscribedUserIds.add(userId);
    }

    public void removeUser(UUID userId) {
        subscribedUserIds.remove(userId);
    }

    public Set<UUID> getSubscribedUserIds() {
        return subscribedUserIds;
    }
}
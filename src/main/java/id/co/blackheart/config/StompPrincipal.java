package id.co.blackheart.config;

import java.security.Principal;
import java.util.Objects;
import java.util.UUID;

/**
 * Principal attached to every STOMP session after successful CONNECT. Carries
 * the authenticated user's id + role so controllers can authorise subscribe /
 * publish frames without re-parsing the JWT.
 */
public final class StompPrincipal implements Principal {

    private final String email;
    private final UUID userId;
    private final String role;

    public StompPrincipal(String email, UUID userId, String role) {
        this.email = Objects.requireNonNull(email, "email");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.role = role;
    }

    @Override
    public String getName() {
        return email;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getRole() {
        return role;
    }
}

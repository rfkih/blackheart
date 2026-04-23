package id.co.blackheart.service.user;

import id.co.blackheart.model.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Stateless JWT utility — generates and validates signed tokens.
 *
 * <p>Token claims:
 * <ul>
 *   <li>{@code sub}      — user email (standard subject claim)</li>
 *   <li>{@code userId}   — user UUID</li>
 *   <li>{@code role}     — coarse-grained role (USER / ADMIN / SUPER_ADMIN)</li>
 *   <li>{@code tenantId} — tenant UUID (omitted when null; for SaaS isolation)</li>
 * </ul>
 */
@Service
@Slf4j
public class JwtService {

    /**
     * Sentinel that matches the dev-only default in application.properties. If
     * the process starts with this value AND the active profile isn't `dev`
     * we refuse to boot — otherwise a forgotten JWT_SECRET override silently
     * ships a publicly-known signing key to production.
     */
    /** Matches the dev-only default in application.properties byte-for-byte. */
    private static final String DEV_ONLY_SECRET_SENTINEL =
            "ZGV2LW9ubHktaW5zZWN1cmUta2V5LWNoYW5nZS1tZS12aWEtSldUX1NFQ1JFVC1lbnY=";

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Value("${app.jwt.expiration-ms:86400000}")
    private long jwtExpirationMs;

    private final Environment environment;

    public JwtService(Environment environment) {
        this.environment = environment;
    }

    /**
     * Profile names where the dev-only sentinel secret is legitimate.
     * <b>Inverted from an earlier version.</b> The previous logic refused to
     * boot only when the profile was one of {prod, staging, …}; a process
     * launched with no active profile at all therefore silently shipped the
     * publicly-known key. Now we require an explicit dev/test/local profile
     * for the sentinel — anything else (including "no profile") is rejected.
     */
    private static final java.util.Set<String> DEV_SAFE_PROFILES =
            java.util.Set.of("dev", "test", "local");

    @PostConstruct
    void validateSecretOnStartup() {
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException(
                    "app.jwt.secret is not configured — set the JWT_SECRET env var to a Base64-encoded 256-bit key"
            );
        }
        if (DEV_ONLY_SECRET_SENTINEL.equals(jwtSecret)) {
            boolean devSafe = Arrays.stream(environment.getActiveProfiles())
                    .map(String::toLowerCase)
                    .anyMatch(DEV_SAFE_PROFILES::contains);
            if (!devSafe) {
                throw new IllegalStateException(
                        "Refusing to start: app.jwt.secret is the dev-only sentinel. "
                                + "Either set JWT_SECRET to a real Base64-encoded 256-bit key, or "
                                + "launch with SPRING_PROFILES_ACTIVE=dev|test|local to acknowledge "
                                + "that this environment is non-production."
                );
            }
            log.warn(
                    "===== SECURITY WARNING =====\n"
                            + "Starting with the INSECURE dev-only JWT secret. This key is committed to the\n"
                            + "repo and anyone can mint tokens against this instance. Acceptable for local\n"
                            + "development only — set the JWT_SECRET env var before exposing this process\n"
                            + "on any network other than localhost."
            );
        }
    }

    // ── Token generation ──────────────────────────────────────────────────────

    public String generateToken(User user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", user.getUserId().toString());
        claims.put("role", user.getRole());
        return buildToken(claims, user.getEmail(), jwtExpirationMs);
    }

    /**
     * Short-lived (60 s) JWT used exclusively for opening a STOMP WebSocket.
     * The browser fetches this with its HttpOnly session cookie, then sends it
     * in the STOMP CONNECT {@code Authorization} header. Short TTL keeps the
     * exposure window narrow even if the ticket leaks via dev tools.
     */
    public String generateShortLivedTicket(String email, UUID userId, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId.toString());
        claims.put("role", role);
        return buildToken(claims, email, 60_000L);
    }

    private String buildToken(Map<String, Object> extraClaims, String subject, long ttlMs) {
        return Jwts.builder()
                .claims(extraClaims)
                .subject(subject)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + ttlMs))
                .signWith(getSignKey())
                .compact();
    }

    // ── Token validation ──────────────────────────────────────────────────────

    public boolean isTokenValid(String token, String email) {
        try {
            final String tokenEmail = extractEmail(token);
            return tokenEmail.equals(email) && !isTokenExpired(token);
        } catch (JwtException e) {
            log.warn("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }

    // ── Claim extraction ──────────────────────────────────────────────────────

    public String extractEmail(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public UUID extractUserId(String token) {
        String userId = extractClaim(token, claims -> claims.get("userId", String.class));
        return UUID.fromString(userId);
    }

    public String extractRole(String token) {
        return extractClaim(token, claims -> claims.get("role", String.class));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSignKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey getSignKey() {
        byte[] keyBytes = Decoders.BASE64.decode(jwtSecret);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    public long getExpirationMs() {
        return jwtExpirationMs;
    }
}

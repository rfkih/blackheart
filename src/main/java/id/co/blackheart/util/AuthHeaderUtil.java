package id.co.blackheart.util;

import org.springframework.util.StringUtils;

public final class AuthHeaderUtil {

    private static final String BEARER_PREFIX = "Bearer ";

    private AuthHeaderUtil() {}

    /**
     * Extracts the JWT token from an Authorization header.
     * Validates that the header is non-null and starts with "Bearer ".
     *
     * @param authHeader the full Authorization header value
     * @return the raw JWT token without the "Bearer " prefix
     * @throws IllegalArgumentException if the header is null, blank, or does not start with "Bearer "
     */
    public static String extractToken(String authHeader) {
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            throw new IllegalArgumentException(
                    "Authorization header must be 'Bearer <token>'");
        }
        String token = authHeader.substring(BEARER_PREFIX.length());
        if (!StringUtils.hasText(token)) {
            throw new IllegalArgumentException(
                    "Authorization header must be 'Bearer <token>' — token is blank");
        }
        return token;
    }
}
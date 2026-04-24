package id.co.blackheart.service.user;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

/**
 * Issues and clears the authentication cookie that carries the user's JWT.
 *
 * <p>The cookie is:
 * <ul>
 *   <li><b>HttpOnly</b> — inaccessible to JS; XSS cannot exfiltrate it</li>
 *   <li><b>Secure</b> (in production profiles) — sent only over HTTPS</li>
 *   <li><b>SameSite=Lax</b> — mitigates CSRF for top-level cross-site navigations
 *       while still allowing the frontend origin to send it on its own XHRs</li>
 *   <li><b>Path=/</b> — available to every API route, including
 *       {@code /ws} which carries the handshake</li>
 * </ul>
 *
 * <p>The cookie name is deliberately the same {@code blackheart-token} used by
 * the Next.js middleware route-gate so both layers read the same signal. Next
 * middleware runs on the edge and CAN read HttpOnly cookies — only browser JS
 * cannot.
 */
@Service
@Slf4j
public class JwtCookieService {

    public static final String COOKIE_NAME = "blackheart-token";

    @Value("${app.jwt.expiration-ms:900000}")
    private long jwtExpirationMs;

    @Value("${app.cookie.secure:true}")
    private boolean cookieSecure;

    @Value("${app.cookie.samesite:Lax}")
    private String cookieSameSite;

    public void issue(HttpServletResponse response, String token) {
        long maxAgeSeconds = Math.max(1, jwtExpirationMs / 1000);
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, token)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }

    public void clear(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(COOKIE_NAME, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader("Set-Cookie", cookie.toString());
    }
}

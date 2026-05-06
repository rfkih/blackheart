package id.co.blackheart.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.util.ResponseCode;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Rate limits public + abuse-prone endpoints. Per-remote-IP token buckets;
 * in-process state — swap the {@link ConcurrentMap} backing for a Bucket4j
 * Redis adapter when running multiple replicas.
 *
 * <p>Limits:
 * <ul>
 *   <li><b>login</b> (POST /api/v1/users/login): 10 / 5 min, burst 10</li>
 *   <li><b>register</b> (POST /api/v1/users/register): 5 / hour, burst 5</li>
 *   <li><b>support</b> (POST /api/v1/support): 10 / hour, burst 5 — keeps a
 *       compromised or malicious user from flooding the admin inbox while
 *       still allowing a real user to file several reports back-to-back</li>
 * </ul>
 *
 * <p>Runs <b>before</b> {@link JwtAuthenticationFilter} so floods short-circuit
 * before touching the user-details cache or the database.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/api/v1/users/login";
    private static final String REGISTER_PATH = "/api/v1/users/register";
    private static final String SUPPORT_PATH = "/api/v1/support";

    private final ObjectMapper objectMapper;

    private final ConcurrentMap<String, Bucket> loginBuckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Bucket> registerBuckets = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Bucket> supportBuckets = new ConcurrentHashMap<>();

    private Bucket resolveBucket(ConcurrentMap<String, Bucket> store, String key, Bandwidth policy) {
        return store.computeIfAbsent(key, k -> Bucket.builder().addLimit(policy).build());
    }

    private static Bandwidth loginPolicy() {
        return Bandwidth.builder().capacity(10).refillGreedy(10, Duration.ofMinutes(5)).build();
    }

    private static Bandwidth registerPolicy() {
        return Bandwidth.builder().capacity(5).refillGreedy(5, Duration.ofHours(1)).build();
    }

    private static Bandwidth supportPolicy() {
        return Bandwidth.builder().capacity(10).refillGreedy(10, Duration.ofHours(1)).build();
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        // Skip CORS preflight — they're browser-initiated, have no body, and
        // counting them would halve the effective rate budget for real
        // attempts. Also skip non-POST methods on the rate-limited paths.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())
                || !"POST".equalsIgnoreCase(request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        final String path = request.getServletPath();
        final boolean isLogin = LOGIN_PATH.equals(path);
        final boolean isRegister = REGISTER_PATH.equals(path);
        // SUPPORT_PATH covers the bare collection POST only — the path equals
        // the constant exactly. Status PATCH and admin GET use longer paths
        // (PATCH /{id}, GET with query) and aren't matched here.
        final boolean isSupport = SUPPORT_PATH.equals(path);

        if (!isLogin && !isRegister && !isSupport) {
            filterChain.doFilter(request, response);
            return;
        }

        String key = clientKey(request);
        Bucket bucket;
        if (isLogin) {
            bucket = resolveBucket(loginBuckets, key, loginPolicy());
        } else if (isRegister) {
            bucket = resolveBucket(registerBuckets, key, registerPolicy());
        } else {
            bucket = resolveBucket(supportBuckets, key, supportPolicy());
        }

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
            return;
        }

        log.warn("Rate limit hit: path={} client={}", path, key);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", "60");
        objectMapper.writeValue(response.getOutputStream(), ResponseDto.builder()
                .responseCode(HttpStatus.TOO_MANY_REQUESTS.value() + ResponseCode.RATE_LIMITED.getCode())
                .responseDesc(ResponseCode.RATE_LIMITED.getDescription())
                .errorMessage("Too many attempts. Try again in a minute.")
                .build());
    }

    /**
     * Uses X-Forwarded-For when present (behind a trusted proxy). Defaults to
     * the direct remote address. If you run behind a proxy you don't trust,
     * remove the header path — the leftmost value can be spoofed and would
     * let an attacker cycle keys to bypass the limit.
     */
    private String clientKey(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            int comma = forwarded.indexOf(',');
            String first = (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
            if (StringUtils.hasText(first)) return first;
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }
}

package id.co.blackheart.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.user.JwtCookieService;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.service.user.UserDetailsServiceImpl;
import id.co.blackheart.util.ResponseCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.Enumeration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

/**
 * Stateless JWT bearer-token authentication filter.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>No {@code Authorization} header → pass through (handled by Spring Security's
 *       authorization filter which will return 401 via the entry point).</li>
 *   <li>Header present but token invalid/expired → write 401 JSON response immediately.</li>
 *   <li>Token valid → populate {@link SecurityContextHolder} and continue.</li>
 * </ul>
 *
 * <p>Resolved {@link UserDetails} are cached in-process for a short TTL so the
 * hot path doesn't hit the database on every REST call. Invalidation is by
 * time; role/status changes become effective within {@code CACHE_TTL}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);
    /**
     * Hard cap on cached principals. A reasonable upper bound for concurrent
     * distinct users hitting the API within one TTL — well above observed
     * traffic. Prevents an attacker from flooding the filter with unique
     * token payloads to exhaust heap.
     */
    private static final long CACHE_MAX_SIZE = 10_000L;

    private final JwtService jwtService;
    private final UserDetailsServiceImpl userDetailsService;
    private final ObjectMapper objectMapper;

    /**
     * Caffeine-backed bounded cache. W-TinyLFU eviction + explicit max-size
     * prevents the unbounded-growth footgun of the previous ConcurrentHashMap
     * (every distinct email authenticated stayed in memory forever).
     */
    private final Cache<String, UserDetails> userCache = Caffeine.newBuilder()
            .maximumSize(CACHE_MAX_SIZE)
            .expireAfterWrite(CACHE_TTL)
            .build();

    private UserDetails loadUser(String email) {
        UserDetails cached = userCache.getIfPresent(email);
        if (cached != null) return cached;
        UserDetails fresh = userDetailsService.loadUserByUsername(email);
        userCache.put(email, fresh);
        return fresh;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        // Accept JWT from either the Authorization header (CLI, API clients) or
        // the HttpOnly auth cookie (browser sessions). The cookie path exists so
        // the token never has to be exposed to page JS — an XSS on the frontend
        // cannot lift it out of document.cookie.
        final String jwt = resolveToken(request);

        if (jwt == null) {
            filterChain.doFilter(request, response);
            return;
        }

        // Controllers that read @RequestHeader("Authorization") to pull the
        // user id are the norm across this codebase. When the JWT came from
        // the cookie there is no real Authorization header, and a required
        // @RequestHeader parameter would throw MissingRequestHeaderException →
        // 500 on every request. Wrap the request so downstream handlers see a
        // synthetic "Bearer <token>" value. The raw cookie is never the
        // authoritative auth input — SecurityContextHolder is — but for DX
        // this wrapper keeps the existing controllers working unmodified.
        final HttpServletRequest requestForChain =
                request.getHeader("Authorization") != null
                        ? request
                        : new BearerHeaderWrapper(request, jwt);

        try {
            final String email = jwtService.extractEmail(jwt);

            if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = loadUser(email);

                if (jwtService.isTokenValid(jwt, userDetails.getUsername())) {
                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails, null, userDetails.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(requestForChain));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                    log.debug("JWT authenticated: email={}, uri={}", email, request.getRequestURI());
                } else {
                    writeUnauthorized(response, "Token is invalid or expired");
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("JWT processing failed [{}]: {}", request.getRequestURI(), e.getMessage());
            writeUnauthorized(response, "Token is invalid or expired");
            return;
        }

        filterChain.doFilter(requestForChain, response);
    }

    /**
     * Wraps a request to inject a synthetic {@code Authorization: Bearer <token>}
     * header when the token actually arrived via the auth cookie. Keeps the
     * existing {@code @RequestHeader("Authorization")} controller idiom working
     * without touching every controller.
     */
    private static final class BearerHeaderWrapper extends HttpServletRequestWrapper {
        private final String authorizationHeader;

        BearerHeaderWrapper(HttpServletRequest request, String jwt) {
            super(request);
            this.authorizationHeader = "Bearer " + jwt;
        }

        @Override
        public String getHeader(String name) {
            if ("Authorization".equalsIgnoreCase(name)) {
                return authorizationHeader;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if ("Authorization".equalsIgnoreCase(name)) {
                return Collections.enumeration(Collections.singletonList(authorizationHeader));
            }
            return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Enumeration<String> original = super.getHeaderNames();
            java.util.Set<String> names = new java.util.LinkedHashSet<>();
            while (original.hasMoreElements()) {
                names.add(original.nextElement());
            }
            names.add("Authorization");
            return Collections.enumeration(names);
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String candidate = authHeader.substring(7).trim();
            if (!candidate.isEmpty()) {
                return candidate;
            }
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (JwtCookieService.COOKIE_NAME.equals(c.getName())) {
                    String val = c.getValue();
                    if (val != null && !val.isBlank()) {
                        return val;
                    }
                }
            }
        }
        return null;
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ResponseDto.builder()
                .responseCode(HttpStatus.UNAUTHORIZED.value() + ResponseCode.UNAUTHORIZED.getCode())
                .responseDesc(ResponseCode.UNAUTHORIZED.getDescription())
                .errorMessage(message)
                .build());
    }
}

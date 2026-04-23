package id.co.blackheart.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.service.user.UserDetailsServiceImpl;
import id.co.blackheart.util.ResponseCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    private final JwtService jwtService;
    private final UserDetailsServiceImpl userDetailsService;
    private final ObjectMapper objectMapper;

    private final Map<String, CachedUser> userCache = new ConcurrentHashMap<>();

    private record CachedUser(UserDetails details, Instant expiresAt) {
        boolean expired(Instant now) {
            return now.isAfter(expiresAt);
        }
    }

    private UserDetails loadUser(String email) {
        Instant now = Instant.now();
        CachedUser cached = userCache.get(email);
        if (cached != null && !cached.expired(now)) {
            return cached.details();
        }
        UserDetails fresh = userDetailsService.loadUserByUsername(email);
        userCache.put(email, new CachedUser(fresh, now.plus(CACHE_TTL)));
        return fresh;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        // No Bearer header — let the chain continue; entry point handles unauthenticated requests
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        final String jwt = authHeader.substring(7);

        try {
            final String email = jwtService.extractEmail(jwt);

            if (email != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = loadUser(email);

                if (jwtService.isTokenValid(jwt, userDetails.getUsername())) {
                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails, null, userDetails.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
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

        filterChain.doFilter(request, response);
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

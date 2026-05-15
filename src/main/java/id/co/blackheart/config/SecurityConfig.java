package id.co.blackheart.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.filter.AuthRateLimitFilter;
import id.co.blackheart.filter.JwtAuthenticationFilter;
import id.co.blackheart.service.user.UserDetailsServiceImpl;
import id.co.blackheart.util.ResponseCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * Single stateless JWT filter chain — every endpoint requires a Bearer token
 * except the public whitelist below.
 */
@Configuration
@EnableWebSecurity
// prePostEnabled lets controllers use @PreAuthorize("hasRole('ADMIN')") etc.
// Without this, the annotations are silently ignored — every admin-gated
// endpoint would quietly be public.
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
@Slf4j
public class SecurityConfig {

    private static final String ROLE_ADMIN = "ADMIN";

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final AuthRateLimitFilter authRateLimitFilter;
    private final UserDetailsServiceImpl userDetailsService;
    private final ObjectMapper objectMapper;

    /**
     * Comma-separated list of allowed CORS origins. In production the deploy
     * pipeline must set {@code CORS_ALLOWED_ORIGINS} to the real frontend
     * origin(s). The dev fallback keeps local `next dev` on 3000 working out
     * of the box.
     */
    @Value("${app.cors.allowed-origins:http://localhost:3000}")
    private String allowedOriginsCsv;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authenticationProvider(daoAuthProvider())
                // Rate limiter runs before auth so bot traffic is dropped before
                // BCrypt / user-details lookups — both are expensive and make
                // effective DoS oracles.
                .addFilterBefore(authRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/v1/users/register",
                                "/api/v1/users/login",
                                "/api/v1/users/logout",
                                "/api/v1/users/password-reset/request",
                                "/api/v1/users/password-reset/confirm",
                                "/api/v1/users/email/verify",
                                // Dev-only auth bypass (DevAuthController). The
                                // controller bean is gated by @Profile({"dev",
                                // "local","test"}) so on prod profiles the bean
                                // doesn't exist and these URLs return 404. The
                                // URL-level whitelist is permanent so the
                                // unauthenticated request reaches the (possibly
                                // missing) controller without a 401 first.
                                "/api/v1/dev/**",
                                // Frontend + middleware error capture (Phase B/C).
                                // Public because errors fire pre-login (login page
                                // crash, expired-token redirect). Spam protection
                                // is downstream: fingerprint dedup on error_log
                                // collapses repeats; bounded async queue caps
                                // overall throughput.
                                "/api/v1/errors",
                                "/healthcheck",
                                "/ws",
                                "/ws/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                // Probe sub-paths only — k8s liveness/readiness
                                // and external uptime monitors call these
                                // anonymously. They expose status only, not the
                                // component-detail tree (filtered server-side).
                                "/actuator/health/liveness",
                                "/actuator/health/readiness"
                        ).permitAll()
                        // Actuator surfaces internal state (heap, env, thread
                        // dumps). Admin-only on both JVMs; the /research
                        // dashboard polls these from an admin session. Probe
                        // sub-paths above are excluded from this rule.
                        .requestMatchers("/actuator/**").hasRole(ROLE_ADMIN)
                        // Research-JVM actuator, reverse-proxied through this
                        // JVM so the research process stays bound to
                        // 127.0.0.1. Same admin gate as the local actuator —
                        // ResearchProxyController forwards the path through
                        // to research:8081/actuator/**.
                        .requestMatchers("/research-actuator/**").hasRole(ROLE_ADMIN)
                        // Python research orchestrator (FastAPI, 8082)
                        // proxied through ResearchOrchestratorProxyController.
                        // Same admin gate as the actuator surfaces.
                        .requestMatchers("/api/v1/research-orch/**").hasRole(ROLE_ADMIN)
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) -> {
                            response.setStatus(HttpStatus.UNAUTHORIZED.value());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getOutputStream(), ResponseDto.builder()
                                    .responseCode(HttpStatus.UNAUTHORIZED.value() + ResponseCode.UNAUTHORIZED.getCode())
                                    .responseDesc(ResponseCode.UNAUTHORIZED.getDescription())
                                    .errorMessage("Authentication required — provide a valid Bearer token")
                                    .build());
                        })
                        // 403 — token valid but user lacks permission
                        .accessDeniedHandler((request, response, e) -> {
                            response.setStatus(HttpStatus.FORBIDDEN.value());
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getOutputStream(), ResponseDto.builder()
                                    .responseCode(HttpStatus.FORBIDDEN.value() + ResponseCode.ACCESS_DENIED.getCode())
                                    .responseDesc(ResponseCode.ACCESS_DENIED.getDescription())
                                    .errorMessage("You do not have permission to access this resource")
                                    .build());
                        })
                )
                .build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        // Explicit allow-list, not wildcard; credentials enabled so the
        // frontend's HttpOnly auth cookie is transmitted on XHRs. Origins
        // come from `app.cors.allowed-origins` (env override:
        // CORS_ALLOWED_ORIGINS=https://app.example.com,https://staging.example.com).
        List<String> origins = Arrays.stream(allowedOriginsCsv.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
        if (origins.isEmpty()) {
            origins = List.of("http://localhost:3000");
            log.warn("CORS allowed-origins was empty; falling back to localhost:3000");
        }
        log.info("CORS allowed origins: {}", origins);
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        // Permissive header list. Spec forbids "*" when credentials=true, so
        // we spell out the superset of headers browsers / Axios ever send.
        // Tightening too aggressively causes the preflight to silently cancel
        // the real request and the user sees "button does nothing".
        config.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Accept",
                "Accept-Language",
                "Origin",
                "Referer",
                "User-Agent",
                "Cache-Control",
                "Pragma",
                "X-Request-ID",
                "X-Correlation-ID",
                "X-Requested-With",
                "X-XSRF-TOKEN"
        ));
        config.setExposedHeaders(List.of("Content-Type", "X-Request-ID", "X-Correlation-ID"));
        config.setAllowCredentials(true);
        // Cache CORS preflight for 1 h so mutations don't double up on
        // OPTIONS round trips.
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public DaoAuthenticationProvider daoAuthProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}

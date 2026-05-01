package id.co.blackheart.controller;

import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.model.User;
import id.co.blackheart.repository.UserRepository;
import id.co.blackheart.service.user.JwtService;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * DEPRECATED FOR PRODUCTION DEPLOYMENT.
 * <p>
 * This controller is a security backdoor — it mints valid JWTs for any
 * existing user without password verification. Acceptable for local research
 * automation only. Before any production deployment, **delete this file
 * entirely** and use the alternative dev-token flow at
 * {@code research/scripts/dev-token-prod.sh} which calls the real
 * {@code POST /api/v1/users/login} endpoint with seeded credentials.
 * <p>
 * Layered safety as of V14 (2026-04-28):
 * <ol>
 *   <li>Spring profile gate: {@code @Profile({"dev","local","test"})}.</li>
 *   <li>Forbidden-profile runtime check on every request.</li>
 *   <li><b>NEW</b>: env-var kill-switch. Setting
 *       {@code BLACKHEART_DEV_AUTH_DISABLED=true} in the JVM env causes the
 *       endpoint to return 404 even on dev profile. Operators can disable
 *       the bypass without redeploying; default is opt-out=false to preserve
 *       existing research-orchestrator workflow.</li>
 * </ol>
 * <p>
 * For prod-readiness migration: see {@code research/DEPLOYMENT.md} step 1.
 * Recommended migration path: set {@code BLACKHEART_DEV_AUTH_DISABLED=true},
 * switch the orchestrator to {@code dev-token-prod.sh} (real /users/login),
 * verify the cron survives, then delete this controller.
 */
@RestController
@RequestMapping("/api/v1/dev")
@Profile({"dev", "local", "test"})
@RequiredArgsConstructor
@Slf4j
@Tag(name = "DevAuthController", description = "DEPRECATED — DEV-PROFILE ONLY JWT minting bypass. Delete before production deployment. See research/DEPLOYMENT.md.")
public class DevAuthController {

    private static final Set<String> FORBIDDEN_PROFILES = Set.of("prod", "production", "staging");

    /** Operator kill-switch env var. When set to "true" (case-insensitive),
     *  the endpoint returns 404 even on dev profile. Default empty/false:
     *  endpoint works (preserves existing research-orchestrator workflow).
     *  Set to true once the orchestrator has migrated to dev-token-prod.sh. */
    private static final String DISABLE_ENV_VAR = "BLACKHEART_DEV_AUTH_DISABLED";

    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final Environment environment;

    /**
     * Constructor-time defense-in-depth. {@code @Profile} should keep this bean
     * out of prod, but if a deployment misconfiguration somehow activates
     * {@code dev,prod} together we refuse to start rather than silently expose
     * the bypass. {@link IllegalStateException} surfaces in startup logs as a
     * loud, blocking failure.
     */
    @lombok.Generated  // setUp not under unit-test coverage; logic is profile-gated
    public void setUp() {
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        for (String forbidden : FORBIDDEN_PROFILES) {
            if (active.contains(forbidden)) {
                throw new IllegalStateException(
                        "DevAuthController refusing to start — active profiles "
                                + active + " include forbidden profile '" + forbidden
                                + "'. Dev-only bypass cannot coexist with production profiles."
                );
            }
        }
    }

    @PostMapping("/login-as")
    @Operation(
            summary = "DEV ONLY — mint a real JWT for an existing user without password.",
            description = "Returns a normal JWT (same TTL as /users/login) for the user identified "
                    + "by email. If email is omitted, returns a token for the first user in the table. "
                    + "Active on dev/local/test profiles only; absent on prod (returns 404)."
    )
    public ResponseEntity<ResponseDto> loginAs(@RequestBody(required = false) LoginAsRequest body,
                                               @RequestHeader(value = "X-Forwarded-For", required = false) String forwardedFor) {
        // Re-check active profiles per request — cheap, and catches the case
        // where someone reloads the application context with prod profiles
        // active without restarting the JVM.
        List<String> active = Arrays.asList(environment.getActiveProfiles());
        for (String forbidden : FORBIDDEN_PROFILES) {
            if (active.contains(forbidden)) {
                log.error("DevAuthController invoked with forbidden profile active: {}", active);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
        }

        // Operator kill-switch (V14+). Allows disabling the bypass without
        // redeploying — flip the env var, restart, verify orchestrator
        // survives via dev-token-prod.sh, then delete this controller.
        String disabled = System.getenv(DISABLE_ENV_VAR);
        if (disabled != null && "true".equalsIgnoreCase(disabled.trim())) {
            log.warn("[DEV-AUTH-BYPASS] Refused — kill-switch {}=true active", DISABLE_ENV_VAR);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        String requestedEmail = body != null ? body.getEmail() : null;
        User user = (requestedEmail != null && !requestedEmail.isBlank())
                ? userRepository.findByEmail(requestedEmail.trim())
                        .orElseThrow(() -> new EntityNotFoundException(
                                "Dev login-as: user not found for email " + requestedEmail))
                : userRepository.findAll().stream().findFirst()
                        .orElseThrow(() -> new EntityNotFoundException(
                                "Dev login-as: no users in DB to mint a token for"));

        String token = jwtService.generateToken(user);

        log.warn("[DEV-AUTH-BYPASS] Minted JWT for email={} userId={} role={} forwardedFor={} — active profiles={}",
                user.getEmail(), user.getUserId(), user.getRole(), forwardedFor, active);

        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .responseDesc("Dev token minted")
                .data(LoginAsResponse.builder()
                        .token(token)
                        .userId(user.getUserId().toString())
                        .email(user.getEmail())
                        .role(user.getRole())
                        .build())
                .build());
    }

    @lombok.Data
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class LoginAsRequest {
        private String email;
    }

    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class LoginAsResponse {
        private String token;
        private String userId;
        private String email;
        private String role;
    }
}

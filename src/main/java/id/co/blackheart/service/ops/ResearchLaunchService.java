package id.co.blackheart.service.ops;

import id.co.blackheart.dto.response.ResearchLaunchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates research-JVM lifecycle from the trading JVM. The trading JVM is
 * the only stable supervisor on the host — research can be down, so it cannot
 * own its own start/restart. Three operations:
 *
 *  - start():   idempotent ProcessBuilder spawn of the launcher script in
 *               background mode. Returns ALREADY_RUNNING if the health probe
 *               is already passing.
 *  - stop():    forwards the inbound admin Cookie to research's
 *               /actuator/shutdown (gated `management.endpoint.shutdown.enabled`
 *               in application-research.properties). Cookie forwarding works
 *               because both JVMs share JWT_SECRET.
 *  - restart(): stop → wait-down → start → wait-up.
 *
 * Bounded waits everywhere so a stuck research JVM cannot stall the trading
 * JVM's request thread indefinitely.
 *
 * Profile-gated to !research so it never registers on the research JVM itself
 * (which would create a self-supervision loop).
 */
@Service
@Slf4j
@Profile("!research")
public class ResearchLaunchService {

    private static final Duration HEALTH_PROBE_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration STOP_REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration WAIT_FOR_DOWN_BUDGET = Duration.ofSeconds(20);
    private static final Duration WAIT_FOR_UP_BUDGET = Duration.ofSeconds(60);
    private static final Duration WAIT_POLL_INTERVAL = Duration.ofMillis(750);

    private final RestTemplate restTemplate;
    private final String researchUrl;
    private final String launchScript;
    private final String launchShell;
    private final String repoRoot;

    public ResearchLaunchService(
            RestTemplate restTemplate,
            @Value("${app.research.url:http://localhost:8081}") String researchUrl,
            @Value("${app.research.launch-script:research/scripts/run-research-service.sh}") String launchScript,
            @Value("${app.research.launch-shell:bash}") String launchShell,
            @Value("${app.research.repo-root:}") String repoRoot
    ) {
        this.restTemplate = restTemplate;
        this.researchUrl = stripTrailingSlash(researchUrl);
        this.launchScript = launchScript;
        this.launchShell = launchShell;
        this.repoRoot = repoRoot;
    }

    public ResearchLaunchResponse start() {
        if (isHealthy()) {
            return ResearchLaunchResponse.builder()
                    .state("ALREADY_RUNNING")
                    .healthy(true)
                    .message("Research JVM already responding to /actuator/health/liveness.")
                    .observedAt(Instant.now())
                    .build();
        }
        Long pid = spawnLauncher();
        boolean up = waitForHealth(WAIT_FOR_UP_BUDGET);
        return ResearchLaunchResponse.builder()
                .state(up ? "STARTED" : "STARTING")
                .healthy(up)
                .pid(pid)
                .logPath(System.getenv().getOrDefault("LOG_FILE", "/tmp/blackheart-research.log"))
                .message(up
                        ? "Research JVM responded to /actuator/health/liveness."
                        : "Launcher spawned but JVM not yet healthy after "
                                + WAIT_FOR_UP_BUDGET.toSeconds() + "s — keep polling /actuator/health.")
                .observedAt(Instant.now())
                .build();
    }

    public ResearchLaunchResponse stop(String cookieHeader) {
        if (!isHealthy()) {
            return ResearchLaunchResponse.builder()
                    .state("ALREADY_DOWN")
                    .healthy(false)
                    .message("Research JVM was not responding before the stop request.")
                    .observedAt(Instant.now())
                    .build();
        }
        boolean accepted = postShutdown(cookieHeader);
        boolean down = accepted && waitForDown(WAIT_FOR_DOWN_BUDGET);
        return ResearchLaunchResponse.builder()
                .state(down ? "STOPPED" : (accepted ? "STOP_REQUESTED" : "STOP_FAILED"))
                .healthy(!down && isHealthy())
                .message(down
                        ? "Research JVM stopped."
                        : (accepted
                                ? "Shutdown request accepted but JVM still up after "
                                        + WAIT_FOR_DOWN_BUDGET.toSeconds() + "s."
                                : "Shutdown request rejected — check admin auth / actuator config."))
                .observedAt(Instant.now())
                .build();
    }

    public ResearchLaunchResponse restart(String cookieHeader) {
        ResearchLaunchResponse stopped = stop(cookieHeader);
        // Force-wait for actual down state if we accepted the shutdown but
        // the previous block didn't get there (best-effort; spawn is still safe).
        waitForDown(WAIT_FOR_DOWN_BUDGET);
        Long pid = spawnLauncher();
        boolean up = waitForHealth(WAIT_FOR_UP_BUDGET);
        return ResearchLaunchResponse.builder()
                .state(up ? "RESTARTED" : "STARTING")
                .healthy(up)
                .pid(pid)
                .logPath(System.getenv().getOrDefault("LOG_FILE", "/tmp/blackheart-research.log"))
                .message(up
                        ? "Research JVM restarted (prior state: " + stopped.getState() + ")."
                        : "Restart spawned but JVM not yet healthy after "
                                + WAIT_FOR_UP_BUDGET.toSeconds() + "s.")
                .observedAt(Instant.now())
                .build();
    }

    private boolean isHealthy() {
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(researchUrl + "/actuator/health/liveness", String.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (RestClientException e) {
            return false;
        }
    }

    private boolean postShutdown(String cookieHeader) {
        HttpHeaders headers = new HttpHeaders();
        if (StringUtils.hasText(cookieHeader)) {
            headers.add(HttpHeaders.COOKIE, cookieHeader);
        }
        try {
            ResponseEntity<String> resp = restTemplate.exchange(
                    researchUrl + "/actuator/shutdown",
                    HttpMethod.POST,
                    new HttpEntity<>(headers),
                    String.class
            );
            return resp.getStatusCode().is2xxSuccessful();
        } catch (RestClientException e) {
            log.warn("POST /actuator/shutdown to research JVM failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean waitForDown(Duration budget) {
        return waitFor(() -> !isHealthy(), budget);
    }

    private boolean waitForHealth(Duration budget) {
        return waitFor(this::isHealthy, budget);
    }

    private boolean waitFor(java.util.function.BooleanSupplier predicate, Duration budget) {
        Instant deadline = Instant.now().plus(budget);
        while (Instant.now().isBefore(deadline)) {
            if (predicate.getAsBoolean()) return true;
            try {
                Thread.sleep(WAIT_POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return predicate.getAsBoolean();
    }

    private Long spawnLauncher() {
        File workingDir = resolveRepoRoot();
        List<String> cmd = new ArrayList<>();
        cmd.add(launchShell);
        cmd.add(launchScript);
        cmd.add("--background");
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workingDir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(
                        new File(System.getenv().getOrDefault("LOG_FILE", "/tmp/blackheart-research-launcher.log"))));
        try {
            Process p = pb.start();
            log.info("Spawned research launcher: shell={} script={} cwd={} pid={}",
                    launchShell, launchScript, workingDir, p.pid());
            return p.pid();
        } catch (Exception e) {
            log.error("Failed to spawn research launcher (shell={}, script={}, cwd={}): {}",
                    launchShell, launchScript, workingDir, e.getMessage(), e);
            throw new IllegalStateException("Failed to spawn research launcher: " + e.getMessage(), e);
        }
    }

    private File resolveRepoRoot() {
        if (StringUtils.hasText(repoRoot)) {
            return new File(repoRoot);
        }
        return new File(System.getProperty("user.dir"));
    }

    private static String stripTrailingSlash(String s) {
        return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s;
    }
}

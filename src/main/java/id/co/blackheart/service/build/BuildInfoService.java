package id.co.blackheart.service.build;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Resolves the running application's git SHA + version once at startup so
 * downstream callers (the backtest reproducibility manifest, future
 * `/healthcheck` enrichment, etc.) can read them as plain strings without
 * shelling out repeatedly.
 *
 * <p>SHA resolution order:
 * <ol>
 *   <li>{@code GIT_COMMIT} environment variable — what CI sets.</li>
 *   <li>{@code git rev-parse HEAD} via ProcessBuilder — local dev fallback.</li>
 *   <li>The literal string {@code "unknown"} when neither is available.</li>
 * </ol>
 *
 * <p>The shell-out is bounded by a 2-second timeout. If git isn't on PATH or
 * the working directory isn't a repo (Docker image without {@code .git}), we
 * gracefully degrade to {@code "unknown"} rather than logging stack traces.
 */
@Slf4j
@Service
public class BuildInfoService {

    private static final String UNKNOWN = "unknown";

    @Value("${app.version:dev}")
    private String configuredVersion;

    private String gitCommitSha = UNKNOWN;
    private String appVersion = UNKNOWN;

    @PostConstruct
    public void init() {
        gitCommitSha = resolveGitCommitSha();
        appVersion = configuredVersion != null && !configuredVersion.isBlank()
                ? configuredVersion
                : UNKNOWN;
        log.info("[BuildInfo] gitCommitSha={} appVersion={}", gitCommitSha, appVersion);
    }

    public String getGitCommitSha() {
        return gitCommitSha;
    }

    public String getAppVersion() {
        return appVersion;
    }

    private String resolveGitCommitSha() {
        String envSha = System.getenv("GIT_COMMIT");
        if (envSha != null && !envSha.isBlank()) {
            return envSha.trim();
        }

        try {
            Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line = reader.readLine();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    log.debug("[BuildInfo] git rev-parse timed out");
                    return UNKNOWN;
                }
                if (process.exitValue() == 0 && line != null && !line.isBlank()) {
                    return line.trim();
                }
            }
        } catch (Exception e) {
            log.debug("[BuildInfo] git rev-parse failed: {}", e.getMessage());
        }
        return UNKNOWN;
    }
}

package id.co.blackheart.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Result of a research-JVM lifecycle action (start/stop/restart). Surfaces what
 * the trading JVM observed so the operator can confirm the action took effect.
 *
 * {@code state} = "STARTED" | "ALREADY_RUNNING" | "STOPPED" | "RESTARTED" |
 *                 "STARTING" (background spawn issued, not yet healthy) |
 *                 "STOP_REQUESTED" (shutdown POST accepted, not yet down).
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ResearchLaunchResponse {
    private String state;
    private boolean healthy;
    private Long pid;
    private String logPath;
    private String message;
    private Instant observedAt;
}

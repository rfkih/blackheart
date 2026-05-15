package id.co.blackheart.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Snapshot of the global research-pause flag. Read by the dashboard so the
 * operator knows whether OS-cron-driven ticks are currently being honored.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ResearchControlResponse {
    private boolean paused;
    private String reason;
    private Instant updatedAt;
    private UUID updatedByUserId;
}

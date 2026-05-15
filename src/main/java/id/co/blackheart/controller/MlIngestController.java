package id.co.blackheart.controller;

import id.co.blackheart.dto.request.TriggerMlBackfillRequest;
import id.co.blackheart.dto.request.UpdateMlScheduleRequest;
import id.co.blackheart.dto.response.ResponseDto;
import id.co.blackheart.model.HistoricalBackfillJob;
import id.co.blackheart.model.MlIngestSchedule;
import id.co.blackheart.model.MlSourceHealth;
import id.co.blackheart.model.User;
import id.co.blackheart.repository.UserRepository;
import id.co.blackheart.service.mlingest.MlIngestService;
import id.co.blackheart.util.ResponseCode;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin-only control plane for ML/sentiment ingestion. Three responsibility
 * surfaces:
 *
 * <ul>
 *   <li>{@code GET /schedules}, {@code PATCH /schedules/{id}} — manage
 *       per-source cron schedules.</li>
 *   <li>{@code GET /health} — read source health snapshot for the dashboard.</li>
 *   <li>{@code POST /backfill} — trigger manual historical backfill; routes
 *       through the existing {@code historical_backfill_job} table so the
 *       frontend can poll progress via {@code /api/v1/historical/jobs/{id}}.</li>
 * </ul>
 *
 * <p><b>JVM placement</b>: runs in the research JVM (port 8081) like
 * {@code HistoricalBackfillController} — backfill is a compute-heavy
 * operation that doesn't belong in the live trading hot path. Blackridge
 * routes admin pages through {@code researchClient}.
 */
@RestController
@RequestMapping("/api/v1/ml-ingest")
@RequiredArgsConstructor
@Profile("research")
@Tag(name = "MlIngestController", description = "Admin control plane for ML/sentiment ingestion (V67)")
@PreAuthorize("hasRole('ADMIN')")
public class MlIngestController {

    private final MlIngestService mlIngestService;
    private final UserRepository userRepository;

    /**
     * Resolve the authenticated admin's UUID for audit on
     * {@code historical_backfill_job.created_by_user_id}. Matches the helper
     * in {@link HistoricalBackfillController#resolveCurrentUserId()} — both
     * controllers gate on {@code hasRole('ADMIN')}.
     */
    private UUID resolveCurrentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            return null;
        }
        Object principal = auth.getPrincipal();
        String email = (principal instanceof UserDetails ud) ? ud.getUsername() : auth.getName();
        if (!StringUtils.hasText(email)) return null;
        return userRepository.findByEmail(email).map(User::getUserId).orElse(null);
    }

    private String resolveCurrentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth.getPrincipal() == null) {
            return null;
        }
        Object principal = auth.getPrincipal();
        return (principal instanceof UserDetails ud) ? ud.getUsername() : auth.getName();
    }

    // ── Schedules ───────────────────────────────────────────────────────────

    @GetMapping("/schedules")
    public ResponseEntity<ResponseDto> listSchedules() {
        List<MlIngestSchedule> schedules = mlIngestService.listSchedules();
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(schedules)
                .build());
    }

    @GetMapping("/schedules/{id}")
    public ResponseEntity<ResponseDto> getSchedule(@PathVariable Long id) {
        MlIngestSchedule schedule = mlIngestService.getSchedule(id);
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(schedule)
                .build());
    }

    /**
     * Partial update — any null field is left unchanged. Cron is validated
     * server-side and surfaces a 400 on parse failure (via the global
     * IllegalArgumentException handler).
     */
    @PatchMapping("/schedules/{id}")
    public ResponseEntity<ResponseDto> updateSchedule(
            @PathVariable Long id,
            @RequestBody UpdateMlScheduleRequest req
    ) {
        MlIngestSchedule updated = mlIngestService.updateSchedule(id, req, resolveCurrentUserEmail());
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(updated)
                .build());
    }

    // ── Source health (dashboard read-only) ────────────────────────────────

    @GetMapping("/health")
    public ResponseEntity<ResponseDto> listHealth() {
        List<MlSourceHealth> rows = mlIngestService.listSourceHealth();
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(rows)
                .build());
    }

    // ── Manual backfill trigger ────────────────────────────────────────────

    /**
     * Submit a manual backfill job. The service maps the source string to
     * the right {@code BACKFILL_ML_*} JobType and writes a PENDING row to
     * {@code historical_backfill_job}. The frontend polls
     * {@code /api/v1/historical/jobs/{id}} for live progress — same pattern
     * as the existing historical-backfill UI.
     */
    @PostMapping("/backfill")
    public ResponseEntity<ResponseDto> triggerBackfill(@RequestBody TriggerMlBackfillRequest req) {
        HistoricalBackfillJob job = mlIngestService.triggerBackfill(req, resolveCurrentUserId());
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(job)
                .build());
    }

    /**
     * Discovery endpoint — returns the whitelist of known source names so the
     * frontend doesn't hardcode them. Two future-proofing notes:
     *   1. New sources must be added in two places (JobType + MlIngestService
     *      map) — this endpoint reflects the map automatically.
     *   2. Frontend should fall back to a hardcoded default list if this 404s,
     *      so an older backend doesn't break the UI.
     */
    @GetMapping("/sources")
    public ResponseEntity<ResponseDto> listKnownSources() {
        List<String> sources = mlIngestService.knownSources();
        return ResponseEntity.ok(ResponseDto.builder()
                .responseCode(HttpStatus.OK.value() + ResponseCode.SUCCESS.getCode())
                .data(Map.of("sources", sources))
                .build());
    }
}

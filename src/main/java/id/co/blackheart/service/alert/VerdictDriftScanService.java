package id.co.blackheart.service.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Phase 7.5 — PROMOTED verdict drift cron.
 *
 * <p>Nightly, asks the research orchestrator to compare the two most-recent
 * {@code cross_window_run} rows for every PROMOTED account_strategy. For
 * each strategy whose latest verdict ranks below the prior baseline, raise
 * a {@code VERDICT_DRIFT} alert.
 *
 * <p>Why orchestrator-side rather than direct DB read: the rank ordering
 * and the strategy-of-truth for "what is a cross_window_run" both live in
 * the orchestrator's Python code. The trading JVM never imports the
 * cross-window verdict logic — keeps the two services decoupled, same as
 * every other research-side concept.
 *
 * <p>Cheap (read-only, two indexed lookups per strategy). Scheduled at
 * 02:30 UTC by default — comfortably after the P&amp;L deviation alert at
 * 01:30 UTC and well before any operator activity.
 */
@Component
@Profile("!research")
@ConditionalOnProperty(
        name = "app.alerts.verdict-drift.enabled",
        havingValue = "true",
        matchIfMissing = true)
@Slf4j
public class VerdictDriftScanService {

    private final AlertService alertService;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    @Value("${app.research.orchestrator.base-url:http://127.0.0.1:8082}")
    private String orchestratorBaseUrl;

    @Value("${app.research.orchestrator.token:dev-sentinel-not-for-prod}")
    private String orchestratorToken;

    @Value("${app.alerts.verdict-drift.timeout-seconds:60}")
    private long timeoutSeconds;

    public VerdictDriftScanService(AlertService alertService, ObjectMapper objectMapper) {
        this.alertService = alertService;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Scheduled(cron = "${app.alerts.verdict-drift.cron:0 30 2 * * *}", zone = "UTC")
    public void scan() {
        // Snapshot UTC date once at entry. Reused for the orchestrator's
        // Idempotency-Key and for every dedupe key built downstream — never
        // re-derived later, so a midnight-UTC straddle during execution can't
        // split the call into two different days.
        java.time.LocalDate utcDay = java.time.LocalDate.now(java.time.ZoneOffset.UTC);

        JsonNode body;
        try {
            body = callOrchestrator(utcDay);
        } catch (RuntimeException e) {
            log.error("[VerdictDrift] orchestrator call failed", e);
            return;
        }
        if (body == null) return;

        JsonNode drifts = body.get("drifts");
        if (drifts == null || !drifts.isArray()) {
            log.warn("[VerdictDrift] orchestrator returned no 'drifts' array; body={}", body);
            return;
        }

        int total = drifts.size();
        log.info("[VerdictDrift] orchestrator reported {} drift(s)", total);
        for (JsonNode d : drifts) {
            try {
                raiseAlertFor(d, utcDay);
            } catch (RuntimeException e) {
                log.error("[VerdictDrift] failed to raise alert for drift entry: {}", d, e);
            }
        }
    }

    private JsonNode callOrchestrator(java.time.LocalDate utcDay) {
        String url = stripTrailingSlash(orchestratorBaseUrl) + "/verdict-drift/scan";
        // Per-day idempotency: if the cron stamps fire twice in a day (manual
        // invocation, JVM restart in the wrong window) the orchestrator hands
        // back its cached result and we don't double-raise.
        String idempotencyKey = "vdrift-" + utcDay;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("X-Orch-Token", orchestratorToken)
                .header("X-Agent-Name", "trading-jvm")
                .header("Idempotency-Key", idempotencyKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.warn("[VerdictDrift] orchestrator HTTP {}: {}", resp.statusCode(), resp.body());
                return null;
            }
            return objectMapper.readTree(resp.body());
        } catch (java.io.IOException e) {
            throw new RuntimeException("orchestrator I/O error", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("orchestrator call interrupted", e);
        }
    }

    private void raiseAlertFor(JsonNode d, java.time.LocalDate utcDay) {
        String accountStrategyId = textOrEmpty(d, "account_strategy_id");
        String strategyCode = textOrEmpty(d, "strategy_code");
        String intervalName = textOrEmpty(d, "interval_name");
        String instrument = textOrEmpty(d, "instrument");
        String baselineVerdict = textOrEmpty(d, "baseline_verdict");
        String currentVerdict = textOrEmpty(d, "current_verdict");
        int rankDrop = d.has("rank_drop") ? d.get("rank_drop").asInt(1) : 1;

        // Severity asymmetric: a drop into NO_EDGE is the operationally
        // dangerous case (real capital on a strategy the data no longer
        // supports). Lesser drops (e.g. ROBUST → INCONSISTENT) are still
        // worth surfacing but don't warrant a CRITICAL page.
        AlertSeverity severity = "NO_EDGE_CROSS_WINDOW".equals(currentVerdict)
                ? AlertSeverity.CRITICAL
                : AlertSeverity.WARN;

        String message = String.format(
                "%s/%s on %s — verdict drifted %s → %s (rank drop=%d)",
                strategyCode.isEmpty() ? "?" : strategyCode,
                intervalName.isEmpty() ? "?" : intervalName,
                instrument.isEmpty() ? "?" : instrument,
                baselineVerdict,
                currentVerdict,
                rankDrop);

        // Dedupe key includes current verdict so a strategy that drops
        // INCONSISTENT and then later drops further to NO_EDGE re-alerts
        // (the operator wants to know about the second drop). Daily epoch
        // bucket prevents same-day double-fires.
        String dedupeKey = "vdrift_" + accountStrategyId + "_" + currentVerdict + "_" + utcDay;

        alertService.raise(severity, "VERDICT_DRIFT", message, dedupeKey);
    }

    private static String textOrEmpty(JsonNode parent, String field) {
        JsonNode n = parent.get(field);
        return n == null || n.isNull() ? "" : n.asText();
    }

    private static String stripTrailingSlash(String s) {
        return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s;
    }
}

package id.co.blackheart.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reverse proxy on the trading JVM forwarding /api/v1/research-orch/** to the
 * Python research orchestrator on 127.0.0.1:8082. Distinct from
 * {@link ResearchProxyController} (which targets the Java research JVM on
 * 8081) because the orchestrator uses a different auth model: shared-secret
 * X-Orch-Token, not the JWT cookie.
 *
 * Why this exists: the orchestrator binds to loopback only; the dashboard
 * cannot reach it directly. This proxy injects the orch token server-side
 * (so the secret never leaves the host) and admin-gates the path through
 * SecurityConfig — same perimeter as /research-actuator/**.
 *
 * Path rewrite: /api/v1/research-orch/leaderboard → orchestrator:/leaderboard
 */
@Slf4j
@RestController
@Profile("!research")
public class ResearchOrchestratorProxyController {

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade", "host", "content-length"
    );

    private static final String PATH_PREFIX = "/api/v1/research-orch";

    @Value("${app.research.orchestrator.base-url:http://127.0.0.1:8082}")
    private String orchestratorBaseUrl;

    @Value("${app.research.orchestrator.token}")
    private String orchestratorToken;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @RequestMapping({PATH_PREFIX, PATH_PREFIX + "/**"})
    public ResponseEntity<byte[]> proxy(HttpServletRequest req) throws IOException {
        String method = req.getMethod().toUpperCase(Locale.ROOT);
        String upstreamPath = upstreamPath(req);
        URI target = resolveTarget(upstreamPath, req.getQueryString());
        HttpRequest request = buildUpstreamRequest(req, method, target, upstreamTimeout(method));
        return sendAndForward(request, method, upstreamPath);
    }

    private static String upstreamPath(HttpServletRequest req) {
        // /api/v1/research-orch/leaderboard → /leaderboard
        String upstreamPath = req.getRequestURI().substring(PATH_PREFIX.length());
        return upstreamPath.isEmpty() ? "/" : upstreamPath;
    }

    private URI resolveTarget(String upstreamPath, String query) {
        return URI.create(stripTrailingSlash(orchestratorBaseUrl) + upstreamPath
                + (query != null ? "?" + query : ""));
    }

    /**
     * Read timeout sized for the worst-case POST /tick (~30 min synchronous).
     * GETs return in ms; the connectTimeout (5 s on the HttpClient) still
     * catches an unreachable orchestrator quickly. Walk-forward runs up to
     * ~3 h and is intentionally NOT exposed to the dashboard — keep this
     * timeout aligned with /tick, not /walk-forward.
     */
    private static Duration upstreamTimeout(String method) {
        return "GET".equals(method) || "HEAD".equals(method)
                ? Duration.ofSeconds(120)
                : Duration.ofSeconds(2100);
    }

    private HttpRequest buildUpstreamRequest(HttpServletRequest req, String method,
                                             URI target, Duration timeout) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target).timeout(timeout);

        boolean bodyAllowed = !("GET".equals(method) || "HEAD".equals(method));
        byte[] body = bodyAllowed ? req.getInputStream().readAllBytes() : new byte[0];
        builder.method(method, bodyAllowed && body.length > 0
                ? BodyPublishers.ofByteArray(body)
                : BodyPublishers.noBody());

        forwardRequestHeaders(builder, req);

        // Inject orchestrator auth headers from server-side config.
        builder.header("X-Orch-Token", orchestratorToken);
        builder.header("X-Agent-Name", "dashboard");

        return builder.build();
    }

    /**
     * Forward only Accept + Content-Type from the client. Cookie / Authorization
     * are NOT forwarded — orchestrator uses its own token, and the JWT cookie
     * is meaningless to it.
     */
    private void forwardRequestHeaders(HttpRequest.Builder builder, HttpServletRequest req) {
        Enumeration<String> names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (shouldForwardRequestHeader(name)) {
                copyRequestHeader(builder, req, name);
            }
        }
    }

    private static boolean shouldForwardRequestHeader(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (HOP_BY_HOP.contains(lower)) return false;
        return "accept".equals(lower) || "content-type".equals(lower);
    }

    private void copyRequestHeader(HttpRequest.Builder builder, HttpServletRequest req, String name) {
        Enumeration<String> values = req.getHeaders(name);
        while (values.hasMoreElements()) {
            try {
                builder.header(name, values.nextElement());
            } catch (IllegalArgumentException e) {
                log.warn("Invalid header skipped during orchestrator proxy forwarding: {}", e.getMessage());
            }
        }
    }

    private ResponseEntity<byte[]> sendAndForward(HttpRequest request, String method, String upstreamPath) {
        HttpResponse<byte[]> upstream;
        try {
            upstream = http.send(request, BodyHandlers.ofByteArray());
        } catch (ConnectException e) {
            log.warn("Orchestrator unreachable at {}: {}", orchestratorBaseUrl, e.getMessage());
            return jsonError(HttpStatus.BAD_GATEWAY,
                    "Research orchestrator unavailable. Try again in a moment.");
        } catch (HttpTimeoutException e) {
            log.warn("Orchestrator timeout for {} {}: {}", method, upstreamPath, e.getMessage());
            return jsonError(HttpStatus.GATEWAY_TIMEOUT,
                    "Research orchestrator did not respond in time.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return jsonError(HttpStatus.SERVICE_UNAVAILABLE, "Proxy interrupted.");
        } catch (IOException e) {
            log.warn("Orchestrator I/O error for {} {}: {}", method, upstreamPath, e.getMessage());
            return jsonError(HttpStatus.BAD_GATEWAY, "Research orchestrator connection error.");
        }
        return ResponseEntity
                .status(upstream.statusCode())
                .headers(copyResponseHeaders(upstream))
                .body(upstream.body());
    }

    private static HttpHeaders copyResponseHeaders(HttpResponse<?> upstream) {
        HttpHeaders responseHeaders = new HttpHeaders();
        for (Map.Entry<String, java.util.List<String>> entry : upstream.headers().map().entrySet()) {
            String name = entry.getKey();
            if (shouldForwardResponseHeader(name)) {
                for (String v : entry.getValue()) responseHeaders.add(name, v);
            }
        }
        return responseHeaders;
    }

    private static boolean shouldForwardResponseHeader(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        if (HOP_BY_HOP.contains(lower)) return false;
        return !lower.startsWith("access-control-");
    }

    private static String stripTrailingSlash(String s) {
        return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s;
    }

    private static ResponseEntity<byte[]> jsonError(HttpStatus status, String message) {
        String body = "{\"responseCode\":\"" + status.value()
                + "00\",\"responseDesc\":\"" + status.getReasonPhrase()
                + "\",\"errorMessage\":\"" + message.replace("\"", "\\\"") + "\",\"data\":null}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(body.getBytes(StandardCharsets.UTF_8), headers, status);
    }
}

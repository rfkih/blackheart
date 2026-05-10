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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reverse proxy on the trading JVM forwarding research-only endpoints to the
 * research JVM. The research JVM binds to 127.0.0.1:8081 (see
 * application-research.properties) and is not reachable from the network —
 * every browser request goes through 8080 and is auth-checked here before
 * being forwarded.
 *
 * Why this exists: keeps the public-facing surface to a single port + single
 * security perimeter, while preserving the Phase 1 process decoupling (a
 * research crash still cannot disturb live trading).
 *
 * Forwarded paths:
 *   - /api/v1/backtest/**       → research:8081/api/v1/backtest/**
 *   - /api/v1/research/**       → research:8081/api/v1/research/**
 *   - /api/v1/montecarlo/**     → research:8081/api/v1/montecarlo/**
 *   - /api/v1/historical/**     → research:8081/api/v1/historical/**
 *   - /research-actuator/**     → research:8081/actuator/**   (path rewritten,
 *                                  so SecurityConfig can admin-gate it
 *                                  separately from the local /actuator/** rule)
 *
 * Auth: SecurityConfig validates the JWT cookie before this controller is
 * reached. The cookie header is forwarded verbatim; the research JVM
 * re-validates using the shared JWT_SECRET. Double validation is the cost of
 * the simple "no shared session store" model.
 */
@Slf4j
@RestController
@Profile("!research")
public class ResearchProxyController {

    /**
     * RFC 7230 §6.1 hop-by-hop headers — must NOT be forwarded across a proxy.
     * Plus `host` (we regenerate from the target URL) and `content-length`
     * (the HTTP client sets it from the body publisher).
     */
    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailers",
            "transfer-encoding",
            "upgrade",
            "host",
            "content-length"
    );

    private static final String ACTUATOR_PROXY_PREFIX = "/research-actuator";

    @Value("${app.research.base-url:http://127.0.0.1:8081}")
    private String researchBaseUrl;

    /**
     * Per-request upstream timeout (seconds). Deliberately defaulted to
     * <b>18</b> — just under the frontend axios timeout of 20s.
     *
     * <p>Why not longer:
     * <ul>
     *   <li>If we wait longer than the browser, the user always sees axios's
     *       generic "timeout of 20000ms exceeded" instead of our 504 envelope
     *       with a clear "Research service did not respond" message.</li>
     *   <li>The proxy continues holding the upstream socket open after the
     *       browser disconnects — that's what produces the {@code CLOSE_WAIT}
     *       pile-up on port 8081 when the JVM is slow. Returning early lets
     *       the JDK HttpClient close the connection, freeing both ends.</li>
     * </ul>
     *
     * <p>Override via {@code app.research.request-timeout-seconds} for
     * exceptionally long single calls (e.g. a backtest that legitimately
     * runs synchronously for &gt;18s). Most read calls return in &lt;1s.
     */
    @Value("${app.research.request-timeout-seconds:18}")
    private int requestTimeoutSeconds;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @RequestMapping({
            "/api/v1/backtest", "/api/v1/backtest/**",
            "/api/v1/research", "/api/v1/research/**",
            "/api/v1/montecarlo", "/api/v1/montecarlo/**",
            "/api/v1/historical", "/api/v1/historical/**",
            "/research-actuator", "/research-actuator/**"
    })
    public ResponseEntity<byte[]> proxy(HttpServletRequest req) throws IOException {
        String method = req.getMethod().toUpperCase(Locale.ROOT);
        String path = rewritePath(req.getRequestURI());
        URI target = URI.create(stripTrailingSlash(researchBaseUrl) + path
                + (req.getQueryString() != null ? "?" + req.getQueryString() : ""));
        HttpRequest request = buildUpstreamRequest(req, method, target);
        return sendAndForward(request, method, path);
    }

    private static String rewritePath(String uri) {
        // Rewrite /research-actuator/** → /actuator/** before forwarding.
        return uri.startsWith(ACTUATOR_PROXY_PREFIX)
                ? "/actuator" + uri.substring(ACTUATOR_PROXY_PREFIX.length())
                : uri;
    }

    private HttpRequest buildUpstreamRequest(HttpServletRequest req, String method, URI target) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(requestTimeoutSeconds));

        // Method + body. HttpRequest.Builder rejects bodies on GET/DELETE/HEAD
        // by default; use the no-body publisher for those.
        boolean bodyAllowed = !("GET".equals(method) || "HEAD".equals(method));
        byte[] body = bodyAllowed ? req.getInputStream().readAllBytes() : new byte[0];
        builder.method(method, bodyAllowed && body.length > 0
                ? BodyPublishers.ofByteArray(body)
                : BodyPublishers.noBody());

        forwardRequestHeaders(builder, req);
        return builder.build();
    }

    /**
     * Forward headers except the hop-by-hop set. Cookie + Authorization ride
     * here, which is how the research JVM sees the same auth.
     */
    private void forwardRequestHeaders(HttpRequest.Builder builder, HttpServletRequest req) {
        Enumeration<String> names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (!HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) {
                copyRequestHeader(builder, req, name);
            }
        }
    }

    private void copyRequestHeader(HttpRequest.Builder builder, HttpServletRequest req, String name) {
        Enumeration<String> values = req.getHeaders(name);
        while (values.hasMoreElements()) {
            try {
                builder.header(name, values.nextElement());
            } catch (IllegalArgumentException ignored) {
                // HttpRequest restricts a small set of headers (e.g. "Host",
                // "Content-Length"). Already filtered above; if a new
                // restricted header appears, drop it silently rather than
                // 500ing the whole proxy.
            }
        }
    }

    private ResponseEntity<byte[]> sendAndForward(HttpRequest request, String method, String path) {
        HttpResponse<byte[]> upstream;
        try {
            upstream = http.send(request, BodyHandlers.ofByteArray());
        } catch (ConnectException e) {
            log.warn("Research JVM unreachable at {}: {}", researchBaseUrl, e.getMessage());
            return jsonError(HttpStatus.BAD_GATEWAY,
                    "Research service unavailable. Try again in a moment.");
        } catch (HttpTimeoutException e) {
            log.warn("Research JVM timeout for {} {} after {}s: {}",
                    method, path, requestTimeoutSeconds, e.getMessage());
            return jsonError(HttpStatus.GATEWAY_TIMEOUT,
                    "Research service did not respond within "
                            + requestTimeoutSeconds + "s. The upstream JVM may be hung — "
                            + "check thread state (CLOSE_WAIT count, jstack) and consider restarting.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return jsonError(HttpStatus.SERVICE_UNAVAILABLE, "Proxy interrupted.");
        } catch (IOException e) {
            log.warn("Research JVM I/O error for {} {}: {}", method, path, e.getMessage());
            return jsonError(HttpStatus.BAD_GATEWAY, "Research service connection error.");
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

    /**
     * Drop hop-by-hop headers and CORS headers from the upstream response —
     * the trading JVM's own CorsConfigurationSource adds CORS on the way out,
     * and duplicate {@code Access-Control-Allow-Origin} headers fail browser
     * CORS checks.
     */
    private static boolean shouldForwardResponseHeader(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        if (HOP_BY_HOP.contains(lower)) return false;
        return !lower.startsWith("access-control-");
    }

    private static String stripTrailingSlash(String s) {
        return (s != null && s.endsWith("/")) ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * Build the standard error envelope for upstream-failure responses. Uses
     * Jackson rather than hand-rolled string concatenation so messages with
     * special characters (quotes, control chars, newlines, unicode) serialise
     * correctly. Return type is {@code ResponseEntity<byte[]>} to match the
     * proxy's success-path signature; the LoggingService now decodes byte[]
     * bodies as UTF-8 JSON for the log line, so this no longer surfaces as
     * base64 in the request log.
     */
    private static final ObjectMapper ERROR_MAPPER = new ObjectMapper();

    private static ResponseEntity<byte[]> jsonError(HttpStatus status, String message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("responseCode", status.value() + "00");
        envelope.put("responseDesc", status.getReasonPhrase());
        envelope.put("errorMessage", message);
        envelope.put("data", null);
        byte[] body;
        try {
            body = ERROR_MAPPER.writeValueAsBytes(envelope);
        } catch (JsonProcessingException e) {
            // ObjectMapper on a Map<String,String|null> can't actually fail —
            // but the checked exception forces us to handle it. Fall back to
            // a minimal hard-coded envelope so the client still gets JSON.
            body = ("{\"responseCode\":\"" + status.value() + "00\",\"responseDesc\":\""
                    + status.getReasonPhrase() + "\",\"errorMessage\":\"serialization failed\",\"data\":null}")
                    .getBytes(StandardCharsets.UTF_8);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(body, headers, status);
    }
}

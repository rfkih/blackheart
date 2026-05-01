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
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
        String path = req.getRequestURI();
        // Rewrite /research-actuator/** → /actuator/** before forwarding.
        if (path.startsWith(ACTUATOR_PROXY_PREFIX)) {
            path = "/actuator" + path.substring(ACTUATOR_PROXY_PREFIX.length());
        }

        String query = req.getQueryString();
        URI target = URI.create(stripTrailingSlash(researchBaseUrl) + path
                + (query != null ? "?" + query : ""));

        HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(120));

        // Method + body. HttpRequest.Builder rejects bodies on GET/DELETE/HEAD
        // by default; use the no-body publisher for those.
        String method = req.getMethod().toUpperCase(Locale.ROOT);
        boolean bodyAllowed = !("GET".equals(method) || "HEAD".equals(method));
        byte[] body = bodyAllowed ? req.getInputStream().readAllBytes() : new byte[0];
        builder.method(method, bodyAllowed && body.length > 0
                ? BodyPublishers.ofByteArray(body)
                : BodyPublishers.noBody());

        // Forward headers except the hop-by-hop set. Cookie + Authorization
        // ride here, which is how the research JVM sees the same auth.
        Enumeration<String> names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) continue;
            Enumeration<String> values = req.getHeaders(name);
            while (values.hasMoreElements()) {
                try {
                    builder.header(name, values.nextElement());
                } catch (IllegalArgumentException ignored) {
                    // HttpRequest restricts a small set of headers (e.g.
                    // "Host", "Content-Length"). Already filtered above; if
                    // a new restricted header appears, drop it silently
                    // rather than 500ing the whole proxy.
                }
            }
        }

        HttpResponse<byte[]> upstream;
        try {
            upstream = http.send(builder.build(), BodyHandlers.ofByteArray());
        } catch (ConnectException e) {
            log.warn("Research JVM unreachable at {}: {}", researchBaseUrl, e.getMessage());
            return jsonError(HttpStatus.BAD_GATEWAY,
                    "Research service unavailable. Try again in a moment.");
        } catch (HttpTimeoutException e) {
            log.warn("Research JVM timeout for {} {}: {}", method, path, e.getMessage());
            return jsonError(HttpStatus.GATEWAY_TIMEOUT,
                    "Research service did not respond in time.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return jsonError(HttpStatus.SERVICE_UNAVAILABLE,
                    "Proxy interrupted.");
        } catch (IOException e) {
            log.warn("Research JVM I/O error for {} {}: {}", method, path, e.getMessage());
            return jsonError(HttpStatus.BAD_GATEWAY,
                    "Research service connection error.");
        }

        HttpHeaders responseHeaders = new HttpHeaders();
        for (Map.Entry<String, java.util.List<String>> entry : upstream.headers().map().entrySet()) {
            String name = entry.getKey();
            if (name == null) continue;
            if (HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT))) continue;
            // CORS headers from the upstream are dropped — the trading JVM's
            // own CorsConfigurationSource adds them on the way out, and
            // duplicate `Access-Control-Allow-Origin` headers fail browser
            // CORS checks.
            String lower = name.toLowerCase(Locale.ROOT);
            if (lower.startsWith("access-control-")) continue;
            for (String v : entry.getValue()) {
                responseHeaders.add(name, v);
            }
        }

        return ResponseEntity
                .status(upstream.statusCode())
                .headers(responseHeaders)
                .body(upstream.body());
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

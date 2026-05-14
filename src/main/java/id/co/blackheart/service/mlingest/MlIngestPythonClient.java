package id.co.blackheart.service.mlingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * HTTP client for the Python {@code blackheart-ingest} service.
 *
 * <p>The Java {@code BackfillMl*} handlers delegate to this when
 * {@code ingest.python.base-url} is configured. When the property is
 * blank/missing the handlers fall back to the stub simulation — that's
 * what dev environments and CI use when the Python service isn't running.
 *
 * <p><b>Three failure modes the handler cares about:</b>
 * <ul>
 *   <li>{@link NotImplementedException} — Python returned 501 because the
 *       source module isn't implemented yet (staged rollout). Handler
 *       should fall back to stub with a note.</li>
 *   <li>{@link DisabledException} — base URL not configured. Same as 501
 *       semantically; handler runs the stub silently.</li>
 *   <li>Any other {@link RuntimeException} — real failure. Handler lets
 *       it propagate so the job row is marked FAILED.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MlIngestPythonClient {

    /**
     * Empty / unset means "not configured" → handlers run stubs. Set to
     * e.g. {@code http://127.0.0.1:8089} for same-host Python, or
     * {@code http://home-box.tail-xxxx.ts.net:8089} for cross-host
     * deployment via Tailscale.
     */
    @Value("${ingest.python.base-url:}")
    private String baseUrl;

    @Value("${ingest.python.request-timeout-seconds:600}")
    private int requestTimeoutSeconds;

    private final WebClient.Builder webClientBuilder;

    public boolean isEnabled() {
        return StringUtils.hasText(baseUrl);
    }

    /**
     * POST to {@code {baseUrl}/pull/{source}} and return the response body
     * as a JsonNode for the handler to stash in
     * {@code historical_backfill_job.result}.
     *
     * <p>The {@code config} parameter is a JsonNode and is forwarded
     * verbatim — no Object/Map intermediate, so high-precision numeric
     * values in config (BigDecimal-style) don't lose precision through a
     * roundtrip.
     *
     * @throws DisabledException        client is not configured
     * @throws NotImplementedException  Python returned 501
     * @throws RuntimeException         on network or 5xx errors
     */
    public JsonNode pull(String source, LocalDateTime start, LocalDateTime end,
                         String symbol, JsonNode config) {
        if (!isEnabled()) {
            throw new DisabledException();
        }

        ObjectNode body = JsonNodeFactory.instance.objectNode();
        body.put("start", start.toString());
        body.put("end", end.toString());
        if (symbol != null) {
            body.put("symbol", symbol);
        } else {
            body.putNull("symbol");
        }
        // Pass the JsonNode through directly so numeric precision is
        // preserved (Jackson won't downcast BigDecimal-bearing nodes).
        if (config != null && !config.isNull()) {
            body.set("config", config);
        } else {
            body.set("config", JsonNodeFactory.instance.objectNode());
        }

        String url = baseUrl + "/pull/" + source;
        log.info("Python ingest dispatch | url={} start={} end={} symbol={}",
                url, start, end, symbol);

        try {
            return webClientBuilder.build()
                    .post()
                    .uri(url)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(requestTimeoutSeconds))
                    .block();
        } catch (WebClientResponseException e) {
            HttpStatusCode status = e.getStatusCode();
            // Read the body once and truncate for log/error message hygiene.
            String responseBody = e.getResponseBodyAsString();
            String snippet = responseBody.substring(0, Math.min(500, responseBody.length()));
            if (status.value() == 501) {
                log.info("Python source {} not implemented (501): {}", source, snippet);
                throw new NotImplementedException(source);
            }
            String msg = String.format(
                    "Python ingest %s returned %d: %s",
                    source, status.value(), snippet
            );
            log.warn(msg);
            throw new IllegalStateException(msg, e);
        } catch (Exception e) {
            String msg = String.format("Python ingest %s call failed: %s",
                    source, e.getMessage());
            log.warn(msg);
            throw new IllegalStateException(msg, e);
        }
    }

    /**
     * Thrown when the Python client is not configured (base URL blank).
     * Indicates dev environments where the handler should run its stub.
     */
    public static class DisabledException extends RuntimeException {
        public DisabledException() {
            super("ingest.python.base-url is not configured");
        }
    }

    /**
     * Thrown when Python returns 501 for a source that isn't implemented
     * yet. Used during the staged rollout — handler logs and runs stub.
     */
    public static class NotImplementedException extends RuntimeException {
        private final String source;

        public NotImplementedException(String source) {
            super("Python source not implemented: " + source);
            this.source = source;
        }

        public String getSource() {
            return source;
        }
    }
}

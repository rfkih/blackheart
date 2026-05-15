package id.co.blackheart.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import id.co.blackheart.util.HeaderName;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class LoggingServiceImpl implements LoggingService {

    private static final Logger log = LoggerFactory.getLogger(LoggingServiceImpl.class);

    /**
     * Header names whose values must be fully redacted before being logged.
     * Stored lowercase; comparison is case-insensitive (HTTP header names are
     * case-insensitive per RFC 7230, and clients send {@code Authorization}
     * with a capital A while our prior comparison was lowercase-only — that
     * bug exposed full bearer tokens in the request log).
     *
     * <p>{@code cookie} and {@code set-cookie} are included because the JWT
     * also rides as a cookie ({@code blackheart-token}); leaving them in the
     * clear effectively reverses the {@code authorization}-header redaction.
     */
    private static final Set<String> RESTRICTED_HEADERS = Set.of(
            "authorization",
            "cookie",
            "set-cookie",
            "user_key",
            "x-api-key",
            "x-auth-token",
            "proxy-authorization"
    );

    /**
     * Field names (case-insensitive, substring match) whose values must never
     * reach the log. Covers plain credentials, API keys, and tokens that might
     * appear in request or response bodies.
     *
     * <p>Matching is a substring check so we also catch adjacent fields like
     * {@code currentPassword}, {@code newPassword}, {@code apiSecretKey}, etc.
     */
    private static final Set<String> REDACTED_FIELD_NEEDLES = Set.of(
            "password",
            "secret",
            "apikey",
            "api_key",
            "accesstoken",
            "refreshtoken",
            "privatekey"
    );

    private static final String REDACTED = "[REDACTED]";

    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    @Override
    public void logRequest(HttpServletRequest request, Object body) {
        if (!log.isInfoEnabled()) return;
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("method", request.getMethod());
            map.put("path", request.getRequestURI());
            map.put("headers", buildHeadersMapReq(request));

            if (request.getParameterNames() != null && request.getParameterNames().hasMoreElements()) {
                map.put("parameters", buildParametersMap(request));
            }
            if (body != null) {
                map.put("body", serialiseWithRedaction(body));
            }

            log.info(">>REQUEST {}", mapper.writeValueAsString(map));
        } catch (Exception e) {
            log.warn("Failed to log request: {}", e.getMessage());
        }
    }

    @Override
    public void logResponse(HttpServletRequest request, HttpServletResponse response, Object body) {
        if (!log.isInfoEnabled()) return;
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("method", request.getMethod());
            map.put("path", request.getRequestURI());
            map.put("status", response.getStatus());
            map.put("timeTakenMs", getTimeTaken(request));
            map.put("responseHeaders", buildHeadersMapRes(response));
            if (body != null) {
                map.put("responseBody", serialiseWithRedaction(body));
            }

            log.info("<<RESPONSE {}", mapper.writeValueAsString(map));
        } catch (Exception e) {
            log.warn("Failed to log response: {}", e.getMessage());
        }
    }

    /**
     * Serialise a body to JSON with password/secret/token fields scrubbed.
     * Walks the parsed tree so nested structures (login wrapper → user object,
     * envelope → data.accessToken, etc.) get caught without field-class lists.
     *
     * <p>{@code byte[]} bodies (used by the research proxy and other byte-array
     * endpoints) are decoded as UTF-8 and re-parsed as JSON when possible.
     * Without this branch Jackson defaults to base64-encoding the array, which
     * produces logs full of {@code "eyJyZXNwb25zZUNvZGUi..."} that are useless
     * for debugging. Falls back to the UTF-8 string (or the raw object) if
     * the bytes aren't valid JSON.
     */
    private String serialiseWithRedaction(Object body) throws JsonProcessingException {
        Object effective = body;
        if (body instanceof byte[] bytes) {
            String asText = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            try {
                effective = mapper.readTree(asText);
            } catch (Exception parseFail) {
                effective = asText;
            }
        }
        JsonNode tree = mapper.valueToTree(effective);
        redactSensitiveFields(tree);
        return mapper.writeValueAsString(tree);
    }

    private void redactSensitiveFields(JsonNode node) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            List<String> keysToRedact = new ArrayList<>();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                if (isSensitiveFieldName(entry.getKey())) {
                    keysToRedact.add(entry.getKey());
                } else {
                    redactSensitiveFields(entry.getValue());
                }
            }
            for (String key : keysToRedact) {
                obj.set(key, TextNode.valueOf(REDACTED));
            }
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                redactSensitiveFields(child);
            }
        }
    }

    private boolean isSensitiveFieldName(String name) {
        if (!StringUtils.hasText(name)) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String needle : REDACTED_FIELD_NEEDLES) {
            if (lower.contains(needle)) return true;
        }
        return false;
    }

    private Map<String, String> buildHeadersMapReq(HttpServletRequest request) {
        Map<String, String> headers = Collections.list(request.getHeaderNames())
                .stream()
                .collect(Collectors.toMap(
                        key -> key,
                        key -> redactIfSensitiveHeader(key, request.getHeader(key)),
                        (a, b) -> a,
                        LinkedHashMap::new));

        // Ensure X-Request-ID and X-Correlation-ID are present (set by interceptor via MDC)
        headers.computeIfAbsent(HeaderName.X_REQUEST_ID.getValue(),
                k -> MDC.get(HeaderName.X_REQUEST_ID.getValue()));
        headers.computeIfAbsent(HeaderName.X_CORRELATION_ID.getValue(),
                k -> MDC.get(HeaderName.X_CORRELATION_ID.getValue()));

        return headers;
    }

    private Map<String, String> buildHeadersMapRes(HttpServletResponse response) {
        Map<String, String> map = new LinkedHashMap<>();
        response.getHeaderNames().forEach(h -> map.put(h, redactIfSensitiveHeader(h, response.getHeader(h))));
        return map;
    }

    /**
     * Replace the value of any {@link #RESTRICTED_HEADERS} entry with the
     * redacted sentinel. Returns the original value otherwise. Comparison is
     * case-insensitive — a {@code null} or empty value passes through so the
     * log still records the header was present.
     *
     * <p>We deliberately replace the whole value instead of masking the middle
     * because partial values still leak the JWT header + claims (alg, sub,
     * role, exp), which is enough to fingerprint the user / impersonate within
     * the visible window.
     */
    private static String redactIfSensitiveHeader(String name, String value) {
        if (!StringUtils.hasText(value)) return value;
        if (name == null) return value;
        if (RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) return REDACTED;
        return value;
    }

    private Map<String, String> buildParametersMap(HttpServletRequest request) {
        Map<String, String> result = new LinkedHashMap<>();
        Enumeration<String> names = request.getParameterNames();
        while (names.hasMoreElements()) {
            String key = names.nextElement();
            result.put(key, request.getParameter(key));
        }
        return result;
    }

    private long getTimeTaken(HttpServletRequest request) {
        Object startTime = request.getAttribute("startTime");
        if (startTime instanceof Long start) {
            return System.currentTimeMillis() - start;
        }
        return -1L;
    }
}

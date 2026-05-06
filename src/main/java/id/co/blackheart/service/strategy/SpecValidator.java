package id.co.blackheart.service.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Validates {@code param_overrides} maps against the JSON Schema for the
 * strategy's archetype. Schemas are bundled as classpath resources at
 * {@code strategy-schemas/<archetype>.schema.json}.
 *
 * <p>Catalog (M1):
 * <ul>
 *   <li>{@code mean_reversion_oscillator}</li>
 *   <li>{@code trend_pullback}</li>
 *   <li>{@code donchian_breakout}</li>
 *   <li>{@code momentum_mean_reversion}</li>
 * </ul>
 *
 * <p>{@code LEGACY_JAVA} is intentionally absent — legacy strategies use
 * their per-strategy param controllers and validate via their own typed
 * {@code Params} classes. {@link StrategyParamController} blocks
 * {@code LEGACY_JAVA} before validation is reached.
 *
 * <p>Schema loading is eager (at startup) and cached for the JVM's lifetime —
 * archetype schemas are static for the whole {@code archetypeVersion}; mutating
 * one would shift the contract for every strategy bound to it, which is a
 * deliberate operator action, not a hot-reload concern.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SpecValidator {

    private static final String CLASSPATH_PREFIX = "strategy-schemas/";

    private static final List<String> KNOWN_ARCHETYPES = List.of(
            "mean_reversion_oscillator",
            "trend_pullback",
            "donchian_breakout",
            "momentum_mean_reversion"
    );

    private final ObjectMapper objectMapper;

    private final Map<String, JsonSchema> schemaCache = new ConcurrentHashMap<>();

    @PostConstruct
    void warm() {
        for (String archetype : KNOWN_ARCHETYPES) {
            try {
                schemaCache.put(archetype, loadSchema(archetype));
                log.info("SpecValidator loaded schema for archetype={}", archetype);
            } catch (Exception e) {
                // Don't crash boot — a missing schema for an unused archetype must
                // not block trading. Validator will refuse writes to that archetype
                // until the schema is shipped.
                log.error("SpecValidator failed to load schema for archetype={}: {}",
                        archetype, e.getMessage());
            }
        }
    }

    /**
     * Throws {@link InvalidSpecException} when overrides violate the archetype's schema.
     * No-op for empty/null maps (clearing overrides is always allowed).
     *
     * @throws UnknownArchetypeException if archetype has no registered schema
     */
    public void validate(String archetype, Map<String, Object> overrides) {
        if (!StringUtils.hasText(archetype)) {
            throw new UnknownArchetypeException("archetype is required for spec validation");
        }
        if (CollectionUtils.isEmpty(overrides)) {
            return;
        }

        String key = archetype.toLowerCase(Locale.ROOT);
        JsonSchema schema = schemaCache.get(key);
        if (schema == null) {
            throw new UnknownArchetypeException(
                    "No JSON Schema registered for archetype=" + archetype
                            + " (known: " + KNOWN_ARCHETYPES + ")");
        }

        JsonNode payload = objectMapper.valueToTree(overrides);
        Set<ValidationMessage> messages = schema.validate(payload);
        if (!messages.isEmpty()) {
            String summary = messages.stream()
                    .map(ValidationMessage::getMessage)
                    .collect(Collectors.toCollection(LinkedHashSet::new))
                    .stream()
                    .collect(Collectors.joining("; "));
            throw new InvalidSpecException(
                    "param_overrides invalid for archetype=" + archetype + ": " + summary);
        }
    }

    private JsonSchema loadSchema(String archetype) throws IOException {
        ClassPathResource resource = new ClassPathResource(CLASSPATH_PREFIX + archetype + ".schema.json");
        try (InputStream in = resource.getInputStream()) {
            JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
            return factory.getSchema(in);
        }
    }

    public static class InvalidSpecException extends RuntimeException {
        public InvalidSpecException(String message) { super(message); }
    }

    public static class UnknownArchetypeException extends RuntimeException {
        public UnknownArchetypeException(String message) { super(message); }
    }
}

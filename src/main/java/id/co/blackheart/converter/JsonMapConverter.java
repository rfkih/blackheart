package id.co.blackheart.converter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * JPA converter that serialises a {@code Map<String, Object>} to a JSON string for storage in a
 * {@code jsonb} or {@code text} PostgreSQL column, and deserialises it back on read.
 *
 * <p>The mapper used here is intentionally plain — no polymorphic type information is embedded —
 * so the stored JSON remains human-readable and schema-independent.
 */
@Converter
@Slf4j
public class JsonMapConverter implements AttributeConverter<Map<String, Object>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS);
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(Map<String, Object> attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return "{}";
        }
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (Exception e) {
            log.error("Failed to serialize param map to JSON: {}", e.getMessage());
            return "{}";
        }
    }

    @Override
    public Map<String, Object> convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) {
            return new HashMap<>();
        }
        try {
            return MAPPER.readValue(dbData, MAP_TYPE);
        } catch (Exception e) {
            log.warn("Failed to deserialize JSON param map from DB, returning empty: {}", e.getMessage());
            return new HashMap<>();
        }
    }
}

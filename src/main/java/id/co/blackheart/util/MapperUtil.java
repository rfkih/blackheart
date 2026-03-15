package id.co.blackheart.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;


@Component
@AllArgsConstructor
@Slf4j
public class MapperUtil {


    public long getIntervalMinutes(String interval) {
        return switch (interval) {
            case "1m" -> 1;
            case "3m" -> 3;
            case "5m" -> 5;
            case "15m" -> 15;
            case "30m" -> 30;
            case "1h" -> 60;
            case "2h" -> 120;
            case "4h" -> 240;
            case "6h" -> 360;
            case "8h" -> 480;
            case "12h" -> 720;
            case "1d" -> 1440;
            default -> throw new IllegalArgumentException("Unsupported interval: " + interval);
        };
    }

    public static Object map(Object obj, Class<?> target) {
        if (obj==null || target==null) return null;
        Object result = null;
        try {
            result = new ObjectMapper().convertValue(obj, target);
        } catch (Exception e) {
            log.error("Error while mapping object to {}", target.getName());
        }
        return result;
    }

    public static Object mapIgnoreUnknownProps(Object obj, Class<?> target) {
        if (obj==null || target==null) return null;
        Object result = null;
        try {
            result = new ObjectMapper().configure(DeserializationFeature
                            .FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .convertValue(obj, target);
        } catch (Exception e) {
            log.error("Error while mapping and ignore unknown props object to {}", target.getName());
        }
        return result;
    }


    public static String write(Object obj) {
        if (obj==null) return null;
        String result = null;
        try {
            result = new ObjectMapper().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("Can't write Object as String");
            log.error(e.getMessage(),e);
        }
        return result;
    }

    public static Map<String, Object> toMap(Object obj) {
        if (obj==null) return Collections.emptyMap();
        Map<String, Object> result = null;
        try {
            result = new ObjectMapper()
                    .convertValue(obj, new TypeReference<>() {});
        } catch (Exception e) {
            log.error("Can't convert Object to Map<String, Object> class");
            log.error(e.getMessage(),e);
        }

        return result;
    }
}

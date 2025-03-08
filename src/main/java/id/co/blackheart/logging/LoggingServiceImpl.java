package id.co.blackheart.logging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import id.co.blackheart.util.HeaderName;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Component
public class LoggingServiceImpl implements LoggingService{

    private static final Logger log = Logger.getLogger(LoggingServiceImpl.class.getSimpleName());
    private static final String[] restrictedHeader = {"authorization", "user_key"};

    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final String HEADER = "header";
    private static final String PATH = "path";




    @Override
    public void logRequest(HttpServletRequest httpServletRequest, Object body) {
        try {
            Map<String, Object> mapRequest = new HashMap<>();
            mapRequest.put("logType", "REQUEST");
            mapRequest.put("method", httpServletRequest.getMethod());
            mapRequest.put("path", httpServletRequest.getRequestURI());
            mapRequest.put(HEADER, buildHeadersMapReq(httpServletRequest));
            ThreadLocal<Map<String, String>> context = ThreadLocal.withInitial(HashMap::new);
            mapRequest.forEach((k, v) -> context.get().put(k, String.valueOf(v)));
            if (httpServletRequest.getParameterNames() != null && httpServletRequest.getParameterNames().hasMoreElements()) {
                mapRequest.put("parameters", buildParametersMap(httpServletRequest));
                log.info(
                        ">>REQUEST[ method= " + httpServletRequest.getMethod() +
                                ", "+ PATH +" = " + httpServletRequest.getRequestURI() +
                                ", headers= " + mapRequest.get(HEADER) +
                                ", parameters= " + mapRequest.get("parameters")
                );
            }
            if (body != null) {
                mapRequest.put("body", mapper.writeValueAsString(body));
                log.info(
                        ">>REQUEST[ method= " + httpServletRequest.getMethod() +
                                ", " + PATH + " = " + httpServletRequest.getRequestURI() +
                                ", headers= " + mapRequest.get(HEADER) +
                                ", body= " + mapRequest.get("body")
                );

            }
        } catch (Exception e) {
            log.warning(e.getLocalizedMessage());
        }

    }

    @Override
    public void logResponse(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, Object body) {
        try {
            long endTime = System.currentTimeMillis();
            Map<String, Object> mapResponse = new HashMap<>();
            mapResponse.put("logType", "RESPONSE");
            mapResponse.put("method", httpServletRequest.getMethod());
            mapResponse.put("path", httpServletRequest.getRequestURI());
            mapResponse.put("responseHeaders", buildHeadersMapRes(httpServletResponse));
            mapResponse.put("timeTaken", getTimeTaken(httpServletRequest, endTime));
            if (body != null)
                mapResponse.put("responseBody", mapper.writeValueAsString(body));
            ThreadLocal<Map<String, String>> context = ThreadLocal.withInitial(HashMap::new);
            mapResponse.forEach((k, v) -> context.get().put(k, String.valueOf(v)));
            log.info(
                    "<<RESPONSE] method= " + httpServletRequest.getMethod() +
                            ", " +PATH+ " = " + httpServletRequest.getRequestURI() +
                            ", timeTaken= " + mapResponse.get("timeTaken") + "ms" +
                            ", responseHeaders= " + mapResponse.get("responseHeaders") +
                            ", responseBody= " + mapResponse.get("responseBody") + "\n\n"
            );
        } catch (Exception e) {
            log.warning(e.getLocalizedMessage());
        }

    }

    private Map<String, String> buildHeadersMapRes(HttpServletResponse response) {
        Map<String, String> map = new HashMap<>();
        response.getHeaderNames().forEach(header -> map.put(header, response.getHeader(header)));
        return map;
    }

    private Map<String, String> buildParametersMap(HttpServletRequest httpServletRequest){
        Map<String, String> resultMap = new HashMap<>();
        Enumeration<String> paramNames = httpServletRequest.getParameterNames();

        while (paramNames.hasMoreElements()) {
            String key = paramNames.nextElement();
            String value = httpServletRequest.getParameter(key);
            resultMap.put(key, value);
        }
        return resultMap;
    }

    private long getTimeTaken(HttpServletRequest request, long endTime) {
        // Capture end time of API call and calculate response time
        long startTime = (long) request.getAttribute("startTime");
        return endTime - startTime;
    }

    private Map<String, String> buildHeadersMapReq(HttpServletRequest request) {
        Map<String, String> headers = Collections.list(request.getHeaderNames())
                .stream().collect(Collectors.toMap(
                        key -> key,
                        request::getHeader));
        for (String s : restrictedHeader) {
            headers.computeIfPresent(s, (k, v) -> {
                int mid = (v.length() < 50 ? (v.length() / 4) : 12);
                return v.substring(0, mid) + "..." + v.substring(v.length() - mid);
            });
        }
        generateIdHeader(headers);
        return headers;
    }

    private void generateIdHeader(Map<String, String> headers) {
        ThreadLocal<Map<String, String>> context = ThreadLocal.withInitial(HashMap::new);
        context.get().clear();
        context.get().put(HeaderName.X_REQUEST_ID.getValue(),
                headers.computeIfAbsent(HeaderName.X_REQUEST_ID.getValue(),
                        k -> checkAndGenerateUUID(MDC.get(HeaderName.X_REQUEST_ID.getValue()))));
        context.get().put(HeaderName.X_CORRELATION_ID.getValue(),
                headers.computeIfAbsent(HeaderName.X_CORRELATION_ID.getValue(),
                        k -> checkAndGenerateUUID(MDC.get(HeaderName.X_CORRELATION_ID.getValue()))));
    }

    private String checkAndGenerateUUID(String value) {
        if (validateEmpty(value))
            return  UUID.randomUUID().toString();
        return value;
    }


    public boolean validateEmpty(String field) {
        return field == null || field.isEmpty() || field.isBlank();
    }

}
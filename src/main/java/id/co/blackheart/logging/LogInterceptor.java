package id.co.blackheart.logging;


import id.co.blackheart.util.HeaderName;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;

@Component
@RequiredArgsConstructor
public class LogInterceptor implements HandlerInterceptor {

    private final LoggingService loggingService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        MDC.clear();

        generateIdHeader(request, HeaderName.X_REQUEST_ID.getValue(), true);
        generateIdHeader(request, HeaderName.X_CORRELATION_ID.getValue(), true);
        generateIdHeader(request, HeaderName.X_USER_ID.getValue(), false);
        generateIdHeader(request, HeaderName.X_ROLE_ID.getValue(), false);
        generateIdHeader(request, HeaderName.X_BRANCH_ID.getValue(), false);
        generateIdHeader(request, HeaderName.X_CLIENT_ID.getValue(), false);

        if (DispatcherType.REQUEST.name().equals(request.getDispatcherType().name())
                && request.getMethod().equals(HttpMethod.GET.name())) {
            loggingService.logRequest(request, null);
        }
        return true;
    }

    private void generateIdHeader(HttpServletRequest request, String key, boolean generateDefault) {
        String value = request.getHeader(key);
        if (generateDefault && !StringUtils.hasLength(value)) {
            value = UUID.randomUUID().toString();
        }
        MDC.put(key, value);
    }

}


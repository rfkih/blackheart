package id.co.blackheart.logging;

import id.co.blackheart.util.HeaderName;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
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

        setMdc(request, HeaderName.X_REQUEST_ID.getValue(), true);
        setMdc(request, HeaderName.X_CORRELATION_ID.getValue(), true);
        setMdc(request, HeaderName.X_USER_ID.getValue(), false);
        setMdc(request, HeaderName.X_ROLE_ID.getValue(), false);
        setMdc(request, HeaderName.X_BRANCH_ID.getValue(), false);
        setMdc(request, HeaderName.X_CLIENT_ID.getValue(), false);

        // GET requests have no body — log them here directly.
        // POST/PUT/PATCH bodies are logged in CustomRequestBodyAdviceAdapter.afterBodyRead.
        if ("GET".equalsIgnoreCase(request.getMethod())) {
            loggingService.logRequest(request, null);
        }

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // Always clear MDC after the request finishes to prevent bleed-over between
        // reused Tomcat threads.
        MDC.clear();
    }

    private void setMdc(HttpServletRequest request, String key, boolean generateIfAbsent) {
        String value = request.getHeader(key);
        if (generateIfAbsent && !StringUtils.hasLength(value)) {
            value = UUID.randomUUID().toString();
        }
        if (StringUtils.hasLength(value)) {
            MDC.put(key, value);
        }
    }
}

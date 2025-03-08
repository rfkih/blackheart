package id.co.blackheart.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

public class RequestTimingFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpServletRequest = (HttpServletRequest) request;

        long startTime = System.currentTimeMillis();
        httpServletRequest.setAttribute("startTime", startTime);

        chain.doFilter(request, response);
    }

}

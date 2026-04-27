package id.co.blackheart.config;

import id.co.blackheart.interceptor.ClientHttpResponseWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.*;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

@Configuration
public class RestTemplateConfig {

    private static final Logger log = LoggerFactory.getLogger(RestTemplateConfig.class);

    // Truncate logged response bodies to avoid flooding logs with large payloads.
    private static final int MAX_BODY_LOG_LENGTH = 500;

    @Bean
    public RestTemplate restTemplate() {
        RestTemplate restTemplate = new RestTemplate();

        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            log.debug("Outbound request | method={} uri={} bodyBytes={}",
                    request.getMethod(), request.getURI(), body.length);

            ClientHttpResponse response = execution.execute(request, body);

            byte[] responseBody = response.getBody().readAllBytes();
            if (log.isDebugEnabled()) {
                String preview = new String(responseBody, StandardCharsets.UTF_8);
                if (preview.length() > MAX_BODY_LOG_LENGTH) {
                    preview = preview.substring(0, MAX_BODY_LOG_LENGTH) + "...[truncated]";
                }
                log.debug("Outbound response | status={} body={}", response.getStatusCode(), preview);
            }

            return new ClientHttpResponseWrapper(response, responseBody);
        };

        List<ClientHttpRequestInterceptor> interceptors = Collections.singletonList(interceptor);
        restTemplate.setRequestFactory(
                new InterceptingClientHttpRequestFactory(new SimpleClientHttpRequestFactory(), interceptors));

        return restTemplate;
    }

    /**
     * Dedicated RestTemplate for Telegram bot polling with explicit timeouts.
     * Prevents a Telegram API hang from blocking the shared RestTemplate thread pool.
     */
    @Bean("telegramRestTemplate")
    public RestTemplate telegramRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return new RestTemplate(factory);
    }

    /**
     * Dedicated RestTemplate for Binance public ticker calls on user-facing
     * read paths (e.g. portfolio price prefetch). Bounded timeouts so a
     * Binance hiccup never stalls a portfolio response.
     */
    @Bean("binanceRestTemplate")
    public RestTemplate binanceRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3_000);
        factory.setReadTimeout(5_000);
        return new RestTemplate(factory);
    }
}

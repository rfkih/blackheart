package id.co.blackheart.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;

@Configuration
@Slf4j
public class RestClientConfig {


    @Bean
    public RestClient restClient() {

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofSeconds(60))
                .setConnectionRequestTimeout(Timeout.ofSeconds(60))
                .setResponseTimeout(Timeout.ofSeconds(60))
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .addRequestInterceptorFirst(this::logRequestUrl)
                .addResponseInterceptorFirst(this::logResponseBody)
                .build();

        ClientHttpRequestFactory requestFactory = new HttpComponentsClientHttpRequestFactory(httpClient);

        return RestClient.builder()
                .defaultHeader("Content-Type", "application/json")
                .defaultHeader("Accept", "application/json")
                .defaultHeader(HttpHeaders.ACCEPT_ENCODING, "gzip, deflate")
                .requestFactory(requestFactory)
                .build();
    }

    private void logRequestUrl(HttpRequest request, EntityDetails entity, HttpContext context) {
        log.info("Request URL: {}", request.getRequestUri());
    }

    private void logResponseBody(HttpResponse response, EntityDetails entity, HttpContext context) {
        try {
            if (response instanceof ClassicHttpResponse) {
                ClassicHttpResponse classicResponse = (ClassicHttpResponse) response;
                if (classicResponse.getEntity() != null) {
                    String responseBody = EntityUtils.toString(classicResponse.getEntity());
                    log.info("Response Body: {}", responseBody);
                    ContentType contentType = ContentType.parse(classicResponse.getEntity().getContentType());
                    classicResponse.setEntity(new StringEntity(responseBody, contentType));
                }
            }
        } catch (IOException | ParseException ex) {
            log.error("Error reading response body", ex);
        }
    }
}

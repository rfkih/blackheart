package id.co.blackheart.config;

import id.co.blackheart.interceptor.ClientHttpResponseWrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.*;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

@Configuration
public class RestTemplateConfig {

    private static final Logger logger = Logger.getLogger(RestTemplateConfig.class.getName());

    @Bean
    public RestTemplate restTemplate() {
        RestTemplate restTemplate = new RestTemplate();

        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            logger.info("Request URI: " + request.getURI());
            logger.info("Request Body: " + (body.length > 0 ? new String(body, StandardCharsets.UTF_8) : "No Body"));

            ClientHttpResponse response = execution.execute(request, body);

            byte[] responseBody = response.getBody().readAllBytes();
            String responseBodyString = new String(responseBody, StandardCharsets.UTF_8);

            logger.info("Response Status Code: " + response.getStatusCode());
            logger.info("Response Body: " + responseBodyString);

            return new ClientHttpResponseWrapper(response, responseBody);
        };

        List<ClientHttpRequestInterceptor> interceptors = Collections.singletonList(interceptor);
        restTemplate.setRequestFactory(new InterceptingClientHttpRequestFactory(new SimpleClientHttpRequestFactory(), interceptors));

        return restTemplate;
    }
}


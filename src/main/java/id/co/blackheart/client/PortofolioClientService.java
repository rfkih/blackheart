package id.co.blackheart.client;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import id.co.blackheart.dto.AssetDto;
import id.co.blackheart.exception.InvalidResponseException;
import id.co.blackheart.exception.ServiceUnavailableException;
import id.co.blackheart.util.MapperUtil;
import id.co.blackheart.util.TokocryptoResponseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.apache.commons.codec.digest.HmacAlgorithms;
import org.apache.commons.codec.digest.HmacUtils;


import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class PortofolioClientService {

    @Value("${client.tokocrypto.url}")
    private String tokocryptoClientUrl;


    private final RestClient restClient;
    private final ObjectMapper objectMapper;



    public AssetDto getSingleAsset() throws IOException {
        String apiKey = "4D4a187D94638cCAeea4Dd89C923b769MZfR93vHMlDqp0gTVb1S3RY5ajToqgvK";
        String secretKey = "677160Cc9657F62B88c40055F029B18FfrqqZRruKoju6eX3P7MQAgpW8ZD3K5kc";
        long timestamp = System.currentTimeMillis();
        String recvWindow = "5000";
        String asset = "IDR";

        String queryString = String.format("asset=%s&timestamp=%d&recvWindow=%s", asset, timestamp, recvWindow);
        String signature = new HmacUtils(HmacAlgorithms.HMAC_SHA_256, secretKey).hmacHex(queryString);

        Map<String, String> queryParams = Map.of(
                "asset", asset,
                "timestamp", String.valueOf(timestamp),
                "recvWindow", recvWindow,
                "signature", signature
        );
        TokocryptoResponseService responseService =  executeGet("https://www.tokocrypto.com", "/open/v1/account/spot/asset", queryParams, apiKey);
        Map<String, Object> responseMap = MapperUtil.toMap(responseService.getData());
        log.info("responseMap: {}", responseMap);
        return  null;
    }


    private TokocryptoResponseService executeGet(String baseUrl, String path, Map<String, String> queryParams, String apiKey) throws IOException {
        try {
            ResponseEntity<String> response = restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.scheme("https")
                                .host("www.tokocrypto.com")
                                .path(path);
                        queryParams.forEach(uriBuilder::queryParam);
                        return uriBuilder.build();
                    })
                    .header("X-MBX-APIKEY", apiKey)
                    .header("Accept-Encoding", "gzip, deflate, br")
                    .retrieve()
                    .toEntity(String.class);

            return decodeResponse(response);
        } catch (Exception e) {
            throw new ServiceUnavailableException(" - Service Unavailable");
        }
    }



    private TokocryptoResponseService decodeResponse(ResponseEntity<String> response) throws IOException {
        TokocryptoResponseService responseService = objectMapper.readValue(response.getBody(), TokocryptoResponseService.class);
        String message = "";

        if (responseService == null || !"0".equals(responseService.getCode())) {

            String jsonString = response.getBody();
            Pattern leadingPattern = Pattern.compile("^[\"']+");
            Pattern trailingPattern = Pattern.compile("[\"']+$");
            jsonString = leadingPattern.matcher(jsonString).replaceAll("");
            jsonString = trailingPattern.matcher(jsonString).replaceAll("");

            JsonNode jsonNode = objectMapper.readTree(jsonString);  // JSON parsing attempt
            message = jsonNode.path("responseDesc").asText("No Valid Response");

            throw new InvalidResponseException(" - " + message + " - Parameter Service");
        }

        return responseService;
    }



}

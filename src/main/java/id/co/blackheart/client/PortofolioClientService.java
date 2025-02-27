package id.co.blackheart.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;

@Service
@Slf4j
@RequiredArgsConstructor
public class PortofolioClientService {

    private static final String BASE_URL = "https://www.tokocrypto.com";
    private static final String API_KEY = "4D4a187D94638cCAeea4Dd89C923b769MZfR93vHMlDqp0gTVb1S3RY5ajToqgvK";
    private static final String SECRET_KEY = "677160Cc9657F62B88c40055F029B18FfrqqZRruKoju6eX3P7MQAgpW8ZD3K5kc";
    private static final RestTemplate restTemplate = new RestTemplate();
    private static final ObjectMapper objectMapper = new ObjectMapper();


    public JsonNode getSingleAsset(String queryString ,String signature) throws Exception {


        String finalUrl = BASE_URL + "/open/v1/account/spot/asset?" + queryString + "&signature=" + signature;

        //Set headers
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-MBX-APIKEY", API_KEY);
        headers.set("Accept", "*/*");
        headers.set("Accept-Encoding", "gzip, deflate, br");
        headers.set("Connection", "keep-alive");

        ResponseEntity<String> response = restTemplate.getForEntity(finalUrl, String.class);
        log.info("response : " + response.getBody());

        return objectMapper.readTree(response.getBody());
    }

    private JsonNode decodeResponse(ResponseEntity<byte[]> response) throws Exception {
        byte[] responseBody = response.getBody();

        if (responseBody == null || responseBody.length == 0) {
            throw new Exception("❌ Empty response from API");
        }

        String responseString;
        String contentEncoding = response.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);

        if ("gzip".equalsIgnoreCase(contentEncoding)) {
            log.info("✅ Response is GZIP compressed. Attempting decompression...");
            responseString = decompressGzip(responseBody);
        } else {
            responseString = new String(responseBody, StandardCharsets.UTF_8);
        }

        // ✅ Print API Response for Debugging
        log.info("🔍 Decoded JSON Response: {}", responseString);

        // ✅ Parse JSON using Jackson
        return objectMapper.readTree(responseString);
    }

    private String decompressGzip(byte[] compressedData) throws Exception {
        try (ByteArrayInputStream byteStream = new ByteArrayInputStream(compressedData);
             GZIPInputStream gis = new GZIPInputStream(byteStream);
             InputStreamReader reader = new InputStreamReader(gis, StandardCharsets.UTF_8);
             BufferedReader bufferedReader = new BufferedReader(reader)) {

            return bufferedReader.lines().collect(Collectors.joining());
        }
    }

    /**
     * ✅ Correct HMAC SHA256 Signature Generation (Same as Postman)
     */
    private String generateSignature(String secretKey, String queryString) throws Exception {
        Mac sha256Hmac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        sha256Hmac.init(secretKeySpec);
        byte[] hash = sha256Hmac.doFinal(queryString.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(hash);
    }

    private static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            hexString.append(String.format("%02x", b));
        }
        return hexString.toString();
    }
}

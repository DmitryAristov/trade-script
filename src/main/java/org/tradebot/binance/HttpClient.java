package org.tradebot.binance;

import org.tradebot.domain.APIError;
import org.tradebot.domain.HTTPResponse;
import org.tradebot.util.Log;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.tradebot.util.JsonParser.parseAPIError;

public class HttpClient {

    private static final String API_KEY = "****";
    private static final String API_SECRET = "****";
    protected static final String BASE_URL = "https://fapi.binance.com";
    private final Log log = new Log();
    public static long TIME_DIFF = 0;

    public HTTPResponse<String, APIError> sendRequest(String endpoint, String method, Map<String, String> params) {
        return sendRequest(endpoint, method, params, false);
    }

    public HTTPResponse<String, APIError> sendRequest(String endpoint, String method, Map<String, String> params, boolean useBody) {
        try {
            long start = System.nanoTime();
            log.debug(String.format("[REQUEST START] HTTP %s to %s", method, endpoint));
            log.debug(String.format("Initial params: %s", params));

            params.put("recvWindow", "5000");
            params.put("timestamp", String.valueOf(System.currentTimeMillis() + TIME_DIFF));
            String signature = generateSignature(params);
            params.put("signature", signature);

            String query = getParamsString(params);
            URL url = useBody
                    ? new URI(BASE_URL + endpoint).toURL()
                    : new URI(BASE_URL + endpoint + "?" + query).toURL();
            log.debug(String.format("Generated URL: %s", url));

            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setRequestProperty("X-MBX-APIKEY", API_KEY);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            log.debug(String.format("Request properties: %s", connection.getRequestProperties()));

            if (useBody && (method.equals("POST") || method.equals("PUT") || method.equals("DELETE"))) {
                connection.setDoOutput(true);
                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = query.getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }
                log.debug(String.format("Request body: %s", query));
            }

            // response
            int responseCode = connection.getResponseCode();
            log.debug(String.format("Response headers: %s", connection.getHeaderFields()));
            long finish = System.nanoTime();
            double elapsedMs = (finish - start) / 1_000_000.0;
            log.debug(String.format("[REQUEST END] HTTP %s to %s completed in %.2f ms", method, endpoint, elapsedMs));
            return readResponse(connection, responseCode);
        } catch (Exception e) {
            throw log.throwError("Failed to send HTTP request", e);
        }
    }

    private HTTPResponse<String, APIError> readResponse(HttpURLConnection connection, int responseCode) throws IOException {
        if (responseCode >= 200 && responseCode < 300) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String response = reader.lines().collect(Collectors.joining());
                log.debug(String.format("Response code: %d, Response body: %s", responseCode, response));
                return HTTPResponse.success(responseCode, response);
            }
        } else {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getErrorStream()))) {
                String errorResponse = reader.lines().collect(Collectors.joining());
                log.warn(String.format("Response code: %d, Error body: %s", responseCode, errorResponse));
                return HTTPResponse.error(responseCode, parseAPIError(errorResponse));
            }
        }
    }

    protected String generateSignature(Map<String, String> params) throws Exception {
        String queryString = getParamsString(params);
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(API_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        byte[] hash = mac.doFinal(queryString.getBytes(StandardCharsets.UTF_8));
        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) hexString.append('0');
            hexString.append(hex);
        }
        return hexString.toString();
    }

    protected String getParamsString(Map<String, String> params) {
        return params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("&"));
    }

    public HTTPResponse<String, APIError> sendPublicRequest(String endpoint, String method, Map<String, String> params) {
        try {
            long start = System.nanoTime();
            log.debug(String.format("[REQUEST START] HTTP %s to %s", method, endpoint));

            String query = getParamsString(params);
            URL url = new URI(BASE_URL + endpoint + "?" + query).toURL();
            log.debug(String.format("Generated URL: %s", url));

            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            log.debug(String.format("Request properties: %s", connection.getRequestProperties()));

            // response
            int responseCode = connection.getResponseCode();
            Map<String, List<String>> headers = connection.getHeaderFields();
            log.debug(String.format("Response headers: %s", headers));

            long finish = System.nanoTime();
            double elapsedMs = (finish - start) / 1_000_000.0;
            log.debug(String.format("[REQUEST END] HTTP GET to %s completed in %.2f ms", endpoint, elapsedMs));

            return readResponse(connection, responseCode);
        } catch (Exception e) {
            throw log.throwError("Failed to send HTTP request", e);
        }
    }
}

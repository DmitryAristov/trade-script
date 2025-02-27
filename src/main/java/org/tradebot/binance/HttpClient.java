package org.tradebot.binance;

import org.tradebot.domain.APIError;
import org.tradebot.domain.HTTPResponse;
import org.tradebot.util.Log;
import org.tradebot.util.OperationHelper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static org.tradebot.util.JsonParser.parseAPIError;
import static org.tradebot.util.Settings.*;

public class HttpClient {

    private final String apiKey;
    private final String apiSecret;
    private final OperationHelper operationHelper;
    private final Map<Long, APIError> errors = new ConcurrentHashMap<>();

    public HttpClient(String apiKey, String apiSecret, int clientNumber) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.operationHelper = new OperationHelper(clientNumber);
        this.log = new Log(clientNumber);
    }

    private final Log log;

    public HTTPResponse<String> sendRequest(String endpoint, String method, Map<String, String> params) {
        return sendRequest(endpoint, method, params, false);
    }

    private static int requestsCount = 0;
    private static int errorsCount = 0;
    public HTTPResponse<String> sendRequest(String endpoint, String method, final Map<String, String> params, boolean useBody) {
        return operationHelper.performWithRetry(() -> {
            final Map<String, String> paramsCopy = new HashMap<>(params);
            try {
                long start = System.nanoTime();
                log.debug(String.format("[REQUEST START] HTTP %s to %s", method, endpoint));
                log.debug(String.format("Initial params: %s", paramsCopy));

                if (TEST_RUN && SIMULATE_API_ERRORS) {
                    requestsCount++;
                    if (requestsCount % API_ERROR_REPEATING_COUNT == 0) {
                        errorsCount++;
                        if (errorsCount % 2 == 0) {
                            return HTTPResponse.error(500, new APIError(-1, "simulating error response... " + requestsCount));
                        } else {
                            throw new RuntimeException("Simulating exception read response... " + requestsCount);
                        }
                    }
                }

                paramsCopy.put("recvWindow", String.valueOf(RECV_WINDOW));
                paramsCopy.put("timestamp", String.valueOf(System.currentTimeMillis() + TIME_DIFF));
                String signature = generateSignature(paramsCopy);
                paramsCopy.put("signature", signature);

                String query = getParamsString(paramsCopy);
                URL url = useBody
                        ? URI.create(BASE_URL + endpoint).toURL()
                        : URI.create(BASE_URL + endpoint + "?" + query).toURL();
                log.debug(String.format("Generated URL: %s", url));

                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod(method);
                connection.setRequestProperty("X-MBX-APIKEY", apiKey);
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

                int responseCode = connection.getResponseCode();
                log.debug(String.format("Response headers: %s", connection.getHeaderFields()));
                long finish = System.nanoTime();
                double elapsedMs = (finish - start) / 1_000_000.0;
                log.debug(String.format("[REQUEST END] HTTP %s to %s completed in %.2f ms", method, endpoint, elapsedMs));
                return readResponse(connection, responseCode);
            } catch (Exception e) {
                log.writeHttpError(e);
                throw new RuntimeException(e);
            }
        });
    }

    protected String generateSignature(Map<String, String> params) throws Exception {
        String queryString = getParamsString(params);
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
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

    protected HTTPResponse<String> readResponse(HttpURLConnection connection, int responseCode) throws IOException {
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
                APIError apiError = parseAPIError(errorResponse);
                errors.put(System.currentTimeMillis(), apiError);
                log.writeHttpError(apiError);
                return HTTPResponse.error(responseCode, apiError);
            }
        }
    }

    public void logAll() {
        try {
            log.debug(String.format("""
                            HttpClient state:
                            errors: %s
                            """,
                    errors));
        } catch (Exception e) {
            log.warn("Failed to write", e);
        }
    }
}

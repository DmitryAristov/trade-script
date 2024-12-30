package org.tradebot.binance;

import org.tradebot.domain.HttpResponse;
import org.tradebot.util.Log;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class HttpClientService {
    private static final String API_KEY = "****";
    private static final String API_SECRET = "****";
    private static final String BASE_URL = "https://fapi.binance.com";

    public String sendRequest(String endpoint, String method, Map<String, String> params) {
        Log.debug(String.format("send %s request to %s with params %s", method, endpoint, params.toString()));
        try {
            params.put("timestamp", String.valueOf(System.currentTimeMillis()));
            String signature = generateSignature(params);
            params.put("signature", signature);

            URL url = new URI(BASE_URL + endpoint + "?" + getParamsString(params)).toURL();

            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setRequestProperty("X-MBX-APIKEY", API_KEY);
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

            Map<String, List<String>> headers = connection.getHeaderFields();
            Log.debug(String.format("headers %s", headers.toString()));

            int responseCode = connection.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {

                    String response = reader.lines().collect(Collectors.joining());
                    Log.debug(String.format("response code %d, body %s", responseCode, response));
                    return response;
                }
            } else {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getErrorStream()))) {
                    String errorResponse = reader.lines().collect(Collectors.joining());
                    throw Log.error(String.format("http error %d, message %s", responseCode, errorResponse));
                }
            }
        } catch (Exception e) {
            throw Log.error(e);
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

    public HttpResponse sendPublicRequest(String request) {
        return sendPublicRequest(request, false);
    }

    public HttpResponse sendPublicRequest(String request, boolean allowErrorResponse) {
        try {
            URL url = new URI(request).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            Map<String, List<String>> headers = connection.getHeaderFields();
            Log.debug(String.format("headers %s", headers.toString()));

            int responseCode = connection.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                    String response = reader.lines().collect(Collectors.joining());
                    HttpResponse httpResponse = new HttpResponse(responseCode, response);
                    Log.debug(httpResponse.toString());
                    return httpResponse;
                }
            } else {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getErrorStream()))) {
                    String errorResponse = reader.lines().collect(Collectors.joining());
                    if (allowErrorResponse) {
                        return new HttpResponse(responseCode, errorResponse);
                    } else {
                        throw Log.error(new HttpResponse(responseCode, errorResponse).toString());
                    }
                }
            }
        } catch (Exception e) {
            throw Log.error(e);
        }
    }

}

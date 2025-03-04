package org.tradebot.binance;

import org.tradebot.domain.APIError;
import org.tradebot.domain.HTTPResponse;
import org.tradebot.service.TaskManager;
import org.tradebot.util.Log;
import org.tradebot.util.OperationHelper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.tradebot.util.JsonParser.parseAPIError;
import static org.tradebot.util.Settings.BASE_URL;
import static org.tradebot.util.Settings.WRITE_HTTP_ERROR_TASK;

public class PublicHttpClient {
    private final Log log = new Log();
    private final OperationHelper operationHelper = new OperationHelper();
    private final Map<Long, APIError> errors = new ConcurrentHashMap<>();
    private final TaskManager taskManager = TaskManager.getInstance();

    public HTTPResponse<String> sendPublicRequest(String endpoint, String method, Map<String, String> params) {
        return operationHelper.performWithRetry(() -> {
            final Map<String, String> paramsCopy = new HashMap<>(params);
            try {
                long start = System.nanoTime();
                log.debug(String.format("[REQUEST START] HTTP %s to %s", method, endpoint));

                String query = getParamsString(paramsCopy);
                URL url = new URI(BASE_URL + endpoint + "?" + query).toURL();
                log.debug(String.format("Generated URL: %s", url));

                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod(method);
                log.debug(String.format("Request properties: %s", connection.getRequestProperties()));

                int responseCode = connection.getResponseCode();
                Map<String, List<String>> headers = connection.getHeaderFields();
                log.debug(String.format("Response headers: %s", headers));

                long finish = System.nanoTime();
                double elapsedMs = (finish - start) / 1_000_000.0;
                log.debug(String.format("[REQUEST END] HTTP GET to %s completed in %.2f ms", endpoint, elapsedMs));

                return readResponse(connection, responseCode);
            } catch (Exception e) {
                taskManager.schedule(WRITE_HTTP_ERROR_TASK, () -> log.writeHttpError(e), 0, TimeUnit.MILLISECONDS);
                throw log.throwError("Failed to send HTTP request", e);
            }
        });
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
                taskManager.schedule(WRITE_HTTP_ERROR_TASK, () -> log.writeHttpError(apiError), 0, TimeUnit.MILLISECONDS);
                errors.put(System.currentTimeMillis(), apiError);
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

package org.tradebot.util;

import org.tradebot.domain.APIError;
import org.tradebot.domain.HTTPResponse;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.tradebot.util.Settings.RETRIES_COUNT;
import static org.tradebot.util.Settings.RETRY_SLEEP_TIME;

public class OperationHelper {
    private final Log log;

    public OperationHelper(int clientNumber) {
        this.log = new Log(clientNumber);
    }

    public OperationHelper() {
        this.log = new Log();
    }

    public  <T> HTTPResponse<T> performWithRetry(Supplier<HTTPResponse<T>> operation) {
        HTTPResponse<T> response = null;
        for (int i = 0; i < RETRIES_COUNT; i++) {
            try {
                response = operation.get();
            } catch (Exception e) {
                sleep();
                log.warn("HTTP failure", e);
                response = HTTPResponse.error(500, new APIError(-1, e.getMessage()));
                continue;
            }

            if (response.isSuccess()) {
                return response;
            }

            if (i < RETRIES_COUNT - 1) {
                sleep();
                log.warn("Failed to perform operation: " + response.getError());
            }
        }

        log.error("Failed to perform operation with retries: " + response.getError());
        return response;
    }

    private void sleep() {
        try {
            TimeUnit.MILLISECONDS.sleep(RETRY_SLEEP_TIME);
        } catch (InterruptedException e) {
            log.error("Unexpected interrupted error", e);
            Thread.currentThread().interrupt();
        }
    }
}

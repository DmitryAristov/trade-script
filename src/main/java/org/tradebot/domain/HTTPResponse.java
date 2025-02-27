package org.tradebot.domain;

public class HTTPResponse<T> {
    private final int code;
    private final T value;
    private final APIError error;
    private final boolean isSuccess;

    private HTTPResponse(T value, org.tradebot.domain.APIError error, boolean isSuccess, int code) {
        this.value = value;
        this.error = error;
        this.isSuccess = isSuccess;
        this.code = code;
    }

    public static <T> HTTPResponse<T> success(int code, T value) {
        return new HTTPResponse<>(value, null, true, code);
    }

    public static <T> HTTPResponse<T> error(int code, APIError error) {
        return new HTTPResponse<>(null, error, false, code);
    }

    public boolean isSuccess() {
        return isSuccess;
    }

    public boolean isError() {
        return !isSuccess;
    }

    public T getValue() {
        return value;
    }

    public APIError getError() {
        return error;
    }

    public int getStatusCode() {
        return code;
    }

    public T getResponse() {
        if (this.isSuccess()) {
            return this.value;
        } else {
            throw new RuntimeException(this.error.toString());
        }
    }
}

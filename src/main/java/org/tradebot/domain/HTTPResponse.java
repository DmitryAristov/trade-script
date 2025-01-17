package org.tradebot.domain;

public class HTTPResponse<T, E> {
    private final int code;
    private final T value;
    private final E error;
    private final boolean isSuccess;

    private HTTPResponse(T value, E error, boolean isSuccess, int code) {
        this.value = value;
        this.error = error;
        this.isSuccess = isSuccess;
        this.code = code;
    }

    public static <T, E> HTTPResponse<T, E> success(int code, T value) {
        return new HTTPResponse<>(value, null, true, code);
    }

    public static <T, E> HTTPResponse<T, E> error(int code, E error) {
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

    public E getError() {
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

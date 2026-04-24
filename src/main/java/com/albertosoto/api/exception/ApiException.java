package com.albertosoto.api.exception;

import lombok.Getter;

/**
 * Domain exception that communicates an HTTP status code back to the caller.
 * Throw this from any controller or service to produce a structured error response.
 */
@Getter
public class ApiException extends RuntimeException {

    private final int statusCode;

    public ApiException(final int statusCode, final String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public ApiException(final int statusCode, final String message, final Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }
}

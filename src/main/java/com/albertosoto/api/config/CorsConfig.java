package com.albertosoto.api.config;

import java.util.Arrays;
import java.util.List;

/**
 * Centralises CORS policy.
 *
 * Allowed origins are read from the CORS_ALLOWED_ORIGINS environment variable
 * (comma-separated).
 *
 * Example:
 *   CORS_ALLOWED_ORIGINS=https://albertosotogonzalez.com,http://localhost:5173
 */
public final class CorsConfig {

    private static final List<String> ALLOWED_ORIGINS;

    static {
        final String env = System.getenv("CORS_ALLOWED_ORIGINS");
        ALLOWED_ORIGINS = (env != null && !env.isBlank())
                ? Arrays.stream(env.split(",")).map(String::trim).toList()
                : List.of();
    }

    private CorsConfig() {}

    /**
     * Returns the origin to echo back in Access-Control-Allow-Origin,
     * or null if the request origin is not in the allowed list.
     */
    public static String resolveOrigin(final String requestOrigin) {
        if (requestOrigin == null || requestOrigin.isBlank()) {
            return null;
        }
        return ALLOWED_ORIGINS.stream()
                .filter(allowed -> allowed.equalsIgnoreCase(requestOrigin.trim()))
                .findFirst()
                .orElse(null);
    }
}

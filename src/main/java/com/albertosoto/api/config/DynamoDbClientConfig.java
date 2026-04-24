package com.albertosoto.api.config;

import com.albertosoto.api.exception.ApiException;
import lombok.Getter;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Owns the {@link DynamoDbClient} singleton and DynamoDB-related configuration for the
 * lifetime of the Lambda instance. Uses the default credential and region provider chain,
 * which on Lambda reads AWS_DEFAULT_REGION and the execution role credentials automatically.
 *
 * <p>Required environment variables:
 * <ul>
 *   <li>{@code RATE_LIMIT_DAILY_MAX} — maximum number of requests allowed per user per 24 h window</li>
 * </ul>
 */
@Getter
public final class DynamoDbClientConfig {

    private static final String ENV_RATE_LIMIT_DAILY_MAX = "RATE_LIMIT_DAILY_MAX";

    private final DynamoDbClient client;
    private final int dailyLimit;

    public DynamoDbClientConfig() {
        this.client = DynamoDbClient.create();
        this.dailyLimit = parsePositiveInt(ENV_RATE_LIMIT_DAILY_MAX, requireEnv(ENV_RATE_LIMIT_DAILY_MAX));
    }

    private static String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new ApiException(500, "Missing required environment variable: " + name);
        }
        return value;
    }

    private static int parsePositiveInt(String envName, String raw) {
        try {
            int value = Integer.parseInt(raw);
            if (value <= 0) {
                throw new ApiException(500, envName + " must be a positive integer, got: " + raw);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new ApiException(500, "Invalid integer for " + envName + ": " + raw);
        }
    }
}

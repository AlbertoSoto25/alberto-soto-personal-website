package com.albertosoto.api.service;

import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.Map;

/**
 * Per-user rate limiter backed by DynamoDB.
 *
 * <p>Each call to {@link #isAllowed(String)} atomically creates or increments a counter
 * in the {@code rate-limits} table (partition key: {@code id}). The TTL is set only on
 * first insertion and never overwritten, so the window always expires 24 h after the
 * first request in that window.
 *
 * <p>Limit: {@value #DAILY_LIMIT} requests per compound {@code ip:visitorId} key per 24-hour window.
 */
@RequiredArgsConstructor
public class RateLimiter {

    private static final Logger logger = LogManager.getLogger(RateLimiter.class);
    private static final String TABLE_NAME = "alberto-soto-personal-webpage-rate-limits";
    private static final long WINDOW_SECONDS = 86_400L;

    private final DynamoDbClient dynamoDbClient;
    private final int dailyLimit;

    /**
     * Returns {@code true} if the request is within the daily limit and has been counted,
     * or {@code false} if the limit has already been reached.
     *
     * <p>Single {@code UpdateItem} with a conditional expression handles both creation
     * and increment atomically — no separate {@code GetItem} needed:
     * <ul>
     *   <li>Item absent: condition {@code attribute_not_exists(id)} passes; count is set to 1
     *       and ttl is initialised.</li>
     *   <li>Item present, count &lt; limit: condition {@code count &lt; :limit} passes;
     *       count is incremented; ttl is left unchanged via {@code if_not_exists}.</li>
     *   <li>Item present, count &ge; limit: condition fails with
     *       {@link ConditionalCheckFailedException} → return {@code false}.</li>
     * </ul>
     */
    public boolean isAllowed(String key) {
        long expiresAt = Instant.now().getEpochSecond() + WINDOW_SECONDS;
        logger.info("DYNAMO UpdateItem table={} key={} limit={}", TABLE_NAME, key, dailyLimit);

        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(TABLE_NAME)
                .key(Map.of("id", AttributeValue.fromS(key)))
                .updateExpression(
                        "SET #cnt = if_not_exists(#cnt, :zero) + :one, " +
                        "#ttl = if_not_exists(#ttl, :ttl)")
                .conditionExpression("attribute_not_exists(id) OR #cnt < :limit")
                .expressionAttributeNames(Map.of(
                        "#cnt", "count",
                        "#ttl", "ttl"
                ))
                .expressionAttributeValues(Map.of(
                        ":zero",  AttributeValue.fromN("0"),
                        ":one",   AttributeValue.fromN("1"),
                        ":ttl",   AttributeValue.fromN(String.valueOf(expiresAt)),
                        ":limit", AttributeValue.fromN(String.valueOf(dailyLimit))
                ))
                .build();

        try {
            dynamoDbClient.updateItem(request);
            logger.info("DYNAMO allowed key={}", key);
            return true;
        } catch (ConditionalCheckFailedException e) {
            logger.warn("DYNAMO denied key={} reason=limit_reached", key);
            return false;
        }
    }
}

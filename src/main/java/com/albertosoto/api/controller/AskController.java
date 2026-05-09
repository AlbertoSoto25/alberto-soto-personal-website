package com.albertosoto.api.controller;

import com.albertosoto.api.exception.ApiException;
import com.albertosoto.api.model.request.AskRequest;
import com.albertosoto.api.model.response.AskResponse;
import com.albertosoto.api.service.AskService;
import com.albertosoto.api.service.RateLimiter;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handles POST /ask — applies per-user rate limiting, then delegates to {@link AskService}.
 */
@RequiredArgsConstructor
public class AskController implements Controller {

    private static final Logger logger = LogManager.getLogger(AskController.class);
    private static final int HTTP_OK = 200;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final Map<String, String> JSON_HEADER = Map.of("Content-Type", "application/json");
    private static final byte[] STREAM_DELIMITER = new byte[8];

    private final ObjectMapper objectMapper;
    private final AskService askService;
    private final RateLimiter rateLimiter;

    @Override
    public APIGatewayProxyResponseEvent handle(APIGatewayProxyRequestEvent request, Context context) {
        AskRequest askRequest = parseRequest(request.getBody());
        logger.info("ASK question_b64={}", askRequest.getQuestion());
        askRequest.setQuestion(decodeQuestion(askRequest.getQuestion()));

        String ip = extractIp(request);
        String visitorId = askRequest.getVisitorId();
        String rateLimitKey = ip + ":" + visitorId;
        logger.info("ASK rate_limit_check ip={} visitor_id={}", ip, visitorId);

        if (!rateLimiter.isAllowed(rateLimitKey)) {
            logger.warn("RATE_LIMITED ip={} visitor_id={}", ip, visitorId);
            return buildErrorResponse(HTTP_TOO_MANY_REQUESTS,
                    "Daily request limit reached. Please try again tomorrow.");
        }

        logger.info("ASK rate_limit_allowed question_length={}", askRequest.getQuestion().length());
        AskResponse askResponse = askService.ask(askRequest);
        logger.info("ASK completed response_length={}", askResponse.getResponse().length());
        return buildResponse(askResponse);
    }

    private String extractIp(APIGatewayProxyRequestEvent request) {
        Map<String, String> headers = request.getHeaders();
        logger.info("ASK headers={}", headers);
        if (headers != null) {
            String xff = headers.get("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String ip = xff.split(",")[0].trim();
                logger.info("ASK ip_source=X-Forwarded-For raw=\"{}\" resolved={}", xff, ip);
                return ip;
            }
        }
        throw new ApiException(400, "X-Forwarded-For header is required");
    }

    private AskRequest parseRequest(String body) {
        try {
            return objectMapper.readValue(body, AskRequest.class);
        } catch (Exception e) {
            throw new ApiException(400, "Invalid request body", e);
        }
    }

    private String decodeQuestion(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new ApiException(400, "question is required");
        }
        try {
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "question must be Base64-encoded", e);
        }
    }

    private APIGatewayProxyResponseEvent buildResponse(AskResponse askResponse) {
        try {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(HTTP_OK)
                    .withHeaders(JSON_HEADER)
                    .withBody(objectMapper.writeValueAsString(askResponse));
        } catch (Exception e) {
            throw new ApiException(500, "Failed to serialise response", e);
        }
    }

    private APIGatewayProxyResponseEvent buildErrorResponse(int statusCode, String message) {
        try {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(statusCode)
                    .withHeaders(JSON_HEADER)
                    .withBody(objectMapper.writeValueAsString(Map.of("error", message)));
        } catch (Exception e) {
            throw new ApiException(500, "Failed to serialise error response", e);
        }
    }

    public void handleStreaming(APIGatewayProxyRequestEvent request, OutputStream out, String allowedOrigin) throws IOException {
        AskRequest askRequest;
        String rateLimitKey;

        try {
            askRequest = parseRequest(request.getBody());
            logger.info("ASK_STREAM question_b64={}", askRequest.getQuestion());
            askRequest.setQuestion(decodeQuestion(askRequest.getQuestion()));
            String ip = extractIp(request);
            rateLimitKey = ip + ":" + askRequest.getVisitorId();
            logger.info("ASK_STREAM rate_limit_check ip={} visitor_id={}", ip, askRequest.getVisitorId());
        } catch (ApiException e) {
            logger.warn("ASK_STREAM validation_error status={} msg={}", e.getStatusCode(), e.getMessage());
            writeError(out, e.getStatusCode(), e.getMessage(), allowedOrigin);
            return;
        }

        if (!rateLimiter.isAllowed(rateLimitKey)) {
            logger.warn("RATE_LIMITED_STREAM key={}", rateLimitKey);
            writeError(out, HTTP_TOO_MANY_REQUESTS, "Daily request limit reached. Please try again tomorrow.", allowedOrigin);
            return;
        }

        logger.info("ASK_STREAM bedrock_start question_length={}", askRequest.getQuestion().length());

        java.io.ByteArrayOutputStream capture = new java.io.ByteArrayOutputStream();
        OutputStream tee = new OutputStream() {
            @Override public void write(int b) throws IOException { out.write(b); capture.write(b); }
            @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); capture.write(b, off, len); }
            @Override public void flush() throws IOException { out.flush(); }
        };

        writeMetadata(tee, HTTP_OK, streamHeaders(allowedOrigin));
        try {
            askService.askStream(askRequest, tee);
        } catch (Exception e) {
            logger.error("ASK_STREAM bedrock_error: {}", e.getMessage(), e);
        }
        tee.flush();

        String fullEvent = capture.toString(java.nio.charset.StandardCharsets.UTF_8.name())
                .replace("\0", "\\0");
        logger.info("STREAM_FULL_EVENT_TO_FRONTEND:\n{}", fullEvent);
        logger.info("ASK_STREAM complete");
    }

    private void writeError(OutputStream out, int statusCode, String message, String allowedOrigin) throws IOException {
        writeMetadata(out, statusCode, jsonHeaders(allowedOrigin));
        out.write(objectMapper.writeValueAsBytes(Map.of("error", message)));
        out.flush();
    }

    private void writeMetadata(OutputStream out, int statusCode, Map<String, String> headers) throws IOException {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("statusCode", statusCode);
        metadata.put("headers", headers);
        byte[] metadataBytes = objectMapper.writeValueAsBytes(metadata);
        logger.info("STREAM_OUT metadata={}", new String(metadataBytes, java.nio.charset.StandardCharsets.UTF_8));
        out.write(metadataBytes);
        out.write(STREAM_DELIMITER);
        logger.info("STREAM_OUT delimiter written (8 null bytes)");
    }

    private Map<String, String> streamHeaders(String allowedOrigin) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "text/plain;charset=utf-8");
        addCorsHeaders(headers, allowedOrigin);
        return headers;
    }

    private Map<String, String> jsonHeaders(String allowedOrigin) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        addCorsHeaders(headers, allowedOrigin);
        return headers;
    }

    private void addCorsHeaders(Map<String, String> headers, String allowedOrigin) {
        if (allowedOrigin != null) {
            headers.put("Access-Control-Allow-Origin", allowedOrigin);
            headers.put("Vary", "Origin");
        }
    }
}

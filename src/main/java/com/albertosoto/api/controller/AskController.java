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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
        if (headers != null) {
            String xff = headers.get("x-forwarded-for");
            if (xff != null && !xff.isBlank()) {
                String ip = xff.split(",")[0].trim();
                logger.info("ASK ip_source=x-forwarded-for raw=\"{}\" resolved={}", xff, ip);
                return ip;
            }
        }
        String ip = request.getRequestContext().getIdentity().getSourceIp();
        logger.info("ASK ip_source=request_context resolved={}", ip);
        return ip;
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
}

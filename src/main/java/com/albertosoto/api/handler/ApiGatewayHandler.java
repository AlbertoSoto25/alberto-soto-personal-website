package com.albertosoto.api.handler;

import com.albertosoto.api.config.CorsConfig;
import com.albertosoto.api.exception.ApiException;
import com.albertosoto.api.router.Router;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

/**
 * Lambda entry point. Receives all API Gateway REST proxy events and delegates
 * routing to {@link Router}.
 */
public class ApiGatewayHandler
        implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final Logger logger = LogManager.getLogger(ApiGatewayHandler.class);
    private static final int HTTP_INTERNAL_ERROR = 500;

    private final Router router;
    private final ObjectMapper objectMapper;

    public ApiGatewayHandler() {
        this.objectMapper = new ObjectMapper();
        this.router = new Router(objectMapper);
    }

    @Override
    public APIGatewayProxyResponseEvent handleRequest(
            final APIGatewayProxyRequestEvent request,
            final Context context) {

        final String method = request.getHttpMethod();
        final String path   = request.getPath();
        logger.info("REQUEST  {} {}", method, path);

        try {
            final APIGatewayProxyResponseEvent response = withCors(request, router.route(request, context));
            logResponse(method, path, response);
            return response;
        } catch (ApiException e) {
            logger.warn("API_ERROR {} {}", e.getStatusCode(), e.getMessage());
            final APIGatewayProxyResponseEvent errorResponse = withCors(request, buildErrorResponse(e.getStatusCode(), e.getMessage()));
            logResponse(method, path, errorResponse);
            return errorResponse;
        } catch (Exception e) {
            logger.error("UNHANDLED_ERROR {}: {}", e.getClass().getSimpleName(), e.getMessage(), e);
            final APIGatewayProxyResponseEvent errorResponse = withCors(request, buildErrorResponse(HTTP_INTERNAL_ERROR, "Internal server error"));
            logResponse(method, path, errorResponse);
            return errorResponse;
        }
    }

    private void logResponse(final String method, final String path, final APIGatewayProxyResponseEvent response) {
        try {
            logger.info("LAMBDA_RESPONSE {} {} - statusCode={} headers={} body={}",
                    method, path,
                    response.getStatusCode(),
                    objectMapper.writeValueAsString(response.getHeaders()),
                    response.getBody());
        } catch (Exception e) {
            logger.warn("LAMBDA_RESPONSE_LOG_FAILED {}", e.getMessage());
        }
    }

    private APIGatewayProxyResponseEvent withCors(
            final APIGatewayProxyRequestEvent request,
            final APIGatewayProxyResponseEvent response) {

        final String requestOrigin = request.getHeaders() != null
                ? request.getHeaders().get("origin")
                : null;
        final String allowedOrigin = CorsConfig.resolveOrigin(requestOrigin);

        final Map<String, String> headers = new java.util.HashMap<>();
        if (response.getHeaders() != null) headers.putAll(response.getHeaders());
        if (allowedOrigin != null) {
            headers.put("Access-Control-Allow-Origin", allowedOrigin);
            headers.put("Vary", "Origin");
        }
        headers.put("Content-Type", "application/json");
        response.setHeaders(headers);
        return response;
    }

    private APIGatewayProxyResponseEvent buildErrorResponse(final int statusCode, final String message) {
        try {
            final String body = objectMapper.writeValueAsString(Map.of("error", message));
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(statusCode)
                    .withHeaders(Map.of("Content-Type", "application/json"))
                    .withBody(body);
        } catch (Exception e) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(HTTP_INTERNAL_ERROR)
                    .withBody("{\"error\":\"Internal server error\"}");
        }
    }
}

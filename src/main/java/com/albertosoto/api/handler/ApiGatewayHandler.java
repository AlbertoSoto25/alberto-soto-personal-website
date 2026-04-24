package com.albertosoto.api.handler;

import com.albertosoto.api.exception.ApiException;
import com.albertosoto.api.router.Router;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

/**
 * Lambda entry point. Receives all API Gateway proxy events and delegates
 * routing to {@link Router}.
 */
public class ApiGatewayHandler
        implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final Logger logger = LogManager.getLogger(ApiGatewayHandler.class);
    private static final int HTTP_INTERNAL_ERROR = 500;

    private final Router router;
    private final ObjectMapper objectMapper;

    public ApiGatewayHandler() {
        this.objectMapper = new ObjectMapper();
        this.router = new Router(objectMapper);
    }

    @Override
    public APIGatewayV2HTTPResponse handleRequest(
            final APIGatewayV2HTTPEvent request,
            final Context context) {

        final String method = request.getRequestContext().getHttp().getMethod();
        final String path   = request.getRawPath();
        logger.info("REQUEST  {} {}", method, path);

        try {
            final APIGatewayV2HTTPResponse response = withCors(router.route(request, context));
            logger.info("RESPONSE {} {} - {}", method, path, response.getStatusCode());
            return response;
        } catch (ApiException e) {
            logger.warn("API_ERROR {} {}", e.getStatusCode(), e.getMessage());
            return withCors(buildErrorResponse(e.getStatusCode(), e.getMessage()));
        } catch (Exception e) {
            logger.error("UNHANDLED_ERROR {}: {}", e.getClass().getSimpleName(), e.getMessage(), e);
            return withCors(buildErrorResponse(HTTP_INTERNAL_ERROR, "Internal server error"));
        }
    }

    private APIGatewayV2HTTPResponse withCors(final APIGatewayV2HTTPResponse response) {
        Map<String, String> headers = new java.util.HashMap<>();
        if (response.getHeaders() != null) headers.putAll(response.getHeaders());
        headers.put("Access-Control-Allow-Origin", "*");
        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(response.getStatusCode())
                .withHeaders(headers)
                .withBody(response.getBody())
                .build();
    }

    private APIGatewayV2HTTPResponse buildErrorResponse(final int statusCode, final String message) {
        try {
            final String body = objectMapper.writeValueAsString(Map.of("error", message));
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(statusCode)
                    .withHeaders(Map.of("Content-Type", "application/json"))
                    .withBody(body)
                    .build();
        } catch (Exception e) {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(HTTP_INTERNAL_ERROR)
                    .withBody("{\"error\":\"Internal server error\"}")
                    .build();
        }
    }
}

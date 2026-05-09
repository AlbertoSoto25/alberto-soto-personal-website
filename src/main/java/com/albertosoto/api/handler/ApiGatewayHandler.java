package com.albertosoto.api.handler;

import com.albertosoto.api.config.CorsConfig;
import com.albertosoto.api.controller.AskController;
import com.albertosoto.api.exception.ApiException;
import com.albertosoto.api.router.Router;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.databind.DeserializationFeature;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lambda entry point. POST /ask is served with response streaming; all other
 * routes are buffered but still written in the streaming-format envelope
 * required by API Gateway when the integration uses InvokeWithResponseStream.
 */
public class ApiGatewayHandler implements RequestStreamHandler {

    private static final Logger logger = LogManager.getLogger(ApiGatewayHandler.class);
    private static final byte[] STREAM_DELIMITER = new byte[8];

    private final Router router;
    private final AskController askController;
    private final ObjectMapper objectMapper;

    public ApiGatewayHandler() {
        this.objectMapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.router = new Router(objectMapper);
        this.askController = router.getAskController();
    }

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        APIGatewayProxyRequestEvent request = objectMapper.readValue(input, APIGatewayProxyRequestEvent.class);

        String method = request.getHttpMethod() != null ? request.getHttpMethod().toUpperCase() : "";
        String path   = normalizePath(request.getPath());
        logger.info("REQUEST {} {}", method, path);

        String origin        = request.getHeaders() != null ? request.getHeaders().get("origin") : null;
        String allowedOrigin = CorsConfig.resolveOrigin(origin);

        if ("POST".equals(method) && "/ask".equals(path)) {
            askController.handleStreaming(request, output, allowedOrigin);
        } else {
            handleBuffered(request, output, context, allowedOrigin);
        }
    }

    private void handleBuffered(
            APIGatewayProxyRequestEvent request,
            OutputStream output,
            Context context,
            String allowedOrigin) throws IOException {

        APIGatewayProxyResponseEvent response;
        try {
            response = router.route(request, context);
        } catch (ApiException e) {
            logger.warn("API_ERROR {} {}", e.getStatusCode(), e.getMessage());
            response = buildErrorResponse(e.getStatusCode(), e.getMessage());
        } catch (Exception e) {
            logger.error("UNHANDLED_ERROR {}: {}", e.getClass().getSimpleName(), e.getMessage(), e);
            response = buildErrorResponse(500, "Internal server error");
        }

        response = withCors(response, allowedOrigin);
        logger.info("RESPONSE {} {} status={}", request.getHttpMethod(), request.getPath(), response.getStatusCode());
        writeBufferedResponse(output, response);
    }

    private void writeBufferedResponse(OutputStream output, APIGatewayProxyResponseEvent response) throws IOException {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("statusCode", response.getStatusCode());
        if (response.getHeaders() != null && !response.getHeaders().isEmpty()) {
            metadata.put("headers", response.getHeaders());
        }
        output.write(objectMapper.writeValueAsBytes(metadata));
        output.write(STREAM_DELIMITER);
        if (response.getBody() != null) {
            output.write(response.getBody().getBytes(StandardCharsets.UTF_8));
        }
        output.flush();
    }

    private APIGatewayProxyResponseEvent withCors(APIGatewayProxyResponseEvent response, String allowedOrigin) {
        Map<String, String> headers = new HashMap<>();
        if (response.getHeaders() != null) {
            headers.putAll(response.getHeaders());
        }
        if (allowedOrigin != null) {
            headers.put("Access-Control-Allow-Origin", allowedOrigin);
            headers.put("Vary", "Origin");
        }
        headers.put("Content-Type", "application/json");
        response.setHeaders(headers);
        return response;
    }

    private String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return "/";
        return rawPath.endsWith("/") && rawPath.length() > 1
                ? rawPath.substring(0, rawPath.length() - 1)
                : rawPath;
    }

    private APIGatewayProxyResponseEvent buildErrorResponse(int statusCode, String message) {
        try {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(statusCode)
                    .withHeaders(Map.of("Content-Type", "application/json"))
                    .withBody(objectMapper.writeValueAsString(Map.of("error", message)));
        } catch (Exception e) {
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(500)
                    .withBody("{\"error\":\"Internal server error\"}");
        }
    }
}

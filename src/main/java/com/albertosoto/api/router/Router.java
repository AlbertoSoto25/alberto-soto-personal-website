package com.albertosoto.api.router;

import com.albertosoto.api.config.BedrockClientConfig;
import com.albertosoto.api.config.CorsConfig;
import com.albertosoto.api.config.DynamoDbClientConfig;
import com.albertosoto.api.controller.AskController;
import com.albertosoto.api.controller.Controller;
import com.albertosoto.api.controller.SessionController;
import com.albertosoto.api.exception.ApiException;
import com.albertosoto.api.service.AskService;
import com.albertosoto.api.service.RateLimiter;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps incoming HTTP method + path combinations to the appropriate
 * {@link Controller} and invokes it
 */
public class Router {

    private static final Logger logger = LogManager.getLogger(Router.class);
    private static final int HTTP_OK = 200;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_METHOD_NOT_ALLOWED = 405;

    /** Key format: "METHOD /path" */
    private final Map<String, Controller> routes = new HashMap<>();

    public Router(final ObjectMapper objectMapper) {
        BedrockClientConfig bedrockConfig = new BedrockClientConfig();
        AskService askService = new AskService(bedrockConfig);
        DynamoDbClientConfig dynamoDbConfig = new DynamoDbClientConfig();
        RateLimiter rateLimiter = new RateLimiter(dynamoDbConfig.getClient(), dynamoDbConfig.getDailyLimit());
        routes.put("POST /ask",     new AskController(objectMapper, askService, rateLimiter));
        routes.put("POST /session", new SessionController(objectMapper));
    }

    /**
     * Resolves the controller for the incoming request and delegates execution.
     *
     * @throws ApiException if no route matches (404) or method is not allowed (405).
     */
    public APIGatewayProxyResponseEvent route(
            final APIGatewayProxyRequestEvent request,
            final Context context) {

        final String path   = normalizePath(request.getPath());
        final String method = request.getHttpMethod().toUpperCase();

        if ("OPTIONS".equals(method)) {
            logger.info("PREFLIGHT OPTIONS {}", path);
            final String requestOrigin = request.getHeaders() != null
                    ? request.getHeaders().get("origin")
                    : null;
            final String allowedOrigin = CorsConfig.resolveOrigin(requestOrigin);
            final Map<String, String> preflightHeaders = new HashMap<>();
            if (allowedOrigin != null) {
                preflightHeaders.put("Access-Control-Allow-Origin",  allowedOrigin);
                preflightHeaders.put("Access-Control-Allow-Methods", "POST,OPTIONS");
                preflightHeaders.put("Access-Control-Allow-Headers", "Content-Type,Authorization");
                preflightHeaders.put("Vary",                         "Origin");
            }
            return new APIGatewayProxyResponseEvent()
                    .withStatusCode(HTTP_OK)
                    .withHeaders(preflightHeaders)
                    .withBody("");
        }

        final String key = method + " " + path;
        final Controller controller = routes.get(key);

        if (controller == null) {
            final boolean pathExists = routes.keySet().stream()
                    .anyMatch(k -> k.endsWith(" " + path));

            if (pathExists) {
                throw new ApiException(HTTP_METHOD_NOT_ALLOWED,
                        "Method " + method + " is not allowed for " + path);
            }
            throw new ApiException(HTTP_NOT_FOUND, "Route not found: " + path);
        }

        logger.info("ROUTING {} → {}", key, controller.getClass().getSimpleName());
        return controller.handle(request, context);
    }

    private String normalizePath(final String rawPath) {
        if (rawPath == null || rawPath.isBlank()) return "/";
        return rawPath.endsWith("/") && rawPath.length() > 1
                ? rawPath.substring(0, rawPath.length() - 1)
                : rawPath;
    }
}

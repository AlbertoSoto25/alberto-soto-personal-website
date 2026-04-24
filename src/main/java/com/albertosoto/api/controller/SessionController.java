package com.albertosoto.api.controller;

import com.albertosoto.api.model.request.SessionRequest;
import com.albertosoto.api.model.response.SessionResponse;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;

import java.util.Map;

/**
 * Handles POST /session requests.
 */
@RequiredArgsConstructor
public class SessionController implements Controller {

    private static final int HTTP_CREATED = 201;

    private final ObjectMapper objectMapper;

    @Override
    public APIGatewayV2HTTPResponse handle(
            final APIGatewayV2HTTPEvent request,
            final Context context) {

        // TODO: parse request body, invoke business logic, build response
        final SessionRequest sessionRequest   = parseRequest(request.getBody());
        final SessionResponse sessionResponse = processSession(sessionRequest, context);

        return buildResponse(HTTP_CREATED, sessionResponse);
    }

    // -------------------------------------------------------------------------
    // Private helpers — implement business logic below
    // -------------------------------------------------------------------------

    private SessionRequest parseRequest(final String body) {
        try {
            return objectMapper.readValue(body, SessionRequest.class);
        } catch (Exception e) {
            throw new com.albertosoto.api.exception.ApiException(400, "Invalid request body");
        }
    }

    private SessionResponse processSession(final SessionRequest request, final Context context) {
        // TODO: implement
        throw new UnsupportedOperationException("SessionController.processSession() not yet implemented");
    }

    private APIGatewayV2HTTPResponse buildResponse(final int statusCode, final Object body) {
        try {
            return APIGatewayV2HTTPResponse.builder()
                    .withStatusCode(statusCode)
                    .withHeaders(Map.of("Content-Type", "application/json"))
                    .withBody(objectMapper.writeValueAsString(body))
                    .build();
        } catch (Exception e) {
            throw new com.albertosoto.api.exception.ApiException(500, "Failed to serialize response");
        }
    }
}

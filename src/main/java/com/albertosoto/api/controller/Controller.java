package com.albertosoto.api.controller;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;

/**
 * Contract that every endpoint controller must fulfill.
 */
public interface Controller {

    /**
     * Handles the incoming API Gateway HTTP v2 request and returns an HTTP response.
     *
     * @param request the raw API Gateway v2 HTTP event
     * @param context the Lambda runtime context
     * @return an {@link APIGatewayV2HTTPResponse} with status code and body
     */
    APIGatewayV2HTTPResponse handle(APIGatewayV2HTTPEvent request, Context context);
}

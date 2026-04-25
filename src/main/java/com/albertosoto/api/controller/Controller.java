package com.albertosoto.api.controller;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;

/**
 * Contract that every endpoint controller must fulfill.
 */
public interface Controller {

    APIGatewayProxyResponseEvent handle(APIGatewayProxyRequestEvent request, Context context);
}

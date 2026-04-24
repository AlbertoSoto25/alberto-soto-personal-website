package com.albertosoto.api.model.response;

import lombok.Builder;
import lombok.Getter;

/**
 * Outgoing payload for POST /ask.
 * Add fields as needed.
 */
@Getter
@Builder
public class AskResponse {

    String response;
    // TODO: add fields, e.g.:
    // private String answer;
    // private String sessionId;
}

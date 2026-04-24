package com.albertosoto.api.model.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Incoming payload for POST /session.
 * Add fields as needed.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionRequest {

    // TODO: add fields, e.g.:
    // private String userId;
    // private Map<String, String> metadata;
}

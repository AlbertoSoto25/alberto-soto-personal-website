package com.albertosoto.api.config;

import com.albertosoto.api.exception.ApiException;
import lombok.Getter;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.util.Optional;

/**
 * Reads Bedrock configuration from environment variables and owns the {@link BedrockRuntimeClient}
 * singleton for the lifetime of the Lambda instance.
 *
 * <p>Required environment variables:
 * <ul>
 *   <li>{@code BEDROCK_MODEL_ID} — model ID or cross-region inference ARN</li>
 *   <li>{@code BEDROCK_MAX_TOKENS} — positive integer</li>
 *   <li>{@code BEDROCK_SYSTEM_PROMPT_KEY} — key into {@link com.albertosoto.api.prompt.PromptLibrary}</li>
 * </ul>
 *
 * <p>Optional environment variables:
 * <ul>
 *   <li>{@code BEDROCK_TEMPERATURE} — float in [0, 1]; omit to use the model's default</li>
 * </ul>
 */
@Getter
public final class BedrockClientConfig {

  private static final String ENV_MODEL_ID = "BEDROCK_MODEL_ID";
  private static final String ENV_MAX_TOKENS = "BEDROCK_MAX_TOKENS";
  private static final String ENV_TEMPERATURE = "BEDROCK_TEMPERATURE";
  private static final String ENV_SYSTEM_PROMPT_KEY = "BEDROCK_SYSTEM_PROMPT_KEY";
  private static final String ENV_GUARDRAIL_ID = "BEDROCK_GUARDRAIL_ID";
  private static final String ENV_GUARDRAIL_VERSION = "BEDROCK_GUARDRAIL_VERSION";


  private final String modelId;
  private final int maxTokens;
  private final Optional<Float> temperature;
  private final String systemPromptKey;
  private final String guardrailId;
  private final String guardrailVersion;
  private final BedrockRuntimeClient client;


  public BedrockClientConfig() {
    this.modelId = requireEnv(ENV_MODEL_ID);
    this.maxTokens = parsePositiveInt(ENV_MAX_TOKENS, requireEnv(ENV_MAX_TOKENS));
    this.temperature = parseOptionalFloat(ENV_TEMPERATURE, System.getenv(ENV_TEMPERATURE));
    this.systemPromptKey = requireEnv(ENV_SYSTEM_PROMPT_KEY);
    this.guardrailId = requireEnv(ENV_GUARDRAIL_ID);
    this.guardrailVersion = parseGuardrailVersion(System.getenv(ENV_GUARDRAIL_VERSION));
    this.client = buildClient();
  }


  private static BedrockRuntimeClient buildClient() {
    return BedrockRuntimeClient.builder()
        .build();
  }

  private static String requireEnv(String name) {
    String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new ApiException(500, "Missing required environment variable: " + name);
    }
    return value;
  }

  private static int parsePositiveInt(String envName, String raw) {
    try {
      int value = Integer.parseInt(raw);
      if (value <= 0) {
        throw new ApiException(500, ENV_MAX_TOKENS + " must be a positive integer, got: " + raw);
      }
      return value;
    } catch (NumberFormatException e) {
      throw new ApiException(500, "Invalid integer for " + envName + ": " + raw);
    }
  }

  private static Optional<Float> parseOptionalFloat(String envName, String raw) {
    if (raw == null || raw.isBlank()) {
      return Optional.empty();
    }
    try {
      return Optional.of(Float.parseFloat(raw));
    } catch (NumberFormatException e) {
      throw new ApiException(500, "Invalid float for " + envName + ": " + raw);
    }
  }

  private static String parseGuardrailVersion(String raw) {
    return (raw == null || raw.isBlank()) ? "DRAFT" : raw.trim();
  }
}

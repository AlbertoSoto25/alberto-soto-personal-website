package com.albertosoto.api.service;

import com.albertosoto.api.config.BedrockClientConfig;
import com.albertosoto.api.exception.ApiException;
import com.albertosoto.api.model.request.AskRequest;
import com.albertosoto.api.model.response.AskResponse;
import com.albertosoto.api.prompt.PromptLibrary;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;

import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Orchestrates a single-turn conversation with AWS Bedrock via the Converse API.
 *
 * <p>Conversation structure (in order):
 * <ol>
 *   <li>System prompt — resolved from {@link PromptLibrary} by {@code BEDROCK_SYSTEM_PROMPT_KEY}</li>
 *   <li>Seed turns (optional) — USER/ASSISTANT pairs from {@link PromptLibrary} that prime the model</li>
 *   <li>User message — the question from the incoming {@link AskRequest}</li>
 * </ol>
 */
@RequiredArgsConstructor
public class AskService {

  private static final Logger logger = LogManager.getLogger(AskService.class);

  private final BedrockClientConfig config;


  /**
   * Sends the question to Bedrock and returns the model's answer.
   *
   * @param request incoming request containing the user's question
   * @return response DTO with the model's reply
   * @throws ApiException on validation failures or Bedrock errors
   */
  public AskResponse ask(AskRequest request) {
    PromptLibrary prompts = resolvePrompts(config.getSystemPromptKey());
    logger.info("BEDROCK prompt_key={} model={} max_tokens={}", config.getSystemPromptKey(), config.getModelId(), config.getMaxTokens());

    logger.info("BEDROCK guardrail_id={} guardrail_version={}", config.getGuardrailId(), config.getGuardrailVersion());
    ConverseRequest converseRequest = ConverseRequest.builder()
        .modelId(config.getModelId())
        .system(buildSystemBlocks(prompts))
        .messages(buildMessages(prompts, request))
        .inferenceConfig(buildInferenceConfig())
        .guardrailConfig(GuardrailConfiguration.builder()
            .guardrailIdentifier(config.getGuardrailId())
            .guardrailVersion(config.getGuardrailVersion())
            .build())
        .build();

    logger.info("BEDROCK invoking...");
    ConverseResponse converseResponse = invoke(converseRequest);
    logger.info("BEDROCK responded stop_reason={}", converseResponse.stopReasonAsString());
    return mapResponse(converseResponse);
  }

  private PromptLibrary resolvePrompts(String key) {
    return PromptLibrary.findByKey(key)
        .orElseThrow(() -> new ApiException(500, "Unknown system prompt key: " + key));
  }

  private List<SystemContentBlock> buildSystemBlocks(PromptLibrary prompts) {
    return List.of(
        SystemContentBlock.builder()
            .text(prompts.getSystemPrompt())
            .build());
  }

  private List<Message> buildMessages(PromptLibrary prompts, AskRequest request) {
    List<Message> messages = new ArrayList<>();
    addSeedTurns(messages, prompts.getSeedTurns());
    messages.add(textMessage(ConversationRole.USER, request.getQuestion()));
    return messages;
  }

  private void addSeedTurns(List<Message> messages, List<PromptLibrary.SeedTurn> seedTurns) {
    for (PromptLibrary.SeedTurn turn : seedTurns) {
      messages.add(textMessage(ConversationRole.USER, turn.userContent()));
      messages.add(textMessage(ConversationRole.ASSISTANT, turn.assistantContent()));
    }
  }

  private Message textMessage(ConversationRole role, String text) {
    return Message.builder()
        .role(role)
        .content(ContentBlock.fromText(text))
        .build();
  }

  private InferenceConfiguration buildInferenceConfig() {
    InferenceConfiguration.Builder builder = InferenceConfiguration.builder()
        .maxTokens(config.getMaxTokens());

    config.getTemperature().ifPresent(builder::temperature);

    return builder.build();
  }

  private ConverseResponse invoke(ConverseRequest request) {
    try {
      return config.getClient().converse(request);
    } catch (SdkException e) {
      throw new ApiException(502, "Bedrock invocation failed: " + e.getMessage(), e);
    }
  }

  private AskResponse mapResponse(ConverseResponse response) {
    String text = Optional.ofNullable(response.output())
        .map(output -> output.message())
        .map(Message::content)
        .orElse(List.of())
        .stream()
        .filter(block -> block.text() != null)
        .map(ContentBlock::text)
        .findFirst()
        .orElseThrow(() -> new ApiException(502, "Bedrock response contained no text content"));

    return AskResponse.builder()
        .response(text)
        .build();
  }
}

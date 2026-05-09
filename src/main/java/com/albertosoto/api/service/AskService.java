package com.albertosoto.api.service;

import com.albertosoto.api.config.BedrockClientConfig;
import com.albertosoto.api.exception.ApiException;
import com.albertosoto.api.model.request.AskRequest;
import com.albertosoto.api.model.response.AskResponse;
import com.albertosoto.api.prompt.PromptLibrary;
import lombok.RequiredArgsConstructor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamResponseHandler;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.GuardrailStreamConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RequiredArgsConstructor
public class AskService {

  private static final Logger logger = LogManager.getLogger(AskService.class);

  private final BedrockClientConfig config;

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

  public void askStream(AskRequest request, OutputStream out) {
    PromptLibrary prompts = resolvePrompts(config.getSystemPromptKey());
    logger.info("BEDROCK_STREAM model={} max_tokens={}", config.getModelId(), config.getMaxTokens());

    ConverseStreamRequest converseRequest = ConverseStreamRequest.builder()
        .modelId(config.getModelId())
        .system(buildSystemBlocks(prompts))
        .messages(buildMessages(prompts, request))
        .inferenceConfig(buildInferenceConfig())
        .guardrailConfig(GuardrailStreamConfiguration.builder()
            .guardrailIdentifier(config.getGuardrailId())
            .guardrailVersion(config.getGuardrailVersion())
            .build())
        .build();

    LinkedBlockingQueue<Optional<String>> queue = new LinkedBlockingQueue<>();
    AtomicReference<Throwable> streamError = new AtomicReference<>();

    ConverseStreamResponseHandler responseHandler = new ConverseStreamResponseHandler() {
      @Override
      public void responseReceived(ConverseStreamResponse response) {}

      @Override
      public void onEventStream(SdkPublisher<ConverseStreamOutput> publisher) {
        publisher.subscribe(event -> event.accept(
            ConverseStreamResponseHandler.Visitor.builder()
                .onContentBlockDelta(e -> {
                  String text = e.delta().text();
                  if (text != null) {
                    logger.info("BEDROCK_STREAM chunk length={} content=\"{}\"", text.length(), text);
                    queue.offer(Optional.of(text));
                  }
                })
                .build()
        ));
      }

      @Override
      public void exceptionOccurred(Throwable throwable) {
        streamError.compareAndSet(null, throwable);
        queue.offer(Optional.empty());
      }

      @Override
      public void complete() {
        queue.offer(Optional.empty());
      }
    };

    config.getAsyncClient().converseStream(converseRequest, responseHandler);

    // Main thread drains the queue and writes to the OutputStream,
    // so each chunk is flushed to the network from the handler thread.
    try {
      while (true) {
        Optional<String> item = queue.poll(60, TimeUnit.SECONDS);
        if (item == null) {
          throw new ApiException(502, "Bedrock stream timed out");
        }
        if (item.isEmpty()) break;
        try {
          out.write(item.get().getBytes(StandardCharsets.UTF_8));
          out.flush();
        } catch (IOException ioEx) {
          logger.error("BEDROCK_STREAM write_error: {}", ioEx.getMessage());
          break;
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ApiException(500, "Bedrock streaming interrupted");
    }

    Throwable t = streamError.get();
    if (t != null) {
      throw new ApiException(502, "Bedrock streaming failed: " + t.getMessage());
    }

    logger.info("BEDROCK_STREAM complete");
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

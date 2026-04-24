package com.albertosoto.api.prompt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public enum PromptLibrary {

  DEFAULT("default", null),
  ALBERTO_ASSISTANT("alberto_assistant", "prompts/alberto_assistant.json");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final String key;
  private final String systemPrompt;
  private final List<SeedTurn> seedTurns;

  PromptLibrary(String key, String resourcePath) {
    this.key = key;
    if (resourcePath == null) {
      this.systemPrompt = "";
      this.seedTurns = List.of();
    } else {
      PromptFile file = loadResource(resourcePath);
      this.systemPrompt = file.systemPrompt();
      this.seedTurns = file.seedTurns() != null ? file.seedTurns() : List.of();
    }
  }

  private static PromptFile loadResource(String path) {
    try (InputStream is = PromptLibrary.class.getClassLoader().getResourceAsStream(path)) {
      if (is == null) {
        throw new IllegalStateException("Prompt file not found on classpath: " + path);
      }
      return MAPPER.readValue(is, PromptFile.class);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to load prompt file: " + path, e);
    }
  }

  public static Optional<PromptLibrary> findByKey(String key) {
    return Arrays.stream(values())
        .filter(p -> p.key.equalsIgnoreCase(key))
        .findFirst();
  }

  public String getSystemPrompt() {
    return systemPrompt;
  }

  public List<SeedTurn> getSeedTurns() {
    return seedTurns;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record PromptFile(String systemPrompt, List<SeedTurn> seedTurns) {}

  public record SeedTurn(String userContent, String assistantContent) {}
}

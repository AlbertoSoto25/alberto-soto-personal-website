# Personal Website API

A serverless Java backend powering the AI assistant on [albertosotogonzalez.com](https://albertosotogonzalez.com). It runs as an AWS Lambda function behind API Gateway, proxies questions to Amazon Bedrock using the Converse API, enforces per-user rate limiting via DynamoDB, and streams responses token-by-token back to the browser.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 17 |
| Build | Maven + `maven-shade-plugin` (fat JAR) |
| Serverless | AWS Lambda (`RequestStreamHandler`) |
| API layer | Amazon API Gateway (REST, streaming integration) |
| LLM | Amazon Bedrock — Converse API |
| Rate limiting | Amazon DynamoDB |
| AWS SDK | AWS SDK for Java v2 (`2.42.x`) |
| Serialization | Jackson `2.16.x` |
| Logging | Log4j 2 |
| Boilerplate reduction | Lombok |
| Testing | JUnit 5 |

---

## Environment Variables

All configuration is injected via Lambda environment variables. The function will refuse to start if any required variable is absent.

### Amazon Bedrock

| Variable | Required | Description |
|---|---|---|
| `BEDROCK_MODEL_ID` | **Yes** | Model ID or cross-region inference ARN (e.g. `anthropic.claude-3-5-sonnet-20241022-v2:0`) |
| `BEDROCK_MAX_TOKENS` | **Yes** | Maximum tokens for the model response (positive integer) |
| `BEDROCK_SYSTEM_PROMPT_KEY` | **Yes** | Key resolving to a `PromptLibrary` entry (e.g. `alberto_assistant`) |
| `BEDROCK_GUARDRAIL_ID` | **Yes** | Bedrock Guardrail identifier |
| `BEDROCK_GUARDRAIL_VERSION` | No | Guardrail version; defaults to `DRAFT` |
| `BEDROCK_TEMPERATURE` | No | Sampling temperature float in `[0, 1]`; omit to use model default |

### Amazon DynamoDB — Rate Limiting

| Variable | Required | Description |
|---|---|---|
| `RATE_LIMIT_DAILY_MAX` | **Yes** | Maximum requests allowed per `ip:visitorId` key per 24-hour window |

## Lambda Configuration

| Setting | Recommended value |
|---|---|
| Handler | `com.albertosoto.api.handler.ApiGatewayHandler` |
| Runtime | `java17` |
| Architecture | `arm64` (Graviton2 — lower cost, same or better throughput) |
| Memory | `512 MB` (increase if cold-start latency matters) |
| Timeout | `30 s` (Bedrock streams can take several seconds) |
| Invoke mode | `RESPONSE_STREAM` (required for streaming) |

## Prompt Library

System prompts live in `src/main/resources/prompts/` as JSON files and are loaded at class-initialization time (i.e., during the Lambda cold start, not per-request).

```json
{
  "systemPrompt": "You are Alberto's personal assistant...",
  "seedTurns": [
    { "userContent": "...", "assistantContent": "..." }
  ]
}
```

Set `BEDROCK_SYSTEM_PROMPT_KEY` to one of the keys registered in `PromptLibrary` (e.g. `alberto_assistant`).

---

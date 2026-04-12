# Responses API

This page covers the modern Responses surface exposed by [OpenAIApi][one.wabbit.web.openai.OpenAIApi].

Main entry points:

- [createResponse][one.wabbit.web.openai.OpenAIApi.createResponse]
- [streamResponse][one.wabbit.web.openai.OpenAIApi.streamResponse]
- [getResponse][one.wabbit.web.openai.OpenAIApi.getResponse]
- [listResponses][one.wabbit.web.openai.OpenAIApi.listResponses]
- [deleteResponse][one.wabbit.web.openai.OpenAIApi.deleteResponse]
- [compactResponse][one.wabbit.web.openai.OpenAIApi.compactResponse]
- [cancelResponse][one.wabbit.web.openai.OpenAIApi.cancelResponse]
- [listResponseInputItems][one.wabbit.web.openai.OpenAIApi.listResponseInputItems]
- [countResponseInputTokens][one.wabbit.web.openai.OpenAIApi.countResponseInputTokens]

## Creating a Response

The main request type is [ResponseCreateRequest][one.wabbit.web.openai.ResponseCreateRequest].

Key fields:

- `model`
- `input`
- `instructions`
- `tools` and `toolChoice`
- `reasoning`, `text`, and `truncation`
- `promptCacheKey`, `promptCacheRetention`, and `safetyIdentifier`
- provider-specific `providerOptions`

Example:

```kotlin
import one.wabbit.web.openai.ModelId
import one.wabbit.web.openai.OpenAIApi
import one.wabbit.web.openai.ResponseCreateRequest
import one.wabbit.web.openai.ResponseInput

suspend fun answer(api: OpenAIApi) =
    api.createResponse(
        ResponseCreateRequest(
            model = ModelId("gpt-5.4-mini"),
            input = ResponseInput.Text("Reply in one short sentence."),
        ),
    )
```

## Structured Output Helpers

The response object type is [ResponseObject][one.wabbit.web.openai.ResponseObject].

Use:

- `outputTexts()` when multiple assistant message items are possible
- `outputText()` only when you know the response has a single merged text output
- `outputJsonElementOrNull()` only when the singleton precondition is actually satisfied

The merged helpers are intentionally singleton-only now. They do not silently concatenate unrelated outputs.

## Streaming

Streaming returns `Flow<ResponseStreamEvent>`.

The usual pattern is:

```kotlin
import one.wabbit.web.openai.collectResponseStream

suspend fun answerStreaming(api: OpenAIApi): String {
    val assembly =
        api.streamResponse(
            ResponseCreateRequest(
                model = ModelId("gpt-5.4-mini"),
                input = ResponseInput.Text("Summarize this."),
            ),
        ).collectResponseStream()

    return assembly.outputText
}
```

The response stream accumulator preserves semantic ordering instead of relying on arrival order.

## Retrieval, Compaction, and Input Items

The retrieval and lifecycle surface is:

- [ResponseRetrieveQuery][one.wabbit.web.openai.ResponseRetrieveQuery]
- [ResponseCompactRequest][one.wabbit.web.openai.ResponseCompactRequest]
- [ResponseInputItemListQuery][one.wabbit.web.openai.ResponseInputItemListQuery]
- [ResponseInputTokensRequest][one.wabbit.web.openai.ResponseInputTokensRequest]

This lets callers:

- retrieve stored responses
- request streamed retrieval with `stream = true`
- compact responses
- inspect input items
- estimate token cost before sending a full response create

## Tools

Responses expose the richer hosted tool surface through [ResponseTool][one.wabbit.web.openai.ResponseTool] and [ResponseToolChoice][one.wabbit.web.openai.ResponseToolChoice].

Important behavior:

- provider gates reject typed built-in tools when the provider advertises `builtinTools = false`
- chat-only tool shapes such as `allowed_tools` are rejected on the Responses surface
- `extraBody` is additive only and cannot override validated request keys

## Practical Rules

- Prefer Responses over Chat Completions when you want the more modern OpenAI surface.
- Use structured helpers when multiple outputs are possible.
- Treat raw tool and raw JSON seams as forward-compatibility escapes, not the default path.

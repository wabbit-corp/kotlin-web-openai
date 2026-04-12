# User Guide

This guide covers the main user-facing programming model of `kotlin-web-openai`.

For high-level module context, start with [dokka-module.md](./dokka-module.md). For provider-specific and testing behavior, also see:

- [Responses API](./responses.md)
- [Chat Completions](./chat-completions.md)
- [Media And Audio](./media-and-audio.md)
- [Files, Uploads, And Vector Stores](./files-and-vector-stores.md)
- [Workflows And Operations](./workflows-and-operations.md)
- [Provider Compatibility](./provider-compatibility.md)
- [Provider-Specific Surfaces](./provider-specific-surfaces.md)
- [Testing And Live Smoke](./testing.md)

## Setup

The client does not create its own transport. You supply an `HttpClient` and an [OpenAIApi.Config][one.wabbit.web.openai.OpenAIApi.Config]:

```kotlin
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import one.wabbit.web.openai.KtorOpenAIApi
import one.wabbit.web.openai.OpenAIApi

val api =
    KtorOpenAIApi(
        client = HttpClient(CIO),
        config =
            OpenAIApi.Config(
                apiKey = requireNotNull(System.getenv("OPENAI_API_KEY")),
            ),
    )
```

That design keeps engine choice, TLS policy, proxying, logging, and external observability in caller control.

## Choosing Between Responses and Chat Completions

There are two main text-generation entry points:

- [OpenAIApi.createResponse][one.wabbit.web.openai.OpenAIApi.createResponse] for the Responses API
- [OpenAIApi.createChatCompletion][one.wabbit.web.openai.OpenAIApi.createChatCompletion] for Chat Completions

Use Responses first when you want the more modern OpenAI surface, especially if you want:

- richer response item modeling
- response retrieval, compaction, and input-item listing
- the modern hosted tool families

Use Chat Completions when you specifically need chat-completions semantics or provider compatibility that is centered on the chat surface.

The typed models keep those surfaces separate where the wire format differs, especially around tools and tool choices.

## Responses

The normal synchronous flow is:

```kotlin
import one.wabbit.web.openai.ModelId
import one.wabbit.web.openai.ResponseCreateRequest
import one.wabbit.web.openai.ResponseInput

suspend fun ask(api: OpenAIApi): String {
    val response =
        api.createResponse(
            ResponseCreateRequest(
                model = ModelId("gpt-5.4-mini"),
                input = ResponseInput.Text("Give a one-sentence answer."),
            ),
        )

    return response.outputText()
}
```

If the response may produce multiple assistant message items, use structured helpers such as `outputTexts()` instead of the singleton-only merged helpers.

## Streaming Responses

Responses streaming exposes a `Flow<ResponseStreamEvent>`:

```kotlin
import one.wabbit.web.openai.collectResponseStream

suspend fun askStreaming(api: OpenAIApi): String {
    val assembly =
        api.streamResponse(
            ResponseCreateRequest(
                model = ModelId("gpt-5.4-mini"),
                input = ResponseInput.Text("Summarize this in one paragraph."),
            ),
        ).collectResponseStream()

    return assembly.outputText
}
```

The stream accumulator preserves structured output ordering. Convenience helpers no longer silently merge multiple unrelated outputs.

## Chat Completions

Chat Completions use [ChatCompletionRequest][one.wabbit.web.openai.ChatCompletionRequest]:

```kotlin
import one.wabbit.web.openai.ChatCompletionRequest
import one.wabbit.web.openai.ChatMessage
import one.wabbit.web.openai.ChatRole
import one.wabbit.web.openai.ModelId

suspend fun askChat(api: OpenAIApi) =
    api.createChatCompletion(
        ChatCompletionRequest(
            model = ModelId("gpt-5.4-mini"),
            messages =
                listOf(
                    ChatMessage(
                        role = ChatRole.USER,
                        content = "Reply with one short sentence.",
                    ),
                ),
        ),
    )
```

Chat streaming is also exposed as a `Flow`, via [OpenAIApi.streamChatCompletion][one.wabbit.web.openai.OpenAIApi.streamChatCompletion].

## Binary Uploads and Downloads

Multipart APIs accept either eager or streaming uploads:

- [BinaryUpload][one.wabbit.web.openai.BinaryUpload] for `ByteArray` payloads
- [StreamingBinaryUpload][one.wabbit.web.openai.StreamingBinaryUpload] for channel-backed uploads

For downloads:

- eager methods return `ByteArray`
- streaming methods such as `downloadFileTo(...)` and `downloadVideoContentTo(...)` emit chunks incrementally

[OpenAIApi.Config.maxEagerBinaryBytes][one.wabbit.web.openai.OpenAIApi.Config.maxEagerBinaryBytes] limits eager binary responses so accidental large downloads fail early instead of exhausting memory.

## Audio

The audio surface includes:

- speech generation
- transcription
- transcription streaming
- translation
- voice creation and voice-consent management

Speech and transcription requests expose streaming APIs where the OpenAI surface is stream-capable. For transcription, use the streaming entry points when `stream = true` is the intended contract instead of forcing the non-streaming helper.

## Realtime

This module currently focuses on the REST-facing Realtime surface:

- client-secret bootstrap
- legacy bootstrap where still needed
- call control endpoints

It does not present websocket session transport as the main public abstraction.

## Error Handling

Typed failures are surfaced through [OpenAIApiError][one.wabbit.web.openai.OpenAIApiError] variants.

Important behavior:

- exception messages do not include body samples by default
- `bodySample` is still available on the exception object for programmatic inspection
- enabling `includeBodySamplesInExceptions` is an explicit debugging choice

## Extension Seams

Forward-compatibility seams exist, but they are intentionally constrained:

- `extraBody` is additive only
- multipart `extraFields` are additive only
- reserved top-level JSON keys and multipart field names cannot be overridden

That policy prevents the escape hatches from bypassing the validated typed surface.

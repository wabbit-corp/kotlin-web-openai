# Module kotlin-web-openai

`kotlin-web-openai` is an OpenAI-first Kotlin Multiplatform client for OpenAI's REST and streaming APIs.

It wraps the API behind typed request and response models built on Ktor and `kotlinx.serialization`, while keeping provider-specific escape hatches explicit instead of silently weakening the main API contract.

OpenAI-native behavior is the primary target. Compatibility-provider paths such as Azure OpenAI, OpenRouter, Ollama, Groq, xAI, DeepSeek, Anthropic compatibility, and Gemini compatibility are guarded and best-effort rather than the main wire contract.

## What It Supports

- Responses API and SSE response streaming
- Chat Completions and SSE chat streaming
- embeddings, moderations, models, images
- speech, transcription, translation, voice, and voice consent APIs
- files, uploads, vector stores, and vector-store file content
- conversations, evals, videos
- Realtime bootstrap and Realtime call-control endpoints

The library is Ktor-based and takes a caller-supplied `HttpClient`, so transport policy stays under application control.

## Installation

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("one.wabbit:kotlin-web-openai:0.0.1")
}
```

Published targets currently include JVM, Android, iOS Arm64, iOS Simulator Arm64, and macOS Arm64.

For deeper topic-by-topic coverage, see:

- [User Guide](./user-guide.md)
- [Responses API](./responses.md)
- [Chat Completions](./chat-completions.md)
- [Media And Audio](./media-and-audio.md)
- [Files, Uploads, And Vector Stores](./files-and-vector-stores.md)
- [Workflows And Operations](./workflows-and-operations.md)
- [Provider Compatibility](./provider-compatibility.md)
- [Provider-Specific Surfaces](./provider-specific-surfaces.md)
- [Testing And Live Smoke](./testing.md)

## Entry Point

The main client implementation is [KtorOpenAIApi][one.wabbit.web.openai.KtorOpenAIApi], which implements [OpenAIApi][one.wabbit.web.openai.OpenAIApi].

You provide:

- a configured `HttpClient`
- an [OpenAIApi.Config][one.wabbit.web.openai.OpenAIApi.Config]
- an API key or provider-specific auth strategy

## Quick Start

```kotlin
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import one.wabbit.web.openai.KtorOpenAIApi
import one.wabbit.web.openai.ModelId
import one.wabbit.web.openai.OpenAIApi
import one.wabbit.web.openai.ResponseCreateRequest
import one.wabbit.web.openai.ResponseInput

suspend fun main() {
    val api =
        KtorOpenAIApi(
            client = HttpClient(CIO),
            config =
                OpenAIApi.Config(
                    apiKey = requireNotNull(System.getenv("OPENAI_API_KEY")),
                ),
        )

    val response =
        api.createResponse(
            ResponseCreateRequest(
                model = ModelId("gpt-5.4-mini"),
                input = ResponseInput.Text("Say hello in one short sentence."),
            ),
        )

    println(response.outputText())
}
```

## Streaming a Response

```kotlin
import one.wabbit.web.openai.ModelId
import one.wabbit.web.openai.OpenAIApi
import one.wabbit.web.openai.ResponseCreateRequest
import one.wabbit.web.openai.ResponseInput
import one.wabbit.web.openai.collectResponseStream

suspend fun summarize(api: OpenAIApi, prompt: String): String {
    val assembly =
        api.streamResponse(
            ResponseCreateRequest(
                model = ModelId("gpt-5.4-mini"),
                input = ResponseInput.Text(prompt),
            ),
        ).collectResponseStream()

    return assembly.outputText
}
```

If a response produces more than one assistant output item, prefer the structured helpers such as `outputTexts()` or the assembled output lists rather than the singleton-only merged helpers.

## Chat Completions

Chat Completions are exposed separately from Responses:

```kotlin
import one.wabbit.web.openai.ChatCompletionRequest
import one.wabbit.web.openai.ChatMessage
import one.wabbit.web.openai.ChatRole
import one.wabbit.web.openai.ModelId

suspend fun complete(api: OpenAIApi) =
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

Responses and Chat Completions intentionally use different tool and tool-choice shapes where the wire contract differs. The typed models reject cross-surface combinations that would serialize incorrectly.

## Audio, Uploads, and Binary Data

For multipart endpoints, the library supports both eager `ByteArray` uploads and channel-backed streaming uploads through [StreamingBinaryUpload][one.wabbit.web.openai.StreamingBinaryUpload].

For binary downloads:

- eager helpers return `ByteArray`
- bounded eager downloads obey `maxEagerBinaryBytes`
- streaming download variants such as `downloadFileTo(...)` and `downloadVideoContentTo(...)` let callers handle large payloads incrementally

Speech and transcription also support streaming flows where the API surface is streaming-capable.

## Transport and Error Handling

The client does not create its own `HttpClient`; callers provide one so they can control:

- engines
- proxies
- TLS policy
- observability
- retries outside the client

[OpenAIApi.Config][one.wabbit.web.openai.OpenAIApi.Config] controls API-specific behavior such as:

- provider and base URL selection
- retry policy and idempotency rules
- default and streaming timeouts
- response metadata callbacks
- exception body-sample visibility
- eager binary download limits

By default, exception messages do not include body samples. If debugging requires payload snippets, opt in explicitly with `includeBodySamplesInExceptions = true`.

## Compatibility and Escape Hatches

This library is OpenAI-first. Compatibility-provider support exists, but provider gates are explicit and may reject features that are not supported cleanly by a given provider.

Extension seams exist for forward compatibility:

- JSON request builders support additive `extraBody`
- some multipart APIs support additive `extraFields`
- raw tool variants exist where the platform surface moves faster than the typed layer

Those seams are additive only. They intentionally do not override validated top-level fields or reserved multipart part names.

## API Notes

- Streaming helpers preserve structured output; singleton convenience helpers now enforce singleton assumptions instead of silently merging unrelated outputs.
- Unknown enum or tagged-union values are preserved with `Unknown(...)` wrappers where the API is expected to evolve.
- Live smoke tests exist for higher-risk or internally inconsistent areas such as eval cancel behavior, chat audio, and the stable video lifecycle path.
- Realtime support currently covers bootstrap plus call control; websocket session-event transport is not the main abstraction in this module.

## Additional Docs

- [User Guide](./user-guide.md)
- [Responses API](./responses.md)
- [Chat Completions](./chat-completions.md)
- [Media And Audio](./media-and-audio.md)
- [Files, Uploads, And Vector Stores](./files-and-vector-stores.md)
- [Workflows And Operations](./workflows-and-operations.md)
- [Provider Compatibility](./provider-compatibility.md)
- [Provider-Specific Surfaces](./provider-specific-surfaces.md)
- [Testing And Live Smoke](./testing.md)

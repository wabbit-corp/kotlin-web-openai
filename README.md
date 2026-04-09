# kotlin-web-openai

`kotlin-web-openai` is an OpenAI-first Kotlin Multiplatform client for OpenAI's REST and streaming APIs.

It wraps the current OpenAI platform surface behind typed request and response models built on Ktor and `kotlinx.serialization`, while keeping provider-specific escape hatches explicit instead of silently weakening the main API.

OpenAI parity is the priority. The library also includes guarded compatibility support for sufficiently OpenAI-like providers such as OpenRouter, Ollama, Azure OpenAI, Groq, xAI, DeepSeek, Anthropic compatibility, and Gemini compatibility, but those paths are best-effort rather than the primary contract.

## Installation

Current release:

```kotlin
dependencies {
    implementation("one.wabbit:kotlin-web-openai:0.0.1")
}
```

The client is Ktor-based and takes an `HttpClient`, so you supply transport configuration appropriate for your target.

## Platform and compatibility

Current project floors and targets:

- Kotlin `2.3.10`
- JDK `21` to build the project
- Android `minSdk 26`
- Published targets:
  - JVM
  - Android
  - iOS Arm64
  - iOS Simulator Arm64
  - macOS Arm64

Transport shape:

- Ktor `3.3.x`
- `kotlinx.serialization` `1.9.x`
- caller-supplied `HttpClient`

API coverage highlights:

- Responses API and streaming
- Chat Completions and streaming
- Audio: speech, transcription, translation, voice, voice consents
- Images, embeddings, moderations, models
- Files, uploads, vector stores
- Conversations
- Evals
- Videos
- Realtime bootstrap and call control

## Minimal example

The snippet below is mirrored by [`ReadmeExamplesTest.kt`](src/commonTest/kotlin/one/wabbit/web/openai/ReadmeExamplesTest.kt).

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

## Practical example: streaming a response

This snippet is also mirrored by [`ReadmeExamplesTest.kt`](src/commonTest/kotlin/one/wabbit/web/openai/ReadmeExamplesTest.kt).

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

If a stream produces more than one assistant output part, use the structured helpers such as `assembly.outputTexts` instead of the singleton-only `outputText` shortcut.

## Support and maintenance

Support level:

- Open source, maintained on a best-effort basis
- OpenAI-native behavior is the primary compatibility target
- compatibility-provider support is guarded, but not all provider drift is modeled immediately

Public support path:

- bug reports and feature requests:
  - [GitHub issues](https://github.com/wabbit-corp/kotlin-web-openai/issues)
- private or security-sensitive concerns:
  - `wabbit@wabbit.one`

When filing issues, include the provider, model, request shape, and any `x-request-id` or client request ID available from the failing call.

## Additional docs

- [Migration notes](MIGRATION.md)
- [Changelog](CHANGELOG.md)
- [License](LICENSE.md)

## License

AGPL-3.0-or-later. See [LICENSE.md](LICENSE.md).

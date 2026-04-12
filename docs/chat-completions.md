# Chat Completions

This page covers the Chat Completions surface exposed by [OpenAIApi.createChatCompletion][one.wabbit.web.openai.OpenAIApi.createChatCompletion] and [OpenAIApi.streamChatCompletion][one.wabbit.web.openai.OpenAIApi.streamChatCompletion].

Stored-completion lifecycle methods are also part of this surface:

- [getStoredChatCompletion][one.wabbit.web.openai.OpenAIApi.getStoredChatCompletion]
- [listStoredChatCompletions][one.wabbit.web.openai.OpenAIApi.listStoredChatCompletions]
- [listStoredChatCompletionMessages][one.wabbit.web.openai.OpenAIApi.listStoredChatCompletionMessages]
- [updateStoredChatCompletion][one.wabbit.web.openai.OpenAIApi.updateStoredChatCompletion]
- [deleteStoredChatCompletion][one.wabbit.web.openai.OpenAIApi.deleteStoredChatCompletion]

## Creating a Chat Completion

The main request type is [ChatCompletionRequest][one.wabbit.web.openai.ChatCompletionRequest].

The minimum useful shape is:

```kotlin
import one.wabbit.web.openai.ChatCompletionRequest
import one.wabbit.web.openai.ChatMessage
import one.wabbit.web.openai.ChatRole
import one.wabbit.web.openai.ModelId
import one.wabbit.web.openai.OpenAIApi

suspend fun ask(api: OpenAIApi) =
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

## Messages and Roles

Chat input messages use [ChatMessage][one.wabbit.web.openai.ChatMessage] with [ChatRole][one.wabbit.web.openai.ChatRole].

The current typed surface supports the documented modern roles, including `function`, while preserving compatibility behavior needed by older streamed payloads.

## Modalities and Audio

Chat Completions also expose modern audio-related request fields:

- `modalities`
- `audio`
- audio continuation support on message items where the API returns audio IDs

Important invariant:

- audio output config requires `modalities` to include audio
- conversely, audio in `modalities` requires the audio output config

## Tools

Chat Completions intentionally do not reuse the Responses wire shape for tools.

Supported typed chat tool families in this client include:

- function tools
- custom tools
- raw tool escape hatches
- `allowed_tools` tool choice

The client rejects hosted Responses-only tool choices on the chat surface so callers do not accidentally send the wrong envelope.

## Streaming

Chat streaming returns `Flow<ChatCompletionStreamEvent>`.

The stream accumulator:

- preserves semantic ordering across interleaved deltas
- preserves legacy streamed `function_call` argument deltas
- keeps multi-choice outputs separated instead of flattening them into one blob

Use structured helpers on [ChatCompletionResponse][one.wabbit.web.openai.ChatCompletionResponse]:

- `choiceTexts()`
- `choiceReasoningContents()`

Use merged helpers such as `outputText()` only when you know there is exactly one choice.

## Stored Completions

When the API stores chat completions, the client also supports lifecycle operations:

- retrieve by completion ID
- list stored completions
- list stored messages
- update stored completion metadata
- delete stored completions

These methods use the same response model family as creation, with storage-specific page wrappers where needed.

## Practical Rules

- Use Chat Completions when you need the chat-specific contract or provider compatibility centered on that surface.
- Use structured helpers when `n > 1` or when multi-choice output matters.
- Keep chat and Responses tool models separate even if the concepts look similar.

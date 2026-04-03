# Goal

`kotlin-web-openai` is intended to be a first-party KMP client for APIs that are sufficiently OpenAI-like.

## Primary objective

The library must fully support OpenAI's own API.

That means:
- Responses API is the primary abstraction.
- OpenAI streaming is treated as a first-class typed event stream, not a token-only shortcut.
- Structured outputs, tool calls, response retrieval, cancellation, and related response lifecycle endpoints are part of the core model.
- Audio surfaces that matter in practice are in scope:
  - text-to-speech
  - speech-to-text / transcription
  - translation
- File-oriented OpenAI surfaces are in scope:
  - files
  - multipart uploads
  - vector stores and vector-store search

## Compatibility objective

The library should also work well with providers that expose OpenAI-compatible APIs, but compatibility support is secondary to OpenAI-native completeness.

Current target providers:
- OpenAI
- Azure OpenAI compatibility layer
- OpenRouter
- Ollama
- Gemini OpenAI compatibility layer
- Groq compatibility layer
- xAI
- DeepSeek
- Anthropic OpenAI compatibility layer

## Non-goals

This library is not intended to model each provider's native API surface when that surface materially differs from OpenAI's API.

In particular:
- Anthropic native Messages API is out of scope.
- Gemini native API is out of scope.
- Groq-native APIs that materially diverge from the OpenAI compatibility surface are out of scope.
- Other provider-native APIs that are not OpenAI-like are out of scope.

If a provider exposes both:
- a native non-OpenAI API, and
- an OpenAI-compatible API,

then only the OpenAI-compatible API is a target here.

## Deprecated endpoint policy

Deprecated APIs are not implemented just for parity.

The rule is:
- if an API is deprecated and is not actively used by one of this library's supported compatibility providers, it is out of scope
- if an API is deprecated in OpenAI's own surface but is still actively used and not deprecated in a supported compatibility provider, it remains in scope

## Design constraints

- Keep the public design close to the other `kotlin-web-*` clients in this workspace.
- Prefer typed request/response models over raw JSON maps.
- Preserve a clear separation between:
  - OpenAI-native/full support
  - provider-specific compatibility options
- Do not reduce OpenAI-native correctness in order to flatten provider differences.
- When providers diverge materially, represent those differences explicitly in capability flags and provider-specific options.

## Architectural stance

- Responses API remains the primary client model.
- Chat Completions exists as a secondary compatibility layer.
- Provider compatibility should be additive and explicit, not a lowest-common-denominator rewrite.

## Standard for "supported"

For OpenAI itself:
- support should be as complete as practical within this library.

For compatibility providers:
- request/response behavior should be correct for the documented compatible surface,
- unsupported features must fail clearly,
- provider-specific extensions should be modeled explicitly when they are useful and stable enough to justify support.

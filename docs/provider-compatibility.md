# Provider Compatibility

`kotlin-web-openai` is OpenAI-first.

That affects both the typed surface and the validation strategy:

- OpenAI-native wire correctness has priority over compatibility shortcuts
- compatibility-provider support is explicit and guarded
- provider-specific drift is surfaced as validation failures instead of being silently serialized into ambiguous requests

## Supported Compatibility Shape

The client currently exposes guarded compatibility paths for providers such as:

- Azure OpenAI
- OpenRouter
- Ollama
- Groq
- xAI
- DeepSeek
- Anthropic compatibility
- Gemini compatibility

These are not treated as one uniform "OpenAI-compatible" bucket. Each provider has distinct capability flags and request restrictions.

## Capability Gates

Provider support is modeled through [OpenAIProvider][one.wabbit.web.openai.OpenAIProvider] capability tables.

Those gates are used to reject requests before they leave the client when a provider does not support a given surface, for example:

- Responses API
- Chat Completions
- hosted or built-in tools
- structured outputs
- Realtime bootstrap
- vector stores, evals, files, or video APIs

This is intentional. Failing early with a typed error is better than relying on vague server-side drift or partial compatibility.

For the actual API groupings those gates apply to, see:

- [Responses API](./responses.md)
- [Chat Completions](./chat-completions.md)
- [Media And Audio](./media-and-audio.md)
- [Files, Uploads, And Vector Stores](./files-and-vector-stores.md)
- [Workflows And Operations](./workflows-and-operations.md)
- [Provider-Specific Surfaces](./provider-specific-surfaces.md)

## Built-In Tools Versus Local Tools

The client distinguishes between:

- typed built-in or hosted tools that depend on OpenAI-style provider support
- plain function tools or custom chat tools that are more portable

When a provider advertises `builtinTools = false`, the client rejects typed hosted tool usage client-side instead of sending a request that is known to be invalid for that provider.

## Responses and Chat Are Separate Contracts

Responses and Chat Completions do not share the same tool envelope on the wire.

This matters because some providers mimic one surface better than the other. The client therefore keeps their typed request models separate and rejects combinations that would serialize to the wrong shape.

## Escape Hatches

Forward-compatibility seams exist:

- `extraBody`
- multipart `extraFields`
- raw tool variants

But those seams are intentionally limited:

- they are additive only
- they cannot overwrite validated top-level keys
- they cannot reuse reserved multipart field names

If a provider needs a materially different contract, the preferred fix is to model it explicitly rather than route around the type system.

## Azure Notes

Azure support in this client now reflects the current v1-style documented surface closely enough to stop rejecting several valid APIs client-side, including files, vector stores, conversations, evals, fine-tuning, and Realtime bootstrap.

Even so, Azure remains a compatibility surface:

- deployment naming still differs from OpenAI model naming
- rollout timing can differ
- some request restrictions remain provider-specific

## Compatibility Expectations

Treat compatibility providers as "supported with guards", not "guaranteed identical to OpenAI".

When reporting compatibility problems, include:

- provider name
- base URL
- model or deployment name
- request shape
- any request IDs or provider-specific trace headers

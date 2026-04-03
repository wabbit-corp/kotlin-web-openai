# PLAN

Audit date: 2026-03-16

This file tracks two things:
- parity scope for `kotlin-web-openai`
- current engineering follow-ups after the large feature and cleanup passes

## Design rules

- [x] Keep the library OpenAI-first, with compatibility support for sufficiently OpenAI-like providers.
- [x] Full OpenAI support remains the priority over broad-but-shallow compatibility.
- [x] Compatibility targets currently include:
  - OpenRouter
  - Ollama
  - Gemini OpenAI compatibility layer
  - xAI
  - DeepSeek
  - Anthropic compatibility layer
  - Azure OpenAI compatibility layer
  - Groq compatibility layer
- [x] Deprecated-endpoint rule:
  - implement a deprecated API only if it is still active and non-deprecated in at least one supported target provider
  - otherwise keep it out of scope
- [x] Abstraction rule:
  - add a type only if it buys a real invariant, a real domain boundary, fewer illegal states, or less duplicated policy
  - do not add cosmetic wrappers that merely rename `String`, `JsonObject`, `JsonElement`, or similar primitives
- [x] Stringly-typed rule:
  - if a field is a finite documented language, prefer a validating wrapper or a small typed domain
  - keep raw strings only for genuinely open-ended/provider-defined surfaces
- [x] Structural rule:
  - prefer acyclic compilation-unit and definition structure
  - keep provider policy centralized rather than smearing it across endpoint code

## OpenAI parity

### Core platform surfaces
- [x] Responses API
  - create / retrieve / cancel / list / input-items list / streaming
- [x] Responses input-token counting
- [x] Chat Completions
  - create / streaming / stored lifecycle
- [x] Models
- [x] Embeddings
- [x] Images
  - generations / edits / variations / partial-image streaming
- [x] Moderations
- [x] Batches
- [x] Fine-tuning
- [x] Audio
  - speech / transcription / translation
- [x] Files
- [x] Uploads
- [x] Vector stores
- [x] Vector store file batches
- [x] Conversations
- [x] Realtime bootstrap
  - client secret creation / typed session config surface
- [x] Webhook verification helpers
- [x] Evals
  - eval run and output-item list queries now support documented `order` and `status` filters
- [x] Videos

### Responses / streaming completeness
- [x] Typed hosted-tool configs
- [x] Typed hosted-tool output items
- [x] Typed hosted-tool stream events
- [x] Typed image-generation tool support inside Responses
- [x] Typed web / file / code / computer-use tool models where the docs are stable enough
- [x] Richer Responses request/response field coverage
- [x] Request-side MCP tool configuration
- [x] Responses error-envelope coverage

### Chat-completions parity
- [x] `tool_choice = "required"`
- [x] `store`
- [x] `metadata`
- [x] `reasoning_effort`
- [x] `verbosity`
- [x] `web_search_options`
- [x] annotations
- [x] `logprobs` / `top_logprobs`
- [x] richer usage details

## Compatibility-provider parity

### OpenRouter
- [x] embeddings
- [x] model listing
- [x] typed routing/plugin/provider options
- [x] explicit stateless Responses handling

### Ollama
- [x] embeddings
- [x] experimental image generation support
- [x] compatibility gating for stateless Responses and supported fields

### Gemini compatibility layer
- [x] models
- [x] embeddings
- [x] image generation
- [x] batch compatibility
- [x] explicit unsupported upload/download guards
- [x] request shaping under `extra_body.google`

### Anthropic compatibility layer
- [x] typed `thinking` options
- [x] explicit compatibility validation for known unsupported controls
- [x] audio input rejected client-side

### xAI
- [x] typed xAI provider options
- [x] models
- [x] files
- [x] batches
  - list/query/cancel shape aligned with current xAI docs:
    - `pagination_token`
    - `:cancel`
    - typed batch state and batch-request state decoding
- [x] image generation
- [x] voice / TTS
- [x] typed xAI agentic-tool support where it maps cleanly onto this client

### DeepSeek
- [x] typed `thinking` support
- [x] `reasoning_content` handling in responses and stream deltas
- [x] prefix beta support
- [x] FIM beta support

### Azure OpenAI compatibility layer
- [x] first-class Azure provider/config
- [x] deployment-style base paths and API-version handling
- [x] content-filter payload modeling where needed
- [x] narrowed validation away from blanket OpenAI parity in the riskiest paths

### Groq compatibility layer
- [x] first-class Groq provider/config
- [x] compatibility audit and explicit unsupported-feature guards
- [x] stateless Responses handling

### Transport ergonomics
- [x] typed response metadata for request IDs and rate-limit headers
  - `Retry-After` metadata now handles both numeric seconds and RFC-1123 HTTP-date forms via the shared parser in `kotlin-web-common`

## Cleanup work

### Completed cleanup
- [x] Centralize provider policy in shared validation helpers.
- [x] Replace many nullable provider-overlay fields with a single provider-options slot on the main request types.
- [x] Tighten invariants across the main request/query models.
- [x] Unify the duplicate SSE parser and split large stream decoders into smaller families.
- [x] Make retries safe by default for mutating or paid endpoints.
  - Why:
    - the default retry policy should not duplicate stateful or billable POST operations on ambiguous failures
    - read/query operations can still retry by default
  - Done:
    - mutating or paid operations now use a separate non-idempotent retry path
    - non-idempotent retries are disabled by default and require explicit opt-in via `retryNonIdempotentRequests`
    - deterministic tests now cover:
      - safe GET retries by default
      - non-idempotent POSTs do not retry by default
      - non-idempotent POSTs retry only when explicitly enabled
    - `Retry-After` is now preserved on typed `Api` / `Http` errors and honored by the default retry policy
- [x] Type the main stable finite-language request fields that are clearly documented.
- [x] Add domain ID wrappers where they buy real safety.
  - examples: `ModelId`, `FileId`, `BatchId`, `FineTuningJobId`, `EvalId`, `EvalRunId`, `VideoId`, `UploadId`
- [x] Keep the main raw extension seam explicit via `JsonExtras` instead of sprinkling raw `JsonObject?` for the common `extraBody` case.

### Still open
- [x] Refresh the Realtime bootstrap models against the current OpenAI Realtime docs.
  - Why:
    - the current request/response DTOs in `Realtime.kt` still reflect the older beta wire shape
    - current docs use newer nested session/audio configuration
    - because `OpenAIApi.createRealtimeSession(...)` and `createRealtimeTranscriptionSession(...)` post these DTOs directly, this is a likely runtime compatibility bug, not just a taste issue
  - Done:
    - regular and transcription session bootstrap requests now use `type`, `output_modalities`, and nested `audio.input` / `audio.output`
    - typed finite Realtime fields were added where the docs are explicit:
      - session type
      - include values
      - audio encodings
      - noise-reduction modes
      - semantic VAD eagerness
    - deterministic tests were updated to assert the current documented wire shape
- [x] Tighten `VectorStores.kt` so OpenAI-native finite shapes are no longer modeled as loose raw JSON where the docs are stable enough.
  - Why:
    - this file is now an outlier relative to the rest of the cleaned API
    - `expires_after`, `chunking_strategy`, and list `filter` values appear stable enough to type
    - leaving them raw keeps the core OpenAI surface looking half-modeled
  - Done:
    - `expires_after` is now typed as a real request/response policy object
    - vector-store file and file-batch filters are now typed status values
    - stable request-side chunking-strategy shapes are now typed
    - file and file-batch response `chunking_strategy` values are now decoded into typed objects
    - genuinely open-ended search `filters` remain raw by design
- [ ] Continue the stable finite-language audit where the docs are explicit and the value space is genuinely closed.
  - Why:
    - this is still a worthwhile cleanup direction
    - but only when it buys real validation or domain clarity
  - Progress:
    - OpenAI-native response state vocabularies were tightened further:
      - top-level Response status
      - response item and response-input-item statuses
      - batch status
      - batch completion window on decoded batch objects
      - file purpose on decoded file objects
      - upload status
      - upload purpose on decoded upload objects
      - file status on decoded file objects
      - documented output-only file purposes on decoded file objects
      - fine-tuning job status
      - video status
      - realtime session type
      - realtime session output modalities
      - realtime session include values
      - image response background / output format / quality / size
      - transcription / translation response format
      - chat-completion finish reasons
      - chat-completion tool-call type
      - DeepSeek FIM finish reasons
      - vector-store status
      - vector-store file and file-batch statuses
      - decoded vector-store chunking-strategy type
    - These decoded response vocabularies now preserve unknown wire values instead of silently collapsing them to `null`.
      - Why:
        - provider drift is real on compatibility targets
        - `null` cannot distinguish "field absent" from "field present but unknown"
        - this keeps the request side closed while making the decode side more honest
- [ ] Continue the invariant sweep for any remaining request/query holes.
  - Why:
    - these are cheap correctness wins
    - the library should reject obviously invalid caller inputs early and consistently
  - Progress:
    - provider config now treats nullable fields as absent-or-valid rather than absent-or-blank:
      - Azure resource name, API version, and deployment names
      - OpenRouter referer and title
      - Ollama bearer token
    - stream endpoints now derive safer default timeouts:
      - regular request defaults stay unchanged
      - streaming defaults now drop request and socket timeouts unless `streamingTimeouts` is explicitly provided
      - this avoids aborting long reasoning / tool-use streams after the normal `15s` request/socket defaults
- [ ] Review binary and streaming endpoints for consistent typed error decoding.
  - Why:
    - a number of endpoints return binary success bodies but still emit JSON API envelopes on failure
    - these should preserve typed `OpenAIApiError.Api` behavior instead of falling back to raw `Http`
  - Progress:
    - `createSpeech`
    - `streamSpeech`
    - `downloadFile`
    - `downloadVideoContent`
    - `createXaiSpeech`
    now decode JSON error envelopes through the common error path
    - permissive JSON error decoding now also handles real non-OpenAI-compatible envelopes seen in the wild:
      - `{"detail":"Unsupported model"}`
      - `{"error":"model not found"}`
      - root-level problem/error objects with `message` / `type` / `code`
      - numeric `error.code` values such as `403`
    - streaming endpoints now explicitly reject successful non-SSE JSON / text / HTML bodies instead of silently yielding empty flows
      - this covers compatibility stacks that return `200` with `application/json` or gateway text bodies even when `stream=true`
    - `ResponseStreamAccumulator` now synthesizes a minimal assistant text message into `finalResponse` when the stream carried text but the final streamed response object omitted it
      - this is intentionally conservative and only applies when the completed response has no text content at all
    - speech streaming now surfaces typed SSE error events instead of only opaque server events
      - `collectSpeechBytes()` now fails on stream errors instead of silently returning truncated or invalid audio
    - audio generation endpoints now reject successful JSON / text / HTML bodies instead of returning error payloads as if they were audio bytes
    - parse failures on successful responses now preserve a body sample on `OpenAIApiError.Parse`
      - this materially improves debugging for malformed provider payloads and compatibility drift
    - malformed streaming chunks now surface as typed `OpenAIApiError.Parse` failures with an event body sample instead of leaking raw serializer exceptions
    - mid-read failures while streaming or reading binary bodies are now wrapped as `OpenAIApiError.Network`
      - this keeps transport failures typed even when the connection dies after the response has already started
- [ ] Consider replacing the remaining open-coded stream event string routing with small internal event-key helpers if that reduces duplication without adding ceremony.
  - Why:
    - this is lower priority than the Realtime and Vector Store work
    - it should only be done if it materially simplifies the code
  - Progress:
    - response-stream text assembly no longer drops `response.output_text.done` payloads when providers omit or de-emphasize delta events

## Out of scope under the deprecated-endpoint rule

- [x] Assistants API
- [x] Threads API
- [x] Messages API
- [x] Runs API

Reason:
- OpenAI deprecated the Assistants-era surface and scheduled retirement for August 26, 2026
- Azure still exposes those endpoints only under the same deprecated Assistants surface
- no supported target provider currently requires those APIs in non-deprecated form

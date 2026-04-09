# Migration Notes

Updated: 2026-04-05

This file records behavior changes that are likely to affect callers after the recent schema-parity and hardening work.

## Exception body samples

- Exception messages no longer include response body samples by default.
- Structured `bodySample` data is still kept on `OpenAIApiError` objects for programmatic inspection.
- To opt back into message-visible samples for debugging, set:
  - `OpenAIApi.Config(includeBodySamplesInExceptions = true)`

Why this changed:
- response bodies can contain prompts, tool outputs, attachment metadata, or provider-returned secrets
- logging `Throwable.message` should not leak that data by default

## `extraBody` and `JsonExtras`

- `JsonExtras` is additive-only now.
- `extraBody` can no longer overwrite validated top-level fields such as:
  - `model`
  - `stream`
  - `tool_choice`
  - `response_format`
- Reserved-key collisions fail fast with an explicit client-side error.

Impact:
- callers that previously relied on `extraBody` to override typed fields must now set those fields through the typed API instead

## Multipart `extraFields`

- Multipart extension maps now reject reserved part names instead of sending duplicate parts and relying on server-side last-one-wins behavior.
- This affects request types that accept multipart extra fields, including file, image, and audio upload surfaces.

Impact:
- callers must stop reusing core multipart names such as `file`, `purpose`, `model`, `response_format`, or `stream` inside `extraFields`

## XOR validation changes

The client now rejects a few previously ambiguous states:

- `ResponseInputItem.FunctionCallOutput`
  - exactly one of `outputText` or `outputContent` must be set
- `OpenRouterRequestOptions`
  - `provider` and `providerRouting` are mutually exclusive

Impact:
- callers that relied on silent overwrite behavior now get a deterministic validation error instead

## Merged-output helpers

Several convenience helpers no longer silently merge semantically distinct outputs.

Changed behavior:
- `ChatCompletionResponse.outputText()`
- `ChatCompletionResponse.reasoningContent()`
- `ChatCompletionResponse.outputJsonElementOrNull()`
- `ResponseObject.outputText()`
- `ResponseObject.outputJsonElementOrNull()`
- `ResponseObject.decodeOutputJson()`

New rule:
- these helpers require a single relevant choice/output message
- multi-choice or multi-message responses should use the structured helpers first:
  - `ChatCompletionResponse.choiceTexts()`
  - `ChatCompletionResponse.choiceReasoningContents()`
  - `ResponseObject.outputTexts()`

Impact:
- callers that previously depended on merged text from `n > 1` responses now need to handle the per-choice/per-message structure explicitly

## Vector-store attribute typing

- Vector-store file and batch attributes are no longer string-only.
- The typed surface now supports:
  - string
  - number
  - boolean

Impact:
- existing string-only call sites keep working
- callers can move off raw JSON for mixed primitive attributes

## Bodyless JSON POSTs

- Bodyless JSON POSTs now send `{}` instead of an empty string.

Why this changed:
- `Content-Type: application/json` with an empty-string body is not valid JSON

Impact:
- this is primarily a wire-level compatibility fix; callers should not need code changes unless they were asserting the old invalid transport behavior in tests

## Live test configuration

Live-test secret discovery is narrower now:

- direct env vars are checked first
- explicit secret-file paths are supported:
  - `WABBIT_LIVE_KEYS_ENV_PATH`
  - `WABBIT_LIVE_ROOT_PRIVATE_PATH`
- fallback secret discovery is limited to repo-root `keys.env` / `root.private.clj`
- parent-directory walking is no longer used

Additional live-test controls added during verification:

- eval smoke:
  - `WABBIT_LIVE_OPENAI_EVAL_MODEL`
  - `WABBIT_LIVE_OPENAI_EVAL_GRADER_MODEL`
- chat-audio smoke:
  - `WABBIT_LIVE_OPENAI_CHAT_AUDIO_MODEL`
  - `WABBIT_LIVE_OPENAI_CHAT_AUDIO_VOICE`
- video smoke:
  - `WABBIT_RUN_LIVE_OPENAI_VIDEO_TEST=true`
  - `WABBIT_LIVE_OPENAI_VIDEO_MODEL`

Notes:
- chat-audio live verification showed that audio payload plus transcript is the stable thing to assert; plain text `message.content` may still be empty even when requesting both `text` and `audio`
- video live smoke is intentionally gated behind its own explicit opt-in because it is the cost-bearing part of the live suite

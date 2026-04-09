# PLAN

Audit date: 2026-04-05

This file turns the current review feedback into an execution backlog.

It is intentionally more operational than the previous parity ledger:
- it distinguishes endpoint presence from trusted schema parity
- it records where the reviews appear stale or only partially correct
- it converts each concern into a concrete checklist with acceptance criteria

`GOAL.md` remains the long-lived product goal document.

## Current read of the codebase

The strong parts are still strong:
- transport structure is good
- retry partitioning is sensible
- unknown-wire-value preservation is good
- streaming/event assembly is unusually disciplined for a client of this size
- provider gating is explicit and mostly coherent

The risky parts are now concentrated in a few categories:
- security and escape-hatch hardening
- evals schema parity
- video schema parity
- modern chat-completions parity
- vector-store request typing
- convenience helpers that collapse structure too aggressively

Several reviewer findings are already fixed or partly stale and should not be copied forward blindly:
- Realtime call-control endpoints now exist
- video edit and extend endpoints now exist
- empty SSE metadata-only events are now ignored
- zero-byte audio-stream reads now yield instead of spinning

Those surfaces still need re-verification, but they are not “missing” anymore.

## Decision rules for this plan

- OpenAI-native correctness wins over compatibility convenience.
- When docs and generated SDKs disagree, do not trust either one blindly.
- If the wire contract is unclear, add a live smoke test before locking in the typed model.
- `extraBody` and multipart `extraFields` are extension seams, not authority-bypassing overrides.
- Convenience helpers must not silently merge semantically distinct outputs unless they enforce singleton preconditions.
- Deprecated APIs remain out of scope unless a supported compatibility provider still requires them in non-deprecated form.

## Definition of done for any item below

- request serialization matches the current documented shape or a verified live shape
- response decoding preserves documented structure instead of dropping important fields
- invariants reject obviously illegal caller states
- deterministic tests cover both the happy path and at least one misuse or drift case
- live smoke coverage exists for any area where the docs are internally inconsistent
- provider gates fail clearly before the request leaves the client

## Phase 0: Security and authority hardening

### 0.1 Exception body-sample leakage

- [x] Add `includeBodySamplesInExceptions` to `OpenAIApi.Config`, defaulting to `false`.
  Files: `OpenAIApi.kt`, `OpenAIApiError.kt`.
  Why: current exception messages can leak prompts, tool outputs, attachment metadata, signed URLs, or provider-returned secrets into logs.
  Done when: human-readable exception messages contain status, URL, and error type, but not payload samples unless the flag is explicitly enabled.

- [x] Keep `bodySample` as structured data on the exception object even when it is hidden from the message.
  Files: `OpenAIApiError.kt`, `OpenAIApi.kt`.
  Why: debugging still needs access to the payload, but not through `Throwable.message`.
  Done when: `bodySample` is still inspectable programmatically while `message` stays clean by default.

- [x] Centralize parse/error exception formatting so every call site obeys the same redaction policy.
  Files: `OpenAIApi.kt`, `OpenAIApiError.kt`.
  Why: the current body-sample handling is spread across parse failures, typed HTTP failures, and successful-but-wrong-content-type helpers.
  Done when: no call site manually concatenates payload text into an exception message.

- [x] Add redaction for obvious sensitive keys when body samples are enabled.
  Files: `OpenAIApi.kt`, `OpenAIApiError.kt`.
  Why: even opt-in debugging should not casually expose fields like `authorization`, `api_key`, `token`, `secret`, `client_secret`, or signed URLs.
  Done when: the redaction helper covers both JSON-looking bodies and plain-text body samples.

- [x] Add deterministic tests for message redaction and opt-in body-sample visibility.
  Files: `OpenAIApiSpec.kt`.
  Done when: tests prove that default exceptions do not leak body content and opt-in mode does.

### 0.2 Escape-hatch authority and reserved-key collisions

- [x] Make `putJsonExtras` reject collisions with validated top-level keys.
  Files: `JsonExtras.kt`, every request builder that relies on `putJsonExtras`.
  Why: `extraBody` currently lets callers override validated fields like `model`, `stream`, `tool_choice`, and `response_format`.
  Done when: collisions fail fast with an explicit error naming the reserved key.

- [x] Decide whether a truly unsafe override mode is needed, and if so, make it explicit.
  Files: `JsonExtras.kt`, request models that currently expose `extraBody`.
  Why: bypass behavior should be deliberate and visibly dangerous, not the default extension seam.
  Decision: the unsafe path is intentionally not offered.
  Done when: safe and unsafe extension paths are distinct, or the unsafe path is intentionally not offered.

- [x] Reject multipart `extraFields` that reuse reserved form-part names.
  Files: `Audio.kt`, `Files.kt`, `Images.kt`, `OpenAIApi.kt`.
  Why: multipart `extraFields` can currently duplicate core fields and rely on undefined server-side last-one-wins behavior.
  Done when: multipart builders reject duplicates for names like `file`, `purpose`, `model`, `response_format`, `stream`, and similar endpoint-specific reserved fields.

- [x] Add collision tests for JSON and multipart requests.
  Files: `OpenAIApiSpec.kt`.
  Done when: tests cover both safe extension keys and rejected collisions.

### 0.3 Silent overwrite and mutually exclusive field bugs

- [x] Fix `ResponseInputItem.FunctionCallOutput` so exactly one of `outputText` or `outputContent` is allowed.
  Files: `Responses.kt`.
  Why: both can currently be supplied, and the second `"output"` write silently overwrites the first.
  Done when: constructor validation enforces XOR and tests prove both-invalid and both-missing cases fail.

- [x] Fix `OpenRouterRequestOptions` so `provider` and `providerRouting` cannot both be set.
  Files: `Responses.kt`.
  Why: both currently target the same `"provider"` field, so one silently overwrites the other.
  Done when: the type rejects the ambiguous state and tests cover it.

- [x] Audit all request builders for repeated-key writes and ambiguous paired fields.
  Files: whole `src/commonMain/kotlin/one/wabbit/web/openai`.
  Why: the same class of bug can hide anywhere multiple optional fields serialize to the same wire key.
  Result:
  - the remaining same-key write sites are now intentional and guarded:
    - `ResponseInputItem.FunctionCallOutput.output`
    - `OpenRouterRequestOptions.provider`
    - additive-only `JsonExtras`
  - no additional silent-overwrite request builders were found in the current request serializers
  - remaining multi-field problems are schema-parity issues tracked in later phases, not authority-bypass collisions
  Done when: the audit produces a short explicit list of remaining intentional collisions or eliminates them.

### 0.4 Bodyless POST normalization

- [x] Inventory every endpoint that currently calls `postJson(..., payload = null, ...)`.
  Files: `OpenAIApi.kt`.
  Why: the client currently sends `Content-Type: application/json` with an empty-string body, which is not valid JSON.
  Result:
  - audited current bodyless JSON POST call sites in `OpenAIApi.kt`
  - the current list is:
    - `cancelResponse`
    - `cancelBatch`
    - `cancelFineTuningJob`
    - `pauseFineTuningJob`
    - `resumeFineTuningJob`
    - `cancelUpload`
    - `cancelVectorStoreFileBatch`
    - `hangupRealtimeCall`
    - `cancelEvalRun`
    - `cancelXaiBatch`
  - all of those now flow through the shared `{}` body policy rather than emitting empty-string JSON bodies
  Done when: the inventory is complete and the policy choice below is applied consistently.

- [x] Standardize bodyless POST behavior.
  Files: `OpenAIApi.kt`.
  Decision needed: either send no body and no JSON content type, or always send `{}` for JSON POSTs that are logically bodyless.
  Done when: all bodyless POST endpoints follow the same rule and tests assert it.

- [x] Add transport tests for bodyless POSTs.
  Files: `OpenAIApiSpec.kt`.
  Done when: the suite locks in the chosen policy for cancel, hangup, and similar endpoints.

### 0.5 Multipart filename sanitization

- [x] Sanitize `BinaryUpload.filename` defensively before writing multipart `Content-Disposition`.
  Files: `OpenAIApi.kt`.
  Why: quotes are escaped today, but CR, LF, path separators, and control characters are not.
  Done when: filenames cannot inject headers or leak path segments into the multipart metadata.

- [x] Add tests for CR/LF, path-looking names, and control-character edge cases.
  Files: `OpenAIApiSpec.kt`.

## Phase 1: Schema parity refresh for unstable surfaces

### 1.1 Evals: rebuild request and response models against the current API

- [x] Rebuild `EvalCreateRequest` around the current `POST /evals` contract.
  Files: `Evals.kt`.
  Why: the request is currently under-modeled and appears to omit required `data_source_config` and `testing_criteria`.
  Done when: the typed request can express the current documented create payload without falling back to `extraBody`.

- [x] Rebuild `EvalRunCreateRequest` around the current `POST /evals/{eval_id}/runs` contract.
  Files: `Evals.kt`.
  Why: the request currently only serializes `name` and `metadata`, but the API expects structured run input.
  Done when: run creation supports the documented `data_source` payload and the stable optional run/model config fields.

- [x] Expand `EvalObject`, `EvalRunObject`, and `EvalRunOutputItemObject` so successful responses do not lose important structure.
  Files: `Evals.kt`.
  Why: under-modeled responses are effectively partial decoders with a typed veneer.
  Result:
  - `EvalObject.dataSourceConfig` and `testingCriteria` now expose stable nested documented fields directly
  - `EvalRunObject.resultCounts` and `dataSource` are now typed instead of generic blobs
  - `EvalRunOutputItemObject.results` and `sample` now preserve documented nested fields directly
  Done when: current documented fields are preserved where the schema is stable, with `Unknown(...)` preservation where needed.

- [x] Re-verify `EvalRunOutputItemStatus` query serialization.
  Files: `Evals.kt`, `OpenAIApiSpec.kt`.
  Why: the review correctly calls out likely drift between `fail` and `failed`.
  Done when: request query serialization and response decoding match docs or verified live behavior.

- [x] Re-verify `cancelEvalRun` against primary sources and live behavior.
  Files: `OpenAIApi.kt`, `Evals.kt`, `OpenAIApiSpec.kt`, live smoke tests.
  Why: the docs are internally inconsistent and the generated SDK points to a different path than the current implementation.
  Result:
  - deterministic tests and implementation align with `POST /evals/{eval_id}/runs/{run_id}`
  - live OpenAI verification succeeded against that path in `OpenAIEvalsLiveSmokeTest.kt`
  Done when: the chosen path is backed by either a live smoke test or official generated SDK behavior, and the rationale is documented in tests or comments.

- [x] Add deterministic request/response contract tests for eval create, run create, cancel, list output items, and output-item retrieval.
  Files: `OpenAIApiSpec.kt`.

- [x] Add at least one live OpenAI smoke test covering eval create, run create, cancel, and output-item listing.
  Files: `OpenAILiveSmokeTest.kt` or a dedicated eval smoke file.
  Why: evals is now a “docs conflict” area and should not rely on mocks alone.

### 1.2 Videos: re-verify the whole surface rather than assuming the current model is trustworthy

- [x] Re-verify `VideoCreateRequest`, `VideoEditRequest`, `VideoExtendRequest`, and `VideoRemixRequest` against the current docs.
  Files: `Videos.kt`, `OpenAIApi.kt`.
  Why: the review correctly identifies schema drift risk, even though some “missing endpoint” claims are now stale.
  Done when: each endpoint’s typed request matches the current field names and invariants.

- [x] Decide where `input_reference` belongs and where `reference_assets` should be removed.
  Files: `Videos.kt`.
  Why: the current create/remix shapes likely still mix old and new field names.
  Done when: every endpoint uses only the fields the endpoint actually accepts.

- [x] Refresh `VideoSeconds`, `VideoSize`, and `VideoStatus` from the current docs.
  Files: `OpenAIFieldTypes.kt`.
  Why: enum drift on media APIs causes subtle runtime failures and confusing DX.
  Notes:
  - `VideoStatus` now recognizes the current `in_progress` wire value while preserving the older `processing` alias as a deprecated compatibility decode path.
  - create-video validation now only accepts the documented `seconds` values `4`, `8`, and `12`, while extend continues to accept `4`, `8`, `12`, `16`, and `20`.
  Done when: request enums align with the documented accepted values and decode-side enums preserve unknowns.

- [x] Add `order` to `VideoListQuery` if it is still documented.
  Files: `Videos.kt`, `OpenAIApi.kt`.

- [x] Add the documented `variant` query parameter to `downloadVideoContent`.
  Files: `Videos.kt`, `OpenAIApi.kt`.
  Done when: callers can request specific output variants without dropping to raw query parameters.

- [x] Tighten `VideoRemixRequest` invariants.
  Files: `Videos.kt`.
  Why: the current `prompt` nullability and extra fields likely allow illegal states.
  Done when: required fields are required and endpoint-specific forbidden fields are rejected.

- [x] Expand `VideoObject` with current response fields such as `progress`, `expires_at`, `seconds`, `size`, and similar stable fields.
  Files: `Videos.kt`.

- [x] Add deterministic tests for create, edit, extend, remix, list ordering, and content-download variant handling.
  Files: `OpenAIApiSpec.kt`.

- [x] Add at least one live smoke test for the cheapest viable video path, or explicitly document why it remains mock-only.
  Files: live smoke test suite.
  Result:
  - `OpenAIVideoLiveSmokeTest.kt` now verifies the stable live lifecycle path: create, get, and delete
  - `listVideos()` was intentionally dropped from the smoke because immediate list visibility was not reliable enough for a contract assertion

### 1.3 Chat Completions: bring the surface up to current documented behavior

- [x] Re-verify `ChatRole` against the current docs and generated SDKs.
  Files: `ChatCompletions.kt`.
  Why: the review flags a missing `function` role, and that should be verified rather than guessed.
  Done when: the role set matches current docs, and deprecated-but-still-returned roles are handled explicitly.

- [x] Add stored chat-completion update if `POST /chat/completions/{completion_id}` remains current.
  Files: `OpenAIApi.kt`, `ChatCompletions.kt`.
  Done when: the interface supports create/get/list/update/delete for stored completions when the docs support it.

- [x] Add audio-output request fields for chat completions.
  Files: `ChatCompletions.kt`.
  Why: modern chat completions can request audio output, and the current request surface appears behind.
  Done when: callers can express the documented audio request config without raw JSON.

- [x] Add assistant-message audio continuation fields such as `audio.id` if still current.
  Files: `ChatCompletions.kt`.
  Why: multi-turn audio continuations should be first-class if the API supports them.

- [x] Model returned chat audio payloads and transcripts where the docs are explicit.
  Files: `ChatCompletions.kt`.
  Done when: response models preserve current audio fields instead of dropping them on decode.

- [x] Re-verify chat tool support against the modern schema.
  Files: `ChatCompletions.kt`, `OpenAIApiSpec.kt`.
  Why: the current surface still appears function-only, and that likely lags current custom-tool behavior.
  Progress:
  - request-side chat completions now accept documented `custom` tools and named custom tool choices
  - response decoding now preserves `custom` tool-call deltas alongside `function`
  - `tool_choice = allowed_tools` is now modeled and `/responses` explicitly rejects that chat-only variant
  Done when: supported chat tool types and tool choices are explicitly modeled or explicitly rejected with clear errors.

- [x] Update chat streaming parsers and accumulators for any new tool-call delta families or audio deltas.
  Files: `ChatCompletions.kt`, `Streaming.kt`.
  Result:
  - the current official chat streaming schema still documents deprecated `function_call` deltas alongside `tool_calls`
  - chat chunk/message models now preserve that deprecated `function_call` payload
  - `ChatCompletionStreamAccumulator` now accumulates deprecated streamed function-call arguments under a stable legacy key
  - no separate chat-audio delta family is currently documented on the official chat streaming events schema

- [x] Add contract tests and at least one live smoke covering the modern chat fields that were added.
  Files: `OpenAIApiSpec.kt`, live smoke suite.
  Progress:
  - deterministic tests now cover `function` role serialization, stored chat completion update, custom-tool request/response payloads, chat audio request config, assistant-message `audio.id`, and decoded audio payloads
  - live smoke coverage now includes `OpenAIChatAudioLiveSmokeTest.kt` against the OpenAI chat-audio surface

### 1.4 Vector stores: loosen attribute typing and support per-file batch input

- [x] Replace string-only attribute maps with a typed primitive value model.
  Files: `VectorStores.kt`.
  Why: the API accepts string, number, and boolean values, but the client currently forces everything into `Map<String, String>`.
  Done when: vector-store attribute typing can represent all documented primitive value types without escaping to raw JSON.

- [x] Support `files: [{file_id, attributes, chunking_strategy}]` in vector-store file-batch creation.
  Files: `VectorStores.kt`.
  Why: the batch-create surface currently supports only global `file_ids`, which is not enough for normal per-file metadata workflows.
  Done when: callers can express both the simple `file_ids` form and the per-file structured form.

- [x] Keep convenience helpers for the common string-only case.
  Files: `VectorStores.kt`.
  Why: loosening the type system should not destroy ergonomics for the common path.

- [x] Add deterministic tests for mixed primitive attribute values and per-file batch payloads.
  Files: `OpenAIApiSpec.kt`.

## Phase 2: Correctness of helpers and stream assembly

### 2.1 Convenience helpers that currently flatten too much structure

- [x] Audit every helper that merges multiple choices, outputs, or content parts into one blob.
  Files: `Responses.kt`, `ChatCompletions.kt`, `Streaming.kt`.
  Start with:
  `ChatCompletionResponse.outputText()`
  `ChatCompletionResponse.reasoningContent()`
  `ChatCompletionResponse.outputJsonElementOrNull()`
  `ResponseObject.outputText()`
  `ResponseObject.decodeOutputJson()`
  `ChatCompletionStreamAccumulator`
  `ResponseStreamAccumulator`
  Progress:
  - `ChatCompletionResponse` now exposes `choiceTexts()` / `choiceReasoningContents()` and rejects merged text/json helpers when `choices.size != 1`
  - `ResponseObject` now exposes `outputTexts()` and rejects merged text/json helpers when multiple assistant messages are present
  - `ChatCompletionStreamAssembly` and `ResponseStreamAssembly` now expose structured per-choice / per-output-part maps and reject merged text/refusal access when multiple values are present
  - the remaining convenience helpers that span multiple choices or items now return collections (`toolCalls()`, `annotations()`, `audioOutputs()`, etc.) rather than lossy merged blobs

- [x] Decide the public rule for each helper.
  Options:
  1. return structured per-choice or per-output-index results
  2. enforce singleton preconditions like `require(choices.size == 1)`
  3. deprecate unsafe merged helpers
  Done when: no helper silently merges semantically distinct outputs without warning.
  Progress:
  - the adopted rule is mixed but now explicit:
    1. expose structured helpers first for multi-choice or multi-part data
    2. keep merged text/json/refusal helpers only for the singleton case
    3. reject ambiguous merged access with clear error messages pointing to the structured helper

- [x] Add tests for `n > 1`, interleaved outputs, and mixed output-item types.
  Files: `OpenAIApiSpec.kt`.

### 2.2 Stream assembly ordering by semantic position instead of arrival order

- [x] Rework `ResponseStreamAccumulator` to materialize text/audio/reasoning in semantic order.
  Files: `Streaming.kt`.
  Why: the current linked-map accumulation can produce incorrect final ordering when deltas arrive interleaved.
  Done when: materialization sorts or structures by output index and content index rather than insertion order.

- [x] Rework `ChatCompletionStreamAccumulator` with the same rule.
  Files: `ChatCompletions.kt`.

- [x] Add interleaved-delta tests that would fail under arrival-order assembly.
  Files: `OpenAIApiSpec.kt`.

### 2.3 Binary upload/download ergonomics and memory safety

- [x] Add streaming upload APIs alongside `ByteArray`-based `BinaryUpload`.
  Files: `OpenAIApi.kt`, upload/file/audio/video request surfaces.
  Why: large files and videos should not require buffering everything in memory.

- [x] Add sink-oriented download helpers such as `downloadFileTo(...)` and `downloadVideoContentTo(...)`.
  Files: `OpenAIApi.kt`.

- [x] Add maximum-size guards for eager `ByteArray` helpers.
  Files: `OpenAIApi.kt`, config or helper options.
  Done when: callers can choose safe eager behavior or a true streaming path.

### 2.4 Streaming timeout defaults

- [x] Revisit default streaming timeout behavior.
  Files: `OpenAIApi.kt`.
  Why: disabling request and socket timeouts entirely avoids false positives on long streams, but can hang forever on broken networks.
  Done when: there is either a default stall timeout, a heartbeat timeout, or an explicit documented rationale for leaving streams unbounded.

- [x] Add tests for stalled-stream behavior.
  Files: `OpenAIApiSpec.kt`.

### 2.5 Provider/tool compatibility enforcement

- [x] Validate builtin tool usage against provider capabilities before sending a request.
  Files: `Responses.kt`, `ChatCompletions.kt`, `ProviderValidation.kt`.
  Why: providers like Ollama can currently accept typed builtin tools at the model layer and only fail later at runtime.
  Done when: unsupported tool/provider combinations fail client-side with a clear error.

- [x] Add provider-specific tests for Responses and Chat Completions tool validation.
  Files: `OpenAIApiSpec.kt`.

### 2.6 Diarized transcription discrimination

- [x] Replace brittle diarized-vs-regular inference with an explicit discriminator when the payload provides one.
  Files: `Audio.kt`.
  Why: inferring type from segment IDs or shape heuristics will age badly.

- [x] Keep heuristic fallback only for provider compatibility payloads that truly omit the discriminator.
  Files: `Audio.kt`.

- [x] Add fixtures for diarized and non-diarized payloads with string IDs and mixed provider-style shapes.
  Files: `OpenAIApiSpec.kt`.

## Phase 3: Test and verification strategy

### 3.1 Replace self-referential mocks with source-backed fixtures

- [x] Audit tests that currently mirror the library’s own stale assumptions.
  Files: `OpenAIApiSpec.kt`.
  Start with:
  eval tests
  video tests
  chat tests
  any schema-refresh area touched above
  Result:
  - the primary eval, video, and chat contract tests now use doc-backed fixtures instead of hand-authored inline payloads
  - remaining synthetic cases are request-serializer invariants, provider-specific compatibility coverage, or deliberate negative-path tests rather than schema source-of-truth fixtures
  - the audit surfaced one real decode drift from the official stored-chat retrieve example (`tool_calls: null`), and the chat message decoder now accepts that shape

- [x] Prefer one of two test sources for contract coverage:
  1. copied official doc examples
  2. live-captured fixtures scrubbed for secrets
  Progress:
  - eval object decode, eval run decode, eval run output-item decode, chat completion decode, stored chat completion lifecycle, and video create/list/retrieve/delete lifecycle coverage now use named doc-backed fixtures with source URLs in `OpenAIApiSpec.kt`
  Done when: important wire-shape tests are anchored to an external source rather than the current implementation.

- [x] Add comments or fixture metadata documenting the source of each contract sample.
  Files: test fixtures or spec file.
  Progress:
  - official-doc fixtures in `OpenAIApiSpec.kt` now carry source comments and access date

### 3.2 Live smoke coverage for doc-conflict surfaces

- [x] Make live-test skips visible in reports instead of returning early as “pass”.
  Files: `OpenAILiveTestSupport.kt`, live smoke tests.
  Done when: disabled/skipped tests are reported as skipped, not as silently passed.

- [x] Add live smoke coverage for eval create/run/cancel/output-item flows.
  Files: live smoke suite.
  Note:
  - live verification against OpenAI succeeded with a `custom` eval schema plus `completions` run data source

- [x] Add live smoke coverage for the modern video surface if cost and latency are acceptable.
  Files: live smoke suite.
  Result:
  - `OpenAIVideoLiveSmokeTest.kt` now covers a minimal OpenAI video lifecycle: create, get, and delete
  - the video smoke is gated behind `WABBIT_RUN_LIVE_OPENAI_VIDEO_TEST=true` so it remains an explicit cost-bearing opt-in instead of part of the default live suite
  - the default live model is `sora-2`, overrideable with `WABBIT_LIVE_OPENAI_VIDEO_MODEL`

- [x] Add live smoke coverage for modern chat audio if credentials and models permit.
  Files: live smoke suite.
  Result:
  - `OpenAIChatAudioLiveSmokeTest.kt` now verifies a live `chat.completions` request with `modalities = ["text","audio"]`, typed audio config, and decoded returned audio payload fields
  - the default live model is `gpt-audio-mini` with `WABBIT_LIVE_OPENAI_CHAT_AUDIO_MODEL` / `WABBIT_LIVE_OPENAI_CHAT_AUDIO_VOICE` overrides for accounts that need different access or voices
  - live verification confirmed the audio payload plus transcript path; plain text assistant content may still be empty even with dual text/audio modalities, so the smoke asserts the audio-specific fields directly

- [x] Add a targeted live verification for any path where docs and generated SDKs disagree.
  Current known example: eval-run cancel.
  Result:
  - live OpenAI verification succeeded against `POST /v1/evals/{eval_id}/runs/{run_id}`
  - the surrounding eval docs remain internally inconsistent for `stored_completions`; the live smoke records the verified working combination in `OpenAIEvalsLiveSmokeTest.kt`

### 3.3 Secret-discovery tightening for live tests

- [x] Restrict secret-file discovery to repo root or an explicit env var.
  Files: `OpenAILiveTestSupport.kt`.
  Why: walking parent directories for `keys.env` and `root.private.clj` is convenient but too broad.

- [x] Document the explicit configuration path for live tests.
  Files: test support docs or comments.

## Phase 4: Documentation and migration follow-through

### 4.1 Public API migration notes

- [x] Document breaking or behavior-changing fixes that are likely to affect callers.
  Candidates:
  exception-message redaction
  reserved-key rejection in `extraBody`
  multipart field-collision rejection
  XOR validation fixes
  helper APIs that switch from merged output to singleton-only or structured output
  vector-store attribute typing changes
  Result:
  - caller-facing migration notes now live in `MIGRATION.md`
  - the notes cover the security hardening, validation changes, helper behavior changes, transport normalization, and live-test env changes introduced during this plan

- [x] Update examples and README snippets if any currently depend on stale chat, eval, video, or vector-store shapes.
  Files: repo docs as needed.
  Result:
  - this module currently has no `README` or example snippets to update
  - the repo-doc follow-through for this plan is therefore the new `MIGRATION.md` file rather than a README patch

### 4.2 Keep this plan current

- [ ] Update this file after each completed slice.
  Rule: when a slice ships, move it to a short “completed recently” note or mark it done with the acceptance criteria preserved.

- [ ] Do not mark a schema-refresh item done on mock tests alone when the docs are conflicting.
  Rule: those items require a live smoke or a verified generated-SDK reference.

## Recommended execution order

1. Phase 0.1 through 0.4.
   Rationale: security leaks and authority bypasses are higher risk than feature gaps.
2. Phase 1.1.
   Rationale: evals is currently the most likely “typed but server-rejected” surface.
3. Phase 1.2.
   Rationale: video drift is broad and touches both request and response enums.
4. Phase 1.3.
   Rationale: chat completions is strategically important and currently behind the modern API.
5. Phase 1.4.
   Rationale: vector-store typing is lower risk than eval/video/chat but still a normal-workflow DX footgun.
6. Phase 2.1 and 2.2.
   Rationale: merged helpers and arrival-order assembly can silently produce wrong results for valid responses.
7. Phase 2.3 through 2.6.
   Rationale: these are important hardening tasks, but they can follow the highest-risk correctness gaps.
8. Phase 3 and Phase 4 continuously alongside the above.

## Explicitly deferred for now

- Assistants / Threads / Runs / Messages legacy surface.
- Realtime websocket/event transport beyond the already implemented REST bootstrap and call-control pieces.
- Provider-native non-OpenAI APIs.
- New platform areas such as ChatKit, Containers, or Skills unless they are explicitly brought into scope later.

## Release Readiness Plan

Release-readiness audit:
- [`tmp/release-checklist/audit.md`](tmp/release-checklist/audit.md)

Immediate blockers:

- [x] Add a real public [`README.md`](README.md).
  Acceptance criteria:
  - explains what `kotlin-web-openai` is for
  - shows the Maven coordinates for the released version
  - includes at least one minimal example and one practical example
  - documents the supported transport/platform shape at a high level
  Result:
  - `README.md` now explains the library scope, install coordinates, supported targets, transport shape, and major API coverage
  - it includes both a minimal `createResponse(...)` example and a practical streaming example
  - both examples are mirrored by `ReadmeExamplesTest.kt` so the docs are exercised in the test suite
  Why:
  the repo currently has no README at all, which is an immediate `NO-GO` for public release.

- [x] Establish a changelog source of truth.
  Acceptance criteria:
  - add `CHANGELOG.md` or adopt a clearly stated GitHub Releases policy
  - record what changed in the first public release
  Result:
  - `CHANGELOG.md` is now the release-note source of truth
  - it includes the initial `0.0.1` entry and points readers at `MIGRATION.md` for caller-visible behavior changes
  Why:
  the repo currently has migration notes but no release-note discipline.

- [x] Replace the placeholder published description.
  Current state:
  the generated POM description now comes from `root.clj` and gives a user-facing summary of the library.

- [ ] Make the release build warning-free, or explicitly decide which warnings are acceptable and remove the rest.
  Acceptance criteria:
  - current deprecation warnings in [`src/commonMain/kotlin/one/wabbit/web/openai/OpenAIFieldTypes.kt`](src/commonMain/kotlin/one/wabbit/web/openai/OpenAIFieldTypes.kt)
    and [`src/commonMain/kotlin/one/wabbit/web/openai/Videos.kt`](src/commonMain/kotlin/one/wabbit/web/openai/Videos.kt)
    are either removed or intentionally accepted as part of the public surface
  - local-mode build noise is understood well enough that `--prod` and clean-room release checks are not warning-ambiguous
  Why:
  release output should not normalize warning spam.

- [ ] Prove external consumer usability with a clean-room smoke test.
  Acceptance criteria:
  - copy the repo outside the monorepo
  - build it there
  - publish to a temp local Maven repo
  - consume it from a fresh external Gradle project without composite-build tricks
  Why:
  this is the highest-value missing proof after docs.

- [x] Make the public support path explicit.
  Acceptance criteria:
  - README or dedicated public docs say where users file issues and what support/maintenance level to expect
  Result:
  - `README.md` now points users to GitHub issues for public support
  - it documents `wabbit@wabbit.one` for private or security-sensitive concerns
  - it states the current maintenance level as best-effort open source support
  Why:
  current internal docs are stronger than the user-facing support story.

Secondary release work:

- [ ] Review the published KMP metadata and fallback POM behavior from an external consumer perspective.
  Why:
  the local root POM currently shows a runtime dependency on `ktor-client-darwin`, which may be fine for Gradle-metadata consumers but still needs proof.

- [ ] Review the public API surface for intentional long-term names and experimental boundaries.
  Why:
  this client has a broad surface area and some intentionally deprecated compatibility aliases.

- [x] Decide and document the supported compatibility story.
  Minimum topics:
  - Kotlin version floor
  - JDK floor
  - Android min SDK
  - Apple/native support expectations
  Result:
  - `README.md` now documents the current Kotlin, JDK, and Android floors
  - it lists the published JVM, Android, iOS Arm64, iOS Simulator Arm64, and macOS Arm64 targets
  - it also makes the support stance explicit: OpenAI-first, guarded compatibility-provider support, best-effort maintenance

- [ ] Add dependency-integrity and supply-chain basics.
  Minimum topics:
  - Gradle dependency verification or equivalent integrity control
  - SBOM generation policy for release artifacts

- [ ] Verify release operations end to end.
  Minimum topics:
  - signing-key readiness
  - CI publish path
  - post-release verification steps
  - rollback/hotfix owner

Recommended execution order:

1. Add `README.md`.
   Rationale:
   biggest public-facing gap and the main blocker for several checklist items at once.
2. Establish changelog policy and first release notes.
   Rationale:
   docs should present a coherent public story before publication mechanics are finalized.
3. Decide how to handle current deprecation and build-warning output.
   Rationale:
   do not let release noise stay ambiguous.
4. Run a clean-room standalone consumer smoke test.
   Rationale:
   highest-value technical validation after the docs/metadata basics.
5. Close the remaining supply-chain and release-ops gaps.
   Rationale:
   these are important, but they are less useful until the artifact is understandable and consumable.

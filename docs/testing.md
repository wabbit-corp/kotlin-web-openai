# Testing And Live Smoke

This project uses two different testing layers:

- deterministic request/response and transport tests
- live smoke tests for surfaces where docs, generated SDKs, or provider behavior are unstable enough that mocks alone are not trustworthy

## Deterministic Tests

The main contract suite lives in `src/commonTest` and focuses on:

- request serialization
- response decoding
- stream assembly
- provider gating
- transport edge cases

These tests are the first line of defense for wire-shape regressions.

## Live Smoke Tests

Live smoke tests exist for higher-risk surfaces such as:

- OpenAI eval create/run/cancel flows
- chat audio
- the stable video lifecycle path
- compatibility-provider sanity checks

Live tests are intentionally visible skips when configuration is absent. They should not silently report "pass" when they were not actually run.

## Running Tests

Typical local runs from the module root:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process jvmTest
```

Run only the main contract suite:

```bash
./gradlew -Dkotlin.compiler.execution.strategy=in-process jvmTest --tests 'one.wabbit.web.openai.OpenAIApiSpec'
```

Run only the OpenAI eval live smoke:

```bash
WABBIT_RUN_LIVE_OPENAI_TEST=true \
./gradlew -Dkotlin.compiler.execution.strategy=in-process jvmTest --tests 'one.wabbit.web.openai.OpenAIEvalsLiveSmokeTest'
```

Run only the chat-audio live smoke:

```bash
WABBIT_RUN_LIVE_OPENAI_TEST=true \
./gradlew -Dkotlin.compiler.execution.strategy=in-process jvmTest --tests 'one.wabbit.web.openai.OpenAIChatAudioLiveSmokeTest'
```

Run only the video live smoke:

```bash
WABBIT_RUN_LIVE_OPENAI_VIDEO_TEST=true \
./gradlew -Dkotlin.compiler.execution.strategy=in-process jvmTest --tests 'one.wabbit.web.openai.OpenAIVideoLiveSmokeTest'
```

## Live Credentials

The live test support code accepts credentials through environment variables first.

Important controls:

- `WABBIT_RUN_LIVE_OPENAI_TEST=true`
- `WABBIT_RUN_LIVE_PROVIDER_TESTS=true`
- `WABBIT_RUN_LIVE_OPENAI_VIDEO_TEST=true`

Optional explicit secret-file paths:

- `WABBIT_LIVE_KEYS_ENV_PATH`
- `WABBIT_LIVE_ROOT_PRIVATE_PATH`

Secret-file fallback is intentionally narrow: repo-root files or explicit paths, not arbitrary parent-directory discovery.

## Why Live Tests Exist

Some OpenAI surfaces have had doc drift or source conflicts serious enough that a purely mock-based contract suite was not sufficient.

Examples:

- eval cancel path ambiguity
- video lifecycle behavior
- chat audio output behavior, where audio payload plus transcript can be present even when plain text content is empty

The live suite exists to keep those areas grounded in observed behavior instead of only in guessed mock payloads.

## Practical Guidance

- Start with deterministic contract tests for any serializer or decoder change.
- Add or update a live smoke when the docs and generated SDKs disagree, or when the platform behavior is known to drift.
- Keep live tests small, direct, and cheap enough to run on purpose rather than by accident.

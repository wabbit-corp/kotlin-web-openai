# Changelog

All notable changes to `kotlin-web-openai` should be recorded in this file.

This file is the release-note source of truth for published versions. GitHub Releases should summarize the same changes, not replace them.

## 0.0.1 - 2026-04-05

Initial public release.

### Added

- Kotlin Multiplatform OpenAI client built on Ktor and `kotlinx.serialization`
- typed OpenAI coverage for Responses, Chat Completions, audio, images, embeddings, files, uploads, vector stores, conversations, evals, videos, models, fine-tuning, and Realtime bootstrap/call control
- streaming helpers for Responses, Chat Completions, images, speech, and transcription
- compatibility-provider support for OpenRouter, Ollama, Azure OpenAI, Groq, xAI, DeepSeek, Anthropic compatibility, and Gemini compatibility
- live smoke coverage for high-risk OpenAI surfaces including eval cancel, chat audio, and video lifecycle checks

### Hardened

- exception body samples are hidden from exception messages by default and redacted when retained
- `extraBody` and multipart extension maps now reject reserved-key collisions instead of silently overriding validated request fields
- singleton-only merged-output helpers now reject ambiguous multi-choice or multi-message output access
- bodyless JSON POSTs now send `{}` instead of invalid empty-string JSON bodies

### Notes

- OpenAI parity is the primary contract
- compatibility-provider support is best-effort and intentionally gated where providers diverge
- see [MIGRATION.md](MIGRATION.md) for caller-visible behavior changes introduced during the pre-release hardening work

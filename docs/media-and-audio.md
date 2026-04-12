# Media And Audio

This page covers the image, speech, transcription, translation, voice, and video surfaces.

## Images

Image methods:

- [generateImage][one.wabbit.web.openai.OpenAIApi.generateImage]
- [editImage][one.wabbit.web.openai.OpenAIApi.editImage]
- [createImageVariation][one.wabbit.web.openai.OpenAIApi.createImageVariation]
- [streamGeneratedImage][one.wabbit.web.openai.OpenAIApi.streamGeneratedImage]
- [streamEditedImage][one.wabbit.web.openai.OpenAIApi.streamEditedImage]

Main request types:

- [ImageGenerateRequest][one.wabbit.web.openai.ImageGenerateRequest]
- [ImageEditRequest][one.wabbit.web.openai.ImageEditRequest]
- [ImageVariationRequest][one.wabbit.web.openai.ImageVariationRequest]

Use the streaming image methods when the provider and request shape support incremental image events.

## Speech

Speech generation uses [SpeechRequest][one.wabbit.web.openai.SpeechRequest] with:

- [createSpeech][one.wabbit.web.openai.OpenAIApi.createSpeech]
- [streamSpeech][one.wabbit.web.openai.OpenAIApi.streamSpeech]

Notable typed behavior:

- `responseFormat` uses the current `pcm` wire value
- `voice` can be a built-in string voice or a custom voice object through [SpeechVoice][one.wabbit.web.openai.SpeechVoice]
- `speed` is validated against the documented range

For streaming speech, use `collectSpeechBytes()` only when you intentionally want a fully buffered result.

## Voice Creation and Consent

Voice endpoints include:

- [createVoice][one.wabbit.web.openai.OpenAIApi.createVoice]
- [createVoiceConsent][one.wabbit.web.openai.OpenAIApi.createVoiceConsent]
- [getVoiceConsent][one.wabbit.web.openai.OpenAIApi.getVoiceConsent]
- [updateVoiceConsent][one.wabbit.web.openai.OpenAIApi.updateVoiceConsent]
- [deleteVoiceConsent][one.wabbit.web.openai.OpenAIApi.deleteVoiceConsent]
- [listVoiceConsents][one.wabbit.web.openai.OpenAIApi.listVoiceConsents]

Both eager and streaming multipart request variants are supported for voice creation and consent creation.

## Transcription and Translation

Transcription methods:

- [createTranscription][one.wabbit.web.openai.OpenAIApi.createTranscription]
- [streamTranscription][one.wabbit.web.openai.OpenAIApi.streamTranscription]

Translation methods:

- [createTranslation][one.wabbit.web.openai.OpenAIApi.createTranslation]

Main request types:

- [TranscriptionRequest][one.wabbit.web.openai.TranscriptionRequest]
- [StreamingTranscriptionRequest][one.wabbit.web.openai.StreamingTranscriptionRequest]
- [TranslationRequest][one.wabbit.web.openai.TranslationRequest]
- [StreamingTranslationRequest][one.wabbit.web.openai.StreamingTranslationRequest]

The transcription layer includes typed support for diarized output. When the API provides an explicit discriminator such as `response_format`, the decoder follows it directly rather than guessing from segment shape.

## Video

Video methods:

- [createVideo][one.wabbit.web.openai.OpenAIApi.createVideo]
- [createVideoCharacter][one.wabbit.web.openai.OpenAIApi.createVideoCharacter]
- [getVideoCharacter][one.wabbit.web.openai.OpenAIApi.getVideoCharacter]
- [editVideo][one.wabbit.web.openai.OpenAIApi.editVideo]
- [extendVideo][one.wabbit.web.openai.OpenAIApi.extendVideo]
- [remixVideo][one.wabbit.web.openai.OpenAIApi.remixVideo]
- [listVideos][one.wabbit.web.openai.OpenAIApi.listVideos]
- [getVideo][one.wabbit.web.openai.OpenAIApi.getVideo]
- [deleteVideo][one.wabbit.web.openai.OpenAIApi.deleteVideo]
- [downloadVideoContent][one.wabbit.web.openai.OpenAIApi.downloadVideoContent]
- [downloadVideoContentTo][one.wabbit.web.openai.OpenAIApi.downloadVideoContentTo]

Current request modeling follows the modern OpenAI field names, including `input_reference` and the documented `variant` query parameter for content download.

## Practical Rules

- Use streaming uploads for large audio or video-related multipart payloads.
- Prefer streaming download helpers for large binary responses.
- Treat media helpers that return `ByteArray` as eager convenience methods, not as the only path.

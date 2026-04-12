# Provider-Specific Surfaces

Most of `kotlin-web-openai` is provider-gated around the OpenAI-native contract. A small part of the module exposes explicitly provider-specific APIs that are not meant to pretend they are portable.

## DeepSeek FIM

DeepSeek-specific fill-in-the-middle completion support is exposed through:

- [createDeepSeekFimCompletion][one.wabbit.web.openai.OpenAIApi.createDeepSeekFimCompletion]
- [DeepSeekFimCompletionRequest][one.wabbit.web.openai.DeepSeekFimCompletionRequest]
- [DeepSeekFimCompletionResponse][one.wabbit.web.openai.DeepSeekFimCompletionResponse]

This surface is intentionally not folded into the ordinary Chat Completions or Responses request models.

## xAI Extras

xAI-specific APIs currently exposed by the module:

- [createXaiBatch][one.wabbit.web.openai.OpenAIApi.createXaiBatch]
- [addXaiBatchRequests][one.wabbit.web.openai.OpenAIApi.addXaiBatchRequests]
- [getXaiBatch][one.wabbit.web.openai.OpenAIApi.getXaiBatch]
- [listXaiBatches][one.wabbit.web.openai.OpenAIApi.listXaiBatches]
- [listXaiBatchRequests][one.wabbit.web.openai.OpenAIApi.listXaiBatchRequests]
- [listXaiBatchResults][one.wabbit.web.openai.OpenAIApi.listXaiBatchResults]
- [cancelXaiBatch][one.wabbit.web.openai.OpenAIApi.cancelXaiBatch]
- [createXaiSpeech][one.wabbit.web.openai.OpenAIApi.createXaiSpeech]
- [listXaiVoices][one.wabbit.web.openai.OpenAIApi.listXaiVoices]
- [getXaiVoice][one.wabbit.web.openai.OpenAIApi.getXaiVoice]

These APIs live in the module because they are useful to callers already using the provider abstraction, but they are intentionally named as provider-specific methods rather than being presented as generic OpenAI-equivalent surfaces.

## Why These Are Separate

The module tries to avoid two failure modes:

- silently pretending a provider-specific contract is universally portable
- hiding useful provider-specific functionality behind untyped raw JSON only

These explicitly named methods are the compromise: typed where useful, but honest about provider scope.

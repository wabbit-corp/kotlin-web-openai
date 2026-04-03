package one.wabbit.web.openai

private fun OpenAIProvider.requireSpecificProvider(
    feature: String,
    supported: Boolean,
    unsupportedMessage: String = "$feature is not exposed for $id in this client",
) {
    require(supported) { unsupportedMessage }
}

internal fun OpenAIProvider.requireProviderCapability(
    feature: String,
    supported: Boolean,
) {
    requireSpecificProvider(feature, supported)
}

internal fun OpenAIProvider.requireAudioApiSupport() {
    requireProviderCapability("the audio API", capabilities.audioApi)
}

internal fun OpenAIProvider.requireStatefulResponsesApiSupport() {
    requireProviderCapability(
        "stateful Responses retrieval endpoints",
        capabilities.responsesApi && capabilities.statefulResponses,
    )
}

internal fun OpenAIProvider.requireEmbeddingsApiSupport() {
    requireProviderCapability("embeddings", capabilities.embeddingsApi)
}

internal fun OpenAIProvider.requireModelsApiSupport() {
    requireProviderCapability("models", capabilities.modelsApi)
}

internal fun OpenAIProvider.requireImagesApiSupport() {
    requireProviderCapability("the images API", capabilities.imagesApi)
}

internal fun OpenAIProvider.requireImageEditsApiSupport() {
    requireSpecificProvider(
        "the image edits endpoint",
        this is OpenAIProvider.OpenAI || this is OpenAIProvider.XAI,
    )
}

internal fun OpenAIProvider.requireImageStreamingApiSupport() {
    requireSpecificProvider(
        "image streaming",
        this is OpenAIProvider.OpenAI,
    )
}

internal fun OpenAIProvider.requireModerationsApiSupport() {
    requireProviderCapability("the moderations API", capabilities.moderationsApi)
}

internal fun OpenAIProvider.requireBatchesApiSupport() {
    requireProviderCapability("the batches API", capabilities.batchesApi)
}

internal fun OpenAIProvider.requireFineTuningApiSupport() {
    requireProviderCapability("the fine-tuning API", capabilities.fineTuningApi)
}

internal fun OpenAIProvider.requireRealtimeBootstrapApiSupport() {
    requireProviderCapability("realtime session bootstrap", capabilities.realtimeBootstrapApi)
}

internal fun OpenAIProvider.requireEvalsApiSupport() {
    requireProviderCapability("the evals API", capabilities.evalsApi)
}

internal fun OpenAIProvider.requireVideosApiSupport() {
    requireProviderCapability("the videos API", capabilities.videosApi)
}

internal fun OpenAIProvider.requireXaiBatchApiSupport() {
    requireProviderCapability("the xAI batch API", capabilities.xaiBatchApi)
}

internal fun OpenAIProvider.requireXaiTtsApiSupport() {
    requireProviderCapability("the xAI TTS API", capabilities.xaiTtsApi)
}

internal fun OpenAIProvider.requireVectorStoresApiSupport() {
    requireProviderCapability("the vector stores API", capabilities.vectorStoresApi)
}

internal fun OpenAIProvider.requireOpenAiOnly(feature: String) {
    requireSpecificProvider(
        feature,
        this is OpenAIProvider.OpenAI,
        unsupportedMessage = "$feature is only exposed for the OpenAI provider in this client",
    )
}

internal fun OpenAIProvider.requireStoredChatCompletionsApiSupport() {
    requireOpenAiOnly("stored chat completions")
}

internal fun OpenAIProvider.requireConversationsApiSupport() {
    requireOpenAiOnly("conversations")
}

internal fun OpenAIProvider.requireDeepSeekOnly(feature: String) {
    requireSpecificProvider(
        feature,
        this is OpenAIProvider.DeepSeek,
        unsupportedMessage = "$feature is only exposed for the DeepSeek provider in this client",
    )
}

internal fun OpenAIProvider.requireStatelessResponses(
    previousResponseId: ResponseId?,
    store: Boolean?,
) {
    if (!capabilities.responsesApi || capabilities.statefulResponses) return

    val label =
        when (this) {
            is OpenAIProvider.OpenRouter -> "OpenRouter Responses beta"
            is OpenAIProvider.Ollama -> "Ollama Responses compatibility"
            is OpenAIProvider.Groq -> "Groq Responses compatibility"
            else -> "$id Responses compatibility"
        }

    require(previousResponseId == null) {
        "$label is stateless in this client; previous_response_id is not supported"
    }
    require(store != true) {
        "$label is stateless in this client; store=true is not supported"
    }
}

internal fun OpenAIProvider.requireAzureChatCompatibility(
    reasoningEffort: ReasoningEffort?,
    temperature: Double?,
    topP: Double?,
    presencePenalty: Double?,
    frequencyPenalty: Double?,
    logprobs: Boolean?,
    topLogprobs: Int?,
) {
    if (this !is OpenAIProvider.Azure || reasoningEffort == null) return

    require(temperature == null) {
        "Azure reasoning chat compatibility does not support temperature when reasoning_effort is set"
    }
    require(topP == null) {
        "Azure reasoning chat compatibility does not support top_p when reasoning_effort is set"
    }
    require(presencePenalty == null) {
        "Azure reasoning chat compatibility does not support presence_penalty when reasoning_effort is set"
    }
    require(frequencyPenalty == null) {
        "Azure reasoning chat compatibility does not support frequency_penalty when reasoning_effort is set"
    }
    require(logprobs != true) {
        "Azure reasoning chat compatibility does not support logprobs when reasoning_effort is set"
    }
    require(topLogprobs == null) {
        "Azure reasoning chat compatibility does not support top_logprobs when reasoning_effort is set"
    }
}

internal fun OpenAIProvider.requireResponseStreamingCompatibility(
    usesImageGenerationTool: Boolean,
) {
    if (this !is OpenAIProvider.Azure) return

    require(!usesImageGenerationTool) {
        "Azure Responses image_generation tool does not support streaming in this client; use the image generation API directly"
    }
}

internal fun OpenAIProvider.requireImageGenerationCompatibility(
    responseFormat: ImageResponseFormat?,
) {
    if (this !is OpenAIProvider.Ollama) return

    require(responseFormat == null || responseFormat == ImageResponseFormat.B64_JSON) {
        "Ollama image generation only supports response_format=b64_json in this client"
    }
}

internal fun OpenAIProvider.requireImageVariationsApiSupport() {
    require(this is OpenAIProvider.OpenAI) {
        when (this) {
            is OpenAIProvider.XAI ->
                "xAI compatibility does not expose the image variations endpoint in this client"
            is OpenAIProvider.Gemini ->
                "Gemini compatibility does not expose the image variations endpoint in this client"
            is OpenAIProvider.Ollama ->
                "Ollama compatibility does not expose the image variations endpoint in this client"
            else ->
                "$id does not expose the image variations endpoint in this client"
        }
    }
}

internal fun OpenAIProvider.requireFilesApiSupport() {
    require(capabilities.filesApi) {
        if (this is OpenAIProvider.Gemini) {
            "Gemini compatibility does not expose file upload/download endpoints in this client; use the native Google file APIs instead"
        } else {
            "$id does not expose the files API in this client"
        }
    }
}

internal fun OpenAIProvider.requireUploadsApiSupport() {
    require(capabilities.uploadsApi) {
        if (this is OpenAIProvider.Gemini) {
            "Gemini compatibility does not expose multipart uploads in this client; use the native Google file APIs instead"
        } else {
            "$id does not expose the uploads API in this client"
        }
    }
}

package one.wabbit.web.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class DeepSeekThinkingConfig(
    val type: String,
) {
    init {
        require(type.isNotBlank()) { "DeepSeek thinking type must not be blank" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("type", type)
        }
}

data class DeepSeekRequestOptions(
    val thinking: DeepSeekThinkingConfig? = null,
    val extraBody: JsonExtras? = null,
) : ChatProviderOptions {
    override fun applyTo(builder: kotlinx.serialization.json.JsonObjectBuilder) {
        thinking?.let { builder.put("thinking", it.toJson()) }
        builder.putJsonExtras(extraBody)
    }

    override fun requireCompatibleWith(provider: OpenAIProvider) {
        require(provider is OpenAIProvider.DeepSeek) {
            "DeepSeek request options require DeepSeek provider"
        }
    }
}

data class DeepSeekFimCompletionRequest(
    val model: ModelId,
    val prompt: String,
    val suffix: String? = null,
    val maxTokens: Int? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val stop: String? = null,
    val echo: Boolean? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val logprobs: Int? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(prompt.isNotBlank()) { "DeepSeek FIM prompt must not be blank" }
        require(suffix == null || suffix.isNotBlank()) { "DeepSeek FIM suffix must not be blank when set" }
        require(stop == null || stop.isNotBlank()) { "DeepSeek FIM stop must not be blank when set" }
        require(maxTokens == null || maxTokens > 0) { "DeepSeek FIM maxTokens must be positive when set" }
        require(logprobs == null || logprobs in 0..20) { "DeepSeek FIM logprobs must be between 0 and 20 when set" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("model", model.value)
            put("prompt", prompt)
            suffix?.let { put("suffix", it) }
            maxTokens?.let { put("max_tokens", it) }
            temperature?.let { put("temperature", it) }
            topP?.let { put("top_p", it) }
            stop?.let { put("stop", it) }
            echo?.let { put("echo", it) }
            frequencyPenalty?.let { put("frequency_penalty", it) }
            presencePenalty?.let { put("presence_penalty", it) }
            logprobs?.let { put("logprobs", it) }
            putJsonExtras(extraBody)
        }
}

@Serializable
data class DeepSeekFimChoice(
    val text: String? = null,
    val index: Int? = null,
    @SerialName("finish_reason")
    @Serializable(with = ChatCompletionFinishReasonSerializer::class)
    val finishReason: ChatCompletionFinishReason? = null,
)

@Serializable
data class DeepSeekFimUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

@Serializable
data class DeepSeekFimCompletionResponse(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    val created: Long? = null,
    val model: String? = null,
    val choices: List<DeepSeekFimChoice> = emptyList(),
    val usage: DeepSeekFimUsage? = null,
) {
    fun outputText(): String = choices.mapNotNull { it.text }.joinToString(separator = "")
}

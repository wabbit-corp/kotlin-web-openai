package one.wabbit.web.openai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

enum class ChatRole(val wireName: String) {
    SYSTEM("system"),
    DEVELOPER("developer"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
}

sealed interface ChatMessageContent {
    fun toJson(): JsonElement

    data class Text(
        val text: String,
    ) : ChatMessageContent {
        init {
            require(text.isNotBlank()) { "chat text content must not be blank" }
        }

        override fun toJson(): JsonElement = JsonPrimitive(text)
    }

    data class Parts(
        val parts: List<ChatMessagePart>,
    ) : ChatMessageContent {
        init {
            require(parts.isNotEmpty()) { "chat message parts must not be empty" }
        }

        override fun toJson(): JsonElement =
            JsonArray(parts.map { it.toJson() })
    }
}

sealed interface ChatMessagePart {
    fun toJson(): JsonObject

    data class Text(
        val text: String,
    ) : ChatMessagePart {
        init {
            require(text.isNotBlank()) { "chat text part must not be blank" }
        }

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "text")
                put("text", text)
            }
    }

    data class ImageUrl(
        val url: String,
        val detail: InputImageDetail? = null,
    ) : ChatMessagePart {
        init {
            require(url.isNotBlank()) { "image url must not be blank" }
        }

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") {
                    put("url", url)
                    detail?.let { put("detail", it.wireName) }
                }
            }
    }

    data class InputAudio(
        val data: String,
        val format: InputAudioFormat,
    ) : ChatMessagePart {
        init {
            require(data.isNotBlank()) { "audio data must not be blank" }
        }

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "input_audio")
                putJsonObject("input_audio") {
                    put("data", data)
                    put("format", format.wireName)
                }
            }
    }

    data class File(
        val fileId: FileId? = null,
        val fileData: String? = null,
        val filename: String? = null,
    ) : ChatMessagePart {
        init {
            require(fileId != null || fileData != null) { "chat file part must provide fileId or fileData" }
            require(fileData == null || fileData.isNotBlank()) { "chat file part fileData must not be blank when set" }
            require(filename == null || filename.isNotBlank()) { "chat file part filename must not be blank when set" }
        }

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "file")
                putJsonObject("file") {
                    fileId?.let { put("file_id", it.value) }
                    fileData?.let { put("file_data", it) }
                    filename?.let { put("filename", it) }
                }
            }
    }

    data class Raw(
        val json: JsonObject,
    ) : ChatMessagePart {
        override fun toJson(): JsonObject = json
    }
}

data class ChatToolCall(
    val id: ToolCallId,
    val functionName: String,
    val arguments: String,
) {
    init {
        require(functionName.isNotBlank()) { "chat tool call functionName must not be blank" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("id", id.value)
            put("type", "function")
            putJsonObject("function") {
                put("name", functionName)
                put("arguments", arguments)
            }
        }
}

data class ChatMessage(
    val role: ChatRole,
    val content: ChatMessageContent? = null,
    val name: String? = null,
    val toolCallId: ToolCallId? = null,
    val toolCalls: List<ChatToolCall> = emptyList(),
    val refusal: String? = null,
    val prefix: Boolean? = null,
    val reasoningContent: String? = null,
) {
    init {
        require(content != null || toolCalls.isNotEmpty() || refusal != null) {
            "chat message must provide content, toolCalls, or refusal"
        }
        require(name == null || name.isNotBlank()) { "chat message name must not be blank when set" }
        require(reasoningContent == null || reasoningContent.isNotBlank()) {
            "chat message reasoningContent must not be blank when set"
        }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("role", role.wireName)
            content?.let { put("content", it.toJson()) }
            name?.let { put("name", it) }
            toolCallId?.let { put("tool_call_id", it.value) }
            if (toolCalls.isNotEmpty()) {
                putJsonArray("tool_calls") {
                    toolCalls.forEach { add(it.toJson()) }
                }
            }
            refusal?.let { put("refusal", it) }
            prefix?.let { put("prefix", it) }
            reasoningContent?.let { put("reasoning_content", it) }
        }
}

sealed interface ChatResponseFormat {
    fun toJson(): JsonObject

    data object Text : ChatResponseFormat {
        override fun toJson(): JsonObject = buildJsonObject { put("type", "text") }
    }

    data object JsonObjectFormat : ChatResponseFormat {
        override fun toJson(): JsonObject = buildJsonObject { put("type", "json_object") }
    }

    data class JsonSchema(
        val name: String,
        val schema: JsonObject,
        val description: String? = null,
        val strict: Boolean? = null,
    ) : ChatResponseFormat {
        init {
            require(name.isNotBlank()) { "chat json schema name must not be blank" }
        }

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "json_schema")
                putJsonObject("json_schema") {
                    put("name", name)
                    put("schema", schema)
                    description?.let { put("description", it) }
                    strict?.let { put("strict", it) }
                }
            }
    }
}

sealed interface ChatCompletionFinishReason {
    val wireName: String

    data object Stop : ChatCompletionFinishReason {
        override val wireName: String = "stop"
    }

    data object Length : ChatCompletionFinishReason {
        override val wireName: String = "length"
    }

    data object ToolCalls : ChatCompletionFinishReason {
        override val wireName: String = "tool_calls"
    }

    data object ContentFilter : ChatCompletionFinishReason {
        override val wireName: String = "content_filter"
    }

    data object FunctionCall : ChatCompletionFinishReason {
        override val wireName: String = "function_call"
    }

    data class Unknown(
        override val wireName: String,
    ) : ChatCompletionFinishReason
}

internal object ChatCompletionFinishReasonSerializer :
    PreservingWireValueSerializer<ChatCompletionFinishReason>(
        serialName = "one.wabbit.web.openai.ChatCompletionFinishReason?",
        knownValues =
            listOf(
                ChatCompletionFinishReason.Stop,
                ChatCompletionFinishReason.Length,
                ChatCompletionFinishReason.ToolCalls,
                ChatCompletionFinishReason.ContentFilter,
                ChatCompletionFinishReason.FunctionCall,
            ),
        wireName = ChatCompletionFinishReason::wireName,
        unknown = ChatCompletionFinishReason::Unknown,
    )

data class GeminiRequestOptions(
    val cachedContent: String? = null,
    val safetySettings: JsonArray? = null,
    val thinkingConfig: JsonObject? = null,
    val extraBody: JsonExtras? = null,
) : ChatProviderOptions {
    init {
        require(cachedContent == null || cachedContent.isNotBlank()) { "Gemini cachedContent must not be blank when set" }
    }

    override fun applyTo(builder: kotlinx.serialization.json.JsonObjectBuilder) {
        val googleBody =
            buildJsonObject {
                cachedContent?.let { put("cached_content", it) }
                safetySettings?.let { put("safety_settings", it) }
                thinkingConfig?.let { put("thinking_config", it) }
                putJsonExtras(extraBody)
            }
        if (googleBody.isNotEmpty()) {
            builder.putJsonObject("extra_body") {
                put("google", googleBody)
            }
        }
    }

    override fun requireCompatibleWith(provider: OpenAIProvider) {
        require(provider is OpenAIProvider.Gemini) {
            "Gemini request options require Gemini provider"
        }
    }
}

data class AnthropicThinkingConfig(
    val type: String,
    val budgetTokens: Int? = null,
) {
    init {
        require(type.isNotBlank()) { "Anthropic thinking type must not be blank" }
        require(budgetTokens == null || budgetTokens > 0) { "Anthropic thinking budgetTokens must be positive when set" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("type", type)
            budgetTokens?.let { put("budget_tokens", it) }
        }
}

data class AnthropicRequestOptions(
    val thinking: AnthropicThinkingConfig? = null,
    val extraBody: JsonExtras? = null,
) : ChatProviderOptions {
    override fun applyTo(builder: kotlinx.serialization.json.JsonObjectBuilder) {
        thinking?.let { builder.put("thinking", it.toJson()) }
        builder.putJsonExtras(extraBody)
    }

    override fun requireCompatibleWith(provider: OpenAIProvider) {
        require(provider is OpenAIProvider.Anthropic) {
            "Anthropic request options require Anthropic provider"
        }
    }
}

enum class ChatVerbosity(val wireName: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}

data class ChatWebSearchOptions(
    val searchContextSize: SearchContextSize? = null,
    val userLocation: JsonObject? = null,
    val extraBody: JsonExtras? = null,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            searchContextSize?.let { put("search_context_size", it.wireName) }
            userLocation?.let { put("user_location", it) }
            putJsonExtras(extraBody)
        }
}

data class ChatCompletionRequest(
    val model: ModelId,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxCompletionTokens: Int? = null,
    val n: Int? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val tools: List<ResponseTool> = emptyList(),
    val toolChoice: ResponseToolChoice? = null,
    val parallelToolCalls: Boolean? = null,
    val responseFormat: ChatResponseFormat? = null,
    val metadata: Map<String, String> = emptyMap(),
    val store: Boolean? = null,
    val reasoningEffort: ReasoningEffort? = null,
    val verbosity: ChatVerbosity? = null,
    val webSearchOptions: ChatWebSearchOptions? = null,
    val logprobs: Boolean? = null,
    val topLogprobs: Int? = null,
    val stop: List<String> = emptyList(),
    val streamOptions: JsonObject? = null,
    val user: String? = null,
    val providerOptions: ChatProviderOptions? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(messages.isNotEmpty()) { "chat completion messages must not be empty" }
        require(maxCompletionTokens == null || maxCompletionTokens > 0) {
            "maxCompletionTokens must be positive when set"
        }
        require(n == null || n > 0) { "n must be positive when set" }
        require(metadata.keys.all { it.isNotBlank() }) { "chat completion metadata keys must not be blank" }
        require(topLogprobs == null || topLogprobs in 0..20) {
            "chat completion topLogprobs must be between 0 and 20 when set"
        }
        require(topLogprobs == null || logprobs == true) {
            "chat completion topLogprobs requires logprobs=true"
        }
        require(stop.all { it.isNotBlank() }) { "stop values must not be blank" }
        require(user == null || user.isNotBlank()) { "chat completion user must not be blank when set" }
    }

    fun requireCompatibleWith(provider: OpenAIProvider) {
        require(provider.capabilities.chatCompletions) {
            "${provider.id} does not expose chat completions in this client"
        }
        val hasPrefixMessage = messages.any { it.prefix == true }
        val hasReasoningSeed = messages.any { !it.reasoningContent.isNullOrBlank() }
        if (hasPrefixMessage || hasReasoningSeed) {
            require(provider is OpenAIProvider.DeepSeek) {
                "message prefix/reasoning_content fields are only exposed for DeepSeek compatibility in this client"
            }
        }
        if (responseFormat != null) {
            require(provider.capabilities.structuredOutputs) {
                "${provider.id} does not expose structured chat-completion output controls in this client"
            }
        }
        providerOptions?.requireCompatibleWith(provider)
        provider.requireAzureChatCompatibility(
            reasoningEffort = reasoningEffort,
            temperature = temperature,
            topP = topP,
            presencePenalty = presencePenalty,
            frequencyPenalty = frequencyPenalty,
            logprobs = logprobs,
            topLogprobs = topLogprobs,
        )
        when (provider) {
            is OpenAIProvider.Anthropic,
            -> {
                require(n == null || n == 1) { "Anthropic compatibility requires n to be exactly 1 when set" }
                require(messages.none { it.hasAudioInput() }) {
                    "Anthropic compatibility does not support audio input parts"
                }
            }
            is OpenAIProvider.Groq -> {
                require(n == null || n == 1) { "Groq compatibility requires n to be exactly 1 when set" }
                require(messages.none { it.name != null }) {
                    "Groq compatibility does not support messages[].name in this client"
                }
                require(logprobs != true) {
                    "Groq compatibility does not support chat-completion logprobs in this client"
                }
                require(topLogprobs == null) {
                    "Groq compatibility does not support chat-completion top_logprobs in this client"
                }
            }
            else -> Unit
        }
    }

    fun toJson(stream: Boolean = false): JsonObject =
        buildJsonObject {
            put("model", model.value)
            putJsonArray("messages") {
                messages.forEach { add(it.toJson()) }
            }
            temperature?.let { put("temperature", it) }
            topP?.let { put("top_p", it) }
            maxCompletionTokens?.let { put("max_completion_tokens", it) }
            n?.let { put("n", it) }
            presencePenalty?.let { put("presence_penalty", it) }
            frequencyPenalty?.let { put("frequency_penalty", it) }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { add(it.toJson()) }
                }
            }
            toolChoice?.let { put("tool_choice", it.toJson()) }
            parallelToolCalls?.let { put("parallel_tool_calls", it) }
            responseFormat?.let { put("response_format", it.toJson()) }
            if (metadata.isNotEmpty()) {
                putJsonObject("metadata") {
                    metadata.forEach { (key, value) -> put(key, value) }
                }
            }
            store?.let { put("store", it) }
            reasoningEffort?.let { put("reasoning_effort", it.wireName) }
            verbosity?.let { put("verbosity", it.wireName) }
            webSearchOptions?.let { put("web_search_options", it.toJson()) }
            logprobs?.let { put("logprobs", it) }
            topLogprobs?.let { put("top_logprobs", it) }
            if (stop.isNotEmpty()) {
                putJsonArray("stop") {
                    stop.forEach { add(JsonPrimitive(it)) }
                }
            }
            streamOptions?.let { put("stream_options", it) }
            user?.let { put("user", it) }
            if (stream) put("stream", true)
            providerOptions?.applyTo(this)
            putJsonExtras(extraBody)
        }
}

private fun ChatMessage.hasAudioInput(): Boolean =
    when (val current = content) {
        is ChatMessageContent.Parts -> current.parts.any { it is ChatMessagePart.InputAudio }
        else -> false
    }

@Serializable
data class ChatCompletionPromptTokensDetails(
    @SerialName("cached_tokens") val cachedTokens: Int? = null,
    @SerialName("audio_tokens") val audioTokens: Int? = null,
)

@Serializable
data class ChatCompletionCompletionTokensDetails(
    @SerialName("reasoning_tokens") val reasoningTokens: Int? = null,
    @SerialName("audio_tokens") val audioTokens: Int? = null,
    @SerialName("accepted_prediction_tokens") val acceptedPredictionTokens: Int? = null,
    @SerialName("rejected_prediction_tokens") val rejectedPredictionTokens: Int? = null,
)

@Serializable
data class ChatCompletionUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
    @SerialName("prompt_tokens_details") val promptTokensDetails: ChatCompletionPromptTokensDetails? = null,
    @SerialName("completion_tokens_details") val completionTokensDetails: ChatCompletionCompletionTokensDetails? = null,
)

@Serializable
data class AzureContentFilterResult(
    val filtered: Boolean? = null,
    val severity: String? = null,
    val detected: Boolean? = null,
)

@Serializable
data class AzureContentFilterOffsets(
    @SerialName("check_offset") val checkOffset: Int? = null,
    @SerialName("start_offset") val startOffset: Int? = null,
    @SerialName("end_offset") val endOffset: Int? = null,
)

@Serializable
data class AzureContentFilterResults(
    val hate: AzureContentFilterResult? = null,
    @SerialName("self_harm") val selfHarm: AzureContentFilterResult? = null,
    val sexual: AzureContentFilterResult? = null,
    val violence: AzureContentFilterResult? = null,
    val profanity: AzureContentFilterResult? = null,
    val jailbreak: AzureContentFilterResult? = null,
    @SerialName("protected_material_text") val protectedMaterialText: AzureContentFilterResult? = null,
    @SerialName("protected_material_code") val protectedMaterialCode: AzureContentFilterResult? = null,
    @SerialName("custom_blocklists") val customBlocklists: JsonArray? = null,
)

@Serializable
data class AzurePromptFilterResult(
    @SerialName("prompt_index") val promptIndex: Int? = null,
    @SerialName("content_filter_results") val contentFilterResults: AzureContentFilterResults? = null,
)

@Serializable
data class ChatCompletionToolCallFunction(
    val name: String? = null,
    val arguments: String? = null,
)

sealed interface ChatCompletionToolCallType {
    val wireName: String

    data object Function : ChatCompletionToolCallType {
        override val wireName: String = "function"
    }

    data class Unknown(
        override val wireName: String,
    ) : ChatCompletionToolCallType
}

internal object ChatCompletionToolCallTypeSerializer :
    PreservingWireValueSerializer<ChatCompletionToolCallType>(
        serialName = "one.wabbit.web.openai.ChatCompletionToolCallType?",
        knownValues = listOf(ChatCompletionToolCallType.Function),
        wireName = ChatCompletionToolCallType::wireName,
        unknown = ChatCompletionToolCallType::Unknown,
    )

@Serializable
data class ChatCompletionLogProb(
    val token: String? = null,
    val logprob: Double? = null,
    val bytes: List<Int>? = null,
)

@Serializable
data class ChatCompletionLogProbContent(
    val token: String? = null,
    val logprob: Double? = null,
    val bytes: List<Int>? = null,
    @SerialName("top_logprobs") val topLogprobs: List<ChatCompletionLogProb> = emptyList(),
)

@Serializable
data class ChatCompletionLogProbs(
    val content: List<ChatCompletionLogProbContent> = emptyList(),
    val refusal: List<ChatCompletionLogProbContent> = emptyList(),
)

@Serializable
data class ChatCompletionToolCallDelta(
    val index: Int? = null,
    val id: String? = null,
    @Serializable(with = ChatCompletionToolCallTypeSerializer::class)
    val type: ChatCompletionToolCallType? = null,
    val function: ChatCompletionToolCallFunction? = null,
)

@Serializable
data class ChatCompletionMessageObject(
    val role: String? = null,
    val content: String? = null,
    val refusal: String? = null,
    val annotations: JsonArray? = null,
    @SerialName("tool_calls") val toolCalls: List<ChatCompletionToolCallDelta> = emptyList(),
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

@Serializable
data class ChatCompletionChoice(
    val index: Int,
    val message: ChatCompletionMessageObject? = null,
    @SerialName("content_filter_results") val contentFilterResults: AzureContentFilterResults? = null,
    @SerialName("content_filter_offsets") val contentFilterOffsets: AzureContentFilterOffsets? = null,
    val logprobs: ChatCompletionLogProbs? = null,
    @SerialName("finish_reason")
    @Serializable(with = ChatCompletionFinishReasonSerializer::class)
    val finishReason: ChatCompletionFinishReason? = null,
)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    val created: Long? = null,
    val model: String? = null,
    @SerialName("prompt_filter_results") val promptFilterResults: List<AzurePromptFilterResult> = emptyList(),
    @SerialName("prompt_annotations") val promptAnnotations: List<AzurePromptFilterResult> = emptyList(),
    val choices: List<ChatCompletionChoice> = emptyList(),
    val usage: ChatCompletionUsage? = null,
    @SerialName("system_fingerprint") val systemFingerprint: String? = null,
) {
    fun outputText(): String = choices.mapNotNull { it.message?.content }.joinToString(separator = "")

    fun reasoningContent(): String = choices.mapNotNull { it.message?.reasoningContent }.joinToString(separator = "")

    fun annotations(): List<JsonElement> = choices.flatMap { it.message?.annotations?.toList() ?: emptyList() }

    fun azurePromptFilters(): List<AzurePromptFilterResult> =
        if (promptFilterResults.isNotEmpty()) promptFilterResults else promptAnnotations

    fun toolCalls(): List<ChatCompletionToolCallDelta> = choices.flatMap { it.message?.toolCalls ?: emptyList() }

    fun outputJsonElementOrNull(): JsonElement? =
        outputText().takeIf { it.isNotBlank() }?.let { runCatching { OpenAIJson.parseToJsonElement(it) }.getOrNull() }

    inline fun <reified T> decodeOutputJson(): T =
        OpenAIJson.decodeFromJsonElement(outputJsonElementOrNull() ?: error("Chat completion output is not valid JSON"))
}

@Serializable
data class ChatCompletionChunkDelta(
    val role: String? = null,
    val content: String? = null,
    val annotations: JsonArray? = null,
    @SerialName("tool_calls") val toolCalls: List<ChatCompletionToolCallDelta> = emptyList(),
    val refusal: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
)

@Serializable
data class ChatCompletionChunkChoice(
    val index: Int,
    val delta: ChatCompletionChunkDelta? = null,
    @SerialName("content_filter_results") val contentFilterResults: AzureContentFilterResults? = null,
    @SerialName("content_filter_offsets") val contentFilterOffsets: AzureContentFilterOffsets? = null,
    val logprobs: ChatCompletionLogProbs? = null,
    @SerialName("finish_reason")
    @Serializable(with = ChatCompletionFinishReasonSerializer::class)
    val finishReason: ChatCompletionFinishReason? = null,
)

@Serializable
data class ChatCompletionChunk(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    val created: Long? = null,
    val model: String? = null,
    val choices: List<ChatCompletionChunkChoice> = emptyList(),
)

sealed interface ChatCompletionStreamEvent {
    data class Chunk(val chunk: ChatCompletionChunk) : ChatCompletionStreamEvent
    data object Done : ChatCompletionStreamEvent
    data class Error(val error: ResponseApiError) : ChatCompletionStreamEvent
    data class Unknown(val event: String? = null, val rawData: String = "") : ChatCompletionStreamEvent
}

fun parseChatCompletionStreamEvent(sse: ServerSentEvent): ChatCompletionStreamEvent {
    val body = sse.data.value.trim()
    if (body == "[DONE]") return ChatCompletionStreamEvent.Done
    if (body.isEmpty()) return ChatCompletionStreamEvent.Unknown(event = sse.event)
    val payload = runCatching { OpenAIJson.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: return if (sse.event == "error") {
            ChatCompletionStreamEvent.Error(ResponseApiError(message = body))
        } else {
            ChatCompletionStreamEvent.Unknown(event = sse.event, rawData = body)
        }
    payload.toResponseApiErrorOrNull()?.let { error ->
        return ChatCompletionStreamEvent.Error(error)
    }
    return ChatCompletionStreamEvent.Chunk(OpenAIJson.decodeFromJsonElement<ChatCompletionChunk>(payload))
}

fun parseChatCompletionStreamEvents(source: String): List<ChatCompletionStreamEvent> =
    parseServerSentEvents(source).map(::parseChatCompletionStreamEvent)

data class ChatCompletionStreamAssembly(
    val outputText: String = "",
    val reasoningContent: String = "",
    val refusalText: String = "",
    val toolCallArguments: Map<String, String> = emptyMap(),
    val finishReasons: Map<Int, ChatCompletionFinishReason?> = emptyMap(),
    val lastChunk: ChatCompletionChunk? = null,
    val error: ResponseApiError? = null,
    val isDone: Boolean = false,
)

class ChatCompletionStreamAccumulator {
    private val outputText = StringBuilder()
    private val reasoningContent = StringBuilder()
    private val refusalText = StringBuilder()
    private val toolCallArguments = linkedMapOf<String, StringBuilder>()
    private val finishReasons = linkedMapOf<Int, ChatCompletionFinishReason?>()
    private var lastChunk: ChatCompletionChunk? = null
    private var error: ResponseApiError? = null
    private var isDone: Boolean = false

    fun apply(event: ChatCompletionStreamEvent): ChatCompletionStreamAssembly {
        when (event) {
            is ChatCompletionStreamEvent.Chunk -> {
                lastChunk = event.chunk
                event.chunk.choices.forEach { choice ->
                    choice.delta?.content?.let { outputText.append(it) }
                    choice.delta?.reasoningContent?.let { reasoningContent.append(it) }
                    choice.delta?.refusal?.let { refusalText.append(it) }
                    choice.delta?.toolCalls?.forEach { call ->
                        val key = call.id ?: "choice:${choice.index}:tool:${call.index ?: 0}"
                        call.function?.arguments?.let { argDelta ->
                            toolCallArguments.getOrPut(key) { StringBuilder() }.append(argDelta)
                        }
                    }
                    finishReasons[choice.index] = choice.finishReason
                }
            }
            is ChatCompletionStreamEvent.Error -> error = event.error
            is ChatCompletionStreamEvent.Done -> isDone = true
            is ChatCompletionStreamEvent.Unknown -> Unit
        }
        return snapshot()
    }

    fun snapshot(): ChatCompletionStreamAssembly =
        ChatCompletionStreamAssembly(
            outputText = outputText.toString(),
            reasoningContent = reasoningContent.toString(),
            refusalText = refusalText.toString(),
            toolCallArguments = toolCallArguments.mapValues { it.value.toString() },
            finishReasons = finishReasons.toMap(),
            lastChunk = lastChunk,
            error = error,
            isDone = isDone,
        )
}

suspend fun Flow<ChatCompletionStreamEvent>.collectChatCompletionStream(): ChatCompletionStreamAssembly {
    val accumulator = ChatCompletionStreamAccumulator()
    collect { event -> accumulator.apply(event) }
    return accumulator.snapshot()
}

package one.wabbit.web.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ResponseInputTokensRequest(
    val model: ModelId,
    val input: ResponseInput,
    val instructions: String? = null,
    val tools: List<ResponseTool> = emptyList(),
    val toolChoice: ResponseToolChoice? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(instructions == null || instructions.isNotBlank()) {
            "response input-token count instructions must not be blank when set"
        }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("model", model.value)
            put("input", input.toJson())
            instructions?.let { put("instructions", it) }
            if (tools.isNotEmpty()) {
                put("tools", kotlinx.serialization.json.JsonArray(tools.map { it.toJson() }))
            }
            toolChoice?.let { put("tool_choice", it.toJson()) }
            putJsonExtras(extraBody)
        }
}

@Serializable
data class ResponseInputTokensUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

@Serializable
data class ResponseInputTokensResult(
    @SerialName("object") val objectType: String? = null,
    @SerialName("input_tokens") val inputTokens: Int? = null,
    val usage: ResponseInputTokensUsage? = null,
)

data class StoredChatCompletionListQuery(
    val model: ModelId? = null,
    val limit: Int = 20,
    val after: String? = null,
    val order: ResponseListOrder = ResponseListOrder.DESC,
) {
    init {
        require(limit in 1..100) { "stored chat completions limit must be between 1 and 100" }
        require(after == null || after.isNotBlank()) { "stored chat completions after must not be blank when set" }
    }

    internal fun toParameters(): List<Pair<String, String>> =
        buildList {
            model?.let { add("model" to it.value) }
            add("limit" to limit.toString())
            add("order" to order.wireName)
            after?.let { add("after" to it) }
        }
}

@Serializable
data class StoredChatCompletionPage(
    @SerialName("object") val objectType: String? = null,
    val data: List<ChatCompletionResponse> = emptyList(),
    @SerialName("first_id") val firstId: String? = null,
    @SerialName("last_id") val lastId: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class StoredChatMessagePage(
    @SerialName("object") val objectType: String? = null,
    val data: List<ChatCompletionMessageObject> = emptyList(),
    @SerialName("first_id") val firstId: String? = null,
    @SerialName("last_id") val lastId: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

data class ConversationItemListQuery(
    val limit: Int = 20,
    val after: String? = null,
    val order: ResponseInputItemListOrder = ResponseInputItemListOrder.DESC,
) {
    init {
        require(limit in 1..100) { "conversation item limit must be between 1 and 100" }
        require(after == null || after.isNotBlank()) { "conversation item after must not be blank when set" }
    }

    internal fun toParameters(): List<Pair<String, String>> =
        buildList {
            add("limit" to limit.toString())
            add("order" to order.wireName)
            after?.let { add("after" to it) }
        }
}

data class ConversationCreateRequest(
    val items: List<ResponseInputItem> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
) {
    init {
        require(items.size <= 20) { "conversation create supports at most 20 items" }
        require(metadata.keys.all { it.isNotBlank() }) { "conversation metadata keys must not be blank" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            if (items.isNotEmpty()) {
                put("items", kotlinx.serialization.json.JsonArray(items.map { it.toJson() }))
            }
            if (metadata.isNotEmpty()) {
                put(
                    "metadata",
                    buildJsonObject {
                        metadata.forEach { (key, value) -> put(key, value) }
                    },
                )
            }
        }
}

data class ConversationUpdateRequest(
    val metadata: Map<String, String>,
) {
    init {
        require(metadata.keys.all { it.isNotBlank() }) { "conversation metadata keys must not be blank" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put(
                "metadata",
                buildJsonObject {
                    metadata.forEach { (key, value) -> put(key, value) }
                },
            )
        }
}

data class ConversationItemCreateRequest(
    val items: List<ResponseInputItem>,
    val include: List<ResponseInclude> = emptyList(),
) {
    init {
        require(items.isNotEmpty()) { "conversation item create must contain at least one item" }
        require(items.size <= 20) { "conversation item create supports at most 20 items" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("items", kotlinx.serialization.json.JsonArray(items.map { it.toJson() }))
        }

    internal fun toParameters(): List<Pair<String, String>> =
        buildList {
            include.forEach { add("include" to it.wireName) }
        }
}

@Serializable
data class ConversationObject(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class ConversationItemObject(
    val id: String? = null,
    @SerialName("object") val objectType: String? = null,
    @Serializable(with = NullableResponseItemTypeSerializer::class)
    val type: ResponseItemType? = null,
    @Serializable(with = ResponseStatusSerializer::class)
    val status: ResponseStatus? = null,
    val role: String? = null,
    val content: JsonElement? = null,
    @SerialName("call_id") val callId: String? = null,
    val name: String? = null,
    val arguments: String? = null,
    val output: String? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class ConversationItemPage(
    @SerialName("object") val objectType: String? = null,
    val data: List<ConversationItemObject> = emptyList(),
    @SerialName("first_id") val firstId: String? = null,
    @SerialName("last_id") val lastId: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

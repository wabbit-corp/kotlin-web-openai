package one.wabbit.web.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

data class VideoCreateRequest(
    val model: ModelId,
    val prompt: String,
    val referenceAssets: JsonArray? = null,
    val n: Int? = null,
    val size: VideoSize? = null,
    val seconds: VideoSeconds? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(prompt.isNotBlank()) { "video prompt must not be blank" }
        require(n == null || n > 0) { "video n must be positive when set" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("model", model.value)
            put("prompt", prompt)
            referenceAssets?.let { put("reference_assets", it) }
            n?.let { put("n", it) }
            size?.let { put("size", it.wireName) }
            seconds?.let { put("seconds", it.wireName) }
            putJsonExtras(extraBody)
        }
}

data class VideoRemixRequest(
    val prompt: String? = null,
    val referenceAssets: JsonArray? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(prompt == null || prompt.isNotBlank()) { "video remix prompt must not be blank when set" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            prompt?.let { put("prompt", it) }
            referenceAssets?.let { put("reference_assets", it) }
            putJsonExtras(extraBody)
        }
}

data class VideoListQuery(
    val limit: Int = 20,
    val after: String? = null,
) {
    init {
        require(limit in 1..100) { "video list limit must be between 1 and 100" }
        require(after == null || after.isNotBlank()) { "video list after must not be blank when set" }
    }

    internal fun toParameters(): List<Pair<String, String>> =
        buildList {
            add("limit" to limit.toString())
            after?.let { add("after" to it) }
        }
}

@Serializable
data class VideoObject(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    @Serializable(with = VideoStatusSerializer::class)
    val status: VideoStatus? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("completed_at") val completedAt: Long? = null,
    val model: String? = null,
    val prompt: String? = null,
    val result: JsonObject? = null,
    @SerialName("error") val error: ResponseApiError? = null,
    @SerialName("output") val output: JsonArray? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class VideoPage(
    @SerialName("object") val objectType: String? = null,
    val data: List<VideoObject> = emptyList(),
    @SerialName("first_id") val firstId: String? = null,
    @SerialName("last_id") val lastId: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

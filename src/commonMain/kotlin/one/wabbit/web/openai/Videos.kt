// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.web.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

data class VideoInputReference(
    val fileId: FileId? = null,
    val imageUrl: String? = null,
) {
    init {
        require(listOfNotNull(fileId, imageUrl).size == 1) {
            "video input reference must provide exactly one of fileId or imageUrl"
        }
        require(imageUrl == null || imageUrl.isNotBlank()) { "video input reference imageUrl must not be blank when set" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            fileId?.let { put("file_id", it.value) }
            imageUrl?.let { put("image_url", it) }
        }
}

data class VideoReference(
    val id: VideoId,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("id", id.value)
        }
}

data class VideoCreateRequest(
    val model: ModelId? = null,
    val prompt: String,
    val inputReference: VideoInputReference? = null,
    @Deprecated(
        message = "reference_assets is not part of the current OpenAI video create schema; use inputReference for OpenAI or extraBody for provider-specific extensions",
    )
    val referenceAssets: JsonArray? = null,
    val n: Int? = null,
    val size: VideoSize? = null,
    val seconds: VideoSeconds? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(prompt.isNotBlank()) { "video prompt must not be blank" }
        require(referenceAssets == null) {
            "video referenceAssets is not supported by the current OpenAI create video schema; use inputReference instead"
        }
        require(n == null || n > 0) { "video n must be positive when set" }
        require(seconds == null || seconds in setOf(VideoSeconds.S4, VideoSeconds.S8, VideoSeconds.S12)) {
            "video create seconds must be one of 4, 8, or 12 when set"
        }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            model?.let { put("model", it.value) }
            put("prompt", prompt)
            inputReference?.let { put("input_reference", it.toJson()) }
            n?.let { put("n", it) }
            size?.let { put("size", it.wireName) }
            seconds?.let { put("seconds", it.wireName) }
            putJsonExtras(extraBody)
        }
}

data class VideoCharacterCreateRequest(
    val name: String,
    val video: BinaryUpload,
) {
    init {
        validateVideoCharacterName(name)
    }
}

data class StreamingVideoCharacterCreateRequest(
    val name: String,
    val video: StreamingBinaryUpload,
) {
    init {
        validateVideoCharacterName(name)
    }
}

data class VideoEditRequest(
    val prompt: String,
    val video: VideoReference,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(prompt.isNotBlank()) { "video edit prompt must not be blank" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("prompt", prompt)
            put("video", video.toJson())
            putJsonExtras(extraBody)
        }
}

data class VideoExtendRequest(
    val prompt: String,
    val video: VideoReference,
    val seconds: VideoSeconds,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(prompt.isNotBlank()) { "video extend prompt must not be blank" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("prompt", prompt)
            put("video", video.toJson())
            put("seconds", seconds.wireName)
            putJsonExtras(extraBody)
        }
}

data class VideoRemixRequest(
    val prompt: String,
    @Deprecated(
        message = "reference_assets is not part of the current OpenAI video remix schema; use the path video_id and prompt only",
    )
    val referenceAssets: JsonArray? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(prompt.isNotBlank()) { "video remix prompt must not be blank" }
        require(referenceAssets == null) {
            "video remix referenceAssets is not supported by the current OpenAI remix schema"
        }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("prompt", prompt)
            putJsonExtras(extraBody)
        }
}

private fun validateVideoCharacterName(name: String) {
    require(name.isNotBlank()) { "video character name must not be blank" }
}

enum class VideoListOrder(val wireName: String) {
    ASC("asc"),
    DESC("desc"),
}

data class VideoListQuery(
    val limit: Int = 20,
    val after: String? = null,
    val order: VideoListOrder? = null,
) {
    init {
        require(limit in 0..100) { "video list limit must be between 0 and 100" }
        require(after == null || after.isNotBlank()) { "video list after must not be blank when set" }
    }

    internal fun toParameters(): List<Pair<String, String>> =
        buildList {
            add("limit" to limit.toString())
            after?.let { add("after" to it) }
            order?.let { add("order" to it.wireName) }
        }
}

enum class VideoContentVariant(val wireName: String) {
    VIDEO("video"),
    THUMBNAIL("thumbnail"),
    SPRITESHEET("spritesheet"),
}

@Serializable
data class VideoCharacterObject(
    val id: String,
    @SerialName("created_at") val createdAt: Long? = null,
    val name: String? = null,
)

@Serializable
data class VideoObject(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    @Serializable(with = VideoStatusSerializer::class)
    val status: VideoStatus? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("completed_at") val completedAt: Long? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
    val model: String? = null,
    val progress: Int? = null,
    val prompt: String? = null,
    @SerialName("remixed_from_video_id") val remixedFromVideoId: String? = null,
    val seconds: String? = null,
    val size: String? = null,
    val quality: String? = null,
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

// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.web.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

// xAI batches are not wire-compatible with OpenAI's file-batch API, so they live on a separate surface.
data class XAIBatchCreateRequest(
    val name: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val extraBody: JsonExtras? = null,
) {
    init {
        require(name == null || name.isNotBlank()) { "xAI batch name must not be blank when set" }
        require(metadata.keys.all { it.isNotBlank() }) { "xAI batch metadata keys must not be blank" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            name?.let { put("name", it) }
            if (metadata.isNotEmpty()) {
                putJsonObject("metadata") {
                    metadata.forEach { (key, value) -> put(key, value) }
                }
            }
            putJsonExtras(extraBody)
        }
}

data class XAIBatchRequestInput(
    val customId: String? = null,
    val method: String = "POST",
    val url: String,
    val body: JsonObject,
) {
    init {
        require(customId == null || customId.isNotBlank()) { "xAI batch customId must not be blank when set" }
        require(method.isNotBlank()) { "xAI batch request method must not be blank" }
        require(url.isNotBlank()) { "xAI batch request url must not be blank" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            customId?.let { put("custom_id", it) }
            put("method", method)
            put("url", url)
            put("body", body)
        }
}

data class XAIBatchRequestAppendRequest(
    val requests: List<XAIBatchRequestInput>,
) {
    init {
        require(requests.isNotEmpty()) { "xAI batch request append payload must not be empty" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            putJsonArray("requests") {
                requests.forEach { add(it.toJson()) }
            }
        }
}

data class XAIBatchListQuery(
    val limit: Int = 20,
    val paginationToken: String? = null,
) {
    init {
        require(limit in 1..100) { "xAI batch list limit must be between 1 and 100" }
        require(paginationToken == null || paginationToken.isNotBlank()) {
            "xAI batch list paginationToken must not be blank when set"
        }
    }

    internal fun toParameters(): List<Pair<String, String>> =
        buildList {
            add("limit" to limit.toString())
            paginationToken?.let { add("pagination_token" to it) }
        }
}

data class XAIBatchRequestListQuery(
    val limit: Int = 20,
    val paginationToken: String? = null,
) {
    init {
        require(limit in 1..100) { "xAI batch request list limit must be between 1 and 100" }
        require(paginationToken == null || paginationToken.isNotBlank()) {
            "xAI batch request list paginationToken must not be blank when set"
        }
    }

    internal fun toParameters(): List<Pair<String, String>> =
        buildList {
            add("limit" to limit.toString())
            paginationToken?.let { add("pagination_token" to it) }
        }
}

data class XAIBatchResultListQuery(
    val limit: Int = 20,
    val paginationToken: String? = null,
) {
    init {
        require(limit in 1..100) { "xAI batch result list limit must be between 1 and 100" }
        require(paginationToken == null || paginationToken.isNotBlank()) {
            "xAI batch result list paginationToken must not be blank when set"
        }
    }

    internal fun toParameters(): List<Pair<String, String>> =
        buildList {
            add("limit" to limit.toString())
            paginationToken?.let { add("pagination_token" to it) }
        }
}

@Serializable
data class XAIBatchState(
    @SerialName("num_requests") val numRequests: Int? = null,
    @SerialName("num_pending") val numPending: Int? = null,
    @SerialName("num_success") val numSuccess: Int? = null,
    @SerialName("num_error") val numError: Int? = null,
    @SerialName("num_cancelled") val numCancelled: Int? = null,
)

sealed interface XAIBatchRequestState {
    val wireName: String

    data object Pending : XAIBatchRequestState {
        override val wireName: String = "pending"
    }

    data object Succeeded : XAIBatchRequestState {
        override val wireName: String = "succeeded"
    }

    data object Failed : XAIBatchRequestState {
        override val wireName: String = "failed"
    }

    data object Cancelled : XAIBatchRequestState {
        override val wireName: String = "cancelled"
    }

    data class Unknown(
        override val wireName: String,
    ) : XAIBatchRequestState
}

internal object XAIBatchRequestStateSerializer :
    PreservingWireValueSerializer<XAIBatchRequestState>(
        serialName = "one.wabbit.web.openai.XAIBatchRequestState?",
        knownValues =
            listOf(
                XAIBatchRequestState.Pending,
                XAIBatchRequestState.Succeeded,
                XAIBatchRequestState.Failed,
                XAIBatchRequestState.Cancelled,
            ),
        wireName = XAIBatchRequestState::wireName,
        unknown = XAIBatchRequestState::Unknown,
    )

@Serializable
data class XAIBatchObject(
    @SerialName("batch_id") val id: String,
    @SerialName("object") val objectType: String? = null,
    val name: String? = null,
    @SerialName("create_api_key_id") val createApiKeyId: String? = null,
    @SerialName("create_time") val createTime: String? = null,
    val state: XAIBatchState? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("request_counts") val requestCounts: JsonObject? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class XAIBatchPage(
    @SerialName("object") val objectType: String? = null,
    @SerialName("batches") val batches: List<XAIBatchObject> = emptyList(),
    @SerialName("pagination_token") val paginationToken: String? = null,
) {
    val data: List<XAIBatchObject>
        get() = batches
}

@Serializable
data class XAIBatchRequestObject(
    val id: String? = null,
    @SerialName("object") val objectType: String? = null,
    @SerialName("custom_id") val customId: String? = null,
    val method: String? = null,
    val url: String? = null,
    @Serializable(with = XAIBatchRequestStateSerializer::class)
    val state: XAIBatchRequestState? = null,
    val body: JsonObject? = null,
    val response: JsonObject? = null,
    val error: JsonObject? = null,
)

@Serializable
data class XAIBatchRequestPage(
    @SerialName("object") val objectType: String? = null,
    @SerialName("batch_request_metadata") val batchRequestMetadata: List<XAIBatchRequestObject> = emptyList(),
    @SerialName("pagination_token") val paginationToken: String? = null,
) {
    val data: List<XAIBatchRequestObject>
        get() = batchRequestMetadata
}

@Serializable
data class XAIBatchResultObject(
    val id: String? = null,
    @SerialName("object") val objectType: String? = null,
    @SerialName("custom_id") val customId: String? = null,
    val response: JsonObject? = null,
    val error: JsonObject? = null,
)

@Serializable
data class XAIBatchResultPage(
    @SerialName("object") val objectType: String? = null,
    val results: List<XAIBatchResultObject> = emptyList(),
    @SerialName("pagination_token") val paginationToken: String? = null,
) {
    val data: List<XAIBatchResultObject>
        get() = results
}

enum class XAISpeechAudioFormat(val wireName: String) {
    MP3("mp3"),
    WAV("wav"),
    PCM("pcm"),
}

data class XAISpeechRequest(
    val text: String,
    val voiceId: XAIVoiceId,
    val language: String? = null,
    val model: ModelId? = null,
    val responseFormat: XAISpeechAudioFormat? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(text.isNotBlank()) { "xAI speech text must not be blank" }
        require(language == null || language.isNotBlank()) { "xAI speech language must not be blank when set" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("text", text)
            put("voice_id", voiceId.value)
            language?.let { put("language", it) }
            model?.let { put("model", it.value) }
            responseFormat?.let { put("response_format", it.wireName) }
            putJsonExtras(extraBody)
        }
}

@Serializable
data class XAIVoiceObject(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    val name: String? = null,
    val language: String? = null,
    val gender: String? = null,
    val description: String? = null,
    val preview: String? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class XAIVoicePage(
    @SerialName("object") val objectType: String? = null,
    val data: List<XAIVoiceObject> = emptyList(),
    @SerialName("first_id") val firstId: String? = null,
    @SerialName("last_id") val lastId: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

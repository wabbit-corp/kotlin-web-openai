package one.wabbit.web.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

data class BatchOutputExpiration(
    val anchor: BatchExpirationAnchor,
    val seconds: Int,
) {
    init {
        require(seconds in 3600..2592000) {
            "batch output expiration seconds must be between 3600 and 2592000"
        }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("anchor", anchor.wireName)
            put("seconds", seconds)
        }
}

data class BatchCreateRequest(
    val inputFileId: FileId,
    val endpoint: String,
    val completionWindow: BatchCompletionWindow = BatchCompletionWindow.HOURS_24,
    val metadata: Map<String, String> = emptyMap(),
    val outputExpiresAfter: BatchOutputExpiration? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(endpoint.isNotBlank()) { "batch endpoint must not be blank" }
        require(metadata.keys.all { it.isNotBlank() }) { "batch metadata keys must not be blank" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("input_file_id", inputFileId.value)
            put("endpoint", endpoint)
            put("completion_window", completionWindow.wireName)
            if (metadata.isNotEmpty()) {
                putJsonObject("metadata") {
                    metadata.forEach { (key, value) -> put(key, value) }
                }
            }
            outputExpiresAfter?.let { put("output_expires_after", it.toJson()) }
            putJsonExtras(extraBody)
        }
}

data class BatchListQuery(
    val limit: Int = 20,
    val after: String? = null,
) {
    init {
        require(limit in 1..100) { "batch list limit must be between 1 and 100" }
        require(after == null || after.isNotBlank()) { "batch list after must not be blank when set" }
    }

    internal fun toParameters(): List<Pair<String, String>> =
        buildList {
            add("limit" to limit.toString())
            after?.let { add("after" to it) }
        }
}

@Serializable
data class BatchRequestCounts(
    val total: Int? = null,
    val completed: Int? = null,
    val failed: Int? = null,
)

@Serializable
data class BatchErrors(
    @SerialName("object") val objectType: String? = null,
    val data: List<ResponseApiError> = emptyList(),
)

@Serializable
data class BatchObject(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    val endpoint: String? = null,
    val errors: BatchErrors? = null,
    @SerialName("input_file_id") val inputFileId: String? = null,
    @SerialName("completion_window")
    @Serializable(with = BatchCompletionWindowValueSerializer::class)
    val completionWindow: BatchCompletionWindowValue? = null,
    @Serializable(with = BatchStatusSerializer::class)
    val status: BatchStatus? = null,
    @SerialName("output_file_id") val outputFileId: String? = null,
    @SerialName("error_file_id") val errorFileId: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("in_progress_at") val inProgressAt: Long? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
    @SerialName("finalizing_at") val finalizingAt: Long? = null,
    @SerialName("completed_at") val completedAt: Long? = null,
    @SerialName("failed_at") val failedAt: Long? = null,
    @SerialName("expired_at") val expiredAt: Long? = null,
    @SerialName("cancelling_at") val cancellingAt: Long? = null,
    @SerialName("cancelled_at") val cancelledAt: Long? = null,
    @SerialName("request_counts") val requestCounts: BatchRequestCounts? = null,
    val metadata: JsonObject? = null,
)

sealed interface BatchCompletionWindowValue {
    val wireName: String

    data object Hours24 : BatchCompletionWindowValue {
        override val wireName: String = "24h"
    }

    data class Unknown(
        override val wireName: String,
    ) : BatchCompletionWindowValue
}

internal object BatchCompletionWindowValueSerializer :
    PreservingWireValueSerializer<BatchCompletionWindowValue>(
        serialName = "one.wabbit.web.openai.BatchCompletionWindowValue?",
        knownValues = listOf(BatchCompletionWindowValue.Hours24),
        wireName = BatchCompletionWindowValue::wireName,
        unknown = BatchCompletionWindowValue::Unknown,
    )

@Serializable
data class BatchPage(
    @SerialName("object") val objectType: String? = null,
    val data: List<BatchObject> = emptyList(),
    @SerialName("first_id") val firstId: String? = null,
    @SerialName("last_id") val lastId: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class BatchOutputError(
    val code: String? = null,
    val message: String? = null,
)

@Serializable
data class BatchOutputResponse(
    @SerialName("status_code") val statusCode: Int? = null,
    @SerialName("request_id") val requestId: String? = null,
    val body: JsonObject? = null,
)

@Serializable
data class BatchOutputLine(
    val id: String? = null,
    @SerialName("custom_id") val customId: String? = null,
    val response: BatchOutputResponse? = null,
    val error: BatchOutputError? = null,
)

fun parseBatchOutputLines(text: String): List<BatchOutputLine> =
    text
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line -> OpenAIJson.decodeFromString<BatchOutputLine>(line) }
        .toList()

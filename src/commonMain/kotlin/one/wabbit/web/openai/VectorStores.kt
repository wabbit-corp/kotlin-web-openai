package one.wabbit.web.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

@Serializable
enum class VectorStoreExpirationAnchor(
    val wireName: String,
) {
    @SerialName("last_active_at")
    LAST_ACTIVE_AT("last_active_at"),
}

@Serializable
data class VectorStoreExpirationPolicy(
    val anchor: VectorStoreExpirationAnchor = VectorStoreExpirationAnchor.LAST_ACTIVE_AT,
    val days: Int,
) {
    init {
        require(days > 0) { "vector store expiration days must be positive" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("anchor", anchor.wireName)
            put("days", days)
    }
}

@Serializable
data class VectorStoreStaticChunking(
    @SerialName("max_chunk_size_tokens") val maxChunkSizeTokens: Int,
    @SerialName("chunk_overlap_tokens") val chunkOverlapTokens: Int,
) {
    init {
        require(maxChunkSizeTokens > 0) { "vector store maxChunkSizeTokens must be positive" }
        require(chunkOverlapTokens >= 0) { "vector store chunkOverlapTokens must be non-negative" }
        require(chunkOverlapTokens < maxChunkSizeTokens) {
            "vector store chunkOverlapTokens must be smaller than maxChunkSizeTokens"
        }
    }
}

@Serializable
data class VectorStoreChunkingStrategyObject(
    @Serializable(with = VectorStoreChunkingStrategyTypeSerializer::class)
    val type: VectorStoreChunkingStrategyType? = null,
    @SerialName("static") val staticConfig: VectorStoreStaticChunking? = null,
)

sealed interface VectorStoreChunkingStrategyType {
    val wireName: String

    data object Auto : VectorStoreChunkingStrategyType {
        override val wireName: String = "auto"
    }

    data object Static : VectorStoreChunkingStrategyType {
        override val wireName: String = "static"
    }

    data class Unknown(
        override val wireName: String,
    ) : VectorStoreChunkingStrategyType
}

internal object VectorStoreChunkingStrategyTypeSerializer :
    PreservingWireValueSerializer<VectorStoreChunkingStrategyType>(
        serialName = "one.wabbit.web.openai.VectorStoreChunkingStrategyType?",
        knownValues =
            listOf(
                VectorStoreChunkingStrategyType.Auto,
                VectorStoreChunkingStrategyType.Static,
            ),
        wireName = VectorStoreChunkingStrategyType::wireName,
        unknown = VectorStoreChunkingStrategyType::Unknown,
    )

sealed interface VectorStoreChunkingStrategy {
    fun toJson(): JsonObject

    data object Auto : VectorStoreChunkingStrategy {
        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "auto")
            }
    }

    data class Static(
        val config: VectorStoreStaticChunking,
    ) : VectorStoreChunkingStrategy {
        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "static")
                putJsonObject("static") {
                    put("max_chunk_size_tokens", config.maxChunkSizeTokens)
                    put("chunk_overlap_tokens", config.chunkOverlapTokens)
                }
            }
    }
}

@Serializable
enum class VectorStoreFileStatus(
    val wireName: String,
) {
    @SerialName("in_progress")
    IN_PROGRESS("in_progress"),

    @SerialName("completed")
    COMPLETED("completed"),

    @SerialName("failed")
    FAILED("failed"),

    @SerialName("cancelled")
    CANCELLED("cancelled"),
}

sealed interface VectorStoreStatus {
    val wireName: String

    data object InProgress : VectorStoreStatus {
        override val wireName: String = "in_progress"
    }

    data object Completed : VectorStoreStatus {
        override val wireName: String = "completed"
    }

    data object Expired : VectorStoreStatus {
        override val wireName: String = "expired"
    }

    data class Unknown(
        override val wireName: String,
    ) : VectorStoreStatus
}

internal object VectorStoreStatusSerializer :
    PreservingWireValueSerializer<VectorStoreStatus>(
        serialName = "one.wabbit.web.openai.VectorStoreStatus?",
        knownValues =
            listOf(
                VectorStoreStatus.InProgress,
                VectorStoreStatus.Completed,
                VectorStoreStatus.Expired,
            ),
        wireName = VectorStoreStatus::wireName,
        unknown = VectorStoreStatus::Unknown,
    )

sealed interface VectorStoreFileStatusValue {
    val wireName: String

    data object InProgress : VectorStoreFileStatusValue {
        override val wireName: String = "in_progress"
    }

    data object Completed : VectorStoreFileStatusValue {
        override val wireName: String = "completed"
    }

    data object Failed : VectorStoreFileStatusValue {
        override val wireName: String = "failed"
    }

    data object Cancelled : VectorStoreFileStatusValue {
        override val wireName: String = "cancelled"
    }

    data class Unknown(
        override val wireName: String,
    ) : VectorStoreFileStatusValue
}

internal object VectorStoreFileStatusValueSerializer :
    PreservingWireValueSerializer<VectorStoreFileStatusValue>(
        serialName = "one.wabbit.web.openai.VectorStoreFileStatusValue?",
        knownValues =
            listOf(
                VectorStoreFileStatusValue.InProgress,
                VectorStoreFileStatusValue.Completed,
                VectorStoreFileStatusValue.Failed,
                VectorStoreFileStatusValue.Cancelled,
            ),
        wireName = VectorStoreFileStatusValue::wireName,
        unknown = VectorStoreFileStatusValue::Unknown,
    )

data class VectorStoreCreateRequest(
    val name: String? = null,
    val fileIds: List<FileId> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val expiresAfter: VectorStoreExpirationPolicy? = null,
    val chunkingStrategy: VectorStoreChunkingStrategy? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(name == null || name.isNotBlank()) { "vector store name must not be blank when set" }
        require(metadata.keys.all { it.isNotBlank() }) { "vector store metadata keys must not be blank" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            name?.let { put("name", it) }
            if (fileIds.isNotEmpty()) {
                putJsonArray("file_ids") {
                    fileIds.forEach { add(JsonPrimitive(it.value)) }
                }
            }
            if (metadata.isNotEmpty()) {
                putJsonObject("metadata") {
                    metadata.forEach { (key, value) -> put(key, value) }
                }
            }
            expiresAfter?.let { put("expires_after", it.toJson()) }
            chunkingStrategy?.let { put("chunking_strategy", it.toJson()) }
            putJsonExtras(extraBody)
        }
}

data class VectorStoreUpdateRequest(
    val name: String? = null,
    val metadata: Map<String, String>? = null,
    val expiresAfter: VectorStoreExpirationPolicy? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(name == null || name.isNotBlank()) { "vector store name must not be blank when set" }
        require(metadata == null || metadata.keys.all { it.isNotBlank() }) {
            "vector store metadata keys must not be blank when set"
        }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            name?.let { put("name", it) }
            metadata?.let { values ->
                putJsonObject("metadata") {
                    values.forEach { (key, value) -> put(key, value) }
                }
            }
            expiresAfter?.let { put("expires_after", it.toJson()) }
            putJsonExtras(extraBody)
        }
}

enum class VectorStoreListOrder(val wireName: String) {
    ASC("asc"),
    DESC("desc"),
}

data class VectorStoreListQuery(
    val limit: Int = 20,
    val order: VectorStoreListOrder = VectorStoreListOrder.DESC,
    val after: String? = null,
    val before: String? = null,
) {
    init {
        require(limit in 1..100) { "vector store list limit must be between 1 and 100" }
        require(!(after != null && before != null)) { "after and before cannot both be set" }
        require(after == null || after.isNotBlank()) { "vector store list after must not be blank when set" }
        require(before == null || before.isNotBlank()) { "vector store list before must not be blank when set" }
    }

    internal fun toParameters(): List<Pair<String, String>> =
        buildList {
            add("limit" to limit.toString())
            add("order" to order.wireName)
            after?.let { add("after" to it) }
            before?.let { add("before" to it) }
        }
}

data class VectorStoreSearchRequest(
    val query: String,
    val maxNumResults: Int? = null,
    val rewriteQuery: Boolean? = null,
    val filters: JsonObject? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(query.isNotBlank()) { "vector store search query must not be blank" }
        require(maxNumResults == null || maxNumResults > 0) { "vector store maxNumResults must be positive when set" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("query", query)
            maxNumResults?.let { put("max_num_results", it) }
            rewriteQuery?.let { put("rewrite_query", it) }
            filters?.let { put("filters", it) }
            putJsonExtras(extraBody)
        }
}

data class VectorStoreFileCreateRequest(
    val fileId: FileId,
    val attributes: Map<String, String> = emptyMap(),
    val chunkingStrategy: VectorStoreChunkingStrategy? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(attributes.keys.all { it.isNotBlank() }) { "vector store file attributes keys must not be blank" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("file_id", fileId.value)
            if (attributes.isNotEmpty()) {
                putJsonObject("attributes") {
                    attributes.forEach { (key, value) -> put(key, value) }
                }
            }
            chunkingStrategy?.let { put("chunking_strategy", it.toJson()) }
            putJsonExtras(extraBody)
        }
}

data class VectorStoreFileUpdateRequest(
    val attributes: JsonObject? = null,
    val chunkingStrategy: VectorStoreChunkingStrategy? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(attributes == null || attributes.keys.all { it.isNotBlank() }) {
            "vector store file update attribute keys must not be blank when set"
        }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            attributes?.let { put("attributes", it) }
            chunkingStrategy?.let { put("chunking_strategy", it.toJson()) }
            putJsonExtras(extraBody)
        }
}

enum class VectorStoreFileListOrder(val wireName: String) {
    ASC("asc"),
    DESC("desc"),
}

data class VectorStoreFileListQuery(
    val filter: VectorStoreFileStatus? = null,
    val limit: Int = 20,
    val order: VectorStoreFileListOrder = VectorStoreFileListOrder.DESC,
    val after: String? = null,
    val before: String? = null,
) {
    init {
        require(limit in 1..100) { "vector store file list limit must be between 1 and 100" }
        require(!(after != null && before != null)) { "after and before cannot both be set" }
        require(after == null || after.isNotBlank()) { "vector store file list after must not be blank when set" }
        require(before == null || before.isNotBlank()) { "vector store file list before must not be blank when set" }
    }

    internal fun toParameters(): List<Pair<String, String>> =
        buildList {
            filter?.let { add("filter" to it.wireName) }
            add("limit" to limit.toString())
            add("order" to order.wireName)
            after?.let { add("after" to it) }
            before?.let { add("before" to it) }
        }
}

data class VectorStoreFileBatchCreateRequest(
    val fileIds: List<FileId>,
    val attributes: Map<String, String> = emptyMap(),
    val chunkingStrategy: VectorStoreChunkingStrategy? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(fileIds.isNotEmpty()) { "vector store file batch must contain at least one file id" }
        require(attributes.keys.all { it.isNotBlank() }) { "vector store file batch attributes keys must not be blank" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            putJsonArray("file_ids") {
                fileIds.forEach { add(JsonPrimitive(it.value)) }
            }
            if (attributes.isNotEmpty()) {
                putJsonObject("attributes") {
                    attributes.forEach { (key, value) -> put(key, value) }
                }
            }
            chunkingStrategy?.let { put("chunking_strategy", it.toJson()) }
            putJsonExtras(extraBody)
        }
}

data class VectorStoreFileBatchListQuery(
    val filter: VectorStoreFileStatus? = null,
    val limit: Int = 20,
    val order: VectorStoreFileListOrder = VectorStoreFileListOrder.DESC,
    val after: String? = null,
    val before: String? = null,
) {
    init {
        require(limit in 1..100) { "vector store file batch list limit must be between 1 and 100" }
        require(!(after != null && before != null)) { "after and before cannot both be set" }
        require(after == null || after.isNotBlank()) { "vector store file batch list after must not be blank when set" }
        require(before == null || before.isNotBlank()) { "vector store file batch list before must not be blank when set" }
    }

    internal fun toParameters(): List<Pair<String, String>> =
        buildList {
            filter?.let { add("filter" to it.wireName) }
            add("limit" to limit.toString())
            add("order" to order.wireName)
            after?.let { add("after" to it) }
            before?.let { add("before" to it) }
        }
}

@Serializable
data class VectorStoreFileCounts(
    @SerialName("in_progress") val inProgress: Int? = null,
    val completed: Int? = null,
    val failed: Int? = null,
    val cancelled: Int? = null,
    val total: Int? = null,
)

@Serializable
data class VectorStoreObject(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    val name: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("usage_bytes") val usageBytes: Long? = null,
    @SerialName("file_counts") val fileCounts: VectorStoreFileCounts? = null,
    @Serializable(with = VectorStoreStatusSerializer::class)
    val status: VectorStoreStatus? = null,
    @SerialName("expires_after") val expiresAfter: VectorStoreExpirationPolicy? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
    @SerialName("last_active_at") val lastActiveAt: Long? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class VectorStorePage(
    @SerialName("object") val objectType: String? = null,
    val data: List<VectorStoreObject> = emptyList(),
    @SerialName("first_id") val firstId: String? = null,
    @SerialName("last_id") val lastId: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class VectorStoreSearchResultContent(
    val type: String? = null,
    val text: String? = null,
)

@Serializable
data class VectorStoreSearchResult(
    @SerialName("file_id") val fileId: String? = null,
    val filename: String? = null,
    val score: Double? = null,
    val attributes: JsonObject? = null,
    val content: List<VectorStoreSearchResultContent> = emptyList(),
)

@Serializable
data class VectorStoreSearchResponse(
    @SerialName("object") val objectType: String? = null,
    @SerialName("search_query") val searchQuery: String? = null,
    val data: List<VectorStoreSearchResult> = emptyList(),
)

@Serializable
data class VectorStoreFileObject(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("usage_bytes") val usageBytes: Long? = null,
    @SerialName("vector_store_id") val vectorStoreId: String? = null,
    @Serializable(with = VectorStoreFileStatusValueSerializer::class)
    val status: VectorStoreFileStatusValue? = null,
    @SerialName("last_error") val lastError: JsonObject? = null,
    val attributes: JsonObject? = null,
    @SerialName("chunking_strategy") val chunkingStrategy: VectorStoreChunkingStrategyObject? = null,
)

@Serializable
data class VectorStoreFilePage(
    @SerialName("object") val objectType: String? = null,
    val data: List<VectorStoreFileObject> = emptyList(),
    @SerialName("first_id") val firstId: String? = null,
    @SerialName("last_id") val lastId: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class VectorStoreFileContentPart(
    val type: String? = null,
    val text: String? = null,
)

@Serializable
data class VectorStoreFileContentPage(
    @SerialName("file_id") val fileId: String? = null,
    val filename: String? = null,
    val attributes: JsonObject? = null,
    val content: List<VectorStoreFileContentPart> = emptyList(),
    @SerialName("object") val objectType: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("next_page") val nextPage: String? = null,
)

@Serializable
data class VectorStoreFileBatchObject(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("vector_store_id") val vectorStoreId: String? = null,
    @Serializable(with = VectorStoreFileStatusValueSerializer::class)
    val status: VectorStoreFileStatusValue? = null,
    @SerialName("file_counts") val fileCounts: VectorStoreFileCounts? = null,
    @SerialName("chunking_strategy") val chunkingStrategy: VectorStoreChunkingStrategyObject? = null,
    @SerialName("last_error") val lastError: JsonObject? = null,
    val attributes: JsonObject? = null,
)

@Serializable
data class VectorStoreFileBatchPage(
    @SerialName("object") val objectType: String? = null,
    val data: List<VectorStoreFileBatchObject> = emptyList(),
    @SerialName("first_id") val firstId: String? = null,
    @SerialName("last_id") val lastId: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

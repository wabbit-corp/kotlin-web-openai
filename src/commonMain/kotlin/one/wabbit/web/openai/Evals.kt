package one.wabbit.web.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

enum class EvalListOrder(val wireName: String) {
    ASC("asc"),
    DESC("desc"),
}

sealed interface EvalRunStatus {
    val wireName: String

    data object Queued : EvalRunStatus {
        override val wireName: String = "queued"
    }

    data object InProgress : EvalRunStatus {
        override val wireName: String = "in_progress"
    }

    data object Failed : EvalRunStatus {
        override val wireName: String = "failed"
    }

    data object Completed : EvalRunStatus {
        override val wireName: String = "completed"
    }

    data object Canceled : EvalRunStatus {
        override val wireName: String = "canceled"
    }

    data class Unknown(
        override val wireName: String,
    ) : EvalRunStatus
}

internal object EvalRunStatusSerializer :
    PreservingWireValueSerializer<EvalRunStatus>(
        serialName = "one.wabbit.web.openai.EvalRunStatus?",
        knownValues =
            listOf(
                EvalRunStatus.Queued,
                EvalRunStatus.InProgress,
                EvalRunStatus.Failed,
                EvalRunStatus.Completed,
                EvalRunStatus.Canceled,
            ),
        wireName = EvalRunStatus::wireName,
        unknown = EvalRunStatus::Unknown,
    )

sealed interface EvalRunOutputItemStatus {
    val wireName: String

    data object Pass : EvalRunOutputItemStatus {
        override val wireName: String = "pass"
    }

    data object Failed : EvalRunOutputItemStatus {
        override val wireName: String = "failed"
    }

    data class Unknown(
        override val wireName: String,
    ) : EvalRunOutputItemStatus
}

internal object EvalRunOutputItemStatusSerializer :
    PreservingWireValueSerializer<EvalRunOutputItemStatus>(
        serialName = "one.wabbit.web.openai.EvalRunOutputItemStatus?",
        knownValues =
            listOf(
                EvalRunOutputItemStatus.Pass,
                EvalRunOutputItemStatus.Failed,
            ),
        wireName = EvalRunOutputItemStatus::wireName,
        unknown = EvalRunOutputItemStatus::Unknown,
    )

data class EvalCreateRequest(
    val name: String,
    val metadata: Map<String, String> = emptyMap(),
    val extraBody: JsonExtras? = null,
) {
    init {
        require(name.isNotBlank()) { "eval name must not be blank" }
        require(metadata.keys.all { it.isNotBlank() }) { "eval metadata keys must not be blank" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("name", name)
            if (metadata.isNotEmpty()) {
                putJsonObject("metadata") {
                    metadata.forEach { (key, value) -> put(key, value) }
                }
            }
            putJsonExtras(extraBody)
        }
}

data class EvalUpdateRequest(
    val name: String? = null,
    val metadata: Map<String, String>? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(name == null || name.isNotBlank()) { "eval name must not be blank when set" }
        require(metadata == null || metadata.keys.all { it.isNotBlank() }) {
            "eval metadata keys must not be blank when set"
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
            putJsonExtras(extraBody)
        }
}

data class EvalListQuery(
    val limit: Int = 20,
    val after: String? = null,
) {
    init {
        require(limit in 1..100) { "eval list limit must be between 1 and 100" }
        require(after == null || after.isNotBlank()) { "eval list after must not be blank when set" }
    }

    internal fun toParameters(): List<Pair<String, String>> =
        buildList {
            add("limit" to limit.toString())
            after?.let { add("after" to it) }
        }
}

@Serializable
data class EvalObject(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    val name: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
    val status: String? = null,
    val metadata: JsonObject? = null,
    val config: JsonObject? = null,
)

@Serializable
data class EvalPage(
    @SerialName("object") val objectType: String? = null,
    val data: List<EvalObject> = emptyList(),
    @SerialName("first_id") val firstId: String? = null,
    @SerialName("last_id") val lastId: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

data class EvalRunCreateRequest(
    val name: String? = null,
    val metadata: Map<String, String> = emptyMap(),
    val extraBody: JsonExtras? = null,
) {
    init {
        require(name == null || name.isNotBlank()) { "eval run name must not be blank when set" }
        require(metadata.keys.all { it.isNotBlank() }) { "eval run metadata keys must not be blank" }
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

data class EvalRunUpdateRequest(
    val name: String? = null,
    val metadata: Map<String, String>? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(name == null || name.isNotBlank()) { "eval run name must not be blank when set" }
        require(metadata == null || metadata.keys.all { it.isNotBlank() }) {
            "eval run metadata keys must not be blank when set"
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
            putJsonExtras(extraBody)
        }
}

data class EvalRunListQuery(
    val limit: Int = 20,
    val after: String? = null,
    val order: EvalListOrder = EvalListOrder.ASC,
    val status: EvalRunStatus? = null,
) {
    init {
        require(limit in 1..100) { "eval run list limit must be between 1 and 100" }
        require(after == null || after.isNotBlank()) { "eval run list after must not be blank when set" }
    }

    internal fun toParameters(): List<Pair<String, String>> =
        buildList {
            add("limit" to limit.toString())
            add("order" to order.wireName)
            after?.let { add("after" to it) }
            status?.let { add("status" to it.wireName) }
        }
}

data class EvalRunOutputItemListQuery(
    val limit: Int = 20,
    val after: String? = null,
    val order: EvalListOrder = EvalListOrder.ASC,
    val status: EvalRunOutputItemStatus? = null,
) {
    init {
        require(limit in 1..100) { "eval run output-item list limit must be between 1 and 100" }
        require(after == null || after.isNotBlank()) { "eval run output-item list after must not be blank when set" }
    }

    internal fun toParameters(): List<Pair<String, String>> =
        buildList {
            add("limit" to limit.toString())
            add("order" to order.wireName)
            after?.let { add("after" to it) }
            status?.let { add("status" to it.wireName) }
        }
}

@Serializable
data class EvalRunObject(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    val name: String? = null,
    @Serializable(with = EvalRunStatusSerializer::class)
    val status: EvalRunStatus? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
    val metadata: JsonObject? = null,
    @SerialName("eval_id") val evalId: String? = null,
    val model: String? = null,
    val result: JsonObject? = null,
)

@Serializable
data class EvalRunPage(
    @SerialName("object") val objectType: String? = null,
    val data: List<EvalRunObject> = emptyList(),
    @SerialName("first_id") val firstId: String? = null,
    @SerialName("last_id") val lastId: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class EvalRunOutputItemObject(
    val id: String? = null,
    @SerialName("object") val objectType: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("eval_id") val evalId: String? = null,
    @SerialName("run_id") val runId: String? = null,
    @SerialName("datasource_item") val datasourceItem: JsonObject? = null,
    @SerialName("datasource_item_id") val datasourceItemId: Long? = null,
    @Serializable(with = EvalRunOutputItemStatusSerializer::class)
    val status: EvalRunOutputItemStatus? = null,
    val type: String? = null,
    val sample: JsonObject? = null,
    val results: JsonArray? = null,
    val result: JsonObject? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class EvalRunOutputItemPage(
    @SerialName("object") val objectType: String? = null,
    val data: List<EvalRunOutputItemObject> = emptyList(),
    @SerialName("first_id") val firstId: String? = null,
    @SerialName("last_id") val lastId: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

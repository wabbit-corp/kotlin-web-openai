// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.web.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
        override val wireName: String = "fail"
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
    val dataSourceConfig: JsonObject,
    val testingCriteria: List<JsonObject>,
    val metadata: Map<String, String> = emptyMap(),
    val extraBody: JsonExtras? = null,
) {
    init {
        require(name.isNotBlank()) { "eval name must not be blank" }
        require(dataSourceConfig.isNotEmpty()) { "eval dataSourceConfig must not be empty" }
        require(testingCriteria.isNotEmpty()) { "eval testingCriteria must not be empty" }
        require(metadata.keys.all { it.isNotBlank() }) { "eval metadata keys must not be blank" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("name", name)
            put("data_source_config", dataSourceConfig)
            putJsonArray("testing_criteria") {
                testingCriteria.forEach { add(it) }
            }
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
data class EvalDataSourceConfigObject(
    val type: String? = null,
    val metadata: JsonObject? = null,
    val schema: JsonObject? = null,
    @SerialName("item_schema") val itemSchema: JsonObject? = null,
    @SerialName("include_sample_schema") val includeSampleSchema: Boolean? = null,
)

@Serializable
data class EvalTestingCriterionObject(
    val name: String? = null,
    val type: String? = null,
    val model: String? = null,
    val input: JsonArray? = null,
    @SerialName("passing_labels") val passingLabels: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
    val metadata: JsonObject? = null,
)

@Serializable
data class EvalObject(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    val name: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("updated_at") val updatedAt: Long? = null,
    val status: String? = null,
    val metadata: JsonObject? = null,
    @SerialName("data_source_config") val dataSourceConfig: EvalDataSourceConfigObject? = null,
    @SerialName("testing_criteria") val testingCriteria: List<EvalTestingCriterionObject> = emptyList(),
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
    val dataSource: JsonObject,
    val metadata: Map<String, String> = emptyMap(),
    val extraBody: JsonExtras? = null,
) {
    init {
        require(name == null || name.isNotBlank()) { "eval run name must not be blank when set" }
        require(dataSource.isNotEmpty()) { "eval run dataSource must not be empty" }
        require(metadata.keys.all { it.isNotBlank() }) { "eval run metadata keys must not be blank" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            name?.let { put("name", it) }
            put("data_source", dataSource)
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
data class EvalRunResultCounts(
    val total: Int? = null,
    val errored: Int? = null,
    val failed: Int? = null,
    val passed: Int? = null,
)

@Serializable
data class EvalRunDataSourceObject(
    val type: String? = null,
    val source: JsonObject? = null,
    @SerialName("input_messages") val inputMessages: JsonObject? = null,
    @SerialName("sampling_params") val samplingParams: JsonObject? = null,
    val model: String? = null,
)

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
    @SerialName("report_url") val reportUrl: String? = null,
    @SerialName("data_source") val dataSource: EvalRunDataSourceObject? = null,
    @SerialName("result_counts") val resultCounts: EvalRunResultCounts? = null,
    @SerialName("per_model_usage") val perModelUsage: JsonArray? = null,
    @SerialName("per_testing_criteria_results") val perTestingCriteriaResults: JsonArray? = null,
    val error: JsonObject? = null,
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
data class EvalRunOutputItemResult(
    val name: String? = null,
    val passed: Boolean? = null,
    val score: Double? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class EvalRunOutputItemSample(
    val model: String? = null,
    val input: JsonArray? = null,
    val output: JsonArray? = null,
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
    val sample: EvalRunOutputItemSample? = null,
    val results: List<EvalRunOutputItemResult> = emptyList(),
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

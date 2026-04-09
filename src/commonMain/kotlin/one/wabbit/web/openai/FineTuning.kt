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

sealed interface FineTuningNumberOrAuto {
    fun toJson(): JsonElement

    data object Auto : FineTuningNumberOrAuto {
        override fun toJson(): JsonElement = JsonPrimitive("auto")
    }

    data class IntValue(
        val value: Int,
    ) : FineTuningNumberOrAuto {
        init {
            require(value >= 0) { "fine-tuning integer value must be non-negative" }
        }

        override fun toJson(): JsonElement = JsonPrimitive(value)
    }

    data class DoubleValue(
        val value: Double,
    ) : FineTuningNumberOrAuto {
        init {
            require(value >= 0.0) { "fine-tuning double value must be non-negative" }
        }

        override fun toJson(): JsonElement = JsonPrimitive(value)
    }
}

data class FineTuningSupervisedHyperparameters(
    val batchSize: FineTuningNumberOrAuto? = null,
    val learningRateMultiplier: FineTuningNumberOrAuto? = null,
    val epochs: FineTuningNumberOrAuto? = null,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            batchSize?.let { put("batch_size", it.toJson()) }
            learningRateMultiplier?.let { put("learning_rate_multiplier", it.toJson()) }
            epochs?.let { put("n_epochs", it.toJson()) }
        }
}

data class FineTuningDpoHyperparameters(
    val beta: Double? = null,
    val batchSize: FineTuningNumberOrAuto? = null,
    val learningRateMultiplier: FineTuningNumberOrAuto? = null,
    val epochs: FineTuningNumberOrAuto? = null,
) {
    init {
        require(beta == null || beta >= 0.0) { "DPO beta must be non-negative when set" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            beta?.let { put("beta", it) }
            batchSize?.let { put("batch_size", it.toJson()) }
            learningRateMultiplier?.let { put("learning_rate_multiplier", it.toJson()) }
            epochs?.let { put("n_epochs", it.toJson()) }
        }
}

data class FineTuningReinforcementHyperparameters(
    val batchSize: FineTuningNumberOrAuto? = null,
    val learningRateMultiplier: FineTuningNumberOrAuto? = null,
    val epochs: FineTuningNumberOrAuto? = null,
    val evalInterval: FineTuningNumberOrAuto? = null,
    val evalSamples: FineTuningNumberOrAuto? = null,
    val computeMultiplier: FineTuningNumberOrAuto? = null,
    val reasoningEffort: ReasoningEffort? = null,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            batchSize?.let { put("batch_size", it.toJson()) }
            learningRateMultiplier?.let { put("learning_rate_multiplier", it.toJson()) }
            epochs?.let { put("n_epochs", it.toJson()) }
            evalInterval?.let { put("eval_interval", it.toJson()) }
            evalSamples?.let { put("eval_samples", it.toJson()) }
            computeMultiplier?.let { put("compute_multiplier", it.toJson()) }
            reasoningEffort?.let { put("reasoning_effort", it.wireName) }
        }
}

sealed interface FineTuningMethod {
    fun toJson(): JsonObject

    data class Supervised(
        val hyperparameters: FineTuningSupervisedHyperparameters? = null,
    ) : FineTuningMethod {
        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "supervised")
                putJsonObject("supervised") {
                    hyperparameters?.let { put("hyperparameters", it.toJson()) }
                }
            }
    }

    data class Dpo(
        val hyperparameters: FineTuningDpoHyperparameters? = null,
    ) : FineTuningMethod {
        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "dpo")
                putJsonObject("dpo") {
                    hyperparameters?.let { put("hyperparameters", it.toJson()) }
                }
            }
    }

    data class Reinforcement(
        val grader: JsonObject,
        val hyperparameters: FineTuningReinforcementHyperparameters? = null,
        val responseFormat: JsonObject? = null,
    ) : FineTuningMethod {
        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "reinforcement")
                putJsonObject("reinforcement") {
                    put("grader", grader)
                    hyperparameters?.let { put("hyperparameters", it.toJson()) }
                    responseFormat?.let { put("response_format", it) }
                }
            }
    }

    data class Raw(
        val json: JsonObject,
    ) : FineTuningMethod {
        override fun toJson(): JsonObject = json
    }
}

sealed interface FineTuningIntegration {
    fun toJson(): JsonObject

    data class Wandb(
        val project: String,
        val name: String? = null,
        val entity: String? = null,
        val tags: List<String> = emptyList(),
    ) : FineTuningIntegration {
        init {
            require(project.isNotBlank()) { "wandb project must not be blank" }
            require(name == null || name.isNotBlank()) { "wandb name must not be blank when set" }
            require(entity == null || entity.isNotBlank()) { "wandb entity must not be blank when set" }
            require(tags.all { it.isNotBlank() }) { "wandb tags must not contain blank values" }
        }

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "wandb")
                putJsonObject("wandb") {
                    put("project", project)
                    name?.let { put("name", it) }
                    entity?.let { put("entity", it) }
                    if (tags.isNotEmpty()) {
                        putJsonArray("tags") {
                            tags.forEach { add(JsonPrimitive(it)) }
                        }
                    }
                }
            }
    }

    data class Raw(
        val json: JsonObject,
    ) : FineTuningIntegration {
        override fun toJson(): JsonObject = json
    }
}

data class FineTuningJobCreateRequest(
    val trainingFile: FileId,
    val model: ModelId,
    val validationFile: FileId? = null,
    val suffix: String? = null,
    val seed: Int? = null,
    val metadata: Map<String, String> = emptyMap(),
    val integrations: List<FineTuningIntegration> = emptyList(),
    val method: FineTuningMethod? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(suffix == null || suffix.isNotBlank()) { "fine-tuning suffix must not be blank when set" }
        require(suffix == null || suffix.length <= 64) { "fine-tuning suffix must be at most 64 characters" }
        require(seed == null || seed >= 0) { "fine-tuning seed must be non-negative when set" }
        require(metadata.keys.all { it.isNotBlank() }) { "fine-tuning metadata keys must not be blank" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("training_file", trainingFile.value)
            put("model", model.value)
            validationFile?.let { put("validation_file", it.value) }
            suffix?.let { put("suffix", it) }
            seed?.let { put("seed", it) }
            if (metadata.isNotEmpty()) {
                putJsonObject("metadata") {
                    metadata.forEach { (key, value) -> put(key, value) }
                }
            }
            if (integrations.isNotEmpty()) {
                putJsonArray("integrations") {
                    integrations.forEach { add(it.toJson()) }
                }
            }
            method?.let { put("method", it.toJson()) }
            putJsonExtras(extraBody)
        }
}

sealed interface FineTuningMetadataFilter {
    data object None : FineTuningMetadataFilter

    data class Values(
        val entries: Map<String, String>,
    ) : FineTuningMetadataFilter {
        init {
            require(entries.keys.all { it.isNotBlank() }) { "fine-tuning metadata filter keys must not be blank" }
        }
    }
}

data class FineTuningJobListQuery(
    val after: String? = null,
    val limit: Int = 20,
    val metadata: FineTuningMetadataFilter? = null,
) {
    init {
        require(after == null || after.isNotBlank()) { "fine-tuning jobs after must not be blank when set" }
        require(limit in 1..100) { "fine-tuning jobs limit must be between 1 and 100" }
    }

    internal fun toParameters(): List<Pair<String, String>> =
        buildList {
            after?.let { add("after" to it) }
            add("limit" to limit.toString())
            when (metadata) {
                FineTuningMetadataFilter.None -> add("metadata" to "null")
                is FineTuningMetadataFilter.Values -> metadata.entries.forEach { (key, value) ->
                    add("metadata[$key]" to value)
                }
                null -> Unit
            }
        }
}

data class FineTuningJobEventListQuery(
    val after: String? = null,
    val limit: Int = 20,
) {
    init {
        require(after == null || after.isNotBlank()) { "fine-tuning events after must not be blank when set" }
        require(limit in 1..100) { "fine-tuning events limit must be between 1 and 100" }
    }

    internal fun toParameters(): List<Pair<String, String>> =
        buildList {
            after?.let { add("after" to it) }
            add("limit" to limit.toString())
        }
}

data class FineTuningCheckpointListQuery(
    val after: String? = null,
    val limit: Int = 10,
) {
    init {
        require(after == null || after.isNotBlank()) { "fine-tuning checkpoints after must not be blank when set" }
        require(limit in 1..100) { "fine-tuning checkpoints limit must be between 1 and 100" }
    }

    internal fun toParameters(): List<Pair<String, String>> =
        buildList {
            after?.let { add("after" to it) }
            add("limit" to limit.toString())
        }
}

enum class FineTuningCheckpointPermissionOrder(val wireName: String) {
    ASCENDING("ascending"),
    DESCENDING("descending"),
}

data class FineTuningCheckpointPermissionCreateRequest(
    val projectIds: List<String>,
) {
    init {
        require(projectIds.isNotEmpty()) { "checkpoint permission projectIds must not be empty" }
        require(projectIds.all { it.isNotBlank() }) { "checkpoint permission projectIds must not contain blank values" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            putJsonArray("project_ids") {
                projectIds.forEach { add(JsonPrimitive(it)) }
            }
        }
}

data class FineTuningCheckpointPermissionListQuery(
    val after: String? = null,
    val limit: Int = 20,
    val order: FineTuningCheckpointPermissionOrder = FineTuningCheckpointPermissionOrder.DESCENDING,
    val projectId: String? = null,
) {
    init {
        require(after == null || after.isNotBlank()) { "checkpoint permissions after must not be blank when set" }
        require(limit in 1..100) { "checkpoint permissions limit must be between 1 and 100" }
        require(projectId == null || projectId.isNotBlank()) { "checkpoint permissions projectId must not be blank when set" }
    }

    internal fun toParameters(): List<Pair<String, String>> =
        buildList {
            after?.let { add("after" to it) }
            add("limit" to limit.toString())
            add("order" to order.wireName)
            projectId?.let { add("project_id" to it) }
        }
}

data class FineTuningGraderRunRequest(
    val grader: JsonObject,
    val item: JsonElement? = null,
    val modelSample: JsonElement? = null,
    val extraBody: JsonExtras? = null,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("grader", grader)
            item?.let { put("item", it) }
            modelSample?.let { put("model_sample", it) }
            putJsonExtras(extraBody)
        }
}

data class FineTuningGraderValidateRequest(
    val grader: JsonObject,
    val extraBody: JsonExtras? = null,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("grader", grader)
            putJsonExtras(extraBody)
        }
}

@Serializable
data class FineTuningJob(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    val model: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("finished_at") val finishedAt: Long? = null,
    @SerialName("fine_tuned_model") val fineTunedModel: String? = null,
    @SerialName("organization_id") val organizationId: String? = null,
    @SerialName("result_files") val resultFiles: List<String> = emptyList(),
    @Serializable(with = FineTuningJobStatusSerializer::class)
    val status: FineTuningJobStatus? = null,
    @SerialName("validation_file") val validationFile: String? = null,
    @SerialName("training_file") val trainingFile: String? = null,
    val hyperparameters: JsonObject? = null,
    @SerialName("trained_tokens") val trainedTokens: Long? = null,
    val error: ResponseApiError? = null,
    val integrations: JsonArray? = null,
    val seed: Int? = null,
    @SerialName("estimated_finish") val estimatedFinish: Long? = null,
    val method: JsonObject? = null,
    val metadata: JsonObject? = null,
    @SerialName("user_provided_suffix") val userProvidedSuffix: String? = null,
    @SerialName("usage_metrics") val usageMetrics: JsonObject? = null,
    @SerialName("shared_with_openai") val sharedWithOpenAI: Boolean? = null,
)

@Serializable
data class FineTuningJobPage(
    @SerialName("object") val objectType: String? = null,
    val data: List<FineTuningJob> = emptyList(),
    @SerialName("first_id") val firstId: String? = null,
    @SerialName("last_id") val lastId: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class FineTuningJobEvent(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    val level: String? = null,
    val message: String? = null,
    val data: JsonElement? = null,
    val type: String? = null,
)

@Serializable
data class FineTuningJobEventPage(
    @SerialName("object") val objectType: String? = null,
    val data: List<FineTuningJobEvent> = emptyList(),
    @SerialName("first_id") val firstId: String? = null,
    @SerialName("last_id") val lastId: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class FineTuningCheckpointMetrics(
    @SerialName("full_valid_loss") val fullValidLoss: Double? = null,
    @SerialName("full_valid_mean_token_accuracy") val fullValidMeanTokenAccuracy: Double? = null,
    val step: Int? = null,
    @SerialName("train_loss") val trainLoss: Double? = null,
    @SerialName("train_mean_token_accuracy") val trainMeanTokenAccuracy: Double? = null,
    @SerialName("valid_loss") val validLoss: Double? = null,
    @SerialName("valid_mean_token_accuracy") val validMeanTokenAccuracy: Double? = null,
)

@Serializable
data class FineTuningCheckpoint(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("fine_tuned_model_checkpoint") val fineTunedModelCheckpoint: String? = null,
    @SerialName("fine_tuning_job_id") val fineTuningJobId: String? = null,
    val metrics: FineTuningCheckpointMetrics? = null,
    @SerialName("step_number") val stepNumber: Int? = null,
)

@Serializable
data class FineTuningCheckpointPage(
    @SerialName("object") val objectType: String? = null,
    val data: List<FineTuningCheckpoint> = emptyList(),
    @SerialName("first_id") val firstId: String? = null,
    @SerialName("last_id") val lastId: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class FineTuningCheckpointPermission(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("project_id") val projectId: String? = null,
)

@Serializable
data class FineTuningCheckpointPermissionPage(
    @SerialName("object") val objectType: String? = null,
    val data: List<FineTuningCheckpointPermission> = emptyList(),
    @SerialName("first_id") val firstId: String? = null,
    @SerialName("last_id") val lastId: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class FineTuningCheckpointPermissionDeleted(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    val deleted: Boolean = false,
)

@Serializable
data class FineTuningGraderRunResult(
    val reward: Double? = null,
    val metadata: JsonObject? = null,
    @SerialName("sub_rewards") val subRewards: JsonObject? = null,
    @SerialName("model_grader_token_usage_per_model") val modelGraderTokenUsagePerModel: JsonObject? = null,
)

@Serializable
data class FineTuningGraderValidateResult(
    val grader: JsonObject,
)

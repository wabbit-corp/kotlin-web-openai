// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.web.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

sealed interface EmbeddingInput {
    fun toJson(): JsonElement

    data class Text(
        val text: String,
    ) : EmbeddingInput {
        init {
            require(text.isNotBlank()) { "embedding text input must not be blank" }
        }

        override fun toJson(): JsonElement = JsonPrimitive(text)
    }

    data class TextBatch(
        val values: List<String>,
    ) : EmbeddingInput {
        init {
            require(values.isNotEmpty()) { "embedding text batch must not be empty" }
            require(values.all { it.isNotBlank() }) { "embedding text batch must not contain blank values" }
        }

        override fun toJson(): JsonElement =
            buildJsonArray {
                values.forEach { add(JsonPrimitive(it)) }
            }
    }

    data class Tokens(
        val values: List<Int>,
    ) : EmbeddingInput {
        init {
            require(values.isNotEmpty()) { "embedding token input must not be empty" }
        }

        override fun toJson(): JsonElement =
            buildJsonArray {
                values.forEach { add(JsonPrimitive(it)) }
            }
    }

    data class TokenBatch(
        val values: List<List<Int>>,
    ) : EmbeddingInput {
        init {
            require(values.isNotEmpty()) { "embedding token batch must not be empty" }
            require(values.all { it.isNotEmpty() }) { "embedding token batch must not contain empty items" }
        }

        override fun toJson(): JsonElement =
            buildJsonArray {
                values.forEach { batch ->
                    add(
                        buildJsonArray {
                            batch.forEach { add(JsonPrimitive(it)) }
                        },
                    )
                }
            }
    }

    data class Raw(
        val json: JsonElement,
    ) : EmbeddingInput {
        override fun toJson(): JsonElement = json
    }
}

enum class EmbeddingEncodingFormat(val wireName: String) {
    FLOAT("float"),
    BASE64("base64"),
}

data class EmbeddingCreateRequest(
    val model: ModelId,
    val input: EmbeddingInput,
    val dimensions: Int? = null,
    val encodingFormat: EmbeddingEncodingFormat? = null,
    val user: String? = null,
    val providerOptions: EmbeddingProviderOptions? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(dimensions == null || dimensions > 0) { "embedding dimensions must be positive when set" }
        require(user == null || user.isNotBlank()) { "embedding user must not be blank when set" }
    }

    fun requireCompatibleWith(provider: OpenAIProvider) {
        require(provider.capabilities.embeddingsApi) {
            "${provider.id} does not expose embeddings in this client"
        }
        providerOptions?.requireCompatibleWith(provider)
    }

    fun toJson(): kotlinx.serialization.json.JsonObject =
        buildJsonObject {
            put("model", model.value)
            put("input", input.toJson())
            dimensions?.let { put("dimensions", it) }
            encodingFormat?.let { put("encoding_format", it.wireName) }
            user?.let { put("user", it) }
            providerOptions?.applyTo(this)
            putJsonExtras(extraBody)
        }
}

@Serializable
data class EmbeddingUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

@Serializable
data class EmbeddingObject(
    @SerialName("object") val objectType: String? = null,
    val index: Int = 0,
    val embedding: JsonElement,
) {
    fun floatEmbeddingOrNull(): List<Double>? =
        runCatching {
            embedding.jsonArray.map { value ->
                value.jsonPrimitive.doubleOrNull ?: return null
            }
        }.getOrNull()

    fun requireFloatEmbedding(): List<Double> =
        floatEmbeddingOrNull() ?: error("Embedding payload is not a float array")

    fun base64EmbeddingOrNull(): String? = runCatching { embedding.jsonPrimitive.contentOrNull }.getOrNull()
}

@Serializable
data class EmbeddingResponse(
    @SerialName("object") val objectType: String? = null,
    val data: List<EmbeddingObject> = emptyList(),
    val model: String? = null,
    val usage: EmbeddingUsage? = null,
)

fun EmbeddingResponse.firstFloatEmbeddingOrNull(): List<Double>? = data.firstOrNull()?.floatEmbeddingOrNull()

fun EmbeddingResponse.firstBase64EmbeddingOrNull(): String? = data.firstOrNull()?.base64EmbeddingOrNull()

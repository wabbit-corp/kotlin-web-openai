// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.web.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

sealed interface ModerationInput {
    fun toJson(): JsonElement

    data class Text(
        val text: String,
    ) : ModerationInput {
        init {
            require(text.isNotBlank()) { "moderation text must not be blank" }
        }

        override fun toJson(): JsonElement = JsonPrimitive(text)
    }

    data class TextBatch(
        val values: List<String>,
    ) : ModerationInput {
        init {
            require(values.isNotEmpty()) { "moderation text batch must not be empty" }
            require(values.all { it.isNotBlank() }) { "moderation text batch must not contain blank values" }
        }

        override fun toJson(): JsonElement =
            buildJsonArray {
                values.forEach { add(JsonPrimitive(it)) }
            }
    }

    data class Items(
        val values: List<ModerationInputItem>,
    ) : ModerationInput {
        init {
            require(values.isNotEmpty()) { "moderation items must not be empty" }
        }

        override fun toJson(): JsonElement =
            buildJsonArray {
                values.forEach { add(it.toJson()) }
            }
    }

    data class Raw(
        val json: JsonElement,
    ) : ModerationInput {
        override fun toJson(): JsonElement = json
    }
}

sealed interface ModerationInputItem {
    fun toJson(): JsonObject

    data class Text(
        val text: String,
    ) : ModerationInputItem {
        init {
            require(text.isNotBlank()) { "moderation item text must not be blank" }
        }

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "text")
                put("text", text)
            }
    }

    data class ImageUrl(
        val url: String,
    ) : ModerationInputItem {
        init {
            require(url.isNotBlank()) { "moderation image url must not be blank" }
        }

        override fun toJson(): JsonObject =
            buildJsonObject {
                put("type", "image_url")
                putJsonObject("image_url") {
                    put("url", url)
                }
            }
    }

    data class Raw(
        val json: JsonObject,
    ) : ModerationInputItem {
        override fun toJson(): JsonObject = json
    }
}

data class ModerationCreateRequest(
    val input: ModerationInput,
    val model: ModelId? = null,
    val extraBody: JsonExtras? = null,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            put("input", input.toJson())
            model?.let { put("model", it.value) }
            putJsonExtras(extraBody)
        }
}

@Serializable
data class ModerationResult(
    val flagged: Boolean = false,
    val categories: JsonObject? = null,
    @SerialName("category_scores") val categoryScores: JsonObject? = null,
    @SerialName("category_applied_input_types") val categoryAppliedInputTypes: JsonObject? = null,
)

@Serializable
data class ModerationResponse(
    val id: String,
    val model: String? = null,
    val results: List<ModerationResult> = emptyList(),
) {
    fun anyFlagged(): Boolean = results.any { it.flagged }
}

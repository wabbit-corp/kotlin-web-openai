// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.web.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class OpenAIWebhookEvent(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    val type: String,
    @SerialName("created_at") val createdAt: Long? = null,
    val data: JsonObject? = null,
)

fun decodeOpenAIWebhookEvent(payload: String): OpenAIWebhookEvent =
    OpenAIJson.decodeFromString(payload)

fun decodeOpenAIWebhookEventOrNull(payload: String): OpenAIWebhookEvent? =
    runCatching { decodeOpenAIWebhookEvent(payload) }.getOrNull()

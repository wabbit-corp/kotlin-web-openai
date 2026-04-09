// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.web.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlin.jvm.JvmInline

/**
 * Additive-only request extensions for provider-specific or forward-compat JSON fields.
 *
 * These extras are intentionally not an unsafe override channel: typed request fields win,
 * and collisions are rejected instead of silently replacing validated values.
 */
@JvmInline
value class JsonExtras(
    val value: JsonObject,
)

internal fun JsonObjectBuilder.putJsonExtras(extras: JsonExtras?) {
    extras?.value?.forEach { (key, value) ->
        val previous = put(key, value)
        if (previous != null) {
            put(key, previous)
            throw IllegalArgumentException("JSON extras cannot override existing key '$key'")
        }
    }
}

internal fun requireNoExtraFieldCollisions(
    owner: String,
    extraFields: Map<String, String>,
    reservedFieldNames: Set<String>,
) {
    val collisions = extraFields.keys.intersect(reservedFieldNames)
    require(collisions.isEmpty()) {
        "$owner extra field names must not reuse reserved fields: ${collisions.sorted().joinToString(", ")}"
    }
}

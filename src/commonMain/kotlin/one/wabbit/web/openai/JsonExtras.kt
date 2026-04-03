package one.wabbit.web.openai

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlin.jvm.JvmInline

@JvmInline
value class JsonExtras(
    val value: JsonObject,
)

internal fun JsonObjectBuilder.putJsonExtras(extras: JsonExtras?) {
    extras?.value?.forEach { (key, value) -> put(key, value) }
}

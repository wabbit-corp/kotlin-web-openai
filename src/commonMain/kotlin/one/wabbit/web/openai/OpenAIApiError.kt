// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.web.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import one.wabbit.web.common.RetryPolicy

sealed class OpenAIApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Http(
        val url: String,
        val status: Int,
        val bodySample: String?,
        val retryAfterSeconds: Double? = null,
        includeBodySampleInMessage: Boolean = false,
    ) :
        OpenAIApiError(
            formatHttpErrorMessage(url, status, bodySample, includeBodySampleInMessage),
        )

    class Api(
        val url: String,
        val status: Int,
        val error: ResponseApiError,
        val retryAfterSeconds: Double? = null,
    ) :
        OpenAIApiError(
            buildString {
                append("OpenAI-compatible API error from ")
                append(url)
                append(" (")
                append(status)
                append(")")
                error.type?.let {
                    append(": ")
                    append(it)
                }
                error.message?.let {
                    append(": ")
                    append(it)
                }
            },
        )

    class Network(val url: String, cause: Throwable) :
        OpenAIApiError(
            "Network failure talking to $url: ${cause::class.simpleName ?: "Throwable"}: ${cause.message}",
            cause,
        )

    class Parse(
        val url: String,
        cause: Throwable,
        val bodySample: String? = null,
        includeBodySampleInMessage: Boolean = false,
    ) :
        OpenAIApiError(
            formatParseErrorMessage(url, cause, bodySample, includeBodySampleInMessage),
            cause,
        )
}

typealias OpenAIRetryPolicy = RetryPolicy<OpenAIApiError>

internal fun sanitizeExceptionBodySample(bodySample: String?): String? =
    bodySample?.let(::redactSensitiveBodySample)

private fun formatHttpErrorMessage(
    url: String,
    status: Int,
    bodySample: String?,
    includeBodySampleInMessage: Boolean,
): String =
    buildString {
        append("HTTP ")
        append(status)
        append(" from ")
        append(url)
        if (includeBodySampleInMessage && !bodySample.isNullOrBlank()) {
            append(", body sample: ")
            append(bodySample.take(256))
        }
    }

private fun formatParseErrorMessage(
    url: String,
    cause: Throwable,
    bodySample: String?,
    includeBodySampleInMessage: Boolean,
): String =
    buildString {
        append("Failed to parse response from ")
        append(url)
        append(": ")
        append(sanitizeParseCauseMessage(cause))
        if (includeBodySampleInMessage && !bodySample.isNullOrBlank()) {
            append(", body sample: ")
            append(bodySample.take(256))
        }
    }

private fun sanitizeParseCauseMessage(cause: Throwable): String {
    val raw = cause.message?.trim().orEmpty()
    if (raw.isBlank()) {
        return cause::class.simpleName ?: "parse error"
    }
    return raw
        .substringBefore("JSON input:")
        .substringBefore("body sample:")
        .trim()
        .ifBlank { cause::class.simpleName ?: "parse error" }
}

private const val RedactedBodySampleValue: String = "[REDACTED]"

private val sensitiveBodySampleKeys =
    setOf(
        "authorization",
        "api_key",
        "apikey",
        "access_token",
        "refresh_token",
        "token",
        "secret",
        "client_secret",
        "signed_url",
        "signature",
    )

private val authorizationAssignmentPattern =
    Regex("""(?i)\b(authorization)\b(\s*[:=]\s*)(Bearer\s+)?([^\s,;]+)""")

private val sensitiveAssignmentPattern =
    Regex(
        """(?i)\b(api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|signed[_-]?url|signature|secret|token)\b(\s*[:=]\s*)([^\s,;]+)""",
    )

private val sensitiveQueryParameterPattern =
    Regex(
        """(?i)([?&](?:api[_-]?key|access_token|refresh_token|client_secret|signature|sig|token|secret|key)=)([^&#\s]+)""",
    )

private fun redactSensitiveBodySample(bodySample: String): String {
    val trimmed = bodySample.trim()
    val jsonBody =
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            runCatching { OpenAIJson.parseToJsonElement(bodySample) }.getOrNull()
        } else {
            null
        }

    return jsonBody?.redactSensitiveValues()?.toString() ?: redactSensitivePlainText(bodySample)
}

private fun JsonElement.redactSensitiveValues(): JsonElement =
    when (this) {
        is JsonObject ->
            buildJsonObject {
                this@redactSensitiveValues.forEach { (key, value) ->
                    put(
                        key,
                        if (key.normalizeSensitiveKey() in sensitiveBodySampleKeys) {
                            JsonPrimitive(RedactedBodySampleValue)
                        } else {
                            value.redactSensitiveValues()
                        },
                    )
                }
            }
        is JsonArray ->
            buildJsonArray {
                this@redactSensitiveValues.forEach { add(it.redactSensitiveValues()) }
            }
        is JsonPrimitive ->
            if (isString) {
                JsonPrimitive(redactSensitivePlainText(content))
            } else {
                this
            }
    }

private fun redactSensitivePlainText(bodySample: String): String =
    bodySample
        .replace(authorizationAssignmentPattern) { match ->
            match.groupValues[1] + match.groupValues[2] + match.groupValues[3] + RedactedBodySampleValue
        }.replace(sensitiveAssignmentPattern) { match ->
            match.groupValues[1] + match.groupValues[2] + RedactedBodySampleValue
        }.replace(sensitiveQueryParameterPattern) { match ->
            match.groupValues[1] + RedactedBodySampleValue
        }

private fun String.normalizeSensitiveKey(): String = lowercase().replace('-', '_')

package one.wabbit.web.openai

import one.wabbit.web.common.RetryPolicy

sealed class OpenAIApiError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Http(
        val url: String,
        val status: Int,
        val bodySample: String?,
        val retryAfterSeconds: Double? = null,
    ) :
        OpenAIApiError(
            buildString {
                append("HTTP ")
                append(status)
                append(" from ")
                append(url)
                if (!bodySample.isNullOrBlank()) {
                    append(", body sample: ")
                    append(bodySample.take(256))
                }
            },
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
    ) :
        OpenAIApiError(
            buildString {
                append("Failed to parse response from ")
                append(url)
                append(": ")
                append(cause.message)
                if (!bodySample.isNullOrBlank()) {
                    append(", body sample: ")
                    append(bodySample.take(256))
                }
            },
            cause,
        )
}

typealias OpenAIRetryPolicy = RetryPolicy<OpenAIApiError>

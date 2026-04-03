package one.wabbit.web.openai

import io.ktor.http.Headers

data class OpenAIRateLimitBucket(
    val limit: Long? = null,
    val remaining: Long? = null,
    val reset: String? = null,
)

data class OpenAIRateLimitMetadata(
    val requests: OpenAIRateLimitBucket? = null,
    val tokens: OpenAIRateLimitBucket? = null,
    val inputTokens: OpenAIRateLimitBucket? = null,
    val outputTokens: OpenAIRateLimitBucket? = null,
)

data class OpenAIResponseMetadata(
    val providerId: String,
    val url: String,
    val status: Int,
    val headers: Headers,
    val requestId: String? = null,
    val processingMillis: Long? = null,
    val retryAfterSeconds: Double? = null,
    val rateLimits: OpenAIRateLimitMetadata = OpenAIRateLimitMetadata(),
)

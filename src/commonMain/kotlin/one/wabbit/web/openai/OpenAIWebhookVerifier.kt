@file:OptIn(
    dev.whyoleg.cryptography.CryptographyProviderApi::class,
    dev.whyoleg.cryptography.DelicateCryptographyApi::class,
    kotlin.io.encoding.ExperimentalEncodingApi::class,
)

package one.wabbit.web.openai

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

sealed class OpenAIWebhookVerificationException(message: String) : IllegalArgumentException(message) {
    class MissingHeader(header: String) : OpenAIWebhookVerificationException("missing webhook header: $header")
    class InvalidTimestamp(raw: String) : OpenAIWebhookVerificationException("invalid webhook timestamp: $raw")
    class TimestampOutsideTolerance(raw: String, tolerance: Duration) :
        OpenAIWebhookVerificationException("webhook timestamp $raw is outside tolerance ${tolerance.inWholeSeconds}s")
    data object InvalidSignature : OpenAIWebhookVerificationException("webhook signature verification failed")
}

/**
 * Common verifier for OpenAI webhook deliveries using the Standard Webhooks style headers documented by OpenAI:
 * `webhook-id`, `webhook-timestamp`, and `webhook-signature`.
 */
object OpenAIWebhookVerifier {
    private val hmac = CryptographyProvider.Default.get(HMAC)

    suspend fun verify(
        payload: ByteArray,
        webhookId: String,
        webhookTimestamp: String,
        webhookSignature: String,
        secret: String,
        tolerance: Duration = 5.minutes,
        nowEpochSeconds: Long = Clock.System.now().epochSeconds,
    ): OpenAIWebhookEvent {
        verifySignature(
            payload = payload,
            webhookId = webhookId,
            webhookTimestamp = webhookTimestamp,
            webhookSignature = webhookSignature,
            secret = secret,
            tolerance = tolerance,
            nowEpochSeconds = nowEpochSeconds,
        )
        return decodeOpenAIWebhookEvent(payload.decodeToString())
    }

    suspend fun verify(
        payload: ByteArray,
        headers: Map<String, String>,
        secret: String,
        tolerance: Duration = 5.minutes,
        nowEpochSeconds: Long = Clock.System.now().epochSeconds,
    ): OpenAIWebhookEvent =
        verify(
            payload = payload,
            webhookId = headers.requireHeader("webhook-id"),
            webhookTimestamp = headers.requireHeader("webhook-timestamp"),
            webhookSignature = headers.requireHeader("webhook-signature"),
            secret = secret,
            tolerance = tolerance,
            nowEpochSeconds = nowEpochSeconds,
        )

    suspend fun verifySignature(
        payload: ByteArray,
        webhookId: String,
        webhookTimestamp: String,
        webhookSignature: String,
        secret: String,
        tolerance: Duration = 5.minutes,
        nowEpochSeconds: Long = Clock.System.now().epochSeconds,
    ): Boolean {
        val timestamp = webhookTimestamp.toLongOrNull() ?: throw OpenAIWebhookVerificationException.InvalidTimestamp(webhookTimestamp)
        val skew = kotlin.math.abs(nowEpochSeconds - timestamp)
        if (skew > tolerance.inWholeSeconds) {
            throw OpenAIWebhookVerificationException.TimestampOutsideTolerance(webhookTimestamp, tolerance)
        }

        val signedPayload = "$webhookId.$webhookTimestamp.${payload.decodeToString()}".encodeToByteArray()
        val key = hmac.keyDecoder(SHA256).decodeFromByteArray(HMAC.Key.Format.RAW, decodeSecret(secret))
        val expected = key.signatureGenerator().generateSignature(signedPayload)
        val candidates = parseSignatureHeader(webhookSignature)
        if (candidates.none { candidate -> constantTimeEquals(candidate, expected) }) {
            throw OpenAIWebhookVerificationException.InvalidSignature
        }
        return true
    }

    private fun Map<String, String>.requireHeader(name: String): String =
        entries.firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }?.value
            ?: throw OpenAIWebhookVerificationException.MissingHeader(name)

    private fun parseSignatureHeader(raw: String): List<ByteArray> {
        val matches = Regex("""v1[=,]([A-Za-z0-9+/=_-]+)""").findAll(raw).mapNotNull { decodeSignature(it.groupValues[1]) }.toList()
        return if (matches.isNotEmpty()) {
            matches
        } else {
            listOfNotNull(decodeSignature(raw.trim()))
        }
    }

    private fun decodeSignature(raw: String): ByteArray? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        return runCatching { Base64.Default.decode(value) }
            .recoverCatching { Base64.UrlSafe.decode(value) }
            .recoverCatching { value.hexToByteArray() }
            .getOrNull()
    }

    private fun decodeSecret(secret: String): ByteArray =
        if (secret.startsWith("whsec_")) {
            val encoded = secret.removePrefix("whsec_")
            runCatching { Base64.Default.decode(encoded) }
                .recoverCatching { Base64.UrlSafe.decode(encoded) }
                .getOrElse { encoded.encodeToByteArray() }
        } else {
            secret.encodeToByteArray()
        }

    private fun constantTimeEquals(left: ByteArray, right: ByteArray): Boolean {
        if (left.size != right.size) return false
        var diff = 0
        for (i in left.indices) {
            diff = diff or (left[i].toInt() xor right[i].toInt())
        }
        return diff == 0
    }

    private fun String.hexToByteArray(): ByteArray {
        val clean = trim()
        require(clean.length % 2 == 0) { "hex signature must have even length" }
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}

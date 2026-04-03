package one.wabbit.web.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@Suppress("LongParameterList")
data class RealtimeAudioFormatConfig(
    val type: RealtimeAudioEncoding,
    val rate: Int? = null,
) {
    init {
        when (type) {
            RealtimeAudioEncoding.AUDIO_PCM -> require(rate == null || rate > 0) {
                "PCM realtime audio format rate must be positive when set"
            }
            RealtimeAudioEncoding.AUDIO_PCMU,
            RealtimeAudioEncoding.AUDIO_PCMA,
            -> require(rate == null) {
                "${type.wireName} realtime audio format must not specify rate"
            }
        }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("type", type.wireName)
            rate?.let { put("rate", it) }
        }

    companion object {
        val PCM_24K = RealtimeAudioFormatConfig(type = RealtimeAudioEncoding.AUDIO_PCM, rate = 24_000)
        val PCMU = RealtimeAudioFormatConfig(type = RealtimeAudioEncoding.AUDIO_PCMU)
        val PCMA = RealtimeAudioFormatConfig(type = RealtimeAudioEncoding.AUDIO_PCMA)
    }
}

sealed interface RealtimeNoiseReduction {
    fun toJsonElement(): JsonElement

    data object Disabled : RealtimeNoiseReduction {
        override fun toJsonElement(): JsonElement = JsonNull
    }

    data class Enabled(
        val type: RealtimeNoiseReductionType,
    ) : RealtimeNoiseReduction {
        override fun toJsonElement(): JsonElement =
            buildJsonObject {
                put("type", type.wireName)
            }
    }
}

data class RealtimeInputTranscriptionConfig(
    val model: ModelId,
    val language: String? = null,
    val prompt: String? = null,
) {
    init {
        require(language == null || language.isNotBlank()) {
            "realtime input transcription language must not be blank when set"
        }
        require(prompt == null || prompt.isNotBlank()) {
            "realtime input transcription prompt must not be blank when set"
        }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("model", model.value)
            language?.let { put("language", it) }
            prompt?.let { put("prompt", it) }
        }
}

sealed interface RealtimeTurnDetection {
    fun toJsonElement(): JsonElement

    data object Disabled : RealtimeTurnDetection {
        override fun toJsonElement(): JsonElement = JsonNull
    }

    @Suppress("LongParameterList")
    data class ServerVad(
        val threshold: Double? = null,
        val prefixPaddingMs: Int? = null,
        val silenceDurationMs: Int? = null,
        val createResponse: Boolean? = null,
        val interruptResponse: Boolean? = null,
        val idleTimeoutMs: Int? = null,
    ) : RealtimeTurnDetection {
        init {
            require(threshold == null || threshold in 0.0..1.0) {
                "server VAD threshold must be between 0.0 and 1.0 when set"
            }
            require(prefixPaddingMs == null || prefixPaddingMs >= 0) {
                "server VAD prefixPaddingMs must be non-negative when set"
            }
            require(silenceDurationMs == null || silenceDurationMs >= 0) {
                "server VAD silenceDurationMs must be non-negative when set"
            }
            require(idleTimeoutMs == null || idleTimeoutMs >= 0) {
                "server VAD idleTimeoutMs must be non-negative when set"
            }
        }

        override fun toJsonElement(): JsonElement =
            buildJsonObject {
                put("type", "server_vad")
                threshold?.let { put("threshold", it) }
                prefixPaddingMs?.let { put("prefix_padding_ms", it) }
                silenceDurationMs?.let { put("silence_duration_ms", it) }
                createResponse?.let { put("create_response", it) }
                interruptResponse?.let { put("interrupt_response", it) }
                idleTimeoutMs?.let { put("idle_timeout_ms", it) }
            }
    }

    data class SemanticVad(
        val eagerness: RealtimeSemanticVadEagerness? = null,
        val createResponse: Boolean? = null,
        val interruptResponse: Boolean? = null,
    ) : RealtimeTurnDetection {
        override fun toJsonElement(): JsonElement =
            buildJsonObject {
                put("type", "semantic_vad")
                eagerness?.let { put("eagerness", it.wireName) }
                createResponse?.let { put("create_response", it) }
                interruptResponse?.let { put("interrupt_response", it) }
            }
    }
}

data class RealtimeAudioInputConfig(
    val format: RealtimeAudioFormatConfig? = null,
    val noiseReduction: RealtimeNoiseReduction? = null,
    val transcription: RealtimeInputTranscriptionConfig? = null,
    val turnDetection: RealtimeTurnDetection? = null,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            format?.let { put("format", it.toJson()) }
            noiseReduction?.let { put("noise_reduction", it.toJsonElement()) }
            transcription?.let { put("transcription", it.toJson()) }
            turnDetection?.let { put("turn_detection", it.toJsonElement()) }
        }
}

data class RealtimeAudioOutputConfig(
    val format: RealtimeAudioFormatConfig? = null,
    val voice: String? = null,
    val speed: Double? = null,
) {
    init {
        require(voice == null || voice.isNotBlank()) { "realtime output voice must not be blank when set" }
        require(speed == null || speed > 0.0) { "realtime output speed must be positive when set" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            format?.let { put("format", it.toJson()) }
            voice?.let { put("voice", it) }
            speed?.let { put("speed", it) }
        }
}

data class RealtimeAudioConfig(
    val input: RealtimeAudioInputConfig? = null,
    val output: RealtimeAudioOutputConfig? = null,
) {
    fun toJson(): JsonObject =
        buildJsonObject {
            input?.let { put("input", it.toJson()) }
            output?.let { put("output", it.toJson()) }
        }
}

@Suppress("LongParameterList")
data class RealtimeSessionCreateRequest(
    val model: ModelId,
    val type: RealtimeSessionType = RealtimeSessionType.REALTIME,
    val outputModalities: List<RealtimeModality> = emptyList(),
    val instructions: String? = null,
    val audio: RealtimeAudioConfig? = null,
    val tools: List<ResponseTool> = emptyList(),
    val toolChoice: ResponseToolChoice? = null,
    val temperature: Double? = null,
    val maxResponseOutputTokens: JsonElement? = null,
    val include: List<RealtimeInclude> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val prompt: JsonObject? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(instructions == null || instructions.isNotBlank()) {
            "realtime session instructions must not be blank when set"
        }
        require(metadata.keys.all { it.isNotBlank() }) { "realtime session metadata keys must not be blank" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("type", type.wireName)
            put("model", model.value)
            if (outputModalities.isNotEmpty()) {
                putJsonArray("output_modalities") {
                    outputModalities.forEach { add(JsonPrimitive(it.wireName)) }
                }
            }
            instructions?.let { put("instructions", it) }
            audio?.let { put("audio", it.toJson()) }
            if (tools.isNotEmpty()) {
                putJsonArray("tools") {
                    tools.forEach { add(it.toJson()) }
                }
            }
            toolChoice?.let { put("tool_choice", it.toJson()) }
            temperature?.let { put("temperature", it) }
            maxResponseOutputTokens?.let { put("max_response_output_tokens", it) }
            if (include.isNotEmpty()) {
                putJsonArray("include") {
                    include.forEach { add(JsonPrimitive(it.wireName)) }
                }
            }
            if (metadata.isNotEmpty()) {
                put("metadata", JsonObject(metadata.mapValues { JsonPrimitive(it.value) }))
            }
            prompt?.let { put("prompt", it) }
            putJsonExtras(extraBody)
        }
}

data class RealtimeTranscriptionSessionCreateRequest(
    val model: ModelId,
    val type: RealtimeSessionType = RealtimeSessionType.TRANSCRIPTION,
    val audio: RealtimeAudioConfig,
    val include: List<RealtimeInclude> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val extraBody: JsonExtras? = null,
) {
    init {
        require(type == RealtimeSessionType.TRANSCRIPTION) {
            "realtime transcription sessions must use type=transcription"
        }
        require(metadata.keys.all { it.isNotBlank() }) {
            "realtime transcription session metadata keys must not be blank"
        }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("type", type.wireName)
            put("model", model.value)
            put("audio", audio.toJson())
            if (include.isNotEmpty()) {
                putJsonArray("include") {
                    include.forEach { add(JsonPrimitive(it.wireName)) }
                }
            }
            if (metadata.isNotEmpty()) {
                put("metadata", JsonObject(metadata.mapValues { JsonPrimitive(it.value) }))
            }
            putJsonExtras(extraBody)
        }
}

@Serializable
data class RealtimeClientSecret(
    val value: String? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
)

sealed interface RealtimeSessionTypeValue {
    val wireName: String

    data object Realtime : RealtimeSessionTypeValue {
        override val wireName: String = "realtime"
    }

    data object Transcription : RealtimeSessionTypeValue {
        override val wireName: String = "transcription"
    }

    data class Unknown(
        override val wireName: String,
    ) : RealtimeSessionTypeValue
}

internal object RealtimeSessionTypeValueSerializer :
    PreservingWireValueSerializer<RealtimeSessionTypeValue>(
        serialName = "one.wabbit.web.openai.RealtimeSessionTypeValue?",
        knownValues =
            listOf(
                RealtimeSessionTypeValue.Realtime,
                RealtimeSessionTypeValue.Transcription,
            ),
        wireName = RealtimeSessionTypeValue::wireName,
        unknown = RealtimeSessionTypeValue::Unknown,
    )

sealed interface RealtimeIncludeValue {
    val wireName: String

    data object ItemInputAudioTranscriptionLogprobs : RealtimeIncludeValue {
        override val wireName: String = "item.input_audio_transcription.logprobs"
    }

    data object ItemInputAudioTranscriptionConfidence : RealtimeIncludeValue {
        override val wireName: String = "item.input_audio_transcription.confidence"
    }

    data class Unknown(
        override val wireName: String,
    ) : RealtimeIncludeValue
}

internal object RealtimeIncludeValueSerializer :
    PreservingRequiredWireValueSerializer<RealtimeIncludeValue>(
        serialName = "one.wabbit.web.openai.RealtimeIncludeValue",
        knownValues =
            listOf(
                RealtimeIncludeValue.ItemInputAudioTranscriptionLogprobs,
                RealtimeIncludeValue.ItemInputAudioTranscriptionConfidence,
            ),
        wireName = RealtimeIncludeValue::wireName,
        unknown = RealtimeIncludeValue::Unknown,
    )

sealed interface RealtimeModalityValue {
    val wireName: String

    data object Text : RealtimeModalityValue {
        override val wireName: String = "text"
    }

    data object Audio : RealtimeModalityValue {
        override val wireName: String = "audio"
    }

    data class Unknown(
        override val wireName: String,
    ) : RealtimeModalityValue
}

internal object RealtimeModalityValueSerializer :
    PreservingRequiredWireValueSerializer<RealtimeModalityValue>(
        serialName = "one.wabbit.web.openai.RealtimeModalityValue",
        knownValues =
            listOf(
                RealtimeModalityValue.Text,
                RealtimeModalityValue.Audio,
            ),
        wireName = RealtimeModalityValue::wireName,
        unknown = RealtimeModalityValue::Unknown,
    )

@OptIn(ExperimentalSerializationApi::class)
internal object RealtimeModalityValueListSerializer : kotlinx.serialization.KSerializer<List<RealtimeModalityValue>?> {
    private val delegate = kotlinx.serialization.builtins.ListSerializer(RealtimeModalityValueSerializer)

    override val descriptor = delegate.descriptor

    override fun serialize(
        encoder: kotlinx.serialization.encoding.Encoder,
        value: List<RealtimeModalityValue>?,
    ) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeSerializableValue(delegate, value)
        }
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): List<RealtimeModalityValue>? {
        if (!decoder.decodeNotNullMark()) {
            decoder.decodeNull()
            return null
        }
        return decoder.decodeSerializableValue(delegate)
    }
}

@Serializable
data class RealtimeSessionObject(
    val id: String? = null,
    @SerialName("object") val objectType: String? = null,
    @Serializable(with = RealtimeSessionTypeValueSerializer::class)
    val type: RealtimeSessionTypeValue? = null,
    val model: String? = null,
    @SerialName("output_modalities")
    @Serializable(with = RealtimeModalityValueListSerializer::class)
    val outputModalities: List<RealtimeModalityValue>? = null,
    val instructions: String? = null,
    @SerialName("client_secret") val clientSecret: RealtimeClientSecret? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
    val metadata: JsonObject? = null,
    val audio: JsonObject? = null,
    val tools: JsonArray? = null,
    @SerialName("tool_choice") val toolChoice: JsonElement? = null,
    val temperature: Double? = null,
    @SerialName("max_response_output_tokens") val maxResponseOutputTokens: JsonElement? = null,
    @Serializable(with = RealtimeIncludeValueListSerializer::class)
    val include: List<RealtimeIncludeValue>? = null,
    val prompt: JsonObject? = null,
)

@OptIn(ExperimentalSerializationApi::class)
internal object RealtimeIncludeValueListSerializer : kotlinx.serialization.KSerializer<List<RealtimeIncludeValue>?> {
    private val delegate = kotlinx.serialization.builtins.ListSerializer(RealtimeIncludeValueSerializer)

    override val descriptor = delegate.descriptor

    override fun serialize(
        encoder: kotlinx.serialization.encoding.Encoder,
        value: List<RealtimeIncludeValue>?,
    ) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeSerializableValue(delegate, value)
        }
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): List<RealtimeIncludeValue>? {
        if (!decoder.decodeNotNullMark()) {
            decoder.decodeNull()
            return null
        }
        return decoder.decodeSerializableValue(delegate)
    }
}

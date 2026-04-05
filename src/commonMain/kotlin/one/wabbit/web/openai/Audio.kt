package one.wabbit.web.openai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class SpeechAudioFormat(val wireName: String) {
    MP3("mp3"),
    OPUS("opus"),
    AAC("aac"),
    FLAC("flac"),
    WAV("wav"),
    PCM("pcm"),

    ;

    companion object {
        @Deprecated("Use PCM", ReplaceWith("PCM"))
        val PCM16: SpeechAudioFormat = PCM
    }
}

enum class SpeechStreamFormat(val wireName: String) {
    AUDIO("audio"),
    SSE("sse"),
}

sealed interface SpeechVoice {
    fun toJsonElement(): kotlinx.serialization.json.JsonElement

    data class BuiltIn(
        val name: String,
    ) : SpeechVoice {
        init {
            require(name.isNotBlank()) { "speech voice name must not be blank" }
        }

        override fun toJsonElement(): kotlinx.serialization.json.JsonElement = JsonPrimitive(name)
    }

    data class Custom(
        val id: String,
    ) : SpeechVoice {
        init {
            require(id.isNotBlank()) { "speech voice id must not be blank" }
        }

        override fun toJsonElement(): kotlinx.serialization.json.JsonElement =
            buildJsonObject {
                put("id", id)
            }
    }
}

data class SpeechRequest(
    val model: ModelId,
    val input: String,
    val voice: SpeechVoice,
    val responseFormat: SpeechAudioFormat? = null,
    val speed: Double? = null,
    val instructions: String? = null,
    val streamFormat: SpeechStreamFormat? = null,
    val extraBody: JsonExtras? = null,
) {
    constructor(
        model: ModelId,
        input: String,
        voice: String,
        responseFormat: SpeechAudioFormat? = null,
        speed: Double? = null,
        instructions: String? = null,
        streamFormat: SpeechStreamFormat? = null,
        extraBody: JsonExtras? = null,
    ) : this(
        model = model,
        input = input,
        voice = SpeechVoice.BuiltIn(voice),
        responseFormat = responseFormat,
        speed = speed,
        instructions = instructions,
        streamFormat = streamFormat,
        extraBody = extraBody,
    )

    init {
        require(input.isNotBlank()) { "speech input must not be blank" }
        require(speed == null || speed in 0.25..4.0) {
            "speech speed must be between 0.25 and 4.0 when set"
        }
        require(instructions == null || instructions.isNotBlank()) {
            "speech instructions must not be blank when set"
        }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("model", model.value)
            put("input", input)
            put("voice", voice.toJsonElement())
            responseFormat?.let { put("response_format", it.wireName) }
            speed?.let { put("speed", it) }
            instructions?.let { put("instructions", it) }
            streamFormat?.let { put("stream_format", it.wireName) }
            putJsonExtras(extraBody)
        }
}

sealed interface SpeechStreamEvent {
    data class Started(
        val contentType: String? = null,
    ) : SpeechStreamEvent

    data class AudioChunk(
        val bytes: ByteArray,
    ) : SpeechStreamEvent {
        override fun equals(other: Any?): Boolean =
            other is AudioChunk && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    data class ServerEvent(
        val event: String? = null,
        val data: String,
        val id: String? = null,
        val retryMillis: Long? = null,
    ) : SpeechStreamEvent {
        init {
            require(data.isNotEmpty()) { "speech server event data must not be empty" }
        }
    }

    data class Error(
        val error: ResponseApiError,
    ) : SpeechStreamEvent

    data object Done : SpeechStreamEvent
}

suspend fun Flow<SpeechStreamEvent>.collectSpeechBytes(): ByteArray {
    val chunks = mutableListOf<ByteArray>()
    var streamError: ResponseApiError? = null
    collect { event ->
        when (event) {
            is SpeechStreamEvent.AudioChunk -> chunks += event.bytes
            is SpeechStreamEvent.Error -> streamError = event.error
            else -> Unit
        }
    }
    streamError?.let { error ->
        error(
            buildString {
                append("speech stream error")
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
    }
    val totalSize = chunks.sumOf { it.size }
    val result = ByteArray(totalSize)
    var offset = 0
    chunks.forEach { chunk ->
        chunk.copyInto(result, destinationOffset = offset)
        offset += chunk.size
    }
    return result
}

data class VoiceCreateRequest(
    val name: String,
    val consentId: String,
    val audioSample: BinaryUpload,
) {
    init {
        require(name.isNotBlank()) { "voice name must not be blank" }
        require(consentId.isNotBlank()) { "voice consentId must not be blank" }
    }
}

data class VoiceConsentCreateRequest(
    val name: String,
    val language: String,
    val recording: BinaryUpload,
) {
    init {
        require(name.isNotBlank()) { "voice consent name must not be blank" }
        require(language.isNotBlank()) { "voice consent language must not be blank" }
    }
}

data class VoiceConsentUpdateRequest(
    val name: String,
) {
    init {
        require(name.isNotBlank()) { "voice consent name must not be blank" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("name", name)
        }
}

data class VoiceConsentListQuery(
    val limit: Int = 20,
    val after: String? = null,
) {
    init {
        require(limit in 1..100) { "voice consent limit must be between 1 and 100" }
        require(after == null || after.isNotBlank()) { "voice consent after must not be blank when set" }
    }

    internal fun toParameters(): List<Pair<String, String>> =
        buildList {
            add("limit" to limit.toString())
            after?.let { add("after" to it) }
        }
}

@Serializable
data class AudioVoiceObject(
    val id: String,
    @SerialName("created_at") val createdAt: Long? = null,
    val name: String? = null,
    @SerialName("object") val objectType: String? = null,
)

@Serializable
data class VoiceConsentObject(
    val id: String,
    @SerialName("created_at") val createdAt: Long? = null,
    val language: String? = null,
    val name: String? = null,
    @SerialName("object") val objectType: String? = null,
)

@Serializable
data class VoiceConsentPage(
    @SerialName("object") val objectType: String? = null,
    val data: List<VoiceConsentObject> = emptyList(),
    @SerialName("first_id") val firstId: String? = null,
    @SerialName("last_id") val lastId: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
enum class AudioTextResponseFormat(val wireName: String) {
    @SerialName("json")
    JSON("json"),

    @SerialName("text")
    TEXT("text"),

    @SerialName("srt")
    SRT("srt"),

    @SerialName("verbose_json")
    VERBOSE_JSON("verbose_json"),

    @SerialName("vtt")
    VTT("vtt"),

    @SerialName("diarized_json")
    DIARIZED_JSON("diarized_json"),
}

sealed interface AudioTextResponseFormatValue {
    val wireName: String

    data object Json : AudioTextResponseFormatValue {
        override val wireName: String = "json"
    }

    data object Text : AudioTextResponseFormatValue {
        override val wireName: String = "text"
    }

    data object Srt : AudioTextResponseFormatValue {
        override val wireName: String = "srt"
    }

    data object VerboseJson : AudioTextResponseFormatValue {
        override val wireName: String = "verbose_json"
    }

    data object Vtt : AudioTextResponseFormatValue {
        override val wireName: String = "vtt"
    }

    data object DiarizedJson : AudioTextResponseFormatValue {
        override val wireName: String = "diarized_json"
    }

    data class Unknown(
        override val wireName: String,
    ) : AudioTextResponseFormatValue
}

internal object AudioTextResponseFormatValueSerializer :
    PreservingWireValueSerializer<AudioTextResponseFormatValue>(
        serialName = "one.wabbit.web.openai.AudioTextResponseFormatValue?",
        knownValues =
            listOf(
                AudioTextResponseFormatValue.Json,
                AudioTextResponseFormatValue.Text,
                AudioTextResponseFormatValue.Srt,
                AudioTextResponseFormatValue.VerboseJson,
                AudioTextResponseFormatValue.Vtt,
                AudioTextResponseFormatValue.DiarizedJson,
            ),
        wireName = AudioTextResponseFormatValue::wireName,
        unknown = AudioTextResponseFormatValue::Unknown,
    )

sealed interface AudioChunkingStrategy {
    data object Auto : AudioChunkingStrategy

    data class ServerVad(
        val threshold: Double? = null,
        val prefixPaddingMs: Int? = null,
        val silenceDurationMs: Int? = null,
    ) : AudioChunkingStrategy {
        init {
            require(threshold == null || threshold in 0.0..1.0) {
                "audio chunking strategy threshold must be between 0.0 and 1.0 when set"
            }
            require(prefixPaddingMs == null || prefixPaddingMs >= 0) {
                "audio chunking strategy prefixPaddingMs must be non-negative when set"
            }
            require(silenceDurationMs == null || silenceDurationMs >= 0) {
                "audio chunking strategy silenceDurationMs must be non-negative when set"
            }
        }
    }
}

data class TranscriptionRequest(
    val file: BinaryUpload,
    val model: ModelId,
    val chunkingStrategy: AudioChunkingStrategy? = null,
    val knownSpeakerNames: List<String> = emptyList(),
    val knownSpeakerReferences: List<String> = emptyList(),
    val language: String? = null,
    val prompt: String? = null,
    val responseFormat: AudioTextResponseFormat? = null,
    val stream: Boolean? = null,
    val temperature: Double? = null,
    val include: List<AudioTranscriptionInclude> = emptyList(),
    val timestampGranularities: List<AudioTimestampGranularity> = emptyList(),
    val extraFields: Map<String, String> = emptyMap(),
) {
    init {
        require(knownSpeakerNames.all { it.isNotBlank() }) {
            "transcription knownSpeakerNames must not contain blank values"
        }
        require(knownSpeakerReferences.all { it.isNotBlank() }) {
            "transcription knownSpeakerReferences must not contain blank values"
        }
        require(knownSpeakerNames.size == knownSpeakerReferences.size) {
            "transcription knownSpeakerNames and knownSpeakerReferences must have the same size"
        }
        require(knownSpeakerNames.size <= 4) {
            "transcription supports at most 4 knownSpeakerNames"
        }
        require(language == null || language.isNotBlank()) { "transcription language must not be blank when set" }
        require(prompt == null || prompt.isNotBlank()) { "transcription prompt must not be blank when set" }
        require(extraFields.keys.all { it.isNotBlank() }) { "transcription extra field names must not be blank" }
    }
}

data class TranslationRequest(
    val file: BinaryUpload,
    val model: ModelId,
    val prompt: String? = null,
    val responseFormat: AudioTextResponseFormat? = null,
    val temperature: Double? = null,
    val extraFields: Map<String, String> = emptyMap(),
) {
    init {
        require(prompt == null || prompt.isNotBlank()) { "translation prompt must not be blank when set" }
        require(extraFields.keys.all { it.isNotBlank() }) { "translation extra field names must not be blank" }
    }
}

@Serializable
data class TranscriptionWord(
    val word: String? = null,
    val start: Double? = null,
    val end: Double? = null,
    val speaker: String? = null,
)

@Serializable
data class TranscriptionLogProb(
    val token: String? = null,
    val bytes: List<Int> = emptyList(),
    val logprob: Double? = null,
)

@Serializable
data class TranscriptionSegment(
    val id: Int? = null,
    val text: String? = null,
    val start: Double? = null,
    val end: Double? = null,
    val speaker: String? = null,
    val type: String? = null,
    @SerialName("avg_logprob") val avgLogprob: Double? = null,
    @SerialName("compression_ratio") val compressionRatio: Double? = null,
    @SerialName("no_speech_prob") val noSpeechProb: Double? = null,
    val seek: Int? = null,
    val temperature: Double? = null,
    val tokens: List<Int> = emptyList(),
)

@Serializable
data class TranscriptionDiarizedSegment(
    val id: String? = null,
    val text: String? = null,
    val start: Double? = null,
    val end: Double? = null,
    val speaker: String? = null,
    val type: String? = null,
)

@Serializable
private data class TranscriptionResultSurrogate(
    val text: String,
    val task: String? = null,
    val language: String? = null,
    val duration: Double? = null,
    val logprobs: List<TranscriptionLogProb> = emptyList(),
    val words: List<TranscriptionWord> = emptyList(),
    val segments: JsonArray? = null,
    @SerialName("response_format")
    @Serializable(with = AudioTextResponseFormatValueSerializer::class)
    val responseFormat: AudioTextResponseFormatValue? = null,
    val usage: JsonObject? = null,
)

internal object TranscriptionResultSerializer : KSerializer<TranscriptionResult> {
    override val descriptor = TranscriptionResultSurrogate.serializer().descriptor

    override fun deserialize(decoder: Decoder): TranscriptionResult {
        val jsonDecoder = decoder as? JsonDecoder ?: error("TranscriptionResultSerializer only supports JSON")
        val payload = jsonDecoder.json.decodeFromJsonElement(TranscriptionResultSurrogate.serializer(), jsonDecoder.decodeJsonElement())
        val segmentsElement = payload.segments ?: JsonArray(emptyList())
        val useDiarizedSegments =
            payload.responseFormat == AudioTextResponseFormatValue.DiarizedJson ||
                segmentsElement.any { segment ->
                    segment.jsonObject["id"]?.jsonPrimitive?.isString == true
                }
        return TranscriptionResult(
            text = payload.text,
            task = payload.task,
            language = payload.language,
            duration = payload.duration,
            logprobs = payload.logprobs,
            words = payload.words,
            segments =
                if (useDiarizedSegments) {
                    emptyList()
                } else {
                    jsonDecoder.json.decodeFromJsonElement(
                        ListSerializer(TranscriptionSegment.serializer()),
                        segmentsElement,
                    )
                },
            diarizedSegments =
                if (useDiarizedSegments) {
                    jsonDecoder.json.decodeFromJsonElement(
                        ListSerializer(TranscriptionDiarizedSegment.serializer()),
                        segmentsElement,
                    )
                } else {
                    emptyList()
                },
            responseFormat = payload.responseFormat,
            usage = payload.usage,
        )
    }

    override fun serialize(encoder: Encoder, value: TranscriptionResult) {
        val jsonEncoder = encoder as? JsonEncoder ?: error("TranscriptionResultSerializer only supports JSON")
        val payload =
            buildJsonObject {
                put("text", value.text)
                value.task?.let { put("task", it) }
                value.language?.let { put("language", it) }
                value.duration?.let { put("duration", it) }
                if (value.logprobs.isNotEmpty()) {
                    put(
                        "logprobs",
                        jsonEncoder.json.encodeToJsonElement(
                            ListSerializer(TranscriptionLogProb.serializer()),
                            value.logprobs,
                        ),
                    )
                }
                if (value.words.isNotEmpty()) {
                    put(
                        "words",
                        jsonEncoder.json.encodeToJsonElement(
                            ListSerializer(TranscriptionWord.serializer()),
                            value.words,
                        ),
                    )
                }
                when {
                    value.diarizedSegments.isNotEmpty() ->
                        put(
                            "segments",
                            jsonEncoder.json.encodeToJsonElement(
                                ListSerializer(TranscriptionDiarizedSegment.serializer()),
                                value.diarizedSegments,
                            ),
                        )
                    value.segments.isNotEmpty() ->
                        put(
                            "segments",
                            jsonEncoder.json.encodeToJsonElement(
                                ListSerializer(TranscriptionSegment.serializer()),
                                value.segments,
                            ),
                        )
                }
                value.responseFormat?.let { put("response_format", it.wireName) }
                value.usage?.let { put("usage", it) }
            }
        jsonEncoder.encodeJsonElement(payload)
    }
}

@Serializable(with = TranscriptionResultSerializer::class)
data class TranscriptionResult(
    val text: String,
    val task: String? = null,
    val language: String? = null,
    val duration: Double? = null,
    val logprobs: List<TranscriptionLogProb> = emptyList(),
    val words: List<TranscriptionWord> = emptyList(),
    val segments: List<TranscriptionSegment> = emptyList(),
    val diarizedSegments: List<TranscriptionDiarizedSegment> = emptyList(),
    @SerialName("response_format")
    @Serializable(with = AudioTextResponseFormatValueSerializer::class)
    val responseFormat: AudioTextResponseFormatValue? = null,
    val usage: JsonObject? = null,
)

@Serializable
data class TranslationResult(
    val text: String,
    val language: String? = null,
    val duration: Double? = null,
    val words: List<TranscriptionWord> = emptyList(),
    val segments: List<TranscriptionSegment> = emptyList(),
    @SerialName("response_format")
    @Serializable(with = AudioTextResponseFormatValueSerializer::class)
    val responseFormat: AudioTextResponseFormatValue? = null,
    val usage: JsonObject? = null,
)

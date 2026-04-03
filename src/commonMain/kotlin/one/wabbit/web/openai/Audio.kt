package one.wabbit.web.openai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class SpeechAudioFormat(val wireName: String) {
    MP3("mp3"),
    OPUS("opus"),
    AAC("aac"),
    FLAC("flac"),
    WAV("wav"),
    PCM16("pcm16"),
}

enum class SpeechStreamFormat(val wireName: String) {
    AUDIO("audio"),
    SSE("sse"),
}

data class SpeechRequest(
    val model: ModelId,
    val input: String,
    val voice: String,
    val responseFormat: SpeechAudioFormat? = null,
    val speed: Double? = null,
    val instructions: String? = null,
    val streamFormat: SpeechStreamFormat? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(input.isNotBlank()) { "speech input must not be blank" }
        require(voice.isNotBlank()) { "speech voice must not be blank" }
        require(speed == null || speed > 0.0) { "speech speed must be positive when set" }
        require(instructions == null || instructions.isNotBlank()) {
            "speech instructions must not be blank when set"
        }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("model", model.value)
            put("input", input)
            put("voice", voice)
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
    ) : SpeechStreamEvent

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
            ),
        wireName = AudioTextResponseFormatValue::wireName,
        unknown = AudioTextResponseFormatValue::Unknown,
    )

data class TranscriptionRequest(
    val file: BinaryUpload,
    val model: ModelId,
    val language: String? = null,
    val prompt: String? = null,
    val responseFormat: AudioTextResponseFormat? = null,
    val temperature: Double? = null,
    val include: List<AudioTranscriptionInclude> = emptyList(),
    val timestampGranularities: List<AudioTimestampGranularity> = emptyList(),
    val extraFields: Map<String, String> = emptyMap(),
) {
    init {
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
)

@Serializable
data class TranscriptionSegment(
    val id: Int? = null,
    val text: String? = null,
    val start: Double? = null,
    val end: Double? = null,
    @SerialName("avg_logprob") val avgLogprob: Double? = null,
    @SerialName("compression_ratio") val compressionRatio: Double? = null,
    @SerialName("no_speech_prob") val noSpeechProb: Double? = null,
    val tokens: List<Int> = emptyList(),
)

@Serializable
data class TranscriptionResult(
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

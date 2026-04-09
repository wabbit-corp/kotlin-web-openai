// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.web.openai

import kotlin.jvm.JvmInline

enum class SearchContextSize(val wireName: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}

enum class InputImageDetail(val wireName: String) {
    LOW("low"),
    HIGH("high"),
    AUTO("auto"),
}

enum class InputAudioFormat(val wireName: String) {
    WAV("wav"),
    MP3("mp3"),
}

@JvmInline
value class ReasoningEffort(
    val wireName: String,
) {
    init {
        require(wireName.isNotBlank()) { "reasoning effort wireName must not be blank" }
    }

    companion object {
        val NONE = ReasoningEffort("none")
        val MINIMAL = ReasoningEffort("minimal")
        val LOW = ReasoningEffort("low")
        val MEDIUM = ReasoningEffort("medium")
        val HIGH = ReasoningEffort("high")
        val XHIGH = ReasoningEffort("xhigh")
    }
}

enum class ResponseServiceTier(val wireName: String) {
    AUTO("auto"),
    DEFAULT("default"),
    FLEX("flex"),
    SCALE("scale"),
    PRIORITY("priority"),
}

enum class ResponseTruncation(val wireName: String) {
    AUTO("auto"),
    DISABLED("disabled"),
}

enum class ResponsePromptCacheRetention(val wireName: String) {
    IN_MEMORY("in-memory"),
    HOURS_24("24h"),
}

enum class ResponseTextVerbosity(val wireName: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}

enum class ResponseReasoningSummary(val wireName: String) {
    AUTO("auto"),
    CONCISE("concise"),
    DETAILED("detailed"),
}

enum class AudioTranscriptionInclude(val wireName: String) {
    LOGPROBS("logprobs"),
}

enum class AudioTimestampGranularity(val wireName: String) {
    WORD("word"),
    SEGMENT("segment"),
}

enum class RealtimeModality(val wireName: String) {
    TEXT("text"),
    AUDIO("audio"),
}

enum class RealtimeSessionType(val wireName: String) {
    REALTIME("realtime"),
    TRANSCRIPTION("transcription"),
}

enum class RealtimeInclude(val wireName: String) {
    INPUT_AUDIO_TRANSCRIPTION_LOGPROBS("item.input_audio_transcription.logprobs"),
}

enum class RealtimeAudioEncoding(val wireName: String) {
    AUDIO_PCM("audio/pcm"),
    AUDIO_PCMU("audio/pcmu"),
    AUDIO_PCMA("audio/pcma"),
}

enum class RealtimeNoiseReductionType(val wireName: String) {
    NEAR_FIELD("near_field"),
    FAR_FIELD("far_field"),
}

enum class RealtimeSemanticVadEagerness(val wireName: String) {
    AUTO("auto"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
}

@kotlinx.serialization.Serializable
enum class BatchCompletionWindow(val wireName: String) {
    @kotlinx.serialization.SerialName("24h")
    HOURS_24("24h"),
}

sealed interface BatchStatus {
    val wireName: String

    data object Validating : BatchStatus {
        override val wireName: String = "validating"
    }

    data object Failed : BatchStatus {
        override val wireName: String = "failed"
    }

    data object InProgress : BatchStatus {
        override val wireName: String = "in_progress"
    }

    data object Finalizing : BatchStatus {
        override val wireName: String = "finalizing"
    }

    data object Completed : BatchStatus {
        override val wireName: String = "completed"
    }

    data object Expired : BatchStatus {
        override val wireName: String = "expired"
    }

    data object Cancelling : BatchStatus {
        override val wireName: String = "cancelling"
    }

    data object Cancelled : BatchStatus {
        override val wireName: String = "cancelled"
    }

    data class Unknown(
        override val wireName: String,
    ) : BatchStatus
}

internal object BatchStatusSerializer :
    PreservingWireValueSerializer<BatchStatus>(
        serialName = "one.wabbit.web.openai.BatchStatus?",
        knownValues =
            listOf(
                BatchStatus.Validating,
                BatchStatus.Failed,
                BatchStatus.InProgress,
                BatchStatus.Finalizing,
                BatchStatus.Completed,
                BatchStatus.Expired,
                BatchStatus.Cancelling,
                BatchStatus.Cancelled,
            ),
        wireName = BatchStatus::wireName,
        unknown = BatchStatus::Unknown,
    )

sealed interface UploadStatus {
    val wireName: String

    data object Pending : UploadStatus {
        override val wireName: String = "pending"
    }

    data object Completed : UploadStatus {
        override val wireName: String = "completed"
    }

    data object Cancelled : UploadStatus {
        override val wireName: String = "cancelled"
    }

    data object Expired : UploadStatus {
        override val wireName: String = "expired"
    }

    data class Unknown(
        override val wireName: String,
    ) : UploadStatus
}

internal object UploadStatusSerializer :
    PreservingWireValueSerializer<UploadStatus>(
        serialName = "one.wabbit.web.openai.UploadStatus?",
        knownValues =
            listOf(
                UploadStatus.Pending,
                UploadStatus.Completed,
                UploadStatus.Cancelled,
                UploadStatus.Expired,
            ),
        wireName = UploadStatus::wireName,
        unknown = UploadStatus::Unknown,
    )

sealed interface FineTuningJobStatus {
    val wireName: String

    data object ValidatingFiles : FineTuningJobStatus {
        override val wireName: String = "validating_files"
    }

    data object Queued : FineTuningJobStatus {
        override val wireName: String = "queued"
    }

    data object Running : FineTuningJobStatus {
        override val wireName: String = "running"
    }

    data object Paused : FineTuningJobStatus {
        override val wireName: String = "paused"
    }

    data object Succeeded : FineTuningJobStatus {
        override val wireName: String = "succeeded"
    }

    data object Failed : FineTuningJobStatus {
        override val wireName: String = "failed"
    }

    data object Cancelled : FineTuningJobStatus {
        override val wireName: String = "cancelled"
    }

    data class Unknown(
        override val wireName: String,
    ) : FineTuningJobStatus
}

internal object FineTuningJobStatusSerializer :
    PreservingWireValueSerializer<FineTuningJobStatus>(
        serialName = "one.wabbit.web.openai.FineTuningJobStatus?",
        knownValues =
            listOf(
                FineTuningJobStatus.ValidatingFiles,
                FineTuningJobStatus.Queued,
                FineTuningJobStatus.Running,
                FineTuningJobStatus.Paused,
                FineTuningJobStatus.Succeeded,
                FineTuningJobStatus.Failed,
                FineTuningJobStatus.Cancelled,
            ),
        wireName = FineTuningJobStatus::wireName,
        unknown = FineTuningJobStatus::Unknown,
    )

sealed interface VideoStatus {
    val wireName: String

    data object Queued : VideoStatus {
        override val wireName: String = "queued"
    }

    data object InProgress : VideoStatus {
        override val wireName: String = "in_progress"
    }

    @Deprecated("Legacy non-OpenAI status alias; prefer InProgress")
    data object Processing : VideoStatus {
        override val wireName: String = "processing"
    }

    data object Completed : VideoStatus {
        override val wireName: String = "completed"
    }

    data object Failed : VideoStatus {
        override val wireName: String = "failed"
    }

    data object Cancelled : VideoStatus {
        override val wireName: String = "cancelled"
    }

    data class Unknown(
        override val wireName: String,
    ) : VideoStatus
}

internal object VideoStatusSerializer :
    PreservingWireValueSerializer<VideoStatus>(
        serialName = "one.wabbit.web.openai.VideoStatus?",
        knownValues =
            listOf(
                VideoStatus.Queued,
                VideoStatus.InProgress,
                VideoStatus.Processing,
                VideoStatus.Completed,
                VideoStatus.Failed,
                VideoStatus.Cancelled,
            ),
        wireName = VideoStatus::wireName,
        unknown = VideoStatus::Unknown,
    )

enum class OpenRouterCacheControlType(val wireName: String) {
    EPHEMERAL("ephemeral"),
}

enum class OpenRouterDataCollection(val wireName: String) {
    ALLOW("allow"),
    DENY("deny"),
}

enum class OpenRouterProviderSort(val wireName: String) {
    PRICE("price"),
    THROUGHPUT("throughput"),
    LATENCY("latency"),
}

enum class OpenRouterRoute(val wireName: String) {
    FALLBACK("fallback"),
}

@kotlinx.serialization.Serializable
enum class FilePurpose(val wireName: String) {
    @kotlinx.serialization.SerialName("assistants")
    ASSISTANTS("assistants"),

    @kotlinx.serialization.SerialName("batch")
    BATCH("batch"),

    @kotlinx.serialization.SerialName("fine-tune")
    FINE_TUNE("fine-tune"),

    @kotlinx.serialization.SerialName("vision")
    VISION("vision"),

    @kotlinx.serialization.SerialName("user_data")
    USER_DATA("user_data"),

    @kotlinx.serialization.SerialName("evals")
    EVALS("evals"),
}

enum class BatchExpirationAnchor(val wireName: String) {
    CREATED_AT("created_at"),
}

enum class VideoSize(val wireName: String) {
    P720X1280("720x1280"),
    P1280X720("1280x720"),
    P1024X1792("1024x1792"),
    P1792X1024("1792x1024"),
}

enum class VideoSeconds(val wireName: String) {
    S4("4"),
    S8("8"),
    S12("12"),
    S16("16"),
    S20("20"),
}

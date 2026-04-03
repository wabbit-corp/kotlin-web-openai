package one.wabbit.web.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

data class BinaryUpload(
    val filename: String,
    val bytes: ByteArray,
    val contentType: String? = null,
) {
    init {
        require(filename.isNotBlank()) { "binary upload filename must not be blank" }
        require(contentType == null || contentType.isNotBlank()) {
            "binary upload contentType must not be blank when set"
        }
    }
}

data class FileCreateRequest(
    val purpose: FilePurpose,
    val file: BinaryUpload,
    val extraFields: Map<String, String> = emptyMap(),
) {
    init {
        require(extraFields.keys.all { it.isNotBlank() }) { "file extra field names must not be blank" }
    }
}

enum class FileListOrder(val wireName: String) {
    ASC("asc"),
    DESC("desc"),
}

data class FileListQuery(
    val purpose: FilePurpose? = null,
    val limit: Int = 20,
    val order: FileListOrder = FileListOrder.DESC,
    val after: String? = null,
) {
    init {
        require(limit in 1..100) { "file list limit must be between 1 and 100" }
        require(after == null || after.isNotBlank()) { "file list after must not be blank when set" }
    }

    internal fun toParameters(): List<Pair<String, String>> =
        buildList {
            purpose?.let { add("purpose" to it.wireName) }
            add("limit" to limit.toString())
            add("order" to order.wireName)
            after?.let { add("after" to it) }
        }
}

sealed interface FilePurposeValue {
    val wireName: String

    data object Assistants : FilePurposeValue {
        override val wireName: String = "assistants"
    }

    data object Batch : FilePurposeValue {
        override val wireName: String = "batch"
    }

    data object FineTune : FilePurposeValue {
        override val wireName: String = "fine-tune"
    }

    data object Vision : FilePurposeValue {
        override val wireName: String = "vision"
    }

    data object UserData : FilePurposeValue {
        override val wireName: String = "user_data"
    }

    data object Evals : FilePurposeValue {
        override val wireName: String = "evals"
    }

    data object AssistantsOutput : FilePurposeValue {
        override val wireName: String = "assistants_output"
    }

    data object BatchOutput : FilePurposeValue {
        override val wireName: String = "batch_output"
    }

    data object FineTuneResults : FilePurposeValue {
        override val wireName: String = "fine-tune-results"
    }

    data class Unknown(
        override val wireName: String,
    ) : FilePurposeValue
}

internal object FilePurposeValueSerializer :
    PreservingWireValueSerializer<FilePurposeValue>(
        serialName = "one.wabbit.web.openai.FilePurposeValue?",
        knownValues =
            listOf(
                FilePurposeValue.Assistants,
                FilePurposeValue.Batch,
                FilePurposeValue.FineTune,
                FilePurposeValue.Vision,
                FilePurposeValue.UserData,
                FilePurposeValue.Evals,
                FilePurposeValue.AssistantsOutput,
                FilePurposeValue.BatchOutput,
                FilePurposeValue.FineTuneResults,
            ),
        wireName = FilePurposeValue::wireName,
        unknown = FilePurposeValue::Unknown,
    )

sealed interface FileStatusValue {
    val wireName: String

    data object Uploaded : FileStatusValue {
        override val wireName: String = "uploaded"
    }

    data object Processed : FileStatusValue {
        override val wireName: String = "processed"
    }

    data object Error : FileStatusValue {
        override val wireName: String = "error"
    }

    data class Unknown(
        override val wireName: String,
    ) : FileStatusValue
}

internal object FileStatusValueSerializer :
    PreservingWireValueSerializer<FileStatusValue>(
        serialName = "one.wabbit.web.openai.FileStatusValue?",
        knownValues =
            listOf(
                FileStatusValue.Uploaded,
                FileStatusValue.Processed,
                FileStatusValue.Error,
            ),
        wireName = FileStatusValue::wireName,
        unknown = FileStatusValue::Unknown,
    )

@Serializable
data class OpenAIFile(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    val bytes: Long? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    val filename: String? = null,
    @Serializable(with = FilePurposeValueSerializer::class)
    val purpose: FilePurposeValue? = null,
    @Serializable(with = FileStatusValueSerializer::class)
    val status: FileStatusValue? = null,
    @SerialName("status_details") val statusDetails: String? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class FilePage(
    @SerialName("object") val objectType: String? = null,
    val data: List<OpenAIFile> = emptyList(),
    @SerialName("first_id") val firstId: String? = null,
    @SerialName("last_id") val lastId: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class DeletedObject(
    val id: String? = null,
    @SerialName("object") val objectType: String? = null,
    val deleted: Boolean = false,
)

// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.web.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class UploadCreateRequest(
    val filename: String,
    val bytes: Long,
    val mimeType: String,
    val purpose: FilePurpose,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(filename.isNotBlank()) { "upload filename must not be blank" }
        require(bytes > 0) { "upload bytes must be positive" }
        require(mimeType.isNotBlank()) { "upload mimeType must not be blank" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("filename", filename)
            put("bytes", bytes)
            put("mime_type", mimeType)
            put("purpose", purpose.wireName)
            putJsonExtras(extraBody)
        }
}

@Serializable
data class UploadObject(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    @Serializable(with = UploadStatusSerializer::class)
    val status: UploadStatus? = null,
    val filename: String? = null,
    @Serializable(with = FilePurposeValueSerializer::class)
    val purpose: FilePurposeValue? = null,
    val bytes: Long? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
    @SerialName("completed_at") val completedAt: Long? = null,
    @SerialName("file_id") val fileId: String? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class UploadPart(
    val id: String,
    @SerialName("object") val objectType: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("upload_id") val uploadId: String? = null,
)

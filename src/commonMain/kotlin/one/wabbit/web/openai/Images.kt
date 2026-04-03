package one.wabbit.web.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@Serializable
enum class ImageBackground(val wireName: String) {
    @SerialName("transparent")
    TRANSPARENT("transparent"),

    @SerialName("opaque")
    OPAQUE("opaque"),

    @SerialName("auto")
    AUTO("auto"),
}

sealed interface ImageBackgroundValue {
    val wireName: String

    data object Transparent : ImageBackgroundValue {
        override val wireName: String = "transparent"
    }

    data object Opaque : ImageBackgroundValue {
        override val wireName: String = "opaque"
    }

    data object Auto : ImageBackgroundValue {
        override val wireName: String = "auto"
    }

    data class Unknown(
        override val wireName: String,
    ) : ImageBackgroundValue
}

internal object ImageBackgroundValueSerializer :
    PreservingWireValueSerializer<ImageBackgroundValue>(
        serialName = "one.wabbit.web.openai.ImageBackgroundValue?",
        knownValues =
            listOf(
                ImageBackgroundValue.Transparent,
                ImageBackgroundValue.Opaque,
                ImageBackgroundValue.Auto,
            ),
        wireName = ImageBackgroundValue::wireName,
        unknown = ImageBackgroundValue::Unknown,
    )

enum class ImageInputFidelity(val wireName: String) {
    HIGH("high"),
    LOW("low"),
}

enum class ImageModeration(val wireName: String) {
    LOW("low"),
    AUTO("auto"),
}

@Serializable
enum class ImageOutputFormat(val wireName: String) {
    @SerialName("png")
    PNG("png"),

    @SerialName("jpeg")
    JPEG("jpeg"),

    @SerialName("webp")
    WEBP("webp"),
}

sealed interface ImageOutputFormatValue {
    val wireName: String

    data object Png : ImageOutputFormatValue {
        override val wireName: String = "png"
    }

    data object Jpeg : ImageOutputFormatValue {
        override val wireName: String = "jpeg"
    }

    data object Webp : ImageOutputFormatValue {
        override val wireName: String = "webp"
    }

    data class Unknown(
        override val wireName: String,
    ) : ImageOutputFormatValue
}

internal object ImageOutputFormatValueSerializer :
    PreservingWireValueSerializer<ImageOutputFormatValue>(
        serialName = "one.wabbit.web.openai.ImageOutputFormatValue?",
        knownValues =
            listOf(
                ImageOutputFormatValue.Png,
                ImageOutputFormatValue.Jpeg,
                ImageOutputFormatValue.Webp,
            ),
        wireName = ImageOutputFormatValue::wireName,
        unknown = ImageOutputFormatValue::Unknown,
    )

enum class ImageResponseFormat(val wireName: String) {
    URL("url"),
    B64_JSON("b64_json"),
}

@Serializable
enum class ImageQuality(val wireName: String) {
    @SerialName("standard")
    STANDARD("standard"),

    @SerialName("hd")
    HD("hd"),

    @SerialName("low")
    LOW("low"),

    @SerialName("medium")
    MEDIUM("medium"),

    @SerialName("high")
    HIGH("high"),

    @SerialName("auto")
    AUTO("auto"),
}

sealed interface ImageQualityValue {
    val wireName: String

    data object Standard : ImageQualityValue {
        override val wireName: String = "standard"
    }

    data object Hd : ImageQualityValue {
        override val wireName: String = "hd"
    }

    data object Low : ImageQualityValue {
        override val wireName: String = "low"
    }

    data object Medium : ImageQualityValue {
        override val wireName: String = "medium"
    }

    data object High : ImageQualityValue {
        override val wireName: String = "high"
    }

    data object Auto : ImageQualityValue {
        override val wireName: String = "auto"
    }

    data class Unknown(
        override val wireName: String,
    ) : ImageQualityValue
}

internal object ImageQualityValueSerializer :
    PreservingWireValueSerializer<ImageQualityValue>(
        serialName = "one.wabbit.web.openai.ImageQualityValue?",
        knownValues =
            listOf(
                ImageQualityValue.Standard,
                ImageQualityValue.Hd,
                ImageQualityValue.Low,
                ImageQualityValue.Medium,
                ImageQualityValue.High,
                ImageQualityValue.Auto,
            ),
        wireName = ImageQualityValue::wireName,
        unknown = ImageQualityValue::Unknown,
    )

@Serializable
enum class ImageSize(val wireName: String) {
    @SerialName("auto")
    AUTO("auto"),

    @SerialName("256x256")
    X256("256x256"),

    @SerialName("512x512")
    X512("512x512"),

    @SerialName("1024x1024")
    X1024("1024x1024"),

    @SerialName("1536x1024")
    X1536_BY_1024("1536x1024"),

    @SerialName("1024x1536")
    X1024_BY_1536("1024x1536"),

    @SerialName("1792x1024")
    X1792_BY_1024("1792x1024"),

    @SerialName("1024x1792")
    X1024_BY_1792("1024x1792"),
}

sealed interface ImageSizeValue {
    val wireName: String

    data object Auto : ImageSizeValue {
        override val wireName: String = "auto"
    }

    data object X256 : ImageSizeValue {
        override val wireName: String = "256x256"
    }

    data object X512 : ImageSizeValue {
        override val wireName: String = "512x512"
    }

    data object X1024 : ImageSizeValue {
        override val wireName: String = "1024x1024"
    }

    data object X1536_BY_1024 : ImageSizeValue {
        override val wireName: String = "1536x1024"
    }

    data object X1024_BY_1536 : ImageSizeValue {
        override val wireName: String = "1024x1536"
    }

    data object X1792_BY_1024 : ImageSizeValue {
        override val wireName: String = "1792x1024"
    }

    data object X1024_BY_1792 : ImageSizeValue {
        override val wireName: String = "1024x1792"
    }

    data class Unknown(
        override val wireName: String,
    ) : ImageSizeValue
}

internal object ImageSizeValueSerializer :
    PreservingWireValueSerializer<ImageSizeValue>(
        serialName = "one.wabbit.web.openai.ImageSizeValue?",
        knownValues =
            listOf(
                ImageSizeValue.Auto,
                ImageSizeValue.X256,
                ImageSizeValue.X512,
                ImageSizeValue.X1024,
                ImageSizeValue.X1536_BY_1024,
                ImageSizeValue.X1024_BY_1536,
                ImageSizeValue.X1792_BY_1024,
                ImageSizeValue.X1024_BY_1792,
            ),
        wireName = ImageSizeValue::wireName,
        unknown = ImageSizeValue::Unknown,
    )

sealed interface ImageReference {
    fun toJson(): JsonObject

    data class File(
        val fileId: FileId,
    ) : ImageReference {
        override fun toJson(): JsonObject = buildJsonObject { put("file_id", fileId.value) }
    }

    data class ImageUrl(
        val imageUrl: String,
    ) : ImageReference {
        init {
            require(imageUrl.isNotBlank()) { "imageUrl must not be blank" }
        }

        override fun toJson(): JsonObject = buildJsonObject { put("image_url", imageUrl) }
    }

    data class Raw(
        val json: JsonObject,
    ) : ImageReference {
        override fun toJson(): JsonObject = json
    }
}

data class ImageGenerateRequest(
    val prompt: String,
    val model: ModelId? = null,
    val background: ImageBackground? = null,
    val moderation: ImageModeration? = null,
    val n: Int? = null,
    val outputCompression: Int? = null,
    val outputFormat: ImageOutputFormat? = null,
    val partialImages: Int? = null,
    val quality: ImageQuality? = null,
    val responseFormat: ImageResponseFormat? = null,
    val size: ImageSize? = null,
    val stream: Boolean? = null,
    val user: String? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(prompt.isNotBlank()) { "image prompt must not be blank" }
        require(n == null || n in 1..10) { "image n must be between 1 and 10 when set" }
        require(outputCompression == null || outputCompression in 0..100) {
            "image outputCompression must be between 0 and 100 when set"
        }
        require(partialImages == null || partialImages in 0..3) {
            "image partialImages must be between 0 and 3 when set"
        }
        require(user == null || user.isNotBlank()) { "image user must not be blank when set" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("prompt", prompt)
            model?.let { put("model", it.value) }
            background?.let { put("background", it.wireName) }
            moderation?.let { put("moderation", it.wireName) }
            n?.let { put("n", it) }
            outputCompression?.let { put("output_compression", it) }
            outputFormat?.let { put("output_format", it.wireName) }
            partialImages?.let { put("partial_images", it) }
            quality?.let { put("quality", it.wireName) }
            responseFormat?.let { put("response_format", it.wireName) }
            size?.let { put("size", it.wireName) }
            stream?.let { put("stream", it) }
            user?.let { put("user", it) }
            putJsonExtras(extraBody)
        }
}

data class ImageEditRequest(
    val images: List<ImageReference>,
    val prompt: String,
    val background: ImageBackground? = null,
    val inputFidelity: ImageInputFidelity? = null,
    val mask: ImageReference? = null,
    val model: ModelId? = null,
    val moderation: ImageModeration? = null,
    val n: Int? = null,
    val outputCompression: Int? = null,
    val outputFormat: ImageOutputFormat? = null,
    val partialImages: Int? = null,
    val quality: ImageQuality? = null,
    val responseFormat: ImageResponseFormat? = null,
    val size: ImageSize? = null,
    val stream: Boolean? = null,
    val user: String? = null,
    val extraBody: JsonExtras? = null,
) {
    init {
        require(images.isNotEmpty()) { "image edit images must not be empty" }
        require(prompt.isNotBlank()) { "image edit prompt must not be blank" }
        require(n == null || n in 1..10) { "image edit n must be between 1 and 10 when set" }
        require(outputCompression == null || outputCompression in 0..100) {
            "image edit outputCompression must be between 0 and 100 when set"
        }
        require(partialImages == null || partialImages in 0..3) {
            "image edit partialImages must be between 0 and 3 when set"
        }
        require(user == null || user.isNotBlank()) { "image edit user must not be blank when set" }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            putJsonArray("images") {
                images.forEach { add(it.toJson()) }
            }
            put("prompt", prompt)
            background?.let { put("background", it.wireName) }
            inputFidelity?.let { put("input_fidelity", it.wireName) }
            mask?.let { put("mask", it.toJson()) }
            model?.let { put("model", it.value) }
            moderation?.let { put("moderation", it.wireName) }
            n?.let { put("n", it) }
            outputCompression?.let { put("output_compression", it) }
            outputFormat?.let { put("output_format", it.wireName) }
            partialImages?.let { put("partial_images", it) }
            quality?.let { put("quality", it.wireName) }
            responseFormat?.let { put("response_format", it.wireName) }
            size?.let { put("size", it.wireName) }
            stream?.let { put("stream", it) }
            user?.let { put("user", it) }
            putJsonExtras(extraBody)
        }
}

data class ImageVariationRequest(
    val image: BinaryUpload,
    val model: ModelId? = null,
    val n: Int? = null,
    val responseFormat: ImageResponseFormat? = null,
    val size: ImageSize? = null,
    val user: String? = null,
    val extraFields: Map<String, String> = emptyMap(),
) {
    init {
        require(n == null || n in 1..10) { "image variation n must be between 1 and 10 when set" }
        require(user == null || user.isNotBlank()) { "image variation user must not be blank when set" }
        require(extraFields.keys.all { it.isNotBlank() }) { "image variation extra field names must not be blank" }
    }
}

@Serializable
data class ImageTokenDetails(
    @SerialName("image_tokens") val imageTokens: Int? = null,
    @SerialName("text_tokens") val textTokens: Int? = null,
)

@Serializable
data class ImageUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("input_tokens_details") val inputTokenDetails: ImageTokenDetails? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("output_tokens_details") val outputTokenDetails: ImageTokenDetails? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

@Serializable
data class GeneratedImage(
    val url: String? = null,
    @SerialName("b64_json") val b64Json: String? = null,
    @SerialName("revised_prompt") val revisedPrompt: String? = null,
)

@Serializable
data class ImagesResponse(
    val created: Long? = null,
    @Serializable(with = ImageBackgroundValueSerializer::class)
    val background: ImageBackgroundValue? = null,
    val data: List<GeneratedImage> = emptyList(),
    @SerialName("output_format")
    @Serializable(with = ImageOutputFormatValueSerializer::class)
    val outputFormat: ImageOutputFormatValue? = null,
    @Serializable(with = ImageQualityValueSerializer::class)
    val quality: ImageQualityValue? = null,
    @Serializable(with = ImageSizeValueSerializer::class)
    val size: ImageSizeValue? = null,
    val usage: ImageUsage? = null,
) {
    fun urls(): List<String> = data.mapNotNull { it.url }

    fun base64Images(): List<String> = data.mapNotNull { it.b64Json }
}

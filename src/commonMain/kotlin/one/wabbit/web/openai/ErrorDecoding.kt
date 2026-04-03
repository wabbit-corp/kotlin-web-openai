package one.wabbit.web.openai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

internal fun parseResponseApiErrorBody(body: String): ResponseApiError? =
    runCatching { OpenAIJson.parseToJsonElement(body).toResponseApiErrorOrNull() }.getOrNull()

internal fun JsonElement.toResponseApiErrorOrNull(): ResponseApiError? =
    when (this) {
        is JsonObject -> toResponseApiErrorOrNull()
        is JsonPrimitive -> contentOrNull?.takeIf { it.isNotBlank() }?.let { ResponseApiError(message = it) }
        else -> null
    }

internal fun JsonObject.toResponseApiErrorOrNull(): ResponseApiError? {
    get("error")?.toResponseApiErrorOrNull()?.let { parsed ->
        val details =
            when {
                keys.any { it != "error" } -> this
                get("error") is JsonObject -> getValue("error").jsonObject
                else -> parsed.details
            }
        return if (details == parsed.details) parsed else parsed.copy(details = details)
    }

    get("detail")?.toResponseApiErrorOrNull()?.let { parsed ->
        return if (parsed.details != null) parsed else parsed.copy(details = this)
    }

    return decodeResponseApiErrorFieldsOrNull()
}

internal fun JsonObject.decodeResponseApiErrorFieldsOrNull(): ResponseApiError? {
    val message = stringField("message") ?: stringField("detail") ?: stringField("title")
    val type = stringField("type") ?: stringField("status")
    val param = stringField("param")
    val code = decodeResponseErrorCodeOrNull(this["code"])

    if (message == null && type == null && param == null && code == null) return null

    return ResponseApiError(
        message = message,
        type = type,
        param = param,
        code = code,
        details = this,
    )
}

internal fun decodeResponseErrorCodeOrNull(element: JsonElement?): ResponseErrorCode? =
    when (element) {
        null -> null
        is JsonPrimitive -> decodeResponseErrorCodeOrNull(element.contentOrNull)
        else -> null
    }

internal fun decodeResponseErrorCodeOrNull(raw: String?): ResponseErrorCode? =
    raw?.let {
        when (it) {
            ResponseErrorCode.ServerError.wireName -> ResponseErrorCode.ServerError
            ResponseErrorCode.RateLimitExceeded.wireName -> ResponseErrorCode.RateLimitExceeded
            ResponseErrorCode.InvalidPrompt.wireName -> ResponseErrorCode.InvalidPrompt
            ResponseErrorCode.VectorStoreTimeout.wireName -> ResponseErrorCode.VectorStoreTimeout
            ResponseErrorCode.InvalidImage.wireName -> ResponseErrorCode.InvalidImage
            ResponseErrorCode.InvalidImageFormat.wireName -> ResponseErrorCode.InvalidImageFormat
            ResponseErrorCode.InvalidBase64Image.wireName -> ResponseErrorCode.InvalidBase64Image
            ResponseErrorCode.InvalidImageUrl.wireName -> ResponseErrorCode.InvalidImageUrl
            ResponseErrorCode.ImageTooLarge.wireName -> ResponseErrorCode.ImageTooLarge
            ResponseErrorCode.ImageTooSmall.wireName -> ResponseErrorCode.ImageTooSmall
            ResponseErrorCode.ImageParseError.wireName -> ResponseErrorCode.ImageParseError
            ResponseErrorCode.ImageContentPolicyViolation.wireName -> ResponseErrorCode.ImageContentPolicyViolation
            ResponseErrorCode.InvalidImageMode.wireName -> ResponseErrorCode.InvalidImageMode
            ResponseErrorCode.ImageFileTooLarge.wireName -> ResponseErrorCode.ImageFileTooLarge
            ResponseErrorCode.UnsupportedImageMediaType.wireName -> ResponseErrorCode.UnsupportedImageMediaType
            ResponseErrorCode.EmptyImageFile.wireName -> ResponseErrorCode.EmptyImageFile
            ResponseErrorCode.FailedToDownloadImage.wireName -> ResponseErrorCode.FailedToDownloadImage
            ResponseErrorCode.ImageFileNotFound.wireName -> ResponseErrorCode.ImageFileNotFound
            else -> ResponseErrorCode.Unknown(it)
        }
    }

private fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull

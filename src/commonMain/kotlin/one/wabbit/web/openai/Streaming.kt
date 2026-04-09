// SPDX-License-Identifier: AGPL-3.0-or-later

package one.wabbit.web.openai

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.jvm.JvmInline

@JvmInline
value class ServerSentEventData(val value: String)

data class ServerSentEvent(
    val event: String? = null,
    val data: ServerSentEventData,
    val id: String? = null,
    val retryMillis: Long? = null,
)

private object StreamEventType {
    const val DONE = "[DONE]"
    const val ERROR = "error"

    const val RESPONSE_CREATED = "response.created"
    const val RESPONSE_IN_PROGRESS = "response.in_progress"
    const val RESPONSE_COMPLETED = "response.completed"
    const val RESPONSE_FAILED = "response.failed"
    const val RESPONSE_OUTPUT_ITEM_ADDED = "response.output_item.added"
    const val RESPONSE_OUTPUT_ITEM_DONE = "response.output_item.done"
    const val RESPONSE_CONTENT_PART_ADDED = "response.content_part.added"
    const val RESPONSE_CONTENT_PART_DONE = "response.content_part.done"
    const val RESPONSE_OUTPUT_TEXT_DELTA = "response.output_text.delta"
    const val RESPONSE_OUTPUT_TEXT_DONE = "response.output_text.done"
    const val RESPONSE_FUNCTION_CALL_ARGUMENTS_DELTA = "response.function_call_arguments.delta"
    const val RESPONSE_FUNCTION_CALL_ARGUMENTS_DONE = "response.function_call_arguments.done"
    const val RESPONSE_MCP_CALL_ARGUMENTS_DELTA = "response.mcp_call_arguments.delta"
    const val RESPONSE_MCP_CALL_ARGUMENTS_DONE = "response.mcp_call_arguments.done"
    const val RESPONSE_REASONING_SUMMARY_TEXT_DELTA = "response.reasoning_summary_text.delta"
    const val RESPONSE_REASONING_SUMMARY_TEXT_DONE = "response.reasoning_summary_text.done"
    const val RESPONSE_REFUSAL_DELTA = "response.refusal.delta"
    const val RESPONSE_REFUSAL_DONE = "response.refusal.done"
    const val RESPONSE_IMAGE_GENERATION_CALL_PARTIAL_IMAGE = "response.image_generation_call.partial_image"
    const val RESPONSE_OUTPUT_TEXT_ANNOTATION_ADDED = "response.output_text.annotation.added"
    const val RESPONSE_AUDIO_DELTA = "response.audio.delta"
    const val RESPONSE_AUDIO_DONE = "response.audio.done"
    const val RESPONSE_AUDIO_TRANSCRIPT_DELTA = "response.audio.transcript.delta"
    const val RESPONSE_AUDIO_TRANSCRIPT_DONE = "response.audio.transcript.done"
    const val TRANSCRIPT_TEXT_DELTA = "transcript.text.delta"
    const val TRANSCRIPT_TEXT_DONE = "transcript.text.done"

    const val IMAGE_GENERATION_PARTIAL_IMAGE = "image_generation.partial_image"
    const val IMAGE_EDIT_PARTIAL_IMAGE = "image_edit.partial_image"
    const val IMAGE_GENERATION_COMPLETED = "image_generation.completed"
    const val IMAGE_EDIT_COMPLETED = "image_edit.completed"
}

private class ServerSentEventParserState {
    private var event: String? = null
    private var id: String? = null
    private var retryMillis: Long? = null
    private val data = mutableListOf<String>()

    fun feedLine(line: String): ServerSentEvent? {
        if (line.isEmpty()) return flush()
        if (line.startsWith(':')) return null

        val fieldSeparator = line.indexOf(':')
        val field = if (fieldSeparator >= 0) line.substring(0, fieldSeparator) else line
        val rawValue = if (fieldSeparator >= 0) line.substring(fieldSeparator + 1).removePrefix(" ") else ""
        when (field) {
            "event" -> event = rawValue
            "data" -> data += rawValue
            "id" -> id = rawValue
            "retry" -> retryMillis = rawValue.toLongOrNull()
        }
        return null
    }

    fun finish(): ServerSentEvent? = flush()

    private fun flush(): ServerSentEvent? {
        if (data.isEmpty()) {
            event = null
            id = null
            retryMillis = null
            return null
        }
        val result =
            ServerSentEvent(
                event = event,
                data = ServerSentEventData(data.joinToString(separator = "\n")),
                id = id,
                retryMillis = retryMillis,
            )
        event = null
        id = null
        retryMillis = null
        data.clear()
        return result
    }
}

fun parseServerSentEvents(source: String): List<ServerSentEvent> {
    val events = mutableListOf<ServerSentEvent>()
    val parser = ServerSentEventParserState()

    source.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
        parser.feedLine(line)?.let(events::add)
    }
    parser.finish()?.let(events::add)
    return events
}

sealed interface ResponseStreamEvent {
    val type: String

    data object Done : ResponseStreamEvent {
        override val type: String = StreamEventType.DONE
    }

    data class ResponseCreated(val response: ResponseObject) : ResponseStreamEvent {
        override val type: String = StreamEventType.RESPONSE_CREATED
    }

    data class ResponseInProgress(val response: ResponseObject) : ResponseStreamEvent {
        override val type: String = StreamEventType.RESPONSE_IN_PROGRESS
    }

    data class ResponseCompleted(val response: ResponseObject) : ResponseStreamEvent {
        override val type: String = StreamEventType.RESPONSE_COMPLETED
    }

    data class ResponseFailed(
        val response: ResponseObject? = null,
        val error: ResponseApiError? = null,
    ) : ResponseStreamEvent {
        override val type: String = StreamEventType.RESPONSE_FAILED
    }

    data class OutputItemAdded(
        val outputIndex: Int? = null,
        val item: ResponseOutputItem,
    ) : ResponseStreamEvent {
        override val type: String = StreamEventType.RESPONSE_OUTPUT_ITEM_ADDED
    }

    data class OutputItemDone(
        val outputIndex: Int? = null,
        val item: ResponseOutputItem,
    ) : ResponseStreamEvent {
        override val type: String = StreamEventType.RESPONSE_OUTPUT_ITEM_DONE
    }

    data class ContentPartAdded(
        val itemId: String? = null,
        val outputIndex: Int? = null,
        val contentIndex: Int? = null,
        val part: ResponseContentPart? = null,
    ) : ResponseStreamEvent {
        override val type: String = StreamEventType.RESPONSE_CONTENT_PART_ADDED
    }

    data class ContentPartDone(
        val itemId: String? = null,
        val outputIndex: Int? = null,
        val contentIndex: Int? = null,
        val part: ResponseContentPart? = null,
    ) : ResponseStreamEvent {
        override val type: String = StreamEventType.RESPONSE_CONTENT_PART_DONE
    }

    data class OutputTextDelta(
        val itemId: String? = null,
        val outputIndex: Int? = null,
        val contentIndex: Int? = null,
        val delta: String,
    ) : ResponseStreamEvent {
        override val type: String = StreamEventType.RESPONSE_OUTPUT_TEXT_DELTA
    }

    data class OutputTextDone(
        val itemId: String? = null,
        val outputIndex: Int? = null,
        val contentIndex: Int? = null,
        val text: String? = null,
    ) : ResponseStreamEvent {
        override val type: String = StreamEventType.RESPONSE_OUTPUT_TEXT_DONE
    }

    data class FunctionCallArgumentsDelta(
        val itemId: String? = null,
        val outputIndex: Int? = null,
        val delta: String,
    ) : ResponseStreamEvent {
        override val type: String = StreamEventType.RESPONSE_FUNCTION_CALL_ARGUMENTS_DELTA
    }

    data class FunctionCallArgumentsDone(
        val itemId: String? = null,
        val outputIndex: Int? = null,
        val arguments: String? = null,
    ) : ResponseStreamEvent {
        override val type: String = StreamEventType.RESPONSE_FUNCTION_CALL_ARGUMENTS_DONE
    }

    data class McpCallArgumentsDelta(
        val itemId: String? = null,
        val outputIndex: Int? = null,
        val delta: String,
    ) : ResponseStreamEvent {
        override val type: String = StreamEventType.RESPONSE_MCP_CALL_ARGUMENTS_DELTA
    }

    data class McpCallArgumentsDone(
        val itemId: String? = null,
        val outputIndex: Int? = null,
        val arguments: String? = null,
    ) : ResponseStreamEvent {
        override val type: String = StreamEventType.RESPONSE_MCP_CALL_ARGUMENTS_DONE
    }

    data class ReasoningSummaryTextDelta(
        val itemId: String? = null,
        val outputIndex: Int? = null,
        val delta: String,
    ) : ResponseStreamEvent {
        override val type: String = StreamEventType.RESPONSE_REASONING_SUMMARY_TEXT_DELTA
    }

    data class ReasoningSummaryTextDone(
        val itemId: String? = null,
        val outputIndex: Int? = null,
        val text: String? = null,
    ) : ResponseStreamEvent {
        override val type: String = StreamEventType.RESPONSE_REASONING_SUMMARY_TEXT_DONE
    }

    data class RefusalDelta(
        val itemId: String? = null,
        val outputIndex: Int? = null,
        val contentIndex: Int? = null,
        val delta: String,
    ) : ResponseStreamEvent {
        override val type: String = StreamEventType.RESPONSE_REFUSAL_DELTA
    }

    data class RefusalDone(
        val itemId: String? = null,
        val outputIndex: Int? = null,
        val contentIndex: Int? = null,
        val refusal: String? = null,
    ) : ResponseStreamEvent {
        override val type: String = StreamEventType.RESPONSE_REFUSAL_DONE
    }

    data class Error(val error: ResponseApiError) : ResponseStreamEvent {
        override val type: String = StreamEventType.ERROR
    }

    data class HostedToolCallProgress(
        override val type: String,
        val itemId: String? = null,
        val outputIndex: Int? = null,
        val item: ResponseOutputItem? = null,
    ) : ResponseStreamEvent

    data class ImageGenerationCallPartialImage(
        val itemId: String? = null,
        val outputIndex: Int? = null,
        val partialImageIndex: Int? = null,
        val b64Json: String,
    ) : ResponseStreamEvent {
        override val type: String = StreamEventType.RESPONSE_IMAGE_GENERATION_CALL_PARTIAL_IMAGE
    }

    data class OutputTextAnnotationAdded(
        val itemId: String? = null,
        val outputIndex: Int? = null,
        val contentIndex: Int? = null,
        val annotationIndex: Int? = null,
        val annotation: JsonObject? = null,
    ) : ResponseStreamEvent {
        override val type: String = StreamEventType.RESPONSE_OUTPUT_TEXT_ANNOTATION_ADDED
    }

    data class AudioDelta(
        val itemId: String? = null,
        val outputIndex: Int? = null,
        val contentIndex: Int? = null,
        val delta: String,
    ) : ResponseStreamEvent {
        override val type: String = StreamEventType.RESPONSE_AUDIO_DELTA
    }

    data class AudioDone(
        val itemId: String? = null,
        val outputIndex: Int? = null,
        val contentIndex: Int? = null,
        val audio: String? = null,
    ) : ResponseStreamEvent {
        override val type: String = StreamEventType.RESPONSE_AUDIO_DONE
    }

    data class AudioTranscriptDelta(
        val itemId: String? = null,
        val outputIndex: Int? = null,
        val contentIndex: Int? = null,
        val delta: String,
    ) : ResponseStreamEvent {
        override val type: String = StreamEventType.RESPONSE_AUDIO_TRANSCRIPT_DELTA
    }

    data class AudioTranscriptDone(
        val itemId: String? = null,
        val outputIndex: Int? = null,
        val contentIndex: Int? = null,
        val transcript: String? = null,
    ) : ResponseStreamEvent {
        override val type: String = StreamEventType.RESPONSE_AUDIO_TRANSCRIPT_DONE
    }

    data class Unknown(
        override val type: String,
        val payload: JsonObject,
    ) : ResponseStreamEvent
}

sealed interface ImageStreamEvent {
    val type: String

    data object Done : ImageStreamEvent {
        override val type: String = StreamEventType.DONE
    }

    data class PartialImage(
        override val type: String,
        val b64Json: String,
        val createdAt: Long? = null,
        val size: String? = null,
        val quality: String? = null,
        val background: String? = null,
        val outputFormat: String? = null,
        val partialImageIndex: Int? = null,
    ) : ImageStreamEvent

    data class Completed(
        override val type: String,
        val b64Json: String,
        val createdAt: Long? = null,
        val size: String? = null,
        val quality: String? = null,
        val background: String? = null,
        val outputFormat: String? = null,
        val usage: ImageUsage? = null,
    ) : ImageStreamEvent

    data class Error(val error: ResponseApiError) : ImageStreamEvent {
        override val type: String = StreamEventType.ERROR
    }

    data class Unknown(
        override val type: String,
        val payload: JsonObject,
    ) : ImageStreamEvent
}

sealed interface TranscriptionStreamEvent {
    val type: String

    data object Done : TranscriptionStreamEvent {
        override val type: String = StreamEventType.DONE
    }

    data class TextDelta(
        val delta: String,
        val logprobs: List<TranscriptionLogProb> = emptyList(),
    ) : TranscriptionStreamEvent {
        override val type: String = StreamEventType.TRANSCRIPT_TEXT_DELTA
    }

    data class TextDone(
        val text: String,
        val logprobs: List<TranscriptionLogProb> = emptyList(),
        val usage: JsonObject? = null,
    ) : TranscriptionStreamEvent {
        override val type: String = StreamEventType.TRANSCRIPT_TEXT_DONE
    }

    data class Error(val error: ResponseApiError) : TranscriptionStreamEvent {
        override val type: String = StreamEventType.ERROR
    }

    data class Unknown(
        override val type: String,
        val payload: JsonObject,
    ) : TranscriptionStreamEvent
}

fun parseTranscriptionStreamEvent(sse: ServerSentEvent): TranscriptionStreamEvent {
    val body = sse.data.value.trim()
    if (body == StreamEventType.DONE) return TranscriptionStreamEvent.Done
    if (body.isEmpty()) {
        return TranscriptionStreamEvent.Unknown(type = sse.event ?: "unknown", payload = buildJsonObject {})
    }

    val payload = runCatching { OpenAIJson.parseToJsonElement(body).jsonObject }.getOrNull()
    if (payload == null) {
        return if (sse.event == StreamEventType.ERROR) {
            TranscriptionStreamEvent.Error(parseResponseApiErrorBody(body) ?: ResponseApiError(message = body))
        } else {
            TranscriptionStreamEvent.Unknown(
                type = sse.event ?: "unknown",
                payload =
                    buildJsonObject {
                        put("raw_data", body)
                    },
            )
        }
    }

    val type = payload["type"]?.jsonPrimitive?.contentOrNull ?: sse.event ?: "unknown"
    val logprobs =
        payload["logprobs"]?.let {
            OpenAIJson.decodeFromJsonElement(ListSerializer(TranscriptionLogProb.serializer()), it)
        } ?: emptyList()

    return when (type) {
        StreamEventType.TRANSCRIPT_TEXT_DELTA ->
            TranscriptionStreamEvent.TextDelta(
                delta = payload["delta"]?.jsonPrimitive?.content ?: "",
                logprobs = logprobs,
            )

        StreamEventType.TRANSCRIPT_TEXT_DONE ->
            TranscriptionStreamEvent.TextDone(
                text = payload["text"]?.jsonPrimitive?.content ?: "",
                logprobs = logprobs,
                usage = payload["usage"]?.jsonObject,
            )

        StreamEventType.ERROR ->
            TranscriptionStreamEvent.Error(
                parseResponseApiErrorBody(body) ?: ResponseApiError(message = body),
            )

        else -> TranscriptionStreamEvent.Unknown(type = type, payload = payload)
    }
}

fun parseResponseStreamEvent(sse: ServerSentEvent): ResponseStreamEvent {
    val body = sse.data.value.trim()
    if (body == StreamEventType.DONE) return ResponseStreamEvent.Done
    if (body.isEmpty()) {
        return ResponseStreamEvent.Unknown(
            type = sse.event ?: "unknown",
            payload = buildJsonObject {},
        )
    }

    val payload = runCatching { OpenAIJson.parseToJsonElement(body).jsonObject }.getOrNull()
    if (payload == null) {
        return if (sse.event == StreamEventType.ERROR) {
            ResponseStreamEvent.Error(ResponseApiError(message = body))
        } else {
            ResponseStreamEvent.Unknown(
                type = sse.event ?: "unknown",
                payload =
                    buildJsonObject {
                        put("raw_data", body)
                    },
            )
        }
    }
    val type = payload["type"]?.jsonPrimitive?.contentOrNull ?: sse.event ?: "unknown"

    return parseResponseLifecycleEvent(type, payload)
        ?: parseResponseContentEvent(type, payload)
        ?: parseResponseArgumentEvent(type, payload)
        ?: parseResponseAudioEvent(type, payload)
        ?: parseResponseErrorEvent(type, payload)
        ?: parseResponseHostedToolEvent(type, payload)
        ?: ResponseStreamEvent.Unknown(type = type, payload = payload)
}

fun parseImageStreamEvent(sse: ServerSentEvent): ImageStreamEvent {
    val body = sse.data.value.trim()
    if (body == StreamEventType.DONE) return ImageStreamEvent.Done
    if (body.isEmpty()) {
        return ImageStreamEvent.Unknown(type = sse.event ?: "unknown", payload = buildJsonObject {})
    }

    val payload = runCatching { OpenAIJson.parseToJsonElement(body).jsonObject }.getOrNull()
    if (payload == null) {
        return if (sse.event == StreamEventType.ERROR) {
            ImageStreamEvent.Error(ResponseApiError(message = body))
        } else {
            ImageStreamEvent.Unknown(
                type = sse.event ?: "unknown",
                payload =
                    buildJsonObject {
                        put("raw_data", body)
                    },
            )
        }
    }
    val type = payload["type"]?.jsonPrimitive?.contentOrNull ?: sse.event ?: "unknown"

    fun intValue(name: String): Int? = (payload[name] as? JsonPrimitive)?.intOrNull
    fun longValue(name: String): Long? = (payload[name] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
    fun stringValue(name: String): String? = (payload[name] as? JsonPrimitive)?.contentOrNull

    return when (type) {
        StreamEventType.IMAGE_GENERATION_PARTIAL_IMAGE,
        StreamEventType.IMAGE_EDIT_PARTIAL_IMAGE,
        -> ImageStreamEvent.PartialImage(
            type = type,
            b64Json = stringValue("b64_json") ?: "",
            createdAt = longValue("created_at"),
            size = stringValue("size"),
            quality = stringValue("quality"),
            background = stringValue("background"),
            outputFormat = stringValue("output_format"),
            partialImageIndex = intValue("partial_image_index"),
        )

        StreamEventType.IMAGE_GENERATION_COMPLETED,
        StreamEventType.IMAGE_EDIT_COMPLETED,
        -> ImageStreamEvent.Completed(
            type = type,
            b64Json = stringValue("b64_json") ?: "",
            createdAt = longValue("created_at"),
            size = stringValue("size"),
            quality = stringValue("quality"),
            background = stringValue("background"),
            outputFormat = stringValue("output_format"),
            usage = payload["usage"]?.let { OpenAIJson.decodeFromJsonElement(it) },
        )

        StreamEventType.ERROR -> {
            val error = payload.toResponseApiErrorOrNull() ?: payload.decodeErrorLikePayload()
            ImageStreamEvent.Error(error)
        }

        else -> ImageStreamEvent.Unknown(type = type, payload = payload)
    }
}

fun parseResponseStreamEvents(source: String): List<ResponseStreamEvent> =
    parseServerSentEvents(source).map(::parseResponseStreamEvent)

suspend fun collectResponseStreamEvents(
    channel: ByteReadChannel,
    emit: suspend (ResponseStreamEvent) -> Unit,
) {
    collectServerSentEvents(channel) { sse ->
        emit(parseResponseStreamEvent(sse))
    }
}

suspend fun collectImageStreamEvents(
    channel: ByteReadChannel,
    emit: suspend (ImageStreamEvent) -> Unit,
) {
    collectServerSentEvents(channel) { sse ->
        emit(parseImageStreamEvent(sse))
    }
}

suspend fun collectServerSentEvents(
    channel: ByteReadChannel,
    emit: suspend (ServerSentEvent) -> Unit,
) {
    val parser = ServerSentEventParserState()

    while (!channel.isClosedForRead) {
        val line = channel.readUTF8Line()
        if (line == null) break
        parser.feedLine(line)?.let { emit(it) }
    }
    parser.finish()?.let { emit(it) }
}

data class ResponseStreamAssembly(
    val outputTexts: Map<String, String> = emptyMap(),
    val functionCallArguments: Map<String, String> = emptyMap(),
    val mcpCallArguments: Map<String, String> = emptyMap(),
    val reasoningSummaryText: Map<String, String> = emptyMap(),
    val audioData: Map<String, String> = emptyMap(),
    val audioTranscript: Map<String, String> = emptyMap(),
    val refusalTexts: Map<String, String> = emptyMap(),
    val finalResponse: ResponseObject? = null,
    val error: ResponseApiError? = null,
    val isDone: Boolean = false,
) {
    private fun requireSingleOutputPart(helper: String, values: Map<String, String>, structuredHelper: String): String {
        check(values.size == 1) {
            "$helper requires exactly one output part; use $structuredHelper for multi-output streams"
        }
        return values.values.single()
    }

    val outputText: String
        get() = requireSingleOutputPart("Response stream outputText", outputTexts, "outputTexts")

    val refusalText: String
        get() = requireSingleOutputPart("Response stream refusalText", refusalTexts, "refusalTexts")
}

private data class ResponseStreamPosition(
    val outputIndex: Int,
    val contentIndex: Int,
    val itemId: String? = null,
)

private data class ResponseStreamSlot(
    val publicKey: String,
    val outputIndex: Int,
    val contentIndex: Int,
)

class ResponseStreamAccumulator {
    private val outputText = linkedMapOf<ResponseStreamPosition, StringBuilder>()
    private val refusalText = linkedMapOf<ResponseStreamSlot, StringBuilder>()
    private val functionCallArguments = linkedMapOf<String, StringBuilder>()
    private val mcpCallArguments = linkedMapOf<String, StringBuilder>()
    private val reasoningSummaryText = linkedMapOf<ResponseStreamSlot, StringBuilder>()
    private val audioData = linkedMapOf<ResponseStreamSlot, StringBuilder>()
    private val audioTranscript = linkedMapOf<ResponseStreamSlot, StringBuilder>()
    private var finalResponse: ResponseObject? = null
    private var error: ResponseApiError? = null
    private var isDone: Boolean = false

    private fun streamKey(
        itemId: String?,
        outputIndex: Int?,
        contentIndex: Int? = null,
    ): String =
        buildString {
            append(itemId ?: "output:${outputIndex ?: -1}")
            contentIndex?.let {
                append(':')
                append(it)
            }
        }

    private fun outputPosition(
        itemId: String?,
        outputIndex: Int?,
        contentIndex: Int?,
    ): ResponseStreamPosition =
        ResponseStreamPosition(
            outputIndex = outputIndex ?: Int.MAX_VALUE,
            contentIndex = contentIndex ?: Int.MAX_VALUE,
            itemId = itemId,
        )

    private fun streamSlot(
        itemId: String?,
        outputIndex: Int?,
        contentIndex: Int? = null,
    ): ResponseStreamSlot =
        ResponseStreamSlot(
            publicKey = streamKey(itemId, outputIndex, contentIndex),
            outputIndex = outputIndex ?: Int.MAX_VALUE,
            contentIndex = contentIndex ?: Int.MAX_VALUE,
        )

    private fun orderedStringMap(values: Map<ResponseStreamSlot, StringBuilder>): Map<String, String> =
        values
            .toList()
            .sortedWith(compareBy({ it.first.outputIndex }, { it.first.contentIndex }, { it.first.publicKey }))
            .associateTo(linkedMapOf()) { it.first.publicKey to it.second.toString() }

    private fun orderedOutputTextMap(values: Map<ResponseStreamPosition, StringBuilder>): Map<String, String> =
        values
            .toList()
            .sortedWith(compareBy({ it.first.outputIndex }, { it.first.contentIndex }, { it.first.itemId.orEmpty() }))
            .associateTo(linkedMapOf()) { (position, builder) ->
                streamKey(position.itemId, position.outputIndex, position.contentIndex) to builder.toString()
            }

    fun apply(event: ResponseStreamEvent): ResponseStreamAssembly {
        when (event) {
            is ResponseStreamEvent.OutputTextDelta -> {
                val key = outputPosition(event.itemId, event.outputIndex, event.contentIndex)
                outputText.getOrPut(key) { StringBuilder() }.append(event.delta)
            }
            is ResponseStreamEvent.OutputTextDone -> {
                val key = outputPosition(event.itemId, event.outputIndex, event.contentIndex)
                val builder = outputText.getOrPut(key) { StringBuilder() }
                event.text?.let {
                    builder.clear()
                    builder.append(it)
                }
            }
            is ResponseStreamEvent.FunctionCallArgumentsDelta -> {
                val key = streamKey(event.itemId, event.outputIndex)
                functionCallArguments.getOrPut(key) { StringBuilder() }.append(event.delta)
            }
            is ResponseStreamEvent.FunctionCallArgumentsDone -> {
                val key = streamKey(event.itemId, event.outputIndex)
                val builder = functionCallArguments.getOrPut(key) { StringBuilder() }
                event.arguments?.let {
                    builder.clear()
                    builder.append(it)
                }
            }
            is ResponseStreamEvent.McpCallArgumentsDelta -> {
                val key = streamKey(event.itemId, event.outputIndex)
                mcpCallArguments.getOrPut(key) { StringBuilder() }.append(event.delta)
            }
            is ResponseStreamEvent.McpCallArgumentsDone -> {
                val key = streamKey(event.itemId, event.outputIndex)
                val builder = mcpCallArguments.getOrPut(key) { StringBuilder() }
                event.arguments?.let {
                    builder.clear()
                    builder.append(it)
                }
            }
            is ResponseStreamEvent.ReasoningSummaryTextDelta -> {
                val key = streamSlot(event.itemId, event.outputIndex)
                reasoningSummaryText.getOrPut(key) { StringBuilder() }.append(event.delta)
            }
            is ResponseStreamEvent.ReasoningSummaryTextDone -> {
                val key = streamSlot(event.itemId, event.outputIndex)
                val builder = reasoningSummaryText.getOrPut(key) { StringBuilder() }
                event.text?.let {
                    builder.clear()
                    builder.append(it)
                }
            }
            is ResponseStreamEvent.AudioDelta -> {
                val key = streamSlot(event.itemId, event.outputIndex, event.contentIndex)
                audioData.getOrPut(key) { StringBuilder() }.append(event.delta)
            }
            is ResponseStreamEvent.AudioDone -> {
                val key = streamSlot(event.itemId, event.outputIndex, event.contentIndex)
                val builder = audioData.getOrPut(key) { StringBuilder() }
                event.audio?.let {
                    builder.clear()
                    builder.append(it)
                }
            }
            is ResponseStreamEvent.AudioTranscriptDelta -> {
                val key = streamSlot(event.itemId, event.outputIndex, event.contentIndex)
                audioTranscript.getOrPut(key) { StringBuilder() }.append(event.delta)
            }
            is ResponseStreamEvent.AudioTranscriptDone -> {
                val key = streamSlot(event.itemId, event.outputIndex, event.contentIndex)
                val builder = audioTranscript.getOrPut(key) { StringBuilder() }
                event.transcript?.let {
                    builder.clear()
                    builder.append(it)
                }
            }
            is ResponseStreamEvent.RefusalDelta -> {
                val key = streamSlot(event.itemId, event.outputIndex, event.contentIndex)
                refusalText.getOrPut(key) { StringBuilder() }.append(event.delta)
            }
            is ResponseStreamEvent.RefusalDone -> event.refusal?.let {
                val key = streamSlot(event.itemId, event.outputIndex, event.contentIndex)
                val builder = refusalText.getOrPut(key) { StringBuilder() }
                builder.clear()
                builder.append(it)
            }
            is ResponseStreamEvent.ImageGenerationCallPartialImage -> Unit
            is ResponseStreamEvent.HostedToolCallProgress -> Unit
            is ResponseStreamEvent.ResponseCompleted -> finalResponse = event.response
            is ResponseStreamEvent.ResponseFailed -> {
                finalResponse = event.response
                error = event.error ?: event.response?.error
            }
            is ResponseStreamEvent.Error -> error = event.error
            is ResponseStreamEvent.Done -> isDone = true
            else -> Unit
        }
        return snapshot()
    }

    fun snapshot(): ResponseStreamAssembly =
        ResponseStreamAssembly(
            outputTexts = orderedOutputTextMap(outputText),
            functionCallArguments = functionCallArguments.mapValues { it.value.toString() },
            mcpCallArguments = mcpCallArguments.mapValues { it.value.toString() },
            reasoningSummaryText = orderedStringMap(reasoningSummaryText),
            audioData = orderedStringMap(audioData),
            audioTranscript = orderedStringMap(audioTranscript),
            refusalTexts = orderedStringMap(refusalText),
            finalResponse = synthesizedFinalResponse(),
            error = error,
            isDone = isDone,
        )

    private fun synthesizedFinalResponse(): ResponseObject? {
        val response = finalResponse ?: return null
        if (response.outputTexts().isNotEmpty()) return response

        val synthesizedTexts = orderedOutputTextMap(outputText)
        if (synthesizedTexts.size != 1) return response
        val synthesizedText = synthesizedTexts.values.single()
        if (synthesizedText.isBlank()) return response

        return response.copy(
            output =
                response.output +
                    ResponseOutputItem(
                        type = ResponseItemType.Message,
                        role = "assistant",
                        content = listOf(ResponseContentPart(type = "output_text", text = synthesizedText)),
                    ),
        )
    }
}

suspend fun Flow<ResponseStreamEvent>.collectResponseStream(): ResponseStreamAssembly {
    val accumulator = ResponseStreamAccumulator()
    collect { event -> accumulator.apply(event) }
    return accumulator.snapshot()
}

data class ImageStreamAssembly(
    val partialImages: Map<Int, String> = emptyMap(),
    val finalImageBase64: String? = null,
    val usage: ImageUsage? = null,
    val isDone: Boolean = false,
    val error: ResponseApiError? = null,
)

class ImageStreamAccumulator {
    private val partialImages = linkedMapOf<Int, String>()
    private var finalImageBase64: String? = null
    private var usage: ImageUsage? = null
    private var error: ResponseApiError? = null
    private var isDone: Boolean = false

    fun apply(event: ImageStreamEvent): ImageStreamAssembly {
        when (event) {
            is ImageStreamEvent.PartialImage -> {
                val index = event.partialImageIndex ?: partialImages.size
                partialImages[index] = event.b64Json
            }
            is ImageStreamEvent.Completed -> {
                finalImageBase64 = event.b64Json
                usage = event.usage
            }
            is ImageStreamEvent.Error -> error = event.error
            is ImageStreamEvent.Done -> isDone = true
            is ImageStreamEvent.Unknown -> Unit
        }
        return snapshot()
    }

    fun snapshot(): ImageStreamAssembly =
        ImageStreamAssembly(
            partialImages = partialImages.toMap(),
            finalImageBase64 = finalImageBase64,
            usage = usage,
            isDone = isDone,
            error = error,
        )
}

suspend fun Flow<ImageStreamEvent>.collectImageStream(): ImageStreamAssembly {
    val accumulator = ImageStreamAccumulator()
    collect { event -> accumulator.apply(event) }
    return accumulator.snapshot()
}

private fun JsonObject.decodeResponseField(name: String): ResponseObject =
    OpenAIJson.decodeFromJsonElement(getValue(name))

private fun JsonObject.decodeResponseFieldOrNull(name: String): ResponseObject? =
    get(name)?.let { OpenAIJson.decodeFromJsonElement(it) }

private fun JsonObject.decodeItemField(name: String): ResponseOutputItem =
    OpenAIJson.decodeFromJsonElement(getValue(name))

private fun JsonObject.decodeItemFieldOrNull(name: String): ResponseOutputItem? =
    get(name)?.let { OpenAIJson.decodeFromJsonElement(it) }

private fun JsonObject.decodePartFieldOrNull(name: String): ResponseContentPart? =
    get(name)?.let { OpenAIJson.decodeFromJsonElement(it) }

private fun JsonObject.decodeErrorFieldOrNull(name: String): ResponseApiError? =
    get(name)?.let { element ->
        runCatching { OpenAIJson.decodeFromJsonElement<ResponseApiError>(element) }.getOrNull()
            ?: element.toResponseApiErrorOrNull()
    }

private fun JsonObject.decodeErrorLikePayload(): ResponseApiError =
    toResponseApiErrorOrNull()
        ?: ResponseApiError(
            message = (this["message"] as? JsonPrimitive)?.contentOrNull,
            type = (this["type"] as? JsonPrimitive)?.contentOrNull,
            param = (this["param"] as? JsonPrimitive)?.contentOrNull,
            code = this["code"]?.let { decodeResponseErrorCodeOrNull(it) },
        )

private fun JsonObject.intValue(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull

private fun JsonObject.stringValue(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

private fun parseResponseLifecycleEvent(
    type: String,
    payload: JsonObject,
): ResponseStreamEvent? =
    when (type) {
        StreamEventType.RESPONSE_CREATED -> ResponseStreamEvent.ResponseCreated(payload.decodeResponseField("response"))
        StreamEventType.RESPONSE_IN_PROGRESS -> ResponseStreamEvent.ResponseInProgress(payload.decodeResponseField("response"))
        StreamEventType.RESPONSE_COMPLETED -> ResponseStreamEvent.ResponseCompleted(payload.decodeResponseField("response"))
        StreamEventType.RESPONSE_FAILED ->
            ResponseStreamEvent.ResponseFailed(
                response = payload.decodeResponseFieldOrNull("response"),
                error = payload.decodeErrorFieldOrNull("error"),
            )
        StreamEventType.RESPONSE_OUTPUT_ITEM_ADDED ->
            ResponseStreamEvent.OutputItemAdded(
                outputIndex = payload.intValue("output_index"),
                item = payload.decodeItemField("item"),
            )
        StreamEventType.RESPONSE_OUTPUT_ITEM_DONE ->
            ResponseStreamEvent.OutputItemDone(
                outputIndex = payload.intValue("output_index"),
                item = payload.decodeItemField("item"),
            )
        else -> null
    }

private fun parseResponseContentEvent(
    type: String,
    payload: JsonObject,
): ResponseStreamEvent? =
    when (type) {
        StreamEventType.RESPONSE_CONTENT_PART_ADDED ->
            ResponseStreamEvent.ContentPartAdded(
                itemId = payload.stringValue("item_id"),
                outputIndex = payload.intValue("output_index"),
                contentIndex = payload.intValue("content_index"),
                part = payload.decodePartFieldOrNull("part"),
            )
        StreamEventType.RESPONSE_CONTENT_PART_DONE ->
            ResponseStreamEvent.ContentPartDone(
                itemId = payload.stringValue("item_id"),
                outputIndex = payload.intValue("output_index"),
                contentIndex = payload.intValue("content_index"),
                part = payload.decodePartFieldOrNull("part"),
            )
        StreamEventType.RESPONSE_OUTPUT_TEXT_DELTA ->
            ResponseStreamEvent.OutputTextDelta(
                itemId = payload.stringValue("item_id"),
                outputIndex = payload.intValue("output_index"),
                contentIndex = payload.intValue("content_index"),
                delta = payload.stringValue("delta") ?: "",
            )
        StreamEventType.RESPONSE_OUTPUT_TEXT_DONE ->
            ResponseStreamEvent.OutputTextDone(
                itemId = payload.stringValue("item_id"),
                outputIndex = payload.intValue("output_index"),
                contentIndex = payload.intValue("content_index"),
                text = payload.stringValue("text"),
            )
        StreamEventType.RESPONSE_REFUSAL_DELTA ->
            ResponseStreamEvent.RefusalDelta(
                itemId = payload.stringValue("item_id"),
                outputIndex = payload.intValue("output_index"),
                contentIndex = payload.intValue("content_index"),
                delta = payload.stringValue("delta") ?: "",
            )
        StreamEventType.RESPONSE_REFUSAL_DONE ->
            ResponseStreamEvent.RefusalDone(
                itemId = payload.stringValue("item_id"),
                outputIndex = payload.intValue("output_index"),
                contentIndex = payload.intValue("content_index"),
                refusal = payload.stringValue("refusal"),
            )
        StreamEventType.RESPONSE_OUTPUT_TEXT_ANNOTATION_ADDED ->
            ResponseStreamEvent.OutputTextAnnotationAdded(
                itemId = payload.stringValue("item_id"),
                outputIndex = payload.intValue("output_index"),
                contentIndex = payload.intValue("content_index"),
                annotationIndex = payload.intValue("annotation_index"),
                annotation = payload["annotation"]?.jsonObject,
            )
        StreamEventType.RESPONSE_IMAGE_GENERATION_CALL_PARTIAL_IMAGE ->
            ResponseStreamEvent.ImageGenerationCallPartialImage(
                itemId = payload.stringValue("item_id"),
                outputIndex = payload.intValue("output_index"),
                partialImageIndex = payload.intValue("partial_image_index"),
                b64Json = payload.stringValue("b64_json") ?: "",
            )
        else -> null
    }

private fun parseResponseArgumentEvent(
    type: String,
    payload: JsonObject,
): ResponseStreamEvent? =
    when (type) {
        StreamEventType.RESPONSE_FUNCTION_CALL_ARGUMENTS_DELTA ->
            ResponseStreamEvent.FunctionCallArgumentsDelta(
                itemId = payload.stringValue("item_id"),
                outputIndex = payload.intValue("output_index"),
                delta = payload.stringValue("delta") ?: "",
            )
        StreamEventType.RESPONSE_FUNCTION_CALL_ARGUMENTS_DONE ->
            ResponseStreamEvent.FunctionCallArgumentsDone(
                itemId = payload.stringValue("item_id"),
                outputIndex = payload.intValue("output_index"),
                arguments = payload.stringValue("arguments"),
            )
        StreamEventType.RESPONSE_MCP_CALL_ARGUMENTS_DELTA ->
            ResponseStreamEvent.McpCallArgumentsDelta(
                itemId = payload.stringValue("item_id"),
                outputIndex = payload.intValue("output_index"),
                delta = payload.stringValue("delta") ?: "",
            )
        StreamEventType.RESPONSE_MCP_CALL_ARGUMENTS_DONE ->
            ResponseStreamEvent.McpCallArgumentsDone(
                itemId = payload.stringValue("item_id"),
                outputIndex = payload.intValue("output_index"),
                arguments = payload.stringValue("arguments"),
            )
        StreamEventType.RESPONSE_REASONING_SUMMARY_TEXT_DELTA ->
            ResponseStreamEvent.ReasoningSummaryTextDelta(
                itemId = payload.stringValue("item_id"),
                outputIndex = payload.intValue("output_index"),
                delta = payload.stringValue("delta") ?: "",
            )
        StreamEventType.RESPONSE_REASONING_SUMMARY_TEXT_DONE ->
            ResponseStreamEvent.ReasoningSummaryTextDone(
                itemId = payload.stringValue("item_id"),
                outputIndex = payload.intValue("output_index"),
                text = payload.stringValue("text"),
            )
        else -> null
    }

private fun parseResponseAudioEvent(
    type: String,
    payload: JsonObject,
): ResponseStreamEvent? =
    when (type) {
        StreamEventType.RESPONSE_AUDIO_DELTA ->
            ResponseStreamEvent.AudioDelta(
                itemId = payload.stringValue("item_id"),
                outputIndex = payload.intValue("output_index"),
                contentIndex = payload.intValue("content_index"),
                delta = payload.stringValue("delta") ?: "",
            )
        StreamEventType.RESPONSE_AUDIO_DONE ->
            ResponseStreamEvent.AudioDone(
                itemId = payload.stringValue("item_id"),
                outputIndex = payload.intValue("output_index"),
                contentIndex = payload.intValue("content_index"),
                audio = payload.stringValue("audio"),
            )
        StreamEventType.RESPONSE_AUDIO_TRANSCRIPT_DELTA ->
            ResponseStreamEvent.AudioTranscriptDelta(
                itemId = payload.stringValue("item_id"),
                outputIndex = payload.intValue("output_index"),
                contentIndex = payload.intValue("content_index"),
                delta = payload.stringValue("delta") ?: "",
            )
        StreamEventType.RESPONSE_AUDIO_TRANSCRIPT_DONE ->
            ResponseStreamEvent.AudioTranscriptDone(
                itemId = payload.stringValue("item_id"),
                outputIndex = payload.intValue("output_index"),
                contentIndex = payload.intValue("content_index"),
                transcript = payload.stringValue("transcript"),
            )
        else -> null
    }

private fun parseResponseErrorEvent(
    type: String,
    payload: JsonObject,
): ResponseStreamEvent? =
    when (type) {
        StreamEventType.ERROR -> {
            val error = payload.toResponseApiErrorOrNull() ?: payload.decodeErrorLikePayload()
            ResponseStreamEvent.Error(error)
        }
        else -> null
    }

private fun parseResponseHostedToolEvent(
    type: String,
    payload: JsonObject,
): ResponseStreamEvent? {
    val toolSuffix = type.removePrefix("response.")
    if (!toolSuffix.endsWith("_call.in_progress") &&
        !toolSuffix.endsWith("_call.searching") &&
        !toolSuffix.endsWith("_call.completed")
    ) {
        return null
    }
    return ResponseStreamEvent.HostedToolCallProgress(
        type = type,
        itemId = payload.stringValue("item_id"),
        outputIndex = payload.intValue("output_index"),
        item = payload.decodeItemFieldOrNull("item"),
    )
}

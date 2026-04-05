@file:OptIn(kotlin.time.ExperimentalTime::class)

package one.wabbit.web.openai

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.FormBuilder
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import one.wabbit.web.common.Etiquette
import one.wabbit.web.common.RetryAction
import one.wabbit.web.common.RetryPolicy
import one.wabbit.web.common.Schedule
import one.wabbit.web.common.Timeouts
import one.wabbit.web.common.applyEtiquette
import one.wabbit.web.common.applyTimeouts
import one.wabbit.web.common.parseRetryAfterHeader
import one.wabbit.web.common.runWithRetry
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal fun deriveStreamingTimeouts(
    timeouts: Timeouts,
    streamingTimeouts: Timeouts?,
): Timeouts = streamingTimeouts ?: timeouts.copy(request = null, socket = null)

interface OpenAIApi {
    data class Config(
        val provider: OpenAIProvider = OpenAIProvider.OpenAI,
        val baseUrl: String = provider.defaultBaseUrl,
        val apiKey: String? = null,
        val etiquette: Etiquette = Etiquette("one.wabbit.web.openai/1.0"),
        val timeouts: Timeouts = Timeouts(),
        val streamingTimeouts: Timeouts? = null,
        val retryPolicy: OpenAIRetryPolicy? = defaultRetryPolicy(),
        val retryNonIdempotentRequests: Boolean = false,
        val onResponseMetadata: ((OpenAIResponseMetadata) -> Unit)? = null,
    ) {
        init {
            require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
        }
    }

    val config: Config

    suspend fun createResponse(request: ResponseCreateRequest): ResponseObject
    suspend fun getResponse(responseId: ResponseId): ResponseObject
    suspend fun cancelResponse(responseId: ResponseId): ResponseObject
    suspend fun listResponses(query: ResponseListQuery = ResponseListQuery()): ResponsePage
    suspend fun listResponseInputItems(
        responseId: ResponseId,
        query: ResponseInputItemListQuery = ResponseInputItemListQuery(),
    ): ResponseInputItemPage
    suspend fun countResponseInputTokens(request: ResponseInputTokensRequest): ResponseInputTokensResult

    suspend fun createEmbedding(request: EmbeddingCreateRequest): EmbeddingResponse
    suspend fun listModels(): ModelPage
    suspend fun getModel(modelId: ModelId): ModelObject
    suspend fun generateImage(request: ImageGenerateRequest): ImagesResponse
    suspend fun editImage(request: ImageEditRequest): ImagesResponse
    suspend fun createImageVariation(request: ImageVariationRequest): ImagesResponse
    fun streamGeneratedImage(request: ImageGenerateRequest): Flow<ImageStreamEvent>
    fun streamEditedImage(request: ImageEditRequest): Flow<ImageStreamEvent>
    suspend fun createModeration(request: ModerationCreateRequest): ModerationResponse
    suspend fun createBatch(request: BatchCreateRequest): BatchObject
    suspend fun getBatch(batchId: BatchId): BatchObject
    suspend fun listBatches(query: BatchListQuery = BatchListQuery()): BatchPage
    suspend fun cancelBatch(batchId: BatchId): BatchObject
    suspend fun createFineTuningJob(request: FineTuningJobCreateRequest): FineTuningJob
    suspend fun getFineTuningJob(jobId: FineTuningJobId): FineTuningJob
    suspend fun listFineTuningJobs(query: FineTuningJobListQuery = FineTuningJobListQuery()): FineTuningJobPage
    suspend fun cancelFineTuningJob(jobId: FineTuningJobId): FineTuningJob
    suspend fun pauseFineTuningJob(jobId: FineTuningJobId): FineTuningJob
    suspend fun resumeFineTuningJob(jobId: FineTuningJobId): FineTuningJob
    suspend fun listFineTuningEvents(
        jobId: FineTuningJobId,
        query: FineTuningJobEventListQuery = FineTuningJobEventListQuery(),
    ): FineTuningJobEventPage
    suspend fun listFineTuningCheckpoints(
        jobId: FineTuningJobId,
        query: FineTuningCheckpointListQuery = FineTuningCheckpointListQuery(),
    ): FineTuningCheckpointPage
    fun streamResponse(request: ResponseCreateRequest): Flow<ResponseStreamEvent>
    suspend fun createChatCompletion(request: ChatCompletionRequest): ChatCompletionResponse
    suspend fun getStoredChatCompletion(completionId: ChatCompletionId): ChatCompletionResponse
    suspend fun listStoredChatCompletions(query: StoredChatCompletionListQuery = StoredChatCompletionListQuery()): StoredChatCompletionPage
    suspend fun listStoredChatCompletionMessages(completionId: ChatCompletionId): StoredChatMessagePage
    suspend fun deleteStoredChatCompletion(completionId: ChatCompletionId): DeletedObject
    fun streamChatCompletion(request: ChatCompletionRequest): Flow<ChatCompletionStreamEvent>
    suspend fun createDeepSeekFimCompletion(request: DeepSeekFimCompletionRequest): DeepSeekFimCompletionResponse
    suspend fun createSpeech(request: SpeechRequest): ByteArray
    fun streamSpeech(request: SpeechRequest): Flow<SpeechStreamEvent>
    suspend fun createTranscription(request: TranscriptionRequest): TranscriptionResult
    suspend fun createTranslation(request: TranslationRequest): TranslationResult
    suspend fun uploadFile(request: FileCreateRequest): OpenAIFile
    suspend fun getFile(fileId: FileId): OpenAIFile
    suspend fun listFiles(query: FileListQuery = FileListQuery()): FilePage
    suspend fun deleteFile(fileId: FileId): DeletedObject
    suspend fun downloadFile(fileId: FileId): ByteArray
    suspend fun createUpload(request: UploadCreateRequest): UploadObject
    suspend fun addUploadPart(uploadId: UploadId, file: BinaryUpload): UploadPart
    suspend fun completeUpload(uploadId: UploadId, partIds: List<UploadPartId>): UploadObject
    suspend fun cancelUpload(uploadId: UploadId): UploadObject
    suspend fun createVectorStore(request: VectorStoreCreateRequest): VectorStoreObject
    suspend fun getVectorStore(vectorStoreId: VectorStoreId): VectorStoreObject
    suspend fun listVectorStores(query: VectorStoreListQuery = VectorStoreListQuery()): VectorStorePage
    suspend fun updateVectorStore(vectorStoreId: VectorStoreId, request: VectorStoreUpdateRequest): VectorStoreObject
    suspend fun deleteVectorStore(vectorStoreId: VectorStoreId): DeletedObject
    suspend fun searchVectorStore(vectorStoreId: VectorStoreId, request: VectorStoreSearchRequest): VectorStoreSearchResponse
    suspend fun createVectorStoreFile(vectorStoreId: VectorStoreId, request: VectorStoreFileCreateRequest): VectorStoreFileObject
    suspend fun listVectorStoreFiles(
        vectorStoreId: VectorStoreId,
        query: VectorStoreFileListQuery = VectorStoreFileListQuery(),
    ): VectorStoreFilePage
    suspend fun createVectorStoreFileBatch(
        vectorStoreId: VectorStoreId,
        request: VectorStoreFileBatchCreateRequest,
    ): VectorStoreFileBatchObject
    suspend fun getVectorStoreFileBatch(vectorStoreId: VectorStoreId, batchId: VectorStoreFileBatchId): VectorStoreFileBatchObject
    suspend fun cancelVectorStoreFileBatch(vectorStoreId: VectorStoreId, batchId: VectorStoreFileBatchId): VectorStoreFileBatchObject
    suspend fun listVectorStoreFileBatches(
        vectorStoreId: VectorStoreId,
        query: VectorStoreFileBatchListQuery = VectorStoreFileBatchListQuery(),
    ): VectorStoreFileBatchPage
    suspend fun getConversation(conversationId: ConversationId): ConversationObject
    suspend fun listConversationItems(
        conversationId: ConversationId,
        query: ConversationItemListQuery = ConversationItemListQuery(),
    ): ConversationItemPage
    suspend fun getConversationItem(conversationId: ConversationId, itemId: ConversationItemId): ConversationItemObject
    suspend fun deleteConversation(conversationId: ConversationId): DeletedObject
    suspend fun deleteConversationItem(conversationId: ConversationId, itemId: ConversationItemId): DeletedObject
    suspend fun createRealtimeSession(request: RealtimeSessionCreateRequest): RealtimeSessionObject
    suspend fun createRealtimeTranscriptionSession(request: RealtimeTranscriptionSessionCreateRequest): RealtimeSessionObject
    suspend fun createRealtimeClientSecret(
        session: RealtimeSessionCreateRequest,
        expiresAfter: RealtimeClientSecretExpiration? = null,
    ): RealtimeClientSecretResponse
    suspend fun createRealtimeClientSecret(
        session: RealtimeTranscriptionSessionCreateRequest,
        expiresAfter: RealtimeClientSecretExpiration? = null,
    ): RealtimeClientSecretResponse
    suspend fun createEval(request: EvalCreateRequest): EvalObject
    suspend fun getEval(evalId: EvalId): EvalObject
    suspend fun listEvals(query: EvalListQuery = EvalListQuery()): EvalPage
    suspend fun updateEval(evalId: EvalId, request: EvalUpdateRequest): EvalObject
    suspend fun deleteEval(evalId: EvalId): DeletedObject
    suspend fun createEvalRun(evalId: EvalId, request: EvalRunCreateRequest): EvalRunObject
    suspend fun getEvalRun(evalId: EvalId, runId: EvalRunId): EvalRunObject
    suspend fun listEvalRuns(evalId: EvalId, query: EvalRunListQuery = EvalRunListQuery()): EvalRunPage
    suspend fun updateEvalRun(evalId: EvalId, runId: EvalRunId, request: EvalRunUpdateRequest): EvalRunObject
    suspend fun deleteEvalRun(evalId: EvalId, runId: EvalRunId): DeletedObject
    suspend fun listEvalRunOutputItems(
        evalId: EvalId,
        runId: EvalRunId,
        query: EvalRunOutputItemListQuery = EvalRunOutputItemListQuery(),
    ): EvalRunOutputItemPage
    suspend fun createVideo(request: VideoCreateRequest): VideoObject
    suspend fun remixVideo(videoId: VideoId, request: VideoRemixRequest): VideoObject
    suspend fun listVideos(query: VideoListQuery = VideoListQuery()): VideoPage
    suspend fun getVideo(videoId: VideoId): VideoObject
    suspend fun deleteVideo(videoId: VideoId): DeletedObject
    suspend fun downloadVideoContent(videoId: VideoId): ByteArray
    suspend fun createXaiBatch(request: XAIBatchCreateRequest): XAIBatchObject
    suspend fun addXaiBatchRequests(batchId: XAIBatchId, requests: List<XAIBatchRequestInput>): XAIBatchRequestPage
    suspend fun getXaiBatch(batchId: XAIBatchId): XAIBatchObject
    suspend fun listXaiBatches(query: XAIBatchListQuery = XAIBatchListQuery()): XAIBatchPage
    suspend fun listXaiBatchRequests(batchId: XAIBatchId, query: XAIBatchRequestListQuery = XAIBatchRequestListQuery()): XAIBatchRequestPage
    suspend fun listXaiBatchResults(batchId: XAIBatchId, query: XAIBatchResultListQuery = XAIBatchResultListQuery()): XAIBatchResultPage
    suspend fun cancelXaiBatch(batchId: XAIBatchId): XAIBatchObject
    suspend fun createXaiSpeech(request: XAISpeechRequest): ByteArray
    suspend fun listXaiVoices(): XAIVoicePage
    suspend fun getXaiVoice(voiceId: XAIVoiceId): XAIVoiceObject

    companion object {
        fun defaultRetryPolicy(): OpenAIRetryPolicy {
            val schedule =
                Schedule.retries(
                    maxRetries = 4,
                    baseDelay = 250.milliseconds,
                    maxDelay = 5.seconds,
                    jitterFactor = 0.2,
                )
            return RetryPolicy(schedule) { error, _ ->
                when (error) {
                    is OpenAIApiError.Network -> RetryAction.Retry()
                    is OpenAIApiError.Http ->
                        if (error.status == 429 || error.status in 500..599) {
                            error.retryAfterSeconds?.seconds?.let { RetryAction.Retry(it) } ?: RetryAction.Retry()
                        } else {
                            RetryAction.Stop
                        }
                    is OpenAIApiError.Api ->
                        if (error.status == 429 || error.status in 500..599) {
                            error.retryAfterSeconds?.seconds?.let { RetryAction.Retry(it) } ?: RetryAction.Retry()
                        } else {
                            RetryAction.Stop
                        }
                    is OpenAIApiError.Parse -> RetryAction.Stop
                }
            }
        }
    }
}

class KtorOpenAIApi(
    private val httpClient: HttpClient,
    override val config: OpenAIApi.Config = OpenAIApi.Config(),
) : OpenAIApi {
    init {
        check(runCatching { httpClient.pluginOrNull(HttpTimeout) }.getOrNull() != null) {
            "HttpTimeout plugin must be installed on the provided HttpClient for per-request timeouts to work."
        }
    }

    private val provider: OpenAIProvider
        get() = config.provider

    private val effectiveStreamingTimeouts: Timeouts
        get() = deriveStreamingTimeouts(config.timeouts, config.streamingTimeouts)

    override suspend fun createResponse(request: ResponseCreateRequest): ResponseObject =
        withNonIdempotentRetry {
            request.requireCompatibleWith(provider)
            val url = responsesUrl()
            val response =
                postJson(url, request.toJson(stream = false), accept = ContentType.Application.Json.toString()) {
                    applyAzureImageGenerationSelectionHeader(request)
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }

            try {
                OpenAIJson.decodeFromString<ResponseObject>(body)
            } catch (t: Throwable) {
                throw OpenAIApiError.Parse(url, t, bodySample = body.take(2048))
            }
        }

    override fun streamResponse(request: ResponseCreateRequest): Flow<ResponseStreamEvent> = flow {
        request.requireCompatibleWith(provider)
        provider.requireResponseStreamingCompatibility(
            usesImageGenerationTool = request.tools.any { it is ResponseTool.ImageGeneration },
        )
        val url = responsesUrl()
        val statement =
            try {
                httpClient.preparePost(url) {
                    expectSuccess = false
                    accept(ContentType.Text.EventStream)
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    applyEtiquette(config.etiquette)
                    applyTimeouts(effectiveStreamingTimeouts)
                    provider.applyRequest(config.apiKey, this)
                    applyAzureImageGenerationSelectionHeader(request)
                    setBody(request.toJson(stream = true).toString())
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t is OpenAIApiError) throw t
                throw OpenAIApiError.Network(url, t)
            }

        statement.execute { response ->
            observeResponseMetadata(url, response)
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, response.bodyAsText(), response.headers)
            }
            throwUnexpectedSuccessfulNonStreamResponse(url, response, "Responses streaming")
            wrapStreamingTransport(url) {
                collectServerSentEvents(response.bodyAsChannel()) { sse ->
                    val event =
                        try {
                            parseResponseStreamEvent(sse)
                        } catch (t: Throwable) {
                            throw OpenAIApiError.Parse(url, t, bodySample = sse.data.value.take(2048))
                        }
                    emit(event)
                }
            }
        }
    }

    override suspend fun getResponse(responseId: ResponseId): ResponseObject =
        withRetry {
            provider.requireStatefulResponsesApiSupport()
            val url = "${responsesUrl()}/${responseId.value}"
            val response = getRequest(url)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            try {
                OpenAIJson.decodeFromString<ResponseObject>(body)
            } catch (t: Throwable) {
                throw OpenAIApiError.Parse(url, t, bodySample = body.take(2048))
            }
        }

    override suspend fun cancelResponse(responseId: ResponseId): ResponseObject =
        withNonIdempotentRetry {
            provider.requireStatefulResponsesApiSupport()
            val url = "${responsesUrl()}/${responseId.value}/cancel"
            val response = postJson(url, payload = null, accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            try {
                OpenAIJson.decodeFromString<ResponseObject>(body)
            } catch (t: Throwable) {
                throw OpenAIApiError.Parse(url, t, bodySample = body.take(2048))
            }
        }

    override suspend fun listResponses(query: ResponseListQuery): ResponsePage =
        withRetry {
            provider.requireStatefulResponsesApiSupport()
            val url = responsesUrl()
            val response =
                getRequest(url) {
                    query.toParameters().forEach { (key, value) -> parameter(key, value) }
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            try {
                OpenAIJson.decodeFromString<ResponsePage>(body)
            } catch (t: Throwable) {
                throw OpenAIApiError.Parse(url, t, bodySample = body.take(2048))
            }
        }

    override suspend fun listResponseInputItems(
        responseId: ResponseId,
        query: ResponseInputItemListQuery,
    ): ResponseInputItemPage =
        withRetry {
            provider.requireStatefulResponsesApiSupport()
            val url = "${responsesUrl()}/${responseId.value}/input_items"
            val response =
                getRequest(url) {
                    query.toParameters().forEach { (key, value) -> parameter(key, value) }
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            try {
                OpenAIJson.decodeFromString<ResponseInputItemPage>(body)
            } catch (t: Throwable) {
                throw OpenAIApiError.Parse(url, t, bodySample = body.take(2048))
            }
        }

    override suspend fun countResponseInputTokens(request: ResponseInputTokensRequest): ResponseInputTokensResult =
        withRetry {
            provider.requireOpenAiOnly("responses input-token counting")
            val url = "${responsesUrl()}/input_tokens"
            val response = postJson(url, request.toJson(), accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun createEmbedding(request: EmbeddingCreateRequest): EmbeddingResponse =
        withNonIdempotentRetry {
            request.requireCompatibleWith(provider)
            val url = embeddingsUrl()
            val response = postJson(url, request.toJson(), accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun listModels(): ModelPage =
        withRetry {
            provider.requireModelsApiSupport()
            val url = modelsUrl()
            val response = getRequest(url)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeModelPage(url, body)
        }

    override suspend fun getModel(modelId: ModelId): ModelObject =
        withRetry {
            provider.requireModelsApiSupport()
            val url = "${modelsUrl()}/${modelId.value}"
            val response = getRequest(url)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun generateImage(request: ImageGenerateRequest): ImagesResponse =
        withNonIdempotentRetry {
            provider.requireImagesApiSupport()
            provider.requireImageGenerationCompatibility(request.responseFormat)
            require(request.stream != true) {
                "generateImage does not support stream=true; use streamGeneratedImage instead"
            }
            val url = imagesGenerationsUrl()
            val response = postJson(url, request.toJson(), accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun editImage(request: ImageEditRequest): ImagesResponse =
        withNonIdempotentRetry {
            provider.requireImageEditsApiSupport()
            require(request.stream != true) {
                "editImage does not support stream=true; use streamEditedImage instead"
            }
            val url = imagesEditsUrl()
            val response = postJson(url, request.toJson(), accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun createImageVariation(request: ImageVariationRequest): ImagesResponse =
        withNonIdempotentRetry {
            provider.requireImageVariationsApiSupport()
            val url = imagesVariationsUrl()
            val response =
                postMultipart(url, accept = ContentType.Application.Json.toString()) {
                    appendBinaryUpload("image", request.image)
                    request.model?.let { append("model", it.value) }
                    request.n?.let { append("n", it.toString()) }
                    request.responseFormat?.let { append("response_format", it.wireName) }
                    request.size?.let { append("size", it.wireName) }
                    request.user?.let { append("user", it) }
                    request.extraFields.forEach { (key, value) -> append(key, value) }
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override fun streamGeneratedImage(request: ImageGenerateRequest): Flow<ImageStreamEvent> = flow {
        provider.requireImageStreamingApiSupport()
        val url = imagesGenerationsUrl()
        val statement =
            try {
                httpClient.preparePost(url) {
                    expectSuccess = false
                    accept(ContentType.Text.EventStream)
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    applyEtiquette(config.etiquette)
                    applyTimeouts(effectiveStreamingTimeouts)
                    provider.applyRequest(config.apiKey, this)
                    setBody(request.copy(stream = true).toJson().toString())
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t is OpenAIApiError) throw t
                throw OpenAIApiError.Network(url, t)
            }

        statement.execute { response ->
            observeResponseMetadata(url, response)
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, response.bodyAsText(), response.headers)
            }
            throwUnexpectedSuccessfulNonStreamResponse(url, response, "image streaming")
            wrapStreamingTransport(url) {
                collectServerSentEvents(response.bodyAsChannel()) { sse ->
                    val event =
                        try {
                            parseImageStreamEvent(sse)
                        } catch (t: Throwable) {
                            throw OpenAIApiError.Parse(url, t, bodySample = sse.data.value.take(2048))
                        }
                    emit(event)
                }
            }
        }
    }

    override fun streamEditedImage(request: ImageEditRequest): Flow<ImageStreamEvent> = flow {
        provider.requireImageStreamingApiSupport()
        provider.requireImageEditsApiSupport()
        val url = imagesEditsUrl()
        val statement =
            try {
                httpClient.preparePost(url) {
                    expectSuccess = false
                    accept(ContentType.Text.EventStream)
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    applyEtiquette(config.etiquette)
                    applyTimeouts(effectiveStreamingTimeouts)
                    provider.applyRequest(config.apiKey, this)
                    setBody(request.copy(stream = true).toJson().toString())
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t is OpenAIApiError) throw t
                throw OpenAIApiError.Network(url, t)
            }

        statement.execute { response ->
            observeResponseMetadata(url, response)
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, response.bodyAsText(), response.headers)
            }
            throwUnexpectedSuccessfulNonStreamResponse(url, response, "image edit streaming")
            wrapStreamingTransport(url) {
                collectServerSentEvents(response.bodyAsChannel()) { sse ->
                    val event =
                        try {
                            parseImageStreamEvent(sse)
                        } catch (t: Throwable) {
                            throw OpenAIApiError.Parse(url, t, bodySample = sse.data.value.take(2048))
                        }
                    emit(event)
                }
            }
        }
    }

    override suspend fun createModeration(request: ModerationCreateRequest): ModerationResponse =
        withNonIdempotentRetry {
            provider.requireModerationsApiSupport()
            val url = moderationsUrl()
            val response = postJson(url, request.toJson(), accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun createBatch(request: BatchCreateRequest): BatchObject =
        withNonIdempotentRetry {
            provider.requireBatchesApiSupport()
            val url = batchesUrl()
            val response = postJson(url, request.toJson(), accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun getBatch(batchId: BatchId): BatchObject =
        withRetry {
            provider.requireBatchesApiSupport()
            val url = "${batchesUrl()}/${batchId.value}"
            val response = getRequest(url)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun listBatches(query: BatchListQuery): BatchPage =
        withRetry {
            provider.requireBatchesApiSupport()
            val url = batchesUrl()
            val response =
                getRequest(url) {
                    query.toParameters().forEach { (key, value) -> parameter(key, value) }
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun cancelBatch(batchId: BatchId): BatchObject =
        withNonIdempotentRetry {
            provider.requireBatchesApiSupport()
            val url = "${batchesUrl()}/${batchId.value}/cancel"
            val response = postJson(url, payload = null, accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun createFineTuningJob(request: FineTuningJobCreateRequest): FineTuningJob =
        withNonIdempotentRetry {
            provider.requireFineTuningApiSupport()
            val url = fineTuningJobsUrl()
            val response = postJson(url, request.toJson(), accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun getFineTuningJob(jobId: FineTuningJobId): FineTuningJob =
        withRetry {
            provider.requireFineTuningApiSupport()
            val url = "${fineTuningJobsUrl()}/${jobId.value}"
            val response = getRequest(url)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun listFineTuningJobs(query: FineTuningJobListQuery): FineTuningJobPage =
        withRetry {
            provider.requireFineTuningApiSupport()
            val url = fineTuningJobsUrl()
            val response =
                getRequest(url) {
                    query.toParameters().forEach { (key, value) -> parameter(key, value) }
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun cancelFineTuningJob(jobId: FineTuningJobId): FineTuningJob =
        withNonIdempotentRetry {
            provider.requireFineTuningApiSupport()
            val url = "${fineTuningJobsUrl()}/${jobId.value}/cancel"
            val response = postJson(url, payload = null, accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun pauseFineTuningJob(jobId: FineTuningJobId): FineTuningJob =
        withNonIdempotentRetry {
            provider.requireFineTuningApiSupport()
            val url = "${fineTuningJobsUrl()}/${jobId.value}/pause"
            val response = postJson(url, payload = null, accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun resumeFineTuningJob(jobId: FineTuningJobId): FineTuningJob =
        withNonIdempotentRetry {
            provider.requireFineTuningApiSupport()
            val url = "${fineTuningJobsUrl()}/${jobId.value}/resume"
            val response = postJson(url, payload = null, accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun listFineTuningEvents(
        jobId: FineTuningJobId,
        query: FineTuningJobEventListQuery,
    ): FineTuningJobEventPage =
        withRetry {
            provider.requireFineTuningApiSupport()
            val url = "${fineTuningJobsUrl()}/${jobId.value}/events"
            val response =
                getRequest(url) {
                    query.toParameters().forEach { (key, value) -> parameter(key, value) }
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun listFineTuningCheckpoints(
        jobId: FineTuningJobId,
        query: FineTuningCheckpointListQuery,
    ): FineTuningCheckpointPage =
        withRetry {
            provider.requireFineTuningApiSupport()
            val url = "${fineTuningJobsUrl()}/${jobId.value}/checkpoints"
            val response =
                getRequest(url) {
                    query.toParameters().forEach { (key, value) -> parameter(key, value) }
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun createChatCompletion(request: ChatCompletionRequest): ChatCompletionResponse =
        withNonIdempotentRetry {
            request.requireCompatibleWith(provider)
            val url = chatCompletionsUrl(request)
            val response = postJson(url, request.toJson(stream = false), accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            try {
                OpenAIJson.decodeFromString<ChatCompletionResponse>(body)
            } catch (t: Throwable) {
                throw OpenAIApiError.Parse(url, t, bodySample = body.take(2048))
            }
        }

    override suspend fun getStoredChatCompletion(completionId: ChatCompletionId): ChatCompletionResponse =
        withRetry {
            provider.requireStoredChatCompletionsApiSupport()
            val url = "${chatCompletionsUrl()}/${completionId.value}"
            val response = getRequest(url)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun listStoredChatCompletions(query: StoredChatCompletionListQuery): StoredChatCompletionPage =
        withRetry {
            provider.requireStoredChatCompletionsApiSupport()
            val url = chatCompletionsUrl()
            val response =
                getRequest(url) {
                    query.toParameters().forEach { (key, value) -> parameter(key, value) }
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun listStoredChatCompletionMessages(completionId: ChatCompletionId): StoredChatMessagePage =
        withRetry {
            provider.requireStoredChatCompletionsApiSupport()
            val url = "${chatCompletionsUrl()}/${completionId.value}/messages"
            val response = getRequest(url)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun deleteStoredChatCompletion(completionId: ChatCompletionId): DeletedObject =
        withRetry {
            provider.requireStoredChatCompletionsApiSupport()
            val url = "${chatCompletionsUrl()}/${completionId.value}"
            val response = deleteRequest(url)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override fun streamChatCompletion(request: ChatCompletionRequest): Flow<ChatCompletionStreamEvent> = flow {
        request.requireCompatibleWith(provider)
        val url = chatCompletionsUrl(request)
        val statement =
            try {
                httpClient.preparePost(url) {
                    expectSuccess = false
                    accept(ContentType.Text.EventStream)
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    applyEtiquette(config.etiquette)
                    applyTimeouts(effectiveStreamingTimeouts)
                    provider.applyRequest(config.apiKey, this)
                    setBody(request.toJson(stream = true).toString())
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t is OpenAIApiError) throw t
                throw OpenAIApiError.Network(url, t)
            }

        statement.execute { response ->
            observeResponseMetadata(url, response)
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, response.bodyAsText(), response.headers)
            }
            throwUnexpectedSuccessfulNonStreamResponse(url, response, "chat completion streaming")
            wrapStreamingTransport(url) {
                collectServerSentEvents(response.bodyAsChannel()) { sse ->
                    val event =
                        try {
                            parseChatCompletionStreamEvent(sse)
                        } catch (t: Throwable) {
                            throw OpenAIApiError.Parse(url, t, bodySample = sse.data.value.take(2048))
                        }
                    emit(event)
                }
            }
        }
    }

    override suspend fun createDeepSeekFimCompletion(request: DeepSeekFimCompletionRequest): DeepSeekFimCompletionResponse =
        withNonIdempotentRetry {
            provider.requireDeepSeekOnly("DeepSeek FIM completions")
            val url = deepSeekCompletionsUrl()
            val response = postJson(url, request.toJson(), accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun createSpeech(request: SpeechRequest): ByteArray =
        withNonIdempotentRetry {
            provider.requireAudioApiSupport()
            require(request.streamFormat != SpeechStreamFormat.SSE) {
                "createSpeech does not support streamFormat=SSE; use streamSpeech instead"
            }
            val url = speechUrl()
            val response = postJson(url, request.toJson(), accept = "*/*")
            if (!response.status.isSuccess()) {
                throw decodeErrorResponse(url, response)
            }
            throwUnexpectedSuccessfulAudioResponse(url, response, "audio speech generation")
            readBinaryBody(url, response)
        }

    override fun streamSpeech(request: SpeechRequest): Flow<SpeechStreamEvent> = flow {
        provider.requireAudioApiSupport()
        val url = speechUrl()
        val statement =
            try {
                httpClient.preparePost(url) {
                    expectSuccess = false
                    header(HttpHeaders.Accept, "*/*")
                    header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                    applyEtiquette(config.etiquette)
                    applyTimeouts(effectiveStreamingTimeouts)
                    provider.applyRequest(config.apiKey, this)
                    setBody(request.toJson().toString())
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                if (t is OpenAIApiError) throw t
                throw OpenAIApiError.Network(url, t)
            }

        statement.execute { response ->
            observeResponseMetadata(url, response)
            if (!response.status.isSuccess()) {
                throw decodeErrorResponse(url, response)
            }
            if (request.streamFormat == SpeechStreamFormat.SSE) {
                throwUnexpectedSuccessfulNonStreamResponse(url, response, "speech SSE streaming")
                emit(SpeechStreamEvent.Started(response.headers[HttpHeaders.ContentType]))
                wrapStreamingTransport(url) {
                    collectServerSentEvents(response.bodyAsChannel()) { sse ->
                        val body = sse.data.value
                        if (body.trim() == "[DONE]") {
                            emit(SpeechStreamEvent.Done)
                        } else if (sse.event == "error") {
                            emit(SpeechStreamEvent.Error(parseResponseApiErrorBody(body) ?: ResponseApiError(message = body)))
                        } else {
                            emit(
                                SpeechStreamEvent.ServerEvent(
                                    event = sse.event,
                                    data = body,
                                    id = sse.id,
                                    retryMillis = sse.retryMillis,
                                ),
                            )
                        }
                    }
                }
            } else {
                throwUnexpectedSuccessfulAudioResponse(url, response, "streaming audio speech generation")
                emit(SpeechStreamEvent.Started(response.headers[HttpHeaders.ContentType]))
                wrapStreamingTransport(url) {
                    collectByteChunks(response.bodyAsChannel()) { bytes ->
                        emit(SpeechStreamEvent.AudioChunk(bytes))
                    }
                }
                emit(SpeechStreamEvent.Done)
            }
        }
    }

    override suspend fun createTranscription(request: TranscriptionRequest): TranscriptionResult =
        withNonIdempotentRetry {
            provider.requireAudioApiSupport()
            require(request.stream != true) {
                "Streaming audio transcriptions are not implemented in this client yet"
            }
            val url = transcriptionUrl()
            val response =
                postMultipart(url, accept = "*/*") {
                    appendBinaryUpload("file", request.file)
                    append("model", request.model.value)
                    request.chunkingStrategy?.let { appendChunkingStrategy(it) }
                    request.knownSpeakerNames.forEach { append("known_speaker_names[]", it) }
                    request.knownSpeakerReferences.forEach { append("known_speaker_references[]", it) }
                    request.language?.let { append("language", it) }
                    request.prompt?.let { append("prompt", it) }
                    request.responseFormat?.let { append("response_format", it.wireName) }
                    request.stream?.let { append("stream", it.toString()) }
                    request.temperature?.let { append("temperature", it.toString()) }
                    request.include.forEach { append("include[]", it.wireName) }
                    request.timestampGranularities.forEach { append("timestamp_granularities[]", it.wireName) }
                    request.extraFields.forEach { (key, value) -> append(key, value) }
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeTranscriptionResult(url, body, request.responseFormat)
        }

    override suspend fun createTranslation(request: TranslationRequest): TranslationResult =
        withNonIdempotentRetry {
            provider.requireAudioApiSupport()
            val url = translationUrl()
            val response =
                postMultipart(url, accept = "*/*") {
                    appendBinaryUpload("file", request.file)
                    append("model", request.model.value)
                    request.prompt?.let { append("prompt", it) }
                    request.responseFormat?.let { append("response_format", it.wireName) }
                    request.temperature?.let { append("temperature", it.toString()) }
                    request.extraFields.forEach { (key, value) -> append(key, value) }
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeTranslationResult(url, body, request.responseFormat)
        }

    override suspend fun uploadFile(request: FileCreateRequest): OpenAIFile =
        withNonIdempotentRetry {
            provider.requireFilesApiSupport()
            val url = filesUrl()
            val response =
                postMultipart(url, accept = ContentType.Application.Json.toString()) {
                    append("purpose", request.purpose.wireName)
                    appendBinaryUpload("file", request.file)
                    request.extraFields.forEach { (key, value) -> append(key, value) }
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun getFile(fileId: FileId): OpenAIFile =
        withRetry {
            provider.requireFilesApiSupport()
            val url = "${filesUrl()}/${fileId.value}"
            val response = getRequest(url)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun listFiles(query: FileListQuery): FilePage =
        withRetry {
            provider.requireFilesApiSupport()
            val url = filesUrl()
            val response =
                getRequest(url) {
                    query.toParameters().forEach { (key, value) -> parameter(key, value) }
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun deleteFile(fileId: FileId): DeletedObject =
        withRetry {
            provider.requireFilesApiSupport()
            val url = "${filesUrl()}/${fileId.value}"
            val response = deleteRequest(url)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun downloadFile(fileId: FileId): ByteArray =
        withRetry {
            provider.requireFilesApiSupport()
            val url = "${filesUrl()}/${fileId.value}/content"
            val response = getRequest(url, accept = "*/*")
            if (!response.status.isSuccess()) {
                throw decodeErrorResponse(url, response)
            }
            readBinaryBody(url, response)
        }

    override suspend fun createUpload(request: UploadCreateRequest): UploadObject =
        withNonIdempotentRetry {
            provider.requireUploadsApiSupport()
            val url = uploadsUrl()
            val response = postJson(url, request.toJson(), accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun addUploadPart(uploadId: UploadId, file: BinaryUpload): UploadPart =
        withNonIdempotentRetry {
            provider.requireUploadsApiSupport()
            val url = "${uploadsUrl()}/${uploadId.value}/parts"
            val response =
                postMultipart(url, accept = ContentType.Application.Json.toString()) {
                    appendBinaryUpload("data", file)
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun completeUpload(uploadId: UploadId, partIds: List<UploadPartId>): UploadObject =
        withNonIdempotentRetry {
            provider.requireUploadsApiSupport()
            require(partIds.isNotEmpty()) { "partIds must not be empty" }
            val url = "${uploadsUrl()}/${uploadId.value}/complete"
            val payload =
                buildJsonObject {
                    putJsonArray("part_ids") {
                        partIds.forEach { add(JsonPrimitive(it.value)) }
                    }
                }
            val response = postJson(url, payload, accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun cancelUpload(uploadId: UploadId): UploadObject =
        withNonIdempotentRetry {
            provider.requireUploadsApiSupport()
            val url = "${uploadsUrl()}/${uploadId.value}/cancel"
            val response = postJson(url, payload = null, accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun createVectorStore(request: VectorStoreCreateRequest): VectorStoreObject =
        withNonIdempotentRetry {
            provider.requireVectorStoresApiSupport()
            val url = vectorStoresUrl()
            val response =
                postJson(url, request.toJson(), accept = ContentType.Application.Json.toString()) {
                    applyVectorStoresBeta()
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun getVectorStore(vectorStoreId: VectorStoreId): VectorStoreObject =
        withRetry {
            provider.requireVectorStoresApiSupport()
            val url = "${vectorStoresUrl()}/${vectorStoreId.value}"
            val response = getRequest(url, configureRequest = { applyVectorStoresBeta() })
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun listVectorStores(query: VectorStoreListQuery): VectorStorePage =
        withRetry {
            provider.requireVectorStoresApiSupport()
            val url = vectorStoresUrl()
            val response =
                getRequest(
                    url,
                    extraParameters = {
                        query.toParameters().forEach { (key, value) -> parameter(key, value) }
                    },
                    configureRequest = { applyVectorStoresBeta() },
                )
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun updateVectorStore(vectorStoreId: VectorStoreId, request: VectorStoreUpdateRequest): VectorStoreObject =
        withNonIdempotentRetry {
            provider.requireVectorStoresApiSupport()
            val url = "${vectorStoresUrl()}/${vectorStoreId.value}"
            val response =
                postJson(url, request.toJson(), accept = ContentType.Application.Json.toString()) {
                    applyVectorStoresBeta()
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun deleteVectorStore(vectorStoreId: VectorStoreId): DeletedObject =
        withRetry {
            provider.requireVectorStoresApiSupport()
            val url = "${vectorStoresUrl()}/${vectorStoreId.value}"
            val response = deleteRequest(url) { applyVectorStoresBeta() }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun searchVectorStore(
        vectorStoreId: VectorStoreId,
        request: VectorStoreSearchRequest,
    ): VectorStoreSearchResponse =
        withNonIdempotentRetry {
            provider.requireVectorStoresApiSupport()
            val url = "${vectorStoresUrl()}/${vectorStoreId.value}/search"
            val response =
                postJson(url, request.toJson(), accept = ContentType.Application.Json.toString()) {
                    applyVectorStoresBeta()
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun createVectorStoreFile(
        vectorStoreId: VectorStoreId,
        request: VectorStoreFileCreateRequest,
    ): VectorStoreFileObject =
        withNonIdempotentRetry {
            provider.requireVectorStoresApiSupport()
            val url = "${vectorStoresUrl()}/${vectorStoreId.value}/files"
            val response =
                postJson(url, request.toJson(), accept = ContentType.Application.Json.toString()) {
                    applyVectorStoresBeta()
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun listVectorStoreFiles(
        vectorStoreId: VectorStoreId,
        query: VectorStoreFileListQuery,
    ): VectorStoreFilePage =
        withRetry {
            provider.requireVectorStoresApiSupport()
            val url = "${vectorStoresUrl()}/${vectorStoreId.value}/files"
            val response =
                getRequest(
                    url,
                    extraParameters = {
                        query.toParameters().forEach { (key, value) -> parameter(key, value) }
                    },
                    configureRequest = { applyVectorStoresBeta() },
                )
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun createVectorStoreFileBatch(
        vectorStoreId: VectorStoreId,
        request: VectorStoreFileBatchCreateRequest,
    ): VectorStoreFileBatchObject =
        withNonIdempotentRetry {
            provider.requireVectorStoresApiSupport()
            val url = "${vectorStoresUrl()}/${vectorStoreId.value}/file_batches"
            val response =
                postJson(url, request.toJson(), accept = ContentType.Application.Json.toString()) {
                    applyVectorStoresBeta()
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun getVectorStoreFileBatch(vectorStoreId: VectorStoreId, batchId: VectorStoreFileBatchId): VectorStoreFileBatchObject =
        withRetry {
            provider.requireVectorStoresApiSupport()
            val url = "${vectorStoresUrl()}/${vectorStoreId.value}/file_batches/${batchId.value}"
            val response =
                getRequest(url, configureRequest = { applyVectorStoresBeta() })
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun cancelVectorStoreFileBatch(vectorStoreId: VectorStoreId, batchId: VectorStoreFileBatchId): VectorStoreFileBatchObject =
        withNonIdempotentRetry {
            provider.requireVectorStoresApiSupport()
            val url = "${vectorStoresUrl()}/${vectorStoreId.value}/file_batches/${batchId.value}/cancel"
            val response =
                postJson(url, payload = null, accept = ContentType.Application.Json.toString()) {
                    applyVectorStoresBeta()
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun listVectorStoreFileBatches(
        vectorStoreId: VectorStoreId,
        query: VectorStoreFileBatchListQuery,
    ): VectorStoreFileBatchPage =
        withRetry {
            provider.requireVectorStoresApiSupport()
            val url = "${vectorStoresUrl()}/${vectorStoreId.value}/file_batches"
            val response =
                getRequest(
                    url,
                    extraParameters = {
                        query.toParameters().forEach { (key, value) -> parameter(key, value) }
                    },
                    configureRequest = { applyVectorStoresBeta() },
                )
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun getConversation(conversationId: ConversationId): ConversationObject =
        withRetry {
            provider.requireConversationsApiSupport()
            val url = "${conversationsUrl()}/${conversationId.value}"
            val response = getRequest(url)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun listConversationItems(
        conversationId: ConversationId,
        query: ConversationItemListQuery,
    ): ConversationItemPage =
        withRetry {
            provider.requireConversationsApiSupport()
            val url = "${conversationsUrl()}/${conversationId.value}/items"
            val response =
                getRequest(url) {
                    query.toParameters().forEach { (key, value) -> parameter(key, value) }
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun getConversationItem(conversationId: ConversationId, itemId: ConversationItemId): ConversationItemObject =
        withRetry {
            provider.requireConversationsApiSupport()
            val url = "${conversationsUrl()}/${conversationId.value}/items/${itemId.value}"
            val response = getRequest(url)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun deleteConversation(conversationId: ConversationId): DeletedObject =
        withRetry {
            provider.requireConversationsApiSupport()
            val url = "${conversationsUrl()}/${conversationId.value}"
            val response = deleteRequest(url)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun deleteConversationItem(conversationId: ConversationId, itemId: ConversationItemId): DeletedObject =
        withRetry {
            provider.requireConversationsApiSupport()
            val url = "${conversationsUrl()}/${conversationId.value}/items/${itemId.value}"
            val response = deleteRequest(url)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun createRealtimeSession(request: RealtimeSessionCreateRequest): RealtimeSessionObject =
        withNonIdempotentRetry {
            provider.requireRealtimeBootstrapApiSupport()
            val url = realtimeSessionsUrl()
            val response = postJson(url, request.toJson(), accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun createRealtimeTranscriptionSession(
        request: RealtimeTranscriptionSessionCreateRequest,
    ): RealtimeSessionObject =
        withNonIdempotentRetry {
            provider.requireRealtimeBootstrapApiSupport()
            val url = realtimeTranscriptionSessionsUrl()
            val response = postJson(url, request.toJson(), accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun createRealtimeClientSecret(
        session: RealtimeSessionCreateRequest,
        expiresAfter: RealtimeClientSecretExpiration?,
    ): RealtimeClientSecretResponse = createRealtimeClientSecretInternal(session.toClientSecretSessionJson(), expiresAfter)

    override suspend fun createRealtimeClientSecret(
        session: RealtimeTranscriptionSessionCreateRequest,
        expiresAfter: RealtimeClientSecretExpiration?,
    ): RealtimeClientSecretResponse = createRealtimeClientSecretInternal(session.toClientSecretSessionJson(), expiresAfter)

    override suspend fun createEval(request: EvalCreateRequest): EvalObject =
        withNonIdempotentRetry {
            provider.requireEvalsApiSupport()
            val url = evalsUrl()
            val response = postJson(url, request.toJson(), accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun getEval(evalId: EvalId): EvalObject =
        withRetry {
            provider.requireEvalsApiSupport()
            val url = "${evalsUrl()}/${evalId.value}"
            val response = getRequest(url)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun listEvals(query: EvalListQuery): EvalPage =
        withRetry {
            provider.requireEvalsApiSupport()
            val url = evalsUrl()
            val response =
                getRequest(url) {
                    query.toParameters().forEach { (key, value) -> parameter(key, value) }
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun updateEval(evalId: EvalId, request: EvalUpdateRequest): EvalObject =
        withNonIdempotentRetry {
            provider.requireEvalsApiSupport()
            val url = "${evalsUrl()}/${evalId.value}"
            val response = postJson(url, request.toJson(), accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun deleteEval(evalId: EvalId): DeletedObject =
        withRetry {
            provider.requireEvalsApiSupport()
            val url = "${evalsUrl()}/${evalId.value}"
            val response = deleteRequest(url)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun createEvalRun(evalId: EvalId, request: EvalRunCreateRequest): EvalRunObject =
        withNonIdempotentRetry {
            provider.requireEvalsApiSupport()
            val url = "${evalsUrl()}/${evalId.value}/runs"
            val response = postJson(url, request.toJson(), accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun getEvalRun(evalId: EvalId, runId: EvalRunId): EvalRunObject =
        withRetry {
            provider.requireEvalsApiSupport()
            val url = "${evalsUrl()}/${evalId.value}/runs/${runId.value}"
            val response = getRequest(url)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun listEvalRuns(evalId: EvalId, query: EvalRunListQuery): EvalRunPage =
        withRetry {
            provider.requireEvalsApiSupport()
            val url = "${evalsUrl()}/${evalId.value}/runs"
            val response =
                getRequest(url) {
                    query.toParameters().forEach { (key, value) -> parameter(key, value) }
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun updateEvalRun(evalId: EvalId, runId: EvalRunId, request: EvalRunUpdateRequest): EvalRunObject =
        withNonIdempotentRetry {
            provider.requireEvalsApiSupport()
            val url = "${evalsUrl()}/${evalId.value}/runs/${runId.value}"
            val response = postJson(url, request.toJson(), accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun deleteEvalRun(evalId: EvalId, runId: EvalRunId): DeletedObject =
        withRetry {
            provider.requireEvalsApiSupport()
            val url = "${evalsUrl()}/${evalId.value}/runs/${runId.value}"
            val response = deleteRequest(url)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun listEvalRunOutputItems(
        evalId: EvalId,
        runId: EvalRunId,
        query: EvalRunOutputItemListQuery,
    ): EvalRunOutputItemPage =
        withRetry {
            provider.requireEvalsApiSupport()
            val url = "${evalsUrl()}/${evalId.value}/runs/${runId.value}/output_items"
            val response =
                getRequest(url) {
                    query.toParameters().forEach { (key, value) -> parameter(key, value) }
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun createVideo(request: VideoCreateRequest): VideoObject =
        withNonIdempotentRetry {
            provider.requireVideosApiSupport()
            val url = videosUrl()
            val response = postJson(url, request.toJson(), accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun remixVideo(videoId: VideoId, request: VideoRemixRequest): VideoObject =
        withNonIdempotentRetry {
            provider.requireVideosApiSupport()
            val url = "${videosUrl()}/${videoId.value}/remix"
            val response = postJson(url, request.toJson(), accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun listVideos(query: VideoListQuery): VideoPage =
        withRetry {
            provider.requireVideosApiSupport()
            val url = videosUrl()
            val response =
                getRequest(url) {
                    query.toParameters().forEach { (key, value) -> parameter(key, value) }
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun getVideo(videoId: VideoId): VideoObject =
        withRetry {
            provider.requireVideosApiSupport()
            val url = "${videosUrl()}/${videoId.value}"
            val response = getRequest(url)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun deleteVideo(videoId: VideoId): DeletedObject =
        withRetry {
            provider.requireVideosApiSupport()
            val url = "${videosUrl()}/${videoId.value}"
            val response = deleteRequest(url)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun downloadVideoContent(videoId: VideoId): ByteArray =
        withRetry {
            provider.requireVideosApiSupport()
            val url = "${videosUrl()}/${videoId.value}/content"
            val response = getRequest(url, accept = "*/*")
            if (!response.status.isSuccess()) {
                throw decodeErrorResponse(url, response)
            }
            readBinaryBody(url, response)
        }

    override suspend fun createXaiBatch(request: XAIBatchCreateRequest): XAIBatchObject =
        withNonIdempotentRetry {
            provider.requireXaiBatchApiSupport()
            val url = xaiBatchesUrl()
            val response = postJson(url, request.toJson(), accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun addXaiBatchRequests(batchId: XAIBatchId, requests: List<XAIBatchRequestInput>): XAIBatchRequestPage =
        withNonIdempotentRetry {
            provider.requireXaiBatchApiSupport()
            val url = "${xaiBatchesUrl()}/${batchId.value}/requests"
            val response =
                postJson(
                    url,
                    XAIBatchRequestAppendRequest(requests).toJson(),
                    accept = ContentType.Application.Json.toString(),
                )
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun getXaiBatch(batchId: XAIBatchId): XAIBatchObject =
        withRetry {
            provider.requireXaiBatchApiSupport()
            val url = "${xaiBatchesUrl()}/${batchId.value}"
            val response = getRequest(url)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun listXaiBatches(query: XAIBatchListQuery): XAIBatchPage =
        withRetry {
            provider.requireXaiBatchApiSupport()
            val url = xaiBatchesUrl()
            val response =
                getRequest(url) {
                    query.toParameters().forEach { (key, value) -> parameter(key, value) }
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun listXaiBatchRequests(batchId: XAIBatchId, query: XAIBatchRequestListQuery): XAIBatchRequestPage =
        withRetry {
            provider.requireXaiBatchApiSupport()
            val url = "${xaiBatchesUrl()}/${batchId.value}/requests"
            val response =
                getRequest(url) {
                    query.toParameters().forEach { (key, value) -> parameter(key, value) }
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun listXaiBatchResults(batchId: XAIBatchId, query: XAIBatchResultListQuery): XAIBatchResultPage =
        withRetry {
            provider.requireXaiBatchApiSupport()
            val url = "${xaiBatchesUrl()}/${batchId.value}/results"
            val response =
                getRequest(url) {
                    query.toParameters().forEach { (key, value) -> parameter(key, value) }
                }
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun cancelXaiBatch(batchId: XAIBatchId): XAIBatchObject =
        withNonIdempotentRetry {
            provider.requireXaiBatchApiSupport()
            val url = "${xaiBatchesUrl()}/${batchId.value}:cancel"
            val response = postJson(url, payload = null, accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun createXaiSpeech(request: XAISpeechRequest): ByteArray =
        withNonIdempotentRetry {
            provider.requireXaiTtsApiSupport()
            val url = xaiTtsUrl()
            val response = postJson(url, request.toJson(), accept = "*/*")
            if (!response.status.isSuccess()) {
                throw decodeErrorResponse(url, response)
            }
            throwUnexpectedSuccessfulAudioResponse(url, response, "xAI speech generation")
            readBinaryBody(url, response)
        }

    override suspend fun listXaiVoices(): XAIVoicePage =
        withRetry {
            provider.requireXaiTtsApiSupport()
            val url = xaiVoicesUrl()
            val response = getRequest(url)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    override suspend fun getXaiVoice(voiceId: XAIVoiceId): XAIVoiceObject =
        withRetry {
            provider.requireXaiTtsApiSupport()
            val url = "${xaiVoicesUrl()}/${voiceId.value}"
            val response = getRequest(url)
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    private suspend fun <T> withRetry(block: suspend () -> T): T {
        val policy = config.retryPolicy
        return if (policy == null) block() else runWithRetry(policy, block)
    }

    private suspend fun <T> withNonIdempotentRetry(block: suspend () -> T): T {
        val policy = if (config.retryNonIdempotentRequests) config.retryPolicy else null
        return if (policy == null) block() else runWithRetry(policy, block)
    }

    private fun apiBaseUrl(): String = config.baseUrl.trimEnd('/')

    private fun deepSeekBetaUrl(endpointSuffix: String): String {
        val base = apiBaseUrl()
        return if (config.provider is OpenAIProvider.DeepSeek && base.endsWith("/v1")) {
            base.removeSuffix("/v1") + "/beta/$endpointSuffix"
        } else {
            base + "/beta/$endpointSuffix"
        }
    }

    private fun responsesUrl(): String = apiBaseUrl() + "/responses"
    private fun embeddingsUrl(): String {
        val azure = config.provider as? OpenAIProvider.Azure
        return azure?.embeddingsDeployment
            ?.takeIf { it.isNotBlank() }
            ?.let { azureDeploymentUrl(it, "embeddings") }
            ?: apiBaseUrl() + "/embeddings"
    }
    private fun modelsUrl(): String = apiBaseUrl() + "/models"
    private fun imagesGenerationsUrl(): String {
        val azure = config.provider as? OpenAIProvider.Azure
        return azure?.imageGenerationDeployment
            ?.takeIf { it.isNotBlank() }
            ?.let { azureDeploymentUrl(it, "images/generations") }
            ?: apiBaseUrl() + "/images/generations"
    }
    private fun imagesEditsUrl(): String = apiBaseUrl() + "/images/edits"
    private fun imagesVariationsUrl(): String = apiBaseUrl() + "/images/variations"
    private fun moderationsUrl(): String = apiBaseUrl() + "/moderations"
    private fun batchesUrl(): String = apiBaseUrl() + "/batches"
    private fun fineTuningJobsUrl(): String = apiBaseUrl() + "/fine_tuning/jobs"
    private fun chatCompletionsUrl(): String = apiBaseUrl() + "/chat/completions"
    private fun chatCompletionsUrl(request: ChatCompletionRequest): String {
        val azure = config.provider as? OpenAIProvider.Azure
        azure?.chatCompletionsDeployment
            ?.takeIf { it.isNotBlank() }
            ?.let { return azureDeploymentUrl(it, "chat/completions") }
        return if (
            config.provider is OpenAIProvider.DeepSeek &&
            request.messages.any { it.prefix == true }
        ) {
            deepSeekBetaUrl("chat/completions")
        } else {
            chatCompletionsUrl()
        }
    }
    private fun speechUrl(): String = apiBaseUrl() + "/audio/speech"
    private fun transcriptionUrl(): String = apiBaseUrl() + "/audio/transcriptions"
    private fun translationUrl(): String = apiBaseUrl() + "/audio/translations"
    private fun filesUrl(): String = apiBaseUrl() + "/files"
    private fun uploadsUrl(): String = apiBaseUrl() + "/uploads"
    private fun vectorStoresUrl(): String = apiBaseUrl() + "/vector_stores"
    private fun conversationsUrl(): String = apiBaseUrl() + "/conversations"
    private fun realtimeClientSecretsUrl(): String = apiBaseUrl() + "/realtime/client_secrets"
    private fun realtimeSessionsUrl(): String = apiBaseUrl() + "/realtime/sessions"
    private fun realtimeTranscriptionSessionsUrl(): String = apiBaseUrl() + "/realtime/transcription_sessions"
    private fun evalsUrl(): String = apiBaseUrl() + "/evals"
    private fun videosUrl(): String = apiBaseUrl() + "/videos"
    private fun xaiBatchesUrl(): String = apiBaseUrl() + "/batches"
    private fun xaiTtsUrl(): String = apiBaseUrl() + "/tts"
    private fun xaiVoicesUrl(): String = apiBaseUrl() + "/tts/voices"
    private fun deepSeekCompletionsUrl(): String =
        if (config.provider is OpenAIProvider.DeepSeek) {
            deepSeekBetaUrl("completions")
        } else {
            apiBaseUrl() + "/completions"
        }

    private fun azureDeploymentRoot(): String {
        val base = apiBaseUrl()
        return when {
            base.endsWith("/openai/v1") -> base.removeSuffix("/v1")
            base.endsWith("/openai") -> base
            else -> "$base/openai"
        }
    }

    private fun azureDeploymentUrl(deployment: String, endpointSuffix: String): String =
        azureDeploymentRoot().trimEnd('/') + "/deployments/$deployment/$endpointSuffix"

    private fun io.ktor.client.request.HttpRequestBuilder.applyAzureImageGenerationSelectionHeader(
        request: ResponseCreateRequest,
    ) {
        val azure = config.provider as? OpenAIProvider.Azure ?: return
        if (request.tools.none { it is ResponseTool.ImageGeneration }) return
        azure.imageGenerationDeployment
            ?.takeIf { it.isNotBlank() }
            ?.let { header("x-ms-oai-image-generation-deployment", it) }
    }

    private suspend fun createRealtimeClientSecretInternal(
        session: kotlinx.serialization.json.JsonObject,
        expiresAfter: RealtimeClientSecretExpiration?,
    ): RealtimeClientSecretResponse =
        withNonIdempotentRetry {
            provider.requireRealtimeBootstrapApiSupport()
            val url = realtimeClientSecretsUrl()
            val payload =
                buildJsonObject {
                    expiresAfter?.let { put("expires_after", it.toJson()) }
                    put("session", session)
                }
            val response = postJson(url, payload, accept = ContentType.Application.Json.toString())
            val body = response.bodyAsText()
            if (!response.status.isSuccess()) {
                throw decodeError(url, response.status.value, body, response.headers)
            }
            decodeJsonBody(url, body)
        }

    private suspend fun postJson(
        url: String,
        payload: kotlinx.serialization.json.JsonObject?,
        accept: String,
        configureRequest: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ) = try {
        httpClient.post(url) {
            expectSuccess = false
            header(HttpHeaders.Accept, accept)
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            applyEtiquette(config.etiquette)
            applyTimeouts(config.timeouts)
            provider.applyRequest(config.apiKey, this)
            configureRequest()
            if (payload != null) {
                setBody(payload.toString())
            } else {
                setBody("")
            }
        }
            .also { observeResponseMetadata(url, it) }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        if (t is OpenAIApiError) throw t
        throw OpenAIApiError.Network(url, t)
    }

    private suspend fun getRequest(
        url: String,
        accept: String = ContentType.Application.Json.toString(),
        extraParameters: suspend io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
        configureRequest: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ) = try {
        httpClient.get(url) {
            expectSuccess = false
            header(HttpHeaders.Accept, accept)
            applyEtiquette(config.etiquette)
            applyTimeouts(config.timeouts)
            provider.applyRequest(config.apiKey, this)
            configureRequest()
            extraParameters()
        }
            .also { observeResponseMetadata(url, it) }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        if (t is OpenAIApiError) throw t
        throw OpenAIApiError.Network(url, t)
    }

    private suspend fun deleteRequest(
        url: String,
        accept: String = ContentType.Application.Json.toString(),
        configureRequest: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
    ) = try {
        httpClient.delete(url) {
            expectSuccess = false
            header(HttpHeaders.Accept, accept)
            applyEtiquette(config.etiquette)
            applyTimeouts(config.timeouts)
            provider.applyRequest(config.apiKey, this)
            configureRequest()
        }
            .also { observeResponseMetadata(url, it) }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        if (t is OpenAIApiError) throw t
        throw OpenAIApiError.Network(url, t)
    }

    private suspend fun postMultipart(
        url: String,
        accept: String,
        configureRequest: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {},
        buildForm: FormBuilder.() -> Unit,
    ) = try {
        httpClient.post(url) {
            expectSuccess = false
            header(HttpHeaders.Accept, accept)
            applyEtiquette(config.etiquette)
            applyTimeouts(config.timeouts)
            provider.applyRequest(config.apiKey, this)
            configureRequest()
            setBody(MultiPartFormDataContent(formData(buildForm)))
        }
            .also { observeResponseMetadata(url, it) }
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        if (t is OpenAIApiError) throw t
        throw OpenAIApiError.Network(url, t)
    }

    private fun observeResponseMetadata(url: String, response: io.ktor.client.statement.HttpResponse) {
        config.onResponseMetadata?.invoke(buildResponseMetadata(url, response))
    }

    private fun buildResponseMetadata(
        url: String,
        response: io.ktor.client.statement.HttpResponse,
    ): OpenAIResponseMetadata {
        val headers = response.headers
        return OpenAIResponseMetadata(
            providerId = config.provider.id,
            url = url,
            status = response.status.value,
            headers = headers,
            requestId =
                headers["x-request-id"]
                    ?: headers["request-id"]
                    ?: headers["apim-request-id"],
            processingMillis =
                headers["openai-processing-ms"]
                    ?.toLongOrNull()
                    ?: headers["x-openai-processing-ms"]?.toLongOrNull(),
            retryAfterSeconds = parseRetryAfterSeconds(headers),
            rateLimits =
                OpenAIRateLimitMetadata(
                    requests = rateLimitBucket(headers, "requests"),
                    tokens = rateLimitBucket(headers, "tokens"),
                    inputTokens = rateLimitBucket(headers, "input-tokens"),
                    outputTokens = rateLimitBucket(headers, "output-tokens"),
                ),
        )
    }

    private fun rateLimitBucket(headers: Headers, name: String): OpenAIRateLimitBucket? {
        val limit = headers["x-ratelimit-limit-$name"]?.toLongOrNull()
        val remaining = headers["x-ratelimit-remaining-$name"]?.toLongOrNull()
        val reset = headers["x-ratelimit-reset-$name"]
        return if (limit == null && remaining == null && reset == null) null else OpenAIRateLimitBucket(limit, remaining, reset)
    }

    private fun parseRetryAfterSeconds(headers: Headers): Double? =
        parseRetryAfterHeader(headers[HttpHeaders.RetryAfter])?.inWholeMilliseconds?.toDouble()?.div(1000.0)

    private fun decodeError(
        url: String,
        status: Int,
        body: String,
        headers: Headers? = null,
    ): OpenAIApiError {
        val parsed = parseResponseApiErrorBody(body)
        val retryAfterSeconds = headers?.let(::parseRetryAfterSeconds)
        return if (parsed != null) {
            OpenAIApiError.Api(url, status, parsed, retryAfterSeconds = retryAfterSeconds)
        } else {
            OpenAIApiError.Http(url, status, body.take(2048), retryAfterSeconds = retryAfterSeconds)
        }
    }

    private fun FormBuilder.appendChunkingStrategy(strategy: AudioChunkingStrategy) {
        when (strategy) {
            AudioChunkingStrategy.Auto -> append("chunking_strategy", "auto")
            is AudioChunkingStrategy.ServerVad -> {
                append("chunking_strategy[type]", "server_vad")
                strategy.threshold?.let { append("chunking_strategy[threshold]", it.toString()) }
                strategy.prefixPaddingMs?.let { append("chunking_strategy[prefix_padding_ms]", it.toString()) }
                strategy.silenceDurationMs?.let { append("chunking_strategy[silence_duration_ms]", it.toString()) }
            }
        }
    }

    private suspend fun decodeErrorResponse(
        url: String,
        response: io.ktor.client.statement.HttpResponse,
    ): OpenAIApiError {
        val body = response.bodyAsText()
        return decodeError(url, response.status.value, body, response.headers)
    }

    private suspend fun throwUnexpectedSuccessfulNonStreamResponse(
        url: String,
        response: io.ktor.client.statement.HttpResponse,
        operation: String,
    ) {
        val contentType = response.headers[HttpHeaders.ContentType] ?: return
        val normalized = contentType.substringBefore(';').trim().lowercase()
        if (normalized == ContentType.Text.EventStream.toString()) return
        if (!normalized.isLikelyJsonContentType() && normalized !in setOf(ContentType.Text.Plain.toString(), ContentType.Text.Html.toString())) {
            return
        }

        val body = response.bodyAsText()
        parseResponseApiErrorBody(body)?.let { parsed ->
            throw OpenAIApiError.Api(
                url = url,
                status = response.status.value,
                error = parsed,
                retryAfterSeconds = parseRetryAfterSeconds(response.headers),
            )
        }

        throw OpenAIApiError.Parse(
            url,
            IllegalStateException(
                "$operation expected text/event-stream but received $normalized with body sample: ${body.take(256)}",
            ),
        )
    }

    private suspend fun throwUnexpectedSuccessfulAudioResponse(
        url: String,
        response: io.ktor.client.statement.HttpResponse,
        operation: String,
    ) {
        val contentType = response.headers[HttpHeaders.ContentType] ?: return
        val normalized = contentType.substringBefore(';').trim().lowercase()
        if (
            normalized != ContentType.Text.EventStream.toString() &&
            !normalized.isLikelyJsonContentType() &&
            normalized !in setOf(ContentType.Text.Plain.toString(), ContentType.Text.Html.toString())
        ) {
            return
        }

        val body = response.bodyAsText()
        parseResponseApiErrorBody(body)?.let { parsed ->
            throw OpenAIApiError.Api(
                url = url,
                status = response.status.value,
                error = parsed,
                retryAfterSeconds = parseRetryAfterSeconds(response.headers),
            )
        }

        throw OpenAIApiError.Parse(
            url,
            IllegalStateException(
                "$operation expected audio bytes but received $normalized with body sample: ${body.take(256)}",
            ),
        )
    }

    private fun String.isLikelyJsonContentType(): Boolean =
        this == ContentType.Application.Json.toString() || endsWith("/json") || endsWith("+json")

    private suspend inline fun wrapStreamingTransport(
        url: String,
        block: suspend () -> Unit,
    ) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (t is OpenAIApiError) throw t
            throw OpenAIApiError.Network(url, t)
        }
    }

    private suspend fun readBinaryBody(
        url: String,
        response: io.ktor.client.statement.HttpResponse,
    ): ByteArray =
        try {
            response.body()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (t is OpenAIApiError) throw t
            throw OpenAIApiError.Network(url, t)
        }

    private inline fun <reified T> decodeJsonBody(url: String, body: String): T =
        try {
            OpenAIJson.decodeFromString<T>(body)
        } catch (t: Throwable) {
            throw OpenAIApiError.Parse(url, t, bodySample = body.take(2048))
        }

    private fun decodeModelPage(url: String, body: String): ModelPage =
        try {
            val payload = OpenAIJson.parseToJsonElement(body).jsonObject
            val normalized =
                if (payload["data"] == JsonNull) {
                    buildJsonObject {
                        payload.forEach { (key, value) ->
                            if (key == "data") {
                                put("data", buildJsonArray {})
                            } else {
                                put(key, value)
                            }
                        }
                    }
                } else {
                    payload
                }
            OpenAIJson.decodeFromJsonElement<ModelPage>(normalized)
        } catch (t: Throwable) {
            throw OpenAIApiError.Parse(url, t, bodySample = body.take(2048))
        }

    private fun decodeTranscriptionResult(
        url: String,
        body: String,
        requestedFormat: AudioTextResponseFormat?,
    ): TranscriptionResult =
        decodeAudioTextResult(
            url = url,
            body = body,
            requestedFormat = requestedFormat,
            fallback = { text, responseFormat ->
                TranscriptionResult(text = text, responseFormat = responseFormat)
            },
        )

    private fun decodeTranslationResult(
        url: String,
        body: String,
        requestedFormat: AudioTextResponseFormat?,
    ): TranslationResult =
        decodeAudioTextResult(
            url = url,
            body = body,
            requestedFormat = requestedFormat,
            fallback = { text, responseFormat ->
                TranslationResult(text = text, responseFormat = responseFormat)
            },
        )

    private inline fun <reified T> decodeAudioTextResult(
        url: String,
        body: String,
        requestedFormat: AudioTextResponseFormat?,
        fallback: (text: String, responseFormat: AudioTextResponseFormatValue?) -> T,
    ): T =
        when (requestedFormat) {
            AudioTextResponseFormat.TEXT,
            AudioTextResponseFormat.SRT,
            AudioTextResponseFormat.VTT,
            ->
                fallback(
                    body,
                    when (requestedFormat) {
                        AudioTextResponseFormat.TEXT -> AudioTextResponseFormatValue.Text
                        AudioTextResponseFormat.SRT -> AudioTextResponseFormatValue.Srt
                        AudioTextResponseFormat.VTT -> AudioTextResponseFormatValue.Vtt
                    },
                )
            else ->
                runCatching { OpenAIJson.decodeFromString<T>(body) }
                    .getOrElse {
                        if (requestedFormat == null) {
                            fallback(body, null)
                        } else {
                            throw OpenAIApiError.Parse(url, it, bodySample = body.take(2048))
                        }
                    }
        }

    private fun io.ktor.client.request.HttpRequestBuilder.applyVectorStoresBeta() {
        header("OpenAI-Beta", "assistants=v2")
    }

    private fun FormBuilder.appendBinaryUpload(name: String, file: BinaryUpload) {
        append(
            name,
            file.bytes,
            Headers.build {
                append(
                    HttpHeaders.ContentDisposition,
                    """form-data; name="$name"; filename="${file.filename.replace("\"", "%22")}"""",
                )
                file.contentType?.let { append(HttpHeaders.ContentType, it) }
            },
        )
    }

    private suspend fun collectByteChunks(
        channel: ByteReadChannel,
        emit: suspend (ByteArray) -> Unit,
    ) {
        val buffer = ByteArray(8192)
        while (!channel.isClosedForRead) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read < 0) break
            if (read == 0) continue
            emit(buffer.copyOf(read))
        }
    }
}

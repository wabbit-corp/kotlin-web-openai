// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

@file:OptIn(
    dev.whyoleg.cryptography.CryptographyProviderApi::class,
    dev.whyoleg.cryptography.DelicateCryptographyApi::class,
    kotlin.io.encoding.ExperimentalEncodingApi::class,
)

package one.wabbit.web.openai

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.formUrlEncode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import one.wabbit.web.common.RetryAction
import one.wabbit.web.common.RetryPolicy
import one.wabbit.web.common.Schedule
import one.wabbit.web.common.Timeouts
import kotlin.io.encoding.Base64
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class OpenAIApiSpec {
    @Test
    fun `safe read operations retry by default`() = runTest {
        var attempts = 0
        val client = httpClient { request ->
            attempts++
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/v1/models/gpt-4.1-mini", request.url.encodedPath)
            if (attempts == 1) {
                respond(
                    content = """{"error":{"message":"temporary","type":"server_error"}}""",
                    status = HttpStatusCode.InternalServerError,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            } else {
                respondJson("""{"id":"gpt-4.1-mini","object":"model","owned_by":"openai"}""")
            }
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    apiKey = "secret",
                    retryPolicy = singleImmediateRetryPolicy(),
                ),
            )

        val model = api.getModel(ModelId("gpt-4.1-mini"))

        assertEquals("gpt-4.1-mini", model.id)
        assertEquals(2, attempts)
    }

    @Test
    fun `non idempotent operations do not retry by default`() = runTest {
        var attempts = 0
        val client = httpClient { request ->
            attempts++
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/responses", request.url.encodedPath)
            if (attempts == 1) {
                respond(
                    content = """{"error":{"message":"temporary","type":"server_error"}}""",
                    status = HttpStatusCode.InternalServerError,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            } else {
                respondJson("""{"id":"resp_retry","object":"response","status":"completed","output":[]}""")
            }
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    apiKey = "secret",
                    retryPolicy = singleImmediateRetryPolicy(),
                ),
            )

        val error =
            assertFailsWith<OpenAIApiError.Api> {
                api.createResponse(
                    ResponseCreateRequest(
                        model = ModelId("gpt-4.1-mini"),
                        input = ResponseInput.Text("ping"),
                    ),
                )
            }

        assertEquals(500, error.status)
        assertEquals(1, attempts)
    }

    @Test
    fun `non idempotent operations retry when explicitly enabled`() = runTest {
        var attempts = 0
        val client = httpClient { request ->
            attempts++
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/responses", request.url.encodedPath)
            if (attempts == 1) {
                respond(
                    content = """{"error":{"message":"temporary","type":"server_error"}}""",
                    status = HttpStatusCode.InternalServerError,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
            } else {
                respondJson("""{"id":"resp_retry","object":"response","status":"completed","output":[]}""")
            }
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    apiKey = "secret",
                    retryPolicy = singleImmediateRetryPolicy(),
                    retryNonIdempotentRequests = true,
                ),
            )

        val response =
            api.createResponse(
                ResponseCreateRequest(
                    model = ModelId("gpt-4.1-mini"),
                    input = ResponseInput.Text("ping"),
                ),
            )

        assertEquals("resp_retry", response.id)
        assertEquals(2, attempts)
    }

    @Test
    fun `default retry policy honors retry after on typed api errors`() {
        val policy = OpenAIApi.defaultRetryPolicy()
        val run = policy.newRun()
        val delay =
            run.nextDelay(
                OpenAIApiError.Api(
                    url = "https://example.com/v1/responses",
                    status = 429,
                    error = ResponseApiError(message = "slow down"),
                    retryAfterSeconds = 1.5,
                ),
            )

        assertEquals(1.5.seconds, delay)
    }

    @Test
    fun `deriveStreamingTimeouts disables request timeout and adds a default socket stall timeout`() {
        val derived =
            deriveStreamingTimeouts(
                timeouts = Timeouts(request = 15.seconds, connect = 7.seconds, socket = 20.seconds),
                streamingTimeouts = null,
            )

        assertNull(derived.request)
        assertEquals(7.seconds, derived.connect)
        assertEquals(DefaultStreamingSocketTimeout, derived.socket)
    }

    @Test
    fun `deriveStreamingTimeouts respects explicit streaming override`() {
        val override = Timeouts(request = 90.seconds, connect = 5.seconds, socket = 30.seconds)
        val derived =
            deriveStreamingTimeouts(
                timeouts = Timeouts(request = 15.seconds, connect = 7.seconds, socket = 20.seconds),
                streamingTimeouts = override,
            )

        assertEquals(override, derived)
    }

    @Test
    fun `streamResponse applies default socket stall timeout`() = runTest {
        var seenTimeoutMillis: Triple<Long?, Long?, Long?>? = null
        val client = httpClient { request ->
            val timeout = request.getCapabilityOrNull(HttpTimeoutCapability)
            seenTimeoutMillis =
                Triple(
                    timeout?.requestTimeoutMillis,
                    timeout?.connectTimeoutMillis,
                    timeout?.socketTimeoutMillis,
                )
            respond(
                content = "data: [DONE]\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    apiKey = "secret",
                    timeouts = Timeouts(request = 15.seconds, connect = 7.seconds, socket = 20.seconds),
                ),
            )

        api
            .streamResponse(
                ResponseCreateRequest(
                    model = ModelId("gpt-4.1-mini"),
                    input = ResponseInput.Text("hello"),
                ),
            ).toList()

        assertEquals(
            Triple<Long?, Long?, Long?>(null, 7.seconds.inWholeMilliseconds, DefaultStreamingSocketTimeout.inWholeMilliseconds),
            seenTimeoutMillis,
        )
    }

    @Test
    fun `streamResponse respects explicit streaming timeout override`() = runTest {
        var seenTimeoutMillis: Triple<Long?, Long?, Long?>? = null
        val client = httpClient { request ->
            val timeout = request.getCapabilityOrNull(HttpTimeoutCapability)
            seenTimeoutMillis =
                Triple(
                    timeout?.requestTimeoutMillis,
                    timeout?.connectTimeoutMillis,
                    timeout?.socketTimeoutMillis,
                )
            respond(
                content = "data: [DONE]\n\n",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }

        val override = Timeouts(request = 90.seconds, connect = 5.seconds, socket = 30.seconds)
        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    apiKey = "secret",
                    timeouts = Timeouts(request = 15.seconds, connect = 7.seconds, socket = 20.seconds),
                    streamingTimeouts = override,
                ),
            )

        api
            .streamResponse(
                ResponseCreateRequest(
                    model = ModelId("gpt-4.1-mini"),
                    input = ResponseInput.Text("hello"),
                ),
            ).toList()

        assertEquals(
            Triple<Long?, Long?, Long?>(override.request?.inWholeMilliseconds, override.connect?.inWholeMilliseconds, override.socket?.inWholeMilliseconds),
            seenTimeoutMillis,
        )
    }

    @Test
    fun `createResponse posts to responses endpoint and decodes output text`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/responses", request.url.encodedPath)
            assertEquals("Bearer secret", request.headers[HttpHeaders.Authorization])
            respondJson(
                """
                {
                  "id": "resp_123",
                  "object": "response",
                  "status": "completed",
                  "model": "gpt-4.1-mini",
                  "output": [
                    {
                      "id": "msg_1",
                      "type": "message",
                      "role": "assistant",
                      "content": [
                        {"type": "output_text", "text": "Hello from responses."}
                      ]
                    }
                  ],
                  "usage": {
                    "input_tokens": 12,
                    "output_tokens": 5,
                    "total_tokens": 17
                  }
                }
                """.trimIndent(),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val response =
            api.createResponse(
                ResponseCreateRequest(
                    model = ModelId("gpt-4.1-mini"),
                    input = ResponseInput.Text("Say hello"),
                    text = ResponseTextConfig(ResponseTextFormat.PlainText),
                ),
            )

        assertEquals("resp_123", response.id)
        assertEquals("Hello from responses.", response.outputText())
        assertEquals(17, response.usage?.totalTokens)
        assertEquals(ContentType.Application.Json.toString(), seenRequest?.headers?.get(HttpHeaders.Accept))
    }

    @Test
    fun `response helpers expose structured message texts and reject merged multi message output`() {
        val response =
            ResponseObject(
                id = "resp_multi",
                output =
                    listOf(
                        ResponseOutputItem(
                            id = "msg_1",
                            type = ResponseItemType.Message,
                            role = "assistant",
                            content = listOf(ResponseContentPart(type = "output_text", text = """{"city":"Paris"}""")),
                        ),
                        ResponseOutputItem(
                            id = "fc_1",
                            type = ResponseItemType.FunctionCall,
                            callId = "call_1",
                            name = "lookup_weather",
                            arguments = """{"city":"Paris"}""",
                        ),
                        ResponseOutputItem(
                            id = "msg_2",
                            type = ResponseItemType.Message,
                            role = "assistant",
                            content = listOf(ResponseContentPart(type = "output_text", text = """{"city":"Tokyo"}""")),
                        ),
                    ),
            )

        assertEquals(listOf("""{"city":"Paris"}""", """{"city":"Tokyo"}"""), response.outputTexts())

        val textError = assertFailsWith<IllegalStateException> { response.outputText() }
        assertTrue(textError.message.orEmpty().contains("outputTexts()"))

        val jsonError = assertFailsWith<IllegalStateException> { response.outputJsonElementOrNull() }
        assertTrue(jsonError.message.orEmpty().contains("outputTexts()"))
    }

    @Test
    fun `createResponse parse errors hide body sample in message by default`() = runTest {
        val client = httpClient {
            respond(
                content = """{"id":"resp_bad","object":"response","status":"completed","output":"oops"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val error =
            assertFailsWith<OpenAIApiError.Parse> {
                api.createResponse(
                    ResponseCreateRequest(
                        model = ModelId("gpt-4.1-mini"),
                        input = ResponseInput.Text("Say hello"),
                    ),
                )
            }

        assertTrue(error.message.orEmpty().contains("Failed to parse response"))
        assertTrue(!error.message.orEmpty().contains("\"output\":\"oops\""))
        assertEquals("""{"id":"resp_bad","object":"response","status":"completed","output":"oops"}""", error.bodySample)
    }

    @Test
    fun `createResponse parse errors include body sample in message when explicitly enabled`() = runTest {
        val client = httpClient {
            respond(
                content = """{"id":"resp_bad","object":"response","status":"completed","output":"oops"}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    apiKey = "secret",
                    includeBodySamplesInExceptions = true,
                ),
            )
        val error =
            assertFailsWith<OpenAIApiError.Parse> {
                api.createResponse(
                    ResponseCreateRequest(
                        model = ModelId("gpt-4.1-mini"),
                        input = ResponseInput.Text("Say hello"),
                    ),
                )
            }

        assertTrue(error.message.orEmpty().contains("\"output\":\"oops\""))
        assertEquals("""{"id":"resp_bad","object":"response","status":"completed","output":"oops"}""", error.bodySample)
    }

    @Test
    fun `opt-in parse errors redact sensitive json body samples`() = runTest {
        val client = httpClient {
            respond(
                content =
                    """
                    {
                      "id":"resp_bad",
                      "object":"response",
                      "status":"completed",
                      "output":"oops",
                      "client_secret":"cs_123",
                      "nested":{"authorization":"Bearer sk-secret"},
                      "download_url":"https://files.example.invalid/file?sig=abc123&expires=10"
                    }
                    """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    apiKey = "secret",
                    includeBodySamplesInExceptions = true,
                ),
            )
        val error =
            assertFailsWith<OpenAIApiError.Parse> {
                api.createResponse(
                    ResponseCreateRequest(
                        model = ModelId("gpt-4.1-mini"),
                        input = ResponseInput.Text("Say hello"),
                    ),
                )
            }

        val sample = OpenAIJson.parseToJsonElement(assertNotNull(error.bodySample)).jsonObject
        assertEquals("[REDACTED]", sample["client_secret"]?.jsonPrimitive?.content)
        assertEquals(
            "[REDACTED]",
            sample["nested"]?.jsonObject?.get("authorization")?.jsonPrimitive?.content,
        )
        assertEquals(
            "https://files.example.invalid/file?sig=[REDACTED]&expires=10",
            sample["download_url"]?.jsonPrimitive?.content,
        )
        assertTrue(error.message.orEmpty().contains("[REDACTED]"))
        assertTrue(!error.message.orEmpty().contains("cs_123"))
        assertTrue(!error.message.orEmpty().contains("sk-secret"))
        assertTrue(!error.message.orEmpty().contains("abc123"))
    }

    @Test
    fun `raw http errors hide body sample in message by default`() = runTest {
        val client = httpClient {
            respond(
                content = "signed_url=https://files.example.invalid/secret-token",
                status = HttpStatusCode.BadGateway,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val error =
            assertFailsWith<OpenAIApiError.Http> {
                api.getModel(ModelId("gpt-4.1-mini"))
            }

        assertTrue(error.message.orEmpty().contains("HTTP 502"))
        assertTrue(!error.message.orEmpty().contains("signed_url="))
        assertEquals("signed_url=[REDACTED]", error.bodySample)
    }

    @Test
    fun `opt-in raw http errors redact sensitive plain text body samples`() = runTest {
        val client = httpClient {
            respond(
                content =
                    "authorization=Bearer sk-secret api_key=sk-123 signed_url=https://files.example.invalid/file?sig=abc123&expires=10",
                status = HttpStatusCode.BadGateway,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()),
            )
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    apiKey = "secret",
                    includeBodySamplesInExceptions = true,
                ),
            )
        val error =
            assertFailsWith<OpenAIApiError.Http> {
                api.getModel(ModelId("gpt-4.1-mini"))
            }

        assertEquals(
            "authorization=Bearer [REDACTED] api_key=[REDACTED] signed_url=[REDACTED]",
            error.bodySample,
        )
        assertTrue(error.message.orEmpty().contains("[REDACTED]"))
        assertTrue(!error.message.orEmpty().contains("sk-secret"))
        assertTrue(!error.message.orEmpty().contains("sk-123"))
        assertTrue(!error.message.orEmpty().contains("abc123"))
    }

    @Test
    fun `openrouter provider adds attribution headers`() = runTest {
        val provider = OpenAIProvider.OpenRouter(referer = "https://example.com", title = "Example App")
        val client = httpClient { request ->
            assertEquals("Bearer or-key", request.headers[HttpHeaders.Authorization])
            assertEquals("https://example.com", request.headers["HTTP-Referer"])
            assertEquals("Example App", request.headers["X-Title"])
            respondJson(
                """
                {
                  "id": "resp_or",
                  "object": "response",
                  "status": "completed",
                  "model": "openrouter/auto",
                  "output": []
                }
                """.trimIndent(),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(provider = provider, apiKey = "or-key", baseUrl = provider.defaultBaseUrl))
        api.createResponse(ResponseCreateRequest(model = ModelId("openrouter/auto"), input = ResponseInput.Text("ping")))
    }

    @Test
    fun `api errors decode typed envelope`() = runTest {
        val client = httpClient {
            respond(
                content = """{"error":{"message":"bad model","type":"invalid_request_error","code":"model_not_found"}}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val error =
            assertFailsWith<OpenAIApiError.Api> {
                api.createResponse(ResponseCreateRequest(model = ModelId("missing"), input = ResponseInput.Text("ping")))
            }

        assertEquals(400, error.status)
        assertEquals("invalid_request_error", error.error.type)
        assertEquals(ResponseErrorCode.Unknown("model_not_found"), error.error.code)
    }

    @Test
    fun `api errors decode alternative detail envelopes`() = runTest {
        val client = httpClient {
            respond(
                content = """{"detail":"Unsupported model"}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val error =
            assertFailsWith<OpenAIApiError.Api> {
                api.createResponse(ResponseCreateRequest(model = ModelId("missing"), input = ResponseInput.Text("ping")))
            }

        assertEquals(400, error.status)
        assertEquals("Unsupported model", error.error.message)
        assertEquals("""{"detail":"Unsupported model"}""", error.error.details.toString())
    }

    @Test
    fun `api errors preserve numeric error codes from compatibility providers`() = runTest {
        val client = httpClient {
            respond(
                content =
                    """
                    {
                      "error": {
                        "message": "Authentication required",
                        "code": 403,
                        "metadata": {"provider_name": "openrouter"}
                      },
                      "endpoint": "/api/v1/chat/completions"
                    }
                    """.trimIndent(),
                status = HttpStatusCode.Forbidden,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = OpenAIProvider.OpenRouter(),
                    apiKey = "or-key",
                    baseUrl = OpenAIProvider.OpenRouter().defaultBaseUrl,
                ),
            )
        val error =
            assertFailsWith<OpenAIApiError.Api> {
                api.createResponse(ResponseCreateRequest(model = ModelId("openrouter/auto"), input = ResponseInput.Text("ping")))
            }

        assertEquals(403, error.status)
        assertEquals("Authentication required", error.error.message)
        assertEquals(ResponseErrorCode.Unknown("403"), error.error.code)
        assertEquals("/api/v1/chat/completions", error.error.details?.jsonObject?.get("endpoint")?.jsonPrimitive?.content)
        assertEquals(
            "openrouter",
            error.error.details?.jsonObject?.get("error")?.jsonObject?.get("metadata")?.jsonObject?.get("provider_name")?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `stateless responses providers reject retrieval endpoints`() = runTest {
        val api =
            KtorOpenAIApi(
                httpClient { error("request should not be made") },
                OpenAIApi.Config(
                    provider = OpenAIProvider.Ollama(),
                    baseUrl = OpenAIProvider.Ollama().defaultBaseUrl,
                ),
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                api.getResponse(ResponseId("resp_1"))
            }

        assertTrue(error.message?.contains("stateful Responses retrieval endpoints") == true)
    }

    @Test
    fun `server sent event parser handles response text deltas and done marker`() {
        val events =
            parseResponseStreamEvents(
                """
                data: {"type":"response.created","response":{"id":"resp_1","object":"response","status":"in_progress","output":[]}}
                
                data: {"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"Hel"}
                
                data: {"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"lo"}
                
                data: {"type":"response.output_text.done","item_id":"msg_1","output_index":0,"content_index":0,"text":"Hello"}
                
                data: [DONE]
                
                """.trimIndent(),
            )

        assertEquals(5, events.size)
        assertTrue(events[0] is ResponseStreamEvent.ResponseCreated)
        assertEquals("Hel", (events[1] as ResponseStreamEvent.OutputTextDelta).delta)
        assertEquals("lo", (events[2] as ResponseStreamEvent.OutputTextDelta).delta)
        assertEquals("Hello", (events[3] as ResponseStreamEvent.OutputTextDone).text)
        assertEquals(ResponseStreamEvent.Done, events[4])
    }

    @Test
    fun `chat completion stream parser accepts non standard error envelopes`() {
        val event =
            parseChatCompletionStreamEvent(
                ServerSentEvent(
                    data = ServerSentEventData("""{"detail":"Unsupported model"}"""),
                ),
            )

        val error = event as ChatCompletionStreamEvent.Error
        assertEquals("Unsupported model", error.error.message)
    }

    @Test
    fun `chat completion stream parser tolerates plain text error events`() {
        val event =
            parseChatCompletionStreamEvent(
                ServerSentEvent(
                    event = "error",
                    data = ServerSentEventData("gateway timeout"),
                ),
            )

        val error = event as ChatCompletionStreamEvent.Error
        assertEquals("gateway timeout", error.error.message)
    }

    @Test
    fun `chat completion stream parser preserves deprecated function_call deltas`() {
        val event =
            parseChatCompletionStreamEvent(
                ServerSentEvent(
                    data =
                        ServerSentEventData(
                            """
                            {
                              "id":"chatcmpl_fc",
                              "object":"chat.completion.chunk",
                              "created":1741569952,
                              "model":"gpt-5.4",
                              "choices":[
                                {
                                  "index":0,
                                  "delta":{
                                    "role":"assistant",
                                    "function_call":{"name":"lookup_weather","arguments":"{"}
                                  },
                                  "finish_reason":null
                                }
                              ]
                            }
                            """.trimIndent(),
                        ),
                ),
            )

        val chunk = (event as ChatCompletionStreamEvent.Chunk).chunk
        val functionCall = chunk.choices.single().delta?.functionCall
        assertEquals("lookup_weather", functionCall?.name)
        assertEquals("{", functionCall?.arguments)
    }

    @Test
    fun `provider capabilities reflect stateful differences`() {
        assertTrue(OpenAIProvider.OpenAI.capabilities.statefulResponses)
        assertTrue(OpenAIProvider.OpenAI.capabilities.embeddingsApi)
        assertTrue(OpenAIProvider.OpenAI.capabilities.modelsApi)
        assertTrue(OpenAIProvider.OpenAI.capabilities.imagesApi)
        assertTrue(OpenAIProvider.OpenAI.capabilities.moderationsApi)
        assertTrue(OpenAIProvider.OpenAI.capabilities.batchesApi)
        assertTrue(OpenAIProvider.OpenAI.capabilities.audioApi)
        assertTrue(OpenAIProvider.OpenAI.capabilities.filesApi)
        assertTrue(OpenAIProvider.OpenAI.capabilities.uploadsApi)
        assertTrue(OpenAIProvider.OpenAI.capabilities.vectorStoresApi)
        assertTrue(OpenAIProvider.OpenAI.capabilities.fineTuningApi)
        assertTrue(OpenAIProvider.OpenRouter().capabilities.embeddingsApi)
        assertTrue(OpenAIProvider.OpenRouter().capabilities.modelsApi)
        assertTrue(!OpenAIProvider.OpenRouter().capabilities.imagesApi)
        assertTrue(!OpenAIProvider.OpenRouter().capabilities.fineTuningApi)
        assertTrue(OpenAIProvider.Ollama().capabilities.embeddingsApi)
        assertTrue(OpenAIProvider.Ollama().capabilities.modelsApi)
        assertTrue(OpenAIProvider.Ollama().capabilities.imagesApi)
        assertTrue(!OpenAIProvider.Ollama().capabilities.fineTuningApi)
        assertTrue(!OpenAIProvider.OpenRouter().capabilities.statefulResponses)
        assertTrue(!OpenAIProvider.Ollama().capabilities.statefulResponses)
        assertTrue(!OpenAIProvider.DeepSeek.capabilities.responsesApi)
        assertTrue(!OpenAIProvider.DeepSeek.capabilities.embeddingsApi)
        assertTrue(OpenAIProvider.DeepSeek.capabilities.modelsApi)
        assertTrue(OpenAIProvider.DeepSeek.capabilities.chatCompletions)
        assertTrue(OpenAIProvider.XAI.capabilities.responsesApi)
        assertTrue(OpenAIProvider.XAI.capabilities.chatCompletions)
        assertTrue(OpenAIProvider.XAI.capabilities.modelsApi)
        assertTrue(OpenAIProvider.XAI.capabilities.imagesApi)
        assertTrue(OpenAIProvider.XAI.capabilities.filesApi)
        assertTrue(OpenAIProvider.Azure(resourceName = "demo").capabilities.responsesApi)
        assertTrue(OpenAIProvider.Azure(resourceName = "demo").capabilities.statefulResponses)
        assertTrue(OpenAIProvider.Azure(resourceName = "demo").capabilities.chatCompletions)
        assertTrue(OpenAIProvider.Azure(resourceName = "demo").capabilities.embeddingsApi)
        assertTrue(OpenAIProvider.Azure(resourceName = "demo").capabilities.modelsApi)
        assertTrue(OpenAIProvider.Azure(resourceName = "demo").capabilities.imagesApi)
        assertTrue(OpenAIProvider.Azure(resourceName = "demo").capabilities.audioApi)
        assertTrue(OpenAIProvider.Azure(resourceName = "demo").capabilities.filesApi)
        assertTrue(OpenAIProvider.Azure(resourceName = "demo").capabilities.vectorStoresApi)
        assertTrue(OpenAIProvider.Azure(resourceName = "demo").capabilities.fineTuningApi)
        assertTrue(OpenAIProvider.Azure(resourceName = "demo").capabilities.realtimeBootstrapApi)
        assertTrue(OpenAIProvider.Azure(resourceName = "demo").capabilities.evalsApi)
        assertTrue(OpenAIProvider.Azure(resourceName = "demo").capabilities.conversationsApi)
        assertTrue(OpenAIProvider.Groq.capabilities.responsesApi)
        assertTrue(!OpenAIProvider.Groq.capabilities.statefulResponses)
        assertTrue(OpenAIProvider.Groq.capabilities.chatCompletions)
        assertTrue(OpenAIProvider.Groq.capabilities.modelsApi)
        assertTrue(OpenAIProvider.Groq.capabilities.audioApi)
        assertTrue(OpenAIProvider.Groq.capabilities.filesApi)
        assertTrue(OpenAIProvider.Groq.capabilities.batchesApi)
        assertTrue(!OpenAIProvider.Gemini.capabilities.responsesApi)
        assertTrue(OpenAIProvider.Gemini.capabilities.chatCompletions)
        assertTrue(OpenAIProvider.Gemini.capabilities.embeddingsApi)
        assertTrue(OpenAIProvider.Gemini.capabilities.modelsApi)
        assertTrue(OpenAIProvider.Gemini.capabilities.imagesApi)
        assertTrue(OpenAIProvider.Gemini.capabilities.batchesApi)
        assertTrue(!OpenAIProvider.Anthropic.capabilities.responsesApi)
        assertTrue(OpenAIProvider.Anthropic.capabilities.chatCompletions)
    }

    @Test
    fun `responses reject typed builtin tools when provider disables builtin tool support`() {
        val request =
            ResponseCreateRequest(
                model = ModelId("qwen3"),
                input = ResponseInput.Text("ping"),
                tools =
                    listOf(
                        ResponseTool.WebSearch(),
                        ResponseTool.Mcp(serverLabel = "docs", serverUrl = "https://mcp.example.com"),
                    ),
                toolChoice = ResponseToolChoice.Shell,
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                request.requireCompatibleWith(OpenAIProvider.Ollama())
            }

        assertTrue(error.message.orEmpty().contains("ollama"))
        assertTrue(error.message.orEmpty().contains("web_search"))
        assertTrue(error.message.orEmpty().contains("mcp"))
        assertTrue(error.message.orEmpty().contains("shell"))
    }

    @Test
    fun `chat completions still allow function and custom tools when provider disables builtin tool support`() {
        ChatCompletionRequest(
            model = ModelId("qwen3"),
            messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"))),
            tools =
                listOf(
                    ResponseTool.Function(name = "lookup_weather"),
                    ResponseTool.Custom(name = "emit_patch"),
                ),
            toolChoice = ResponseToolChoice.NamedFunction("lookup_weather"),
        ).requireCompatibleWith(OpenAIProvider.Ollama())
    }

    @Test
    fun `streamResponse yields parsed events and assembles final text`() = runTest {
        val client = httpClient {
            respond(
                content =
                    """
                    data: {"type":"response.created","response":{"id":"resp_stream","object":"response","status":"in_progress","output":[]}}
                    
                    data: {"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"Hel"}
                    
                    data: {"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"lo"}
                    
                    data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":1,"delta":"{"}
                    
                    data: {"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":1,"delta":"\"city\":\"Paris\"}"}
                    
                    data: {"type":"response.reasoning_summary_text.delta","item_id":"rs_1","output_index":2,"delta":"Thinking"}
                    
                    data: {"type":"response.completed","response":{"id":"resp_stream","object":"response","status":"completed","output":[]}}
                    
                    data: [DONE]
                    """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val events = api.streamResponse(ResponseCreateRequest(model = ModelId("gpt-4.1-mini"), input = ResponseInput.Text("ping")))
        val assembly = events.collectResponseStream()

        assertEquals("Hello", assembly.outputText)
        assertEquals("""{"city":"Paris"}""", assembly.functionCallArguments["fc_1"])
        assertEquals("Thinking", assembly.reasoningSummaryText["rs_1"])
        assertEquals("resp_stream", assembly.finalResponse?.id)
        assertEquals("Hello", assembly.finalResponse?.outputText())
        assertTrue(assembly.isDone)
    }

    @Test
    fun `streamResponse rejects successful json error bodies`() = runTest {
        val client = httpClient {
            respond(
                content = """{"error":{"message":"stream unavailable","type":"invalid_request_error"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val error =
            assertFailsWith<OpenAIApiError.Api> {
                api
                    .streamResponse(ResponseCreateRequest(model = ModelId("gpt-4.1-mini"), input = ResponseInput.Text("ping")))
                    .collectResponseStream()
            }

        assertEquals(200, error.status)
        assertEquals("stream unavailable", error.error.message)
    }

    @Test
    fun `stream chat completion rejects successful non sse json bodies`() = runTest {
        val client = httpClient {
            respondJson(
                """
                {
                  "id":"chatcmpl_1",
                  "object":"chat.completion",
                  "choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}]
                }
                """.trimIndent(),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val error =
            assertFailsWith<OpenAIApiError.Parse> {
                api
                    .streamChatCompletion(
                        ChatCompletionRequest(
                            model = ModelId("gpt-4.1-mini"),
                            messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("hello"))),
                        ),
                    ).collectChatCompletionStream()
            }

        assertTrue(error.message.orEmpty().contains("expected text/event-stream"))
        assertTrue(!error.message.orEmpty().contains("\"content\":\"hi\""))
        val sample = OpenAIJson.parseToJsonElement(assertNotNull(error.bodySample)).jsonObject
        assertEquals("chatcmpl_1", sample["id"]?.jsonPrimitive?.content)
        assertEquals("chat.completion", sample["object"]?.jsonPrimitive?.content)
    }

    @Test
    fun `streamResponse wraps malformed event chunks as parse errors without leaking body sample by default`() = runTest {
        val client = httpClient {
            respond(
                content =
                    """
                    data: {"type":"response.completed","response":"oops"}

                    data: [DONE]
                    """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val error =
            assertFailsWith<OpenAIApiError.Parse> {
                api
                    .streamResponse(ResponseCreateRequest(model = ModelId("gpt-4.1-mini"), input = ResponseInput.Text("ping")))
                    .collectResponseStream()
            }

        assertTrue(!error.message.orEmpty().contains("\"response\":\"oops\""))
        assertEquals("""{"type":"response.completed","response":"oops"}""", error.bodySample)
    }

    @Test
    fun `response stream assembly uses output text done payloads when deltas are absent`() {
        val events =
            parseServerSentEvents(
                """
                data: {"type":"response.output_text.done","item_id":"msg_1","output_index":0,"content_index":0,"text":"Hello"}

                data: {"type":"response.output_text.done","item_id":"msg_2","output_index":1,"content_index":0,"text":" world"}

                data: [DONE]
                """.trimIndent(),
            ).map(::parseResponseStreamEvent)

        val assembly = ResponseStreamAccumulator().apply {
            events.forEach(::apply)
        }.snapshot()

        assertEquals(listOf("Hello", " world"), assembly.outputTexts.values.toList())
        val error = assertFailsWith<IllegalStateException> { assembly.outputText }
        assertTrue(error.message.orEmpty().contains("outputTexts"))
        assertTrue(assembly.isDone)
    }

    @Test
    fun `response stream assembly orders text by output and content index instead of arrival order`() {
        val assembly =
            ResponseStreamAccumulator().let { accumulator ->
                accumulator.apply(
                    ResponseStreamEvent.OutputTextDone(
                        itemId = "msg_2",
                        outputIndex = 1,
                        contentIndex = 0,
                        text = "world",
                    ),
                )
                accumulator.apply(
                    ResponseStreamEvent.OutputTextDone(
                        itemId = "msg_1",
                        outputIndex = 0,
                        contentIndex = 1,
                        text = "lo ",
                    ),
                )
                accumulator.apply(
                    ResponseStreamEvent.OutputTextDone(
                        itemId = "msg_1",
                        outputIndex = 0,
                        contentIndex = 0,
                        text = "Hel",
                    ),
                )
                accumulator.snapshot()
            }

        assertEquals(listOf("Hel", "lo ", "world"), assembly.outputTexts.values.toList())
        val error = assertFailsWith<IllegalStateException> { assembly.outputText }
        assertTrue(error.message.orEmpty().contains("outputTexts"))
    }

    @Test
    fun `response stream assembly orders reasoning and audio maps by semantic position instead of arrival order`() {
        val assembly =
            ResponseStreamAccumulator().let { accumulator ->
                accumulator.apply(
                    ResponseStreamEvent.ReasoningSummaryTextDone(
                        itemId = "rs_late",
                        outputIndex = 2,
                        text = "second",
                    ),
                )
                accumulator.apply(
                    ResponseStreamEvent.AudioDone(
                        itemId = "audio_late",
                        outputIndex = 3,
                        contentIndex = 1,
                        audio = "bbb",
                    ),
                )
                accumulator.apply(
                    ResponseStreamEvent.AudioTranscriptDone(
                        itemId = "audio_late",
                        outputIndex = 3,
                        contentIndex = 1,
                        transcript = "world",
                    ),
                )
                accumulator.apply(
                    ResponseStreamEvent.AudioDone(
                        itemId = "audio_early",
                        outputIndex = 0,
                        contentIndex = 0,
                        audio = "aaa",
                    ),
                )
                accumulator.apply(
                    ResponseStreamEvent.AudioTranscriptDone(
                        itemId = "audio_early",
                        outputIndex = 0,
                        contentIndex = 0,
                        transcript = "hello",
                    ),
                )
                accumulator.apply(
                    ResponseStreamEvent.ReasoningSummaryTextDone(
                        itemId = "rs_early",
                        outputIndex = 1,
                        text = "first",
                    ),
                )
                accumulator.snapshot()
            }

        assertEquals(listOf("rs_early", "rs_late"), assembly.reasoningSummaryText.keys.toList())
        assertEquals(listOf("first", "second"), assembly.reasoningSummaryText.values.toList())
        assertEquals(listOf("audio_early:0", "audio_late:1"), assembly.audioData.keys.toList())
        assertEquals(listOf("aaa", "bbb"), assembly.audioData.values.toList())
        assertEquals(listOf("audio_early:0", "audio_late:1"), assembly.audioTranscript.keys.toList())
        assertEquals(listOf("hello", "world"), assembly.audioTranscript.values.toList())
    }

    @Test
    fun `response stream parser ignores meta only sse events`() {
        val events =
            parseResponseStreamEvents(
                """
                event: ping

                data: {"type":"response.completed","response":{"id":"resp_meta_sse","object":"response","status":"completed","output":[]}}

                data: [DONE]
                """.trimIndent(),
            )

        assertEquals(2, events.size)
        assertEquals("resp_meta_sse", (events[0] as ResponseStreamEvent.ResponseCompleted).response.id)
        assertEquals(ResponseStreamEvent.Done, events[1])
    }

    @Test
    fun `responses tools serialize typed hosted tools and decode hosted output helpers`() {
        val request =
            ResponseCreateRequest(
                model = ModelId("gpt-5"),
                input = ResponseInput.Text("find docs"),
                tools =
                    listOf(
                        ResponseTool.WebSearch(searchContextSize = SearchContextSize.HIGH),
                        ResponseTool.FileSearch(vectorStoreIds = listOf(VectorStoreId("vs_1")), maxNumResults = 3),
                        ResponseTool.ImageGeneration(size = ImageSize.X1024, partialImages = 2),
                        ResponseTool.CodeInterpreter(),
                        ResponseTool.XSearch(returnCitations = true, maxResults = 5),
                    ),
                toolChoice = ResponseToolChoice.Hosted("image_generation"),
            )

        val json = request.toJson().toString()
        assertTrue(json.contains("\"type\":\"web_search\""))
        assertTrue(json.contains("\"type\":\"file_search\""))
        assertTrue(json.contains("\"vector_store_ids\":[\"vs_1\"]"))
        assertTrue(json.contains("\"type\":\"image_generation\""))
        assertTrue(json.contains("\"partial_images\":2"))
        assertTrue(json.contains("\"type\":\"code_interpreter\""))
        assertTrue(json.contains("\"type\":\"x_search\""))
        assertTrue(json.contains("\"tool_choice\":{\"type\":\"image_generation\"}"))

        val response =
            OpenAIJson.decodeFromString<ResponseObject>(
                """
                {
                  "id":"resp_tools",
                  "object":"response",
                  "status":"completed",
                  "output":[
                    {"id":"img_1","type":"image_generation_call","status":"completed","result":"aW1hZ2U=","revised_prompt":"revised"},
                    {"id":"ws_1","type":"web_search_call","status":"completed","results":[{"url":"https://example.com"}]},
                    {"id":"fs_1","type":"file_search_call","status":"completed","results":[{"file_id":"file_1"}]}
                  ]
                }
                """.trimIndent(),
            )

        assertEquals("aW1hZ2U=", response.imageGenerationCalls().single().result)
        assertEquals("revised", response.imageGenerationCalls().single().revisedPrompt)
        assertEquals(3, response.hostedToolCalls().size)
    }

    @Test
    fun `responses named function tool choice serializes flat function object`() {
        val request =
            ResponseCreateRequest(
                model = ModelId("gpt-5"),
                input = ResponseInput.Text("call get_weather"),
                toolChoice = ResponseToolChoice.NamedFunction("get_weather"),
            )

        val toolChoice = request.toJson()["tool_choice"]!!.jsonObject

        assertEquals("function", toolChoice["type"]?.jsonPrimitive?.content)
        assertEquals("get_weather", toolChoice["name"]?.jsonPrimitive?.content)
        assertTrue(toolChoice["function"] == null)
    }

    @Test
    fun `responses request serializes richer typed fields and preview tool variants`() {
        val request =
            ResponseCreateRequest(
                model = ModelId("gpt-5"),
                input =
                    ResponseInput.Items(
                        listOf(
                            ResponseInputItem.Message(
                                role = ResponseRole.USER,
                                content =
                                    listOf(
                                        ResponseInputContent.InputText("hello"),
                                        ResponseInputContent.InputAudio(data = "UklGRg==", format = InputAudioFormat.WAV),
                                    ),
                            ),
                        ),
                    ),
                background = true,
                conversation =
                    buildJsonObject {
                        put("id", "conv_1")
                    },
                maxToolCalls = 4,
                prompt =
                    ResponsePromptReference(
                        id = "pmpt_1",
                        version = "3",
                        variables = buildJsonObject { put("topic", "science") },
                    ),
                serviceTier = ResponseServiceTier.FLEX,
                truncation = ResponseTruncation.AUTO,
                tools =
                    listOf(
                        ResponseTool.WebSearch(
                            toolType = ResponseTool.WebSearchType.WEB_SEARCH_PREVIEW,
                            filters = buildJsonObject { put("domain", "example.com") },
                        ),
                        ResponseTool.ComputerUse(
                            toolType = ResponseTool.ComputerUseType.COMPUTER_USE_PREVIEW,
                            displayNumber = 1,
                        ),
                ),
            )

        val json = request.toJson()
        val messageContent =
            json["input"]!!
                .jsonArray
                .single()
                .jsonObject["content"]!!
                .jsonArray
        val inputAudio =
            messageContent
                .single { it.jsonObject["type"]?.jsonPrimitive?.content == "input_audio" }
                .jsonObject

        assertEquals("input_audio", inputAudio["type"]?.jsonPrimitive?.content)
        assertEquals("UklGRg==", inputAudio["input_audio"]?.jsonObject?.get("data")?.jsonPrimitive?.content)
        assertEquals("wav", inputAudio["input_audio"]?.jsonObject?.get("format")?.jsonPrimitive?.content)
        assertTrue(inputAudio["data"] == null)
        assertTrue(inputAudio["format"] == null)
        val jsonString = json.toString()
        assertTrue(jsonString.contains("\"background\":true"))
        assertTrue(jsonString.contains("\"conversation\":{\"id\":\"conv_1\"}"))
        assertTrue(jsonString.contains("\"max_tool_calls\":4"))
        assertTrue(jsonString.contains("\"prompt\":{\"id\":\"pmpt_1\""))
        assertTrue(jsonString.contains("\"service_tier\":\"flex\""))
        assertTrue(jsonString.contains("\"truncation\":\"auto\""))
        assertTrue(jsonString.contains("\"type\":\"web_search_preview\""))
        assertTrue(jsonString.contains("\"display_number\":1"))
    }

    @Test
    fun `responses request serializes prompt cache safety and defer loading fields`() {
        val request =
            ResponseCreateRequest(
                model = ModelId("gpt-5"),
                input = ResponseInput.Text("hello"),
                promptCacheKey = "cache-key-1",
                promptCacheRetention = ResponsePromptCacheRetention.HOURS_24,
                safetyIdentifier = "user-hash-1",
                tools =
                    listOf(
                        ResponseTool.Function(
                            name = "lookup_weather",
                            deferLoading = true,
                        ),
                    ),
            )

        val json = request.toJson()
        val tool = json["tools"]!!.jsonArray.single().jsonObject

        assertEquals("cache-key-1", json["prompt_cache_key"]?.jsonPrimitive?.content)
        assertEquals("24h", json["prompt_cache_retention"]?.jsonPrimitive?.content)
        assertEquals("user-hash-1", json["safety_identifier"]?.jsonPrimitive?.content)
        assertEquals("true", tool["defer_loading"]?.jsonPrimitive?.content)
    }

    @Test
    fun `json extras reject overriding reserved keys`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                ResponseCreateRequest(
                    model = ModelId("gpt-5"),
                    input = ResponseInput.Text("hello"),
                    extraBody =
                        JsonExtras(
                            buildJsonObject {
                                put("model", "gpt-override")
                            },
                        ),
                ).toJson()
            }

        assertTrue(error.message.orEmpty().contains("model"))
        assertTrue(error.message.orEmpty().contains("override"))
    }

    @Test
    fun `json extras still allow non reserved extension keys`() {
        val json =
            ResponseCreateRequest(
                model = ModelId("gpt-5"),
                input = ResponseInput.Text("hello"),
                extraBody =
                    JsonExtras(
                        buildJsonObject {
                            put("vendor_extension", "enabled")
                        },
                    ),
            ).toJson()

        assertEquals("enabled", json["vendor_extension"]?.jsonPrimitive?.content)
    }

    @Test
    fun `responses request supports file and item reference inputs with structured tool outputs`() {
        val request =
            ResponseCreateRequest(
                model = ModelId("gpt-5"),
                input =
                    ResponseInput.Items(
                        listOf(
                            ResponseInputItem.ItemReference("msg_prev"),
                            ResponseInputItem.Message(
                                role = ResponseRole.USER,
                                content =
                                    listOf(
                                        ResponseInputContent.InputImage(fileId = FileId("file_img_1"), detail = InputImageDetail.LOW),
                                        ResponseInputContent.InputFile(fileUrl = "https://example.com/input.pdf", filename = "input.pdf"),
                                    ),
                            ),
                            ResponseInputItem.FunctionCallOutput(
                                callId = ToolCallId("call_2"),
                                outputContent = listOf(ResponseInputContent.InputText("done")),
                            ),
                        ),
                    ),
            )

        val json = request.toJson().toString()
        assertTrue(json.contains("\"type\":\"item_reference\""))
        assertTrue(json.contains("\"id\":\"msg_prev\""))
        assertTrue(json.contains("\"file_id\":\"file_img_1\""))
        assertTrue(json.contains("\"file_url\":\"https://example.com/input.pdf\""))
        assertTrue(json.contains("\"filename\":\"input.pdf\""))
        assertTrue(json.contains("\"output\":[{\"type\":\"input_text\",\"text\":\"done\"}]"))
    }

    @Test
    fun `function call output rejects both text and structured content`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                ResponseInputItem.FunctionCallOutput(
                    callId = ToolCallId("call_2"),
                    outputText = "done",
                    outputContent = listOf(ResponseInputContent.InputText("also done")),
                )
            }

        assertTrue(error.message.orEmpty().contains("exactly one"))
    }

    @Test
    fun `multipart extra fields reject reserved field name collisions`() {
        val fileError =
            assertFailsWith<IllegalArgumentException> {
                FileCreateRequest(
                    purpose = FilePurpose.ASSISTANTS,
                    file = BinaryUpload("notes.txt", "hello".encodeToByteArray(), "text/plain"),
                    extraFields = mapOf("purpose" to "batch"),
                )
            }
        val transcriptionError =
            assertFailsWith<IllegalArgumentException> {
                TranscriptionRequest(
                    file = BinaryUpload("audio.wav", "WAVE".encodeToByteArray(), "audio/wav"),
                    model = ModelId("gpt-4o-transcribe"),
                    extraFields = mapOf("stream" to "true"),
                )
            }
        val imageError =
            assertFailsWith<IllegalArgumentException> {
                ImageVariationRequest(
                    image = BinaryUpload("image.png", "PNG".encodeToByteArray(), "image/png"),
                    extraFields = mapOf("image" to "override"),
                )
            }

        assertTrue(fileError.message.orEmpty().contains("purpose"))
        assertTrue(transcriptionError.message.orEmpty().contains("stream"))
        assertTrue(imageError.message.orEmpty().contains("image"))
    }

    @Test
    fun `mcp tool serializes typed approval policies`() {
        val tool =
            ResponseTool.Mcp(
                serverLabel = "docs",
                serverUrl = "https://mcp.example.com",
                requireApproval =
                    ResponseTool.McpRequireApproval.Filter(
                        policy = ResponseTool.McpRequireApproval.Policy.NEVER,
                        toolNames = listOf("search", "fetch"),
                    ),
            )

        val json = tool.toJson().toString()
        assertTrue(json.contains("\"type\":\"mcp\""))
        assertTrue(json.contains("\"require_approval\":{\"never\":{\"tool_names\":[\"search\",\"fetch\"]}}"))
    }

    @Test
    fun `mcp tool serializes allowed tool filters and approval read_only filters`() {
        val tool =
            ResponseTool.Mcp(
                serverLabel = "docs",
                serverUrl = "https://mcp.example.com",
                allowedToolFilter =
                    ResponseTool.McpAllowedTools.Filter(
                        readOnly = true,
                        toolNames = listOf("search", "fetch"),
                    ),
                requireApproval =
                    ResponseTool.McpRequireApproval.Filter(
                        policy = ResponseTool.McpRequireApproval.Policy.ALWAYS,
                        readOnly = false,
                        toolNames = listOf("write"),
                    ),
            )

        val json = tool.toJson().toString()
        assertTrue(json.contains("\"allowed_tools\":{\"read_only\":true,\"tool_names\":[\"search\",\"fetch\"]}"))
        assertTrue(json.contains("\"require_approval\":{\"always\":{\"read_only\":false,\"tool_names\":[\"write\"]}}"))
    }

    @Test
    fun `responses request serializes modern custom shell apply patch and dated web search tools`() {
        val request =
            ResponseCreateRequest(
                model = ModelId("gpt-5"),
                input = ResponseInput.Text("hello"),
                tools =
                    listOf(
                        ResponseTool.Custom(
                            name = "patch",
                            description = "Patch files",
                            format =
                                ResponseTool.CustomInputFormat.Grammar(
                                    definition = "start: WORD",
                                    syntax = ResponseTool.CustomGrammarSyntax.LARK,
                                ),
                        ),
                        ResponseTool.LocalShell,
                        ResponseTool.Shell(
                            environment =
                                ResponseTool.ShellEnvironment.Local(
                                    skills =
                                        listOf(
                                            ResponseTool.LocalSkill(
                                                name = "checks",
                                                description = "Run checks",
                                                path = "/skills/checks",
                                            ),
                                        ),
                                ),
                        ),
                        ResponseTool.ApplyPatch,
                        ResponseTool.WebSearch(toolType = ResponseTool.WebSearchType.WEB_SEARCH_2025_08_26),
                    ),
                toolChoice = ResponseToolChoice.ApplyPatch,
            )

        val json = request.toJson().toString()
        assertTrue(json.contains("\"type\":\"custom\""))
        assertTrue(json.contains("\"format\":{\"type\":\"grammar\",\"definition\":\"start: WORD\",\"syntax\":\"lark\"}"))
        assertTrue(json.contains("\"type\":\"local_shell\""))
        assertTrue(json.contains("\"type\":\"shell\""))
        assertTrue(json.contains("\"environment\":{\"type\":\"local\",\"skills\":["))
        assertTrue(json.contains("\"type\":\"apply_patch\""))
        assertTrue(json.contains("\"type\":\"web_search_2025_08_26\""))
        assertTrue(json.contains("\"tool_choice\":{\"type\":\"apply_patch\"}"))
        assertEquals("shell", ResponseToolChoice.Shell.toJson().jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("custom", ResponseToolChoice.Custom("patch").toJson().jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `responses reject chat only allowed_tools tool choice`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                ResponseCreateRequest(
                    model = ModelId("gpt-5"),
                    input = ResponseInput.Text("hello"),
                    toolChoice =
                        ResponseToolChoice.AllowedTools(
                            mode = ResponseToolChoice.AllowedTools.Mode.AUTO,
                            tools =
                                listOf(
                                    ResponseTool.Function(name = "get_weather"),
                                ),
                        ),
                )
            }

        assertTrue(error.message.orEmpty().contains("chat completions"))
    }

    @Test
    fun `responses input messages encode assistant phase and supported roles`() {
        val message =
            ResponseInputItem.Message(
                role = ResponseRole.ASSISTANT,
                phase = ResponseMessagePhase.COMMENTARY,
                content = listOf(ResponseInputContent.InputText("thinking")),
            )

        val json = message.toJson().toString()
        assertTrue(json.contains("\"role\":\"assistant\""))
        assertTrue(json.contains("\"phase\":\"commentary\""))
        assertEquals(listOf("SYSTEM", "DEVELOPER", "USER", "ASSISTANT"), ResponseRole.entries.map { it.name })
        assertFailsWith<IllegalArgumentException> {
            ResponseInputItem.Message(
                role = ResponseRole.USER,
                phase = ResponseMessagePhase.FINAL_ANSWER,
                content = listOf(ResponseInputContent.InputText("hello")),
            )
        }
    }

    @Test
    fun `responses object decodes richer request state fields`() {
        val response =
            OpenAIJson.decodeFromString<ResponseObject>(
                """
                {
                  "id":"resp_rich",
                  "object":"response",
                  "status":"completed",
                  "background":true,
                  "conversation":{"id":"conv_1"},
                  "max_tool_calls":4,
                  "prompt":{"id":"pmpt_1","version":"3","variables":{"topic":"science"}},
                  "service_tier":"flex",
                  "truncation":"auto",
                  "output":[]
                }
                """.trimIndent(),
            )

        assertTrue(response.background == true)
        assertEquals("conv_1", response.conversation?.get("id")?.jsonPrimitive?.content)
        assertEquals(4, response.maxToolCalls)
        assertEquals("pmpt_1", response.prompt?.id)
        assertEquals("3", response.prompt?.version)
        assertEquals("flex", response.serviceTier)
        assertEquals("auto", response.truncation)
    }

    @Test
    fun `responses object decodes typed error code and incomplete reason helpers`() {
        val response =
            OpenAIJson.decodeFromString<ResponseObject>(
                """
                {
                  "id":"resp_err",
                  "object":"response",
                  "status":"incomplete",
                  "error":{
                    "message":"image policy blocked",
                    "code":"image_content_policy_violation"
                  },
                  "incomplete_details":{
                    "reason":"content_filter"
                  },
                  "output":[]
                }
                """.trimIndent(),
            )

        assertEquals(ResponseErrorCode.ImageContentPolicyViolation, response.error?.code)
        assertEquals(ResponseIncompleteReason.ContentFilter, response.incompleteReasonOrNull())
        assertEquals(ResponseIncompleteReason.ContentFilter, response.incompleteDetails?.reason)
    }

    @Test
    fun `responses object exposes mcp calls and item references`() {
        val response =
            OpenAIJson.decodeFromString<ResponseObject>(
                """
                {
                  "id":"resp_mcp",
                  "object":"response",
                  "status":"completed",
                  "output":[
                    {
                      "id":"mcp_1",
                      "type":"mcp_call",
                      "status":"completed",
                      "name":"search_docs",
                      "arguments":"{\"query\":\"responses\"}",
                      "result":"ok",
                      "server_label":"docs",
                      "server_url":"https://mcp.example.com"
                    }
                  ]
                }
                """.trimIndent(),
            )

        val call = response.mcpCalls().single()
        assertEquals("search_docs", call.name)
        assertEquals("docs", call.serverLabel)
        assertEquals("https://mcp.example.com", call.serverUrl)
        assertEquals("mcp_1", response.output.single().toInputReferenceOrNull()?.id)
        assertEquals("web_search_call.action.sources", ResponseInclude.WEB_SEARCH_CALL_ACTION_SOURCES.wireName)
        assertEquals("web_search_call.results", ResponseInclude.WEB_SEARCH_CALL_RESULTS.wireName)
    }

    @Test
    fun `responses request encodes typed mcp tool and approval response`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            respondJson("""{"id":"resp_mcp_req","object":"response","status":"completed","output":[]}""")
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        api.createResponse(
            ResponseCreateRequest(
                model = ModelId("gpt-4.1-mini"),
                input =
                    ResponseInput.Items(
                        listOf(
                            ResponseInputItem.Message(
                                role = ResponseRole.USER,
                                content = listOf(ResponseInputContent.InputText("Please use MCP.")),
                            ),
                            ResponseInputItem.McpApprovalResponse(
                                approvalRequestId = McpApprovalRequestId("apr_123"),
                                approve = true,
                                reason = "trusted",
                            ),
                        ),
                    ),
                tools =
                    listOf(
                        ResponseTool.Mcp(
                            serverLabel = "shopify",
                            serverUrl = "https://pitchskin.com/api/mcp",
                            headers = buildJsonObject { put("Authorization", "Bearer token") },
                            allowedTools = listOf("search_products"),
                            requireApproval = ResponseTool.McpRequireApproval.Never,
                        ),
                    ),
            ),
        )

        val body = assertNotNull(seenRequest).bodyText()
        assertTrue(body.contains("\"type\":\"mcp\""))
        assertTrue(body.contains("\"server_label\":\"shopify\""))
        assertTrue(body.contains("\"server_url\":\"https://pitchskin.com/api/mcp\""))
        assertTrue(body.contains("\"allowed_tools\":[\"search_products\"]"))
        assertTrue(body.contains("\"require_approval\":\"never\""))
        assertTrue(body.contains("\"type\":\"mcp_approval_response\""))
        assertTrue(body.contains("\"approval_request_id\":\"apr_123\""))
        assertTrue(body.contains("\"approve\":true"))
    }

    @Test
    fun `responses object decodes typed hosted tool payloads and outputs`() {
        val response =
            OpenAIJson.decodeFromString<ResponseObject>(
                """
                {
                  "id":"resp_tools_typed",
                  "object":"response",
                  "status":"completed",
                  "output":[
                    {
                      "id":"ws_1",
                      "type":"web_search_call",
                      "status":"completed",
                      "action":{
                        "type":"search",
                        "queries":["responses api"],
                        "sources":[{"type":"url","url":"https://platform.openai.com/docs","title":"Docs"}]
                      }
                    },
                    {
                      "id":"fs_1",
                      "type":"file_search_call",
                      "status":"completed",
                      "queries":["policy"],
                      "results":[{"file_id":"file_1","filename":"policy.md","score":0.9,"text":"policy text"}]
                    },
                    {
                      "id":"ci_1",
                      "type":"code_interpreter_call",
                      "status":"completed",
                      "code":"print(1)",
                      "container_id":"ctr_1",
                      "outputs":[
                        {"type":"logs","logs":"1"},
                        {"type":"image","url":"https://example.com/out.png"}
                      ]
                    },
                    {
                      "id":"cc_1",
                      "type":"computer_call",
                      "status":"in_progress",
                      "call_id":"call_cc_1",
                      "action":{"type":"click","x":10,"y":20,"button":"left"},
                      "pending_safety_checks":[{"id":"safe_1","code":"confirm","message":"Need approval"}]
                    },
                    {
                      "id":"cco_1",
                      "type":"computer_call_output",
                      "status":"completed",
                      "call_id":"call_cc_1",
                      "output":{"type":"computer_screenshot","image_url":"https://example.com/screen.png"},
                      "acknowledged_safety_checks":[{"id":"safe_1","code":"confirm","message":"ok"}]
                    },
                    {
                      "id":"ct_1",
                      "type":"custom_tool_call",
                      "call_id":"call_custom_1",
                      "name":"patch",
                      "input":"*** Begin Patch"
                    },
                    {
                      "id":"cto_1",
                      "type":"custom_tool_call_output",
                      "call_id":"call_custom_1",
                      "output":[{"type":"input_text","text":"patched"}]
                    },
                    {
                      "id":"sho_1",
                      "type":"shell_call_output",
                      "status":"completed",
                      "call_id":"call_shell_1",
                      "max_output_length":4000,
                      "created_by":"assistant",
                      "output":[{"outcome":{"type":"completed","exit_code":0},"stdout":"ok","stderr":""}]
                    }
                  ]
                }
                """.trimIndent(),
            )

        val webSearch = response.webSearchCalls().single()
        val fileSearch = response.fileSearchCalls().single()
        val codeInterpreter = response.codeInterpreterCalls().single()
        val computerCall = response.computerCalls().single()
        val computerOutput = response.computerCallOutputs().single()
        val customTool = response.customToolCalls().single()
        val customToolOutput = response.customToolCallOutputs().single()
        val shellOutput = response.shellCallOutputs().single()

        assertEquals("search", webSearch.action?.type)
        assertEquals("Docs", webSearch.action?.sources?.single()?.title)
        assertEquals("file_1", fileSearch.results.single().fileId)
        assertEquals("policy.md", fileSearch.results.single().filename)
        assertEquals("print(1)", codeInterpreter.code)
        assertEquals("1", codeInterpreter.outputs.first().logs)
        assertEquals("https://example.com/out.png", codeInterpreter.outputs.last().url)
        assertEquals("call_cc_1", computerCall.callId)
        assertEquals("confirm", computerCall.pendingSafetyChecks.single().code)
        assertEquals("https://example.com/screen.png", computerOutput.output?.imageUrl)
        assertEquals("patch", customTool.name)
        assertEquals("patched", customToolOutput.outputContent.single().getValue("text").jsonPrimitive.content)
        assertEquals(0, shellOutput.output.single().outcome?.exitCode)
        assertEquals("ok", shellOutput.output.single().stdout)
        assertEquals("file_search_call.results", ResponseInclude.FILE_SEARCH_CALL_RESULTS.wireName)
        assertEquals("code_interpreter_call.outputs", ResponseInclude.CODE_INTERPRETER_CALL_OUTPUTS.wireName)
    }

    @Test
    fun `responses object exposes shell local shell and apply patch helpers`() {
        val response =
            OpenAIJson.decodeFromString<ResponseObject>(
                """
                {
                  "id":"resp_tools_2",
                  "object":"response",
                  "status":"completed",
                  "output":[
                    {
                      "id":"sh_1",
                      "type":"shell_call",
                      "status":"in_progress",
                      "call_id":"call_shell_1",
                      "action":{"commands":["echo hi"],"max_output_length":1024,"timeout_ms":5000},
                      "environment":{"type":"local"}
                    },
                    {
                      "id":"lsh_1",
                      "type":"local_shell_call",
                      "status":"completed",
                      "call_id":"call_local_1",
                      "action":{"type":"exec","command":["pwd"],"working_directory":"/tmp"}
                    },
                    {
                      "id":"lsho_1",
                      "type":"local_shell_call_output",
                      "status":"completed",
                      "output":"{\"stdout\":\"ok\"}"
                    },
                    {
                      "id":"ap_1",
                      "type":"apply_patch_call",
                      "status":"completed",
                      "call_id":"call_patch_1",
                      "operation":{"type":"update_file","path":"src/App.kt","diff":"*** Begin Patch"}
                    },
                    {
                      "id":"apo_1",
                      "type":"apply_patch_call_output",
                      "status":"completed",
                      "call_id":"call_patch_1",
                      "output":"patched"
                    }
                  ]
                }
                """.trimIndent(),
            )

        val shellCall = response.shellCalls().single()
        val localShellCall = response.localShellCalls().single()
        val localShellOutput = response.localShellCallOutputs().single()
        val applyPatchCall = response.applyPatchCalls().single()
        val applyPatchOutput = response.applyPatchCallOutputs().single()

        assertEquals("call_shell_1", shellCall.callId)
        assertEquals("echo hi", shellCall.action?.get("commands")?.jsonArray?.single()?.jsonPrimitive?.content)
        assertEquals("call_local_1", localShellCall.callId)
        assertEquals("exec", localShellCall.action?.get("type")?.jsonPrimitive?.content)
        assertEquals("{\"stdout\":\"ok\"}", localShellOutput.output)
        assertEquals("src/App.kt", applyPatchCall.operation?.get("path")?.jsonPrimitive?.content)
        assertEquals("patched", applyPatchOutput.output)
        assertTrue(response.output.any { it.type == ResponseItemType.LocalShellCallOutput })
        assertTrue(response.output.any { it.type == ResponseItemType.ApplyPatchCall })
    }

    @Test
    fun `response stream parser handles hosted tool progress and image generation partials`() {
        val events =
            parseResponseStreamEvents(
                """
                data: {"type":"response.web_search_call.in_progress","item_id":"ws_1","output_index":0}

                data: {"type":"response.image_generation_call.partial_image","item_id":"img_1","output_index":1,"partial_image_index":0,"b64_json":"cGFydGlhbA=="}

                data: {"type":"response.file_search_call.completed","item_id":"fs_1","output_index":2,"item":{"id":"fs_1","type":"file_search_call","status":"completed"}}

                data: [DONE]
                """.trimIndent(),
            )

        assertTrue(events[0] is ResponseStreamEvent.HostedToolCallProgress)
        assertEquals("response.web_search_call.in_progress", (events[0] as ResponseStreamEvent.HostedToolCallProgress).type)
        assertEquals("cGFydGlhbA==", (events[1] as ResponseStreamEvent.ImageGenerationCallPartialImage).b64Json)
        assertTrue(events[2] is ResponseStreamEvent.HostedToolCallProgress)
        assertEquals(ResponseStreamEvent.Done, events[3])
    }

    @Test
    fun `response stream parser handles mcp arguments audio and annotations`() {
        val events =
            parseResponseStreamEvents(
                """
                data: {"type":"response.mcp_call_arguments.delta","item_id":"mcp_1","output_index":1,"delta":"{"}

                data: {"type":"response.mcp_call_arguments.done","item_id":"mcp_1","output_index":1,"arguments":"{\"query\":\"docs\"}"}

                data: {"type":"response.output_text.annotation.added","item_id":"msg_1","output_index":0,"content_index":0,"annotation_index":0,"annotation":{"type":"url_citation","title":"Docs"}}

                data: {"type":"response.audio.delta","item_id":"msg_2","output_index":2,"content_index":0,"delta":"UklG"}

                data: {"type":"response.audio.transcript.delta","item_id":"msg_2","output_index":2,"content_index":0,"delta":"Hel"}

                data: {"type":"response.audio.transcript.done","item_id":"msg_2","output_index":2,"content_index":0,"transcript":"Hello"}

                data: [DONE]
                """.trimIndent(),
            )

        val assembly = ResponseStreamAccumulator().let { accumulator ->
            events.forEach { accumulator.apply(it) }
            accumulator.snapshot()
        }

        assertTrue(events[0] is ResponseStreamEvent.McpCallArgumentsDelta)
        assertTrue(events[1] is ResponseStreamEvent.McpCallArgumentsDone)
        assertTrue(events[2] is ResponseStreamEvent.OutputTextAnnotationAdded)
        assertEquals("Docs", (events[2] as ResponseStreamEvent.OutputTextAnnotationAdded).annotation?.get("title")?.jsonPrimitive?.content)
        assertTrue(events[3] is ResponseStreamEvent.AudioDelta)
        assertTrue(events[4] is ResponseStreamEvent.AudioTranscriptDelta)
        assertTrue(events[5] is ResponseStreamEvent.AudioTranscriptDone)
        assertEquals("""{"query":"docs"}""", assembly.mcpCallArguments["mcp_1"])
        assertEquals("UklG", assembly.audioData["msg_2:0"])
        assertEquals("Hello", assembly.audioTranscript["msg_2:0"])
        assertTrue(assembly.isDone)
    }

    @Test
    fun `response stream parser preserves typed response failure reasons`() {
        val event =
            parseResponseStreamEvents(
                """data: {"type":"response.failed","response":{"id":"resp_fail","object":"response","status":"failed","error":{"message":"rate limited","code":"rate_limit_exceeded"},"incomplete_details":{"reason":"max_output_tokens"},"output":[]},"error":{"message":"rate limited","code":"rate_limit_exceeded"}}""".trimIndent(),
            ).single()

        val failed = event as ResponseStreamEvent.ResponseFailed
        assertEquals(ResponseErrorCode.RateLimitExceeded, failed.error?.code)
        assertEquals(ResponseIncompleteReason.MaxOutputTokens, failed.response?.incompleteReasonOrNull())
    }

    @Test
    fun `structured output helper decodes json output text`() = runTest {
        val client = httpClient {
            respondJson(
                """
                {
                  "id": "resp_json",
                  "object": "response",
                  "status": "completed",
                  "output": [
                    {
                      "id": "msg_1",
                      "type": "message",
                      "role": "assistant",
                      "content": [
                        {"type": "output_text", "text": "{\"ok\":true,\"count\":2}"}
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            )
        }

        @Serializable
        data class Payload(val ok: Boolean, val count: Int)

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val response = api.createResponse(ResponseCreateRequest(model = ModelId("gpt-4.1-mini"), input = ResponseInput.Text("json")))

        val decoded = response.decodeOutputJson<Payload>()
        assertTrue(decoded.ok)
        assertEquals(2, decoded.count)
    }

    @Test
    fun `openrouter request options are merged into request body`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            respondJson("""{"id":"resp_or","object":"response","status":"completed","output":[]}""")
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = OpenAIProvider.OpenRouter(referer = "https://example.com", title = "Example"),
                    apiKey = "or-key",
                    baseUrl = "https://openrouter.ai/api/v1",
                ),
            )

        api.createResponse(
            ResponseCreateRequest(
                model = ModelId("openai/gpt-4.1-mini"),
                input = ResponseInput.Items(
                    listOf(
                        ResponseInputItem.Message(
                            role = ResponseRole.USER,
                            content = listOf(ResponseInputContent.InputText("ping")),
                        ),
                        ResponseInputItem.FunctionCallOutput(callId = ToolCallId("call_1"), output = "ok"),
                    ),
                ),
                providerOptions =
                    OpenRouterRequestOptions(
                        models = listOf("openai/gpt-4.1-mini", "anthropic/claude-3.7-sonnet"),
                        route = OpenRouterRoute.FALLBACK,
                        transforms = listOf("middle-out"),
                        plugins = listOf(OpenRouterPlugin("web")),
                    ),
            ),
        )

        val body = assertNotNull(seenRequest).bodyText()
        assertTrue(body.contains("function_call_output"))
        assertTrue(body.contains("\"models\":[\"openai/gpt-4.1-mini\",\"anthropic/claude-3.7-sonnet\"]"))
        assertTrue(body.contains("\"route\":\"fallback\""))
        assertTrue(body.contains("\"plugins\":[{\"id\":\"web\"}]"))
        assertTrue(body.contains("\"transforms\":[\"middle-out\"]"))
    }

    @Test
    fun `openrouter advanced request options are merged into body`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            respondJson("""{"id":"resp_or2","object":"response","status":"completed","output":[]}""")
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = OpenAIProvider.OpenRouter(),
                    apiKey = "or-key",
                    baseUrl = OpenAIProvider.OpenRouter().defaultBaseUrl,
                ),
            )

        api.createResponse(
            ResponseCreateRequest(
                model = ModelId("openai/gpt-4.1-mini"),
                input = ResponseInput.Text("ping"),
                providerOptions =
                    OpenRouterRequestOptions(
                        modalities = listOf(OpenRouterModality.TEXT, OpenRouterModality.IMAGE),
                        providerRouting =
                            OpenRouterProviderRouting(
                                order = listOf("openai", "anthropic"),
                                allowFallbacks = true,
                                requireParameters = true,
                                dataCollection = OpenRouterDataCollection.DENY,
                                only = listOf("openai"),
                                quantizations = listOf("fp8"),
                                sort = OpenRouterProviderSort.PRICE,
                            ),
                        imageConfig =
                            buildJsonObject {
                                put("aspect_ratio", "1:1")
                            },
                        cacheControl = OpenRouterCacheControl(type = OpenRouterCacheControlType.EPHEMERAL, ttlSeconds = 300),
                    ),
            ),
        )

        val body = assertNotNull(seenRequest).bodyText()
        assertTrue(body.contains("\"modalities\":[\"text\",\"image\"]"))
        assertTrue(body.contains("\"provider\":{\"order\":[\"openai\",\"anthropic\"]"))
        assertTrue(body.contains("\"allow_fallbacks\":true"))
        assertTrue(body.contains("\"require_parameters\":true"))
        assertTrue(body.contains("\"data_collection\":\"deny\""))
        assertTrue(body.contains("\"image_config\":{\"aspect_ratio\":\"1:1\"}"))
        assertTrue(body.contains("\"cache_control\":{\"type\":\"ephemeral\",\"ttl\":300}"))
    }

    @Test
    fun `openrouter request options reject provider and provider routing together`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                OpenRouterRequestOptions(
                    provider =
                        buildJsonObject {
                            put("require_parameters", true)
                        },
                    providerRouting = OpenRouterProviderRouting(order = listOf("openai")),
                )
            }

        assertTrue(error.message.orEmpty().contains("provider"))
        assertTrue(error.message.orEmpty().contains("providerRouting"))
    }

    @Test
    fun `ollama request options are merged into request body`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            respondJson("""{"id":"resp_ollama","object":"response","status":"completed","output":[]}""")
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = OpenAIProvider.Ollama(),
                    baseUrl = "http://localhost:11434/v1",
                ),
            )

        api.createResponse(
            ResponseCreateRequest(
                model = ModelId("qwen3"),
                input = ResponseInput.Text("ping"),
                providerOptions =
                    OllamaRequestOptions(
                        keepAlive = "5m",
                        raw = true,
                        options =
                            buildJsonObject {
                                put("temperature", 0.1)
                            },
                    ),
            ),
        )

        val body = assertNotNull(seenRequest).bodyText()
        assertTrue(body.contains("\"keep_alive\":\"5m\""))
        assertTrue(body.contains("\"raw\":true"))
        assertTrue(body.contains("\"options\":{\"temperature\":0.1}"))
    }

    @Test
    fun `getResponse retrieves a response object`() = runTest {
        val client = httpClient { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/v1/responses/resp_123", request.url.encodedPath)
            respondJson("""{"id":"resp_123","object":"response","status":"completed","output":[]}""")
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val response = api.getResponse(ResponseId("resp_123"))
        assertEquals("resp_123", response.id)
    }

    @Test
    fun `cancelResponse posts cancel endpoint`() = runTest {
        val client = httpClient { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/responses/resp_123/cancel", request.url.encodedPath)
            assertEquals("{}", request.bodyText())
            respondJson("""{"id":"resp_123","object":"response","status":"cancelled","output":[]}""")
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val response = api.cancelResponse(ResponseId("resp_123"))
        assertEquals(ResponseStatus.Cancelled, response.status)
    }

    @Test
    fun `listResponses encodes pagination query`() = runTest {
        val client = httpClient { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/v1/responses", request.url.encodedPath)
            assertEquals("resp_1", request.url.parameters["after"])
            assertEquals("10", request.url.parameters["limit"])
            assertEquals("asc", request.url.parameters["order"])
            assertEquals("gpt-4.1-mini", request.url.parameters["model"])
            respondJson(
                """
                {
                  "object": "list",
                  "data": [
                    {"id":"resp_2","object":"response","status":"completed","output":[]}
                  ],
                  "first_id": "resp_2",
                  "last_id": "resp_2",
                  "has_more": false
                }
                """.trimIndent(),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val page =
            api.listResponses(
                ResponseListQuery(
                    model = ModelId("gpt-4.1-mini"),
                    limit = 10,
                    order = ResponseListOrder.ASC,
                    after = "resp_1",
                ),
            )

        assertEquals("resp_2", page.firstId)
        assertEquals(1, page.data.size)
    }

    @Test
    fun `listResponseInputItems encodes pagination query`() = runTest {
        val client = httpClient { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/v1/responses/resp_123/input_items", request.url.encodedPath)
            assertEquals("item_1", request.url.parameters["after"])
            assertEquals("5", request.url.parameters["limit"])
            assertEquals("asc", request.url.parameters["order"])
            respondJson(
                """
                {
                  "object": "list",
                  "data": [
                    {
                      "id": "item_2",
                      "object": "response.input_item",
                      "type": "message",
                      "role": "user",
                      "content": [{"type":"input_text","text":"hello"}]
                    }
                  ],
                  "first_id": "item_2",
                  "last_id": "item_2",
                  "has_more": false
                }
                """.trimIndent(),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val page =
            api.listResponseInputItems(
                ResponseId("resp_123"),
                ResponseInputItemListQuery(
                    limit = 5,
                    order = ResponseInputItemListOrder.ASC,
                    after = "item_1",
                ),
            )

        assertEquals("item_2", page.firstId)
        assertEquals(ResponseItemType.Message, page.data.single().type)
    }

    @Test
    fun `createChatCompletion decodes secondary compatibility layer`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            assertEquals("/v1/chat/completions", request.url.encodedPath)
            respondJson(
                """
                {
                  "id": "chatcmpl_1",
                  "object": "chat.completion",
                  "model": "deepseek-reasoner",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "{\"answer\":\"42\"}"
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {"prompt_tokens": 10, "completion_tokens": 4, "total_tokens": 14}
                }
                """.trimIndent(),
            )
        }

        @Serializable
        data class Answer(val answer: String)

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = OpenAIProvider.DeepSeek,
                    apiKey = "deepseek-key",
                    baseUrl = OpenAIProvider.DeepSeek.defaultBaseUrl,
                ),
            )

        val response =
            api.createChatCompletion(
                ChatCompletionRequest(
                    model = ModelId("deepseek-reasoner"),
                    messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"))),
                    responseFormat =
                        ChatResponseFormat.JsonSchema(
                            name = "answer",
                            schema =
                                buildJsonObject {
                                    put("type", "object")
                                },
                        ),
                ),
            )

        assertEquals("42", response.decodeOutputJson<Answer>().answer)
        val body = assertNotNull(seenRequest).bodyText()
        assertTrue(body.contains("\"response_format\""))
        assertTrue(body.contains("\"json_schema\""))
    }

    @Test
    fun `azure provider applies api key and api version and decodes content filters`() = runTest {
        var seenRequest: HttpRequestData? = null
        val provider = OpenAIProvider.Azure(resourceName = "demo", apiVersion = "preview")
        val client = httpClient { request ->
            seenRequest = request
            assertEquals("preview", request.url.parameters["api-version"])
            assertEquals("azure-key", request.headers["api-key"])
            respondJson(
                """
                {
                  "id": "chatcmpl_azure",
                  "object": "chat.completion",
                  "model": "gpt-4.1-mini",
                  "prompt_filter_results": [
                    {
                      "prompt_index": 0,
                      "content_filter_results": {
                        "hate": {"filtered": false, "severity": "safe"}
                      }
                    }
                  ],
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "ok"
                      },
                      "content_filter_results": {
                        "sexual": {"filtered": false, "severity": "safe"}
                      },
                      "finish_reason": "stop"
                    }
                  ]
                }
                """.trimIndent(),
            )
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = provider,
                    apiKey = "azure-key",
                    baseUrl = provider.defaultBaseUrl,
                ),
            )

        val response =
            api.createChatCompletion(
                ChatCompletionRequest(
                    model = ModelId("my-deployment"),
                    messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"))),
                ),
            )

        assertEquals("/openai/v1/chat/completions", assertNotNull(seenRequest).url.encodedPath)
        assertEquals("safe", response.azurePromptFilters().single().contentFilterResults?.hate?.severity)
        assertEquals("safe", response.choices.single().contentFilterResults?.sexual?.severity)
    }

    @Test
    fun `azure deployment style endpoints are used when deployments are configured`() = runTest {
        val seenPaths = mutableListOf<String>()
        val seenVersions = mutableListOf<String?>()
        val provider =
            OpenAIProvider.Azure(
                resourceName = "demo",
                apiVersion = "2026-01-01-preview",
                chatCompletionsDeployment = "chat-prod",
                embeddingsDeployment = "embed-prod",
            )
        val client = httpClient { request ->
            seenPaths += request.url.encodedPath
            seenVersions += request.url.parameters["api-version"]
            when (request.url.encodedPath) {
                "/openai/deployments/chat-prod/chat/completions" ->
                    respondJson(
                        """
                        {
                          "id": "chatcmpl_azure_dep",
                          "object": "chat.completion",
                          "model": "ignored-by-azure",
                          "choices": [
                            {
                              "index": 0,
                              "message": {"role": "assistant", "content": "ok"},
                              "finish_reason": "stop"
                            }
                          ]
                        }
                        """.trimIndent(),
                    )
                "/openai/deployments/embed-prod/embeddings" ->
                    respondJson(
                        """
                        {
                          "object":"list",
                          "data":[{"object":"embedding","index":0,"embedding":[0.1,0.2]}],
                          "model":"text-embedding-3-small",
                          "usage":{"prompt_tokens":3,"total_tokens":3}
                        }
                        """.trimIndent(),
                    )
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = provider,
                    apiKey = "azure-key",
                    baseUrl = provider.defaultBaseUrl,
                ),
            )

        val completion =
            api.createChatCompletion(
                ChatCompletionRequest(
                    model = ModelId("ignored-by-azure"),
                    messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"))),
                ),
            )
        val embedding =
            api.createEmbedding(
                EmbeddingCreateRequest(
                    model = ModelId("text-embedding-3-small"),
                    input = EmbeddingInput.TextBatch(listOf("hello")),
                ),
            )

        assertEquals(listOf("/openai/deployments/chat-prod/chat/completions", "/openai/deployments/embed-prod/embeddings"), seenPaths)
        assertEquals(listOf<String?>("2026-01-01-preview", "2026-01-01-preview"), seenVersions)
        assertEquals("ok", assertNotNull(completion.choices.single().message).content)
        assertEquals(1, embedding.data.size)
    }

    @Test
    fun `azure image generation deployment header is only added for responses requests that use image generation`() = runTest {
        val seen = mutableListOf<Pair<String, String?>>()
        val provider =
            OpenAIProvider.Azure(
                resourceName = "demo",
                apiVersion = "2026-01-01-preview",
                imageGenerationDeployment = "img-prod",
            )
        val client = httpClient { request ->
            seen += request.url.encodedPath to request.headers["x-ms-oai-image-generation-deployment"]
            when (request.url.encodedPath) {
                "/openai/v1/responses" ->
                    respondJson("""{"id":"resp_1","object":"response","status":"completed","output":[]}""")
                "/openai/v1/chat/completions" ->
                    respondJson(
                        """
                        {
                          "id":"chatcmpl_1",
                          "object":"chat.completion",
                          "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]
                        }
                        """.trimIndent(),
                    )
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = provider,
                    apiKey = "azure-key",
                    baseUrl = provider.defaultBaseUrl,
                ),
            )

        api.createResponse(
            ResponseCreateRequest(
                model = ModelId("gpt-4.1-mini"),
                input = ResponseInput.Text("make an image"),
                tools = listOf(ResponseTool.ImageGeneration()),
            ),
        )
        api.createChatCompletion(
            ChatCompletionRequest(
                model = ModelId("gpt-4.1-mini"),
                messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"))),
            ),
        )

        assertEquals(
            listOf(
                "/openai/v1/responses" to "img-prod",
                "/openai/v1/chat/completions" to null,
            ),
            seen,
        )
    }

    @Test
    fun `azure reasoning chat validation rejects unsupported controls when reasoning effort is set`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                ChatCompletionRequest(
                    model = ModelId("gpt-5-mini"),
                    messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"))),
                    reasoningEffort = ReasoningEffort.MEDIUM,
                    temperature = 0.2,
                ).requireCompatibleWith(OpenAIProvider.Azure(resourceName = "demo"))
            }

        assertTrue(error.message?.contains("Azure reasoning chat compatibility") == true)
    }

    @Test
    fun `azure responses reject streaming image generation tool`() = runTest {
        val api =
            KtorOpenAIApi(
                httpClient { error("request should not be made") },
                OpenAIApi.Config(
                    provider = OpenAIProvider.Azure(resourceName = "demo"),
                    apiKey = "azure-key",
                    baseUrl = OpenAIProvider.Azure(resourceName = "demo").defaultBaseUrl,
                ),
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                api.streamResponse(
                    ResponseCreateRequest(
                        model = ModelId("gpt-4.1-mini"),
                        input = ResponseInput.Text("make an image"),
                        tools = listOf(ResponseTool.ImageGeneration()),
                    ),
                ).collect { }
            }

        assertTrue(error.message?.contains("does not support streaming") == true)
    }

    @Test
    fun `response metadata callback exposes typed headers for normal and streaming requests`() = runTest {
        val seenMetadata = mutableListOf<OpenAIResponseMetadata>()
        val client = httpClient { request ->
            if (request.headers[HttpHeaders.Accept] == ContentType.Text.EventStream.toString()) {
                respond(
                    content =
                        """
                        data: {"type":"response.created","response":{"id":"resp_stream_meta","object":"response","status":"in_progress","output":[]}}

                        data: [DONE]
                        """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers =
                        headersOf(
                            HttpHeaders.ContentType to listOf(ContentType.Text.EventStream.toString()),
                            "x-request-id" to listOf("req_stream"),
                        ),
                )
            } else {
                respond(
                    content = """{"id":"resp_meta","object":"response","status":"completed","output":[]}""",
                    status = HttpStatusCode.OK,
                    headers =
                        headersOf(
                            HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                            "x-request-id" to listOf("req_normal"),
                            "openai-processing-ms" to listOf("321"),
                            HttpHeaders.RetryAfter to listOf("2.5"),
                            "x-ratelimit-limit-requests" to listOf("100"),
                            "x-ratelimit-remaining-requests" to listOf("99"),
                            "x-ratelimit-reset-requests" to listOf("12s"),
                            "x-ratelimit-limit-tokens" to listOf("1000"),
                            "x-ratelimit-remaining-tokens" to listOf("900"),
                            "x-ratelimit-reset-tokens" to listOf("45s"),
                        ),
                )
            }
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    apiKey = "secret",
                    onResponseMetadata = { seenMetadata += it },
                ),
            )

        api.createResponse(ResponseCreateRequest(model = ModelId("gpt-4.1-mini"), input = ResponseInput.Text("ping")))
        api.streamResponse(ResponseCreateRequest(model = ModelId("gpt-4.1-mini"), input = ResponseInput.Text("ping"))).collectResponseStream()

        assertEquals(2, seenMetadata.size)
        val normal = seenMetadata[0]
        val stream = seenMetadata[1]
        assertEquals("openai", normal.providerId)
        assertEquals("req_normal", normal.requestId)
        assertEquals(321L, normal.processingMillis)
        assertEquals(2.5, normal.retryAfterSeconds)
        assertEquals(100L, normal.rateLimits.requests?.limit)
        assertEquals(99L, normal.rateLimits.requests?.remaining)
        assertEquals("12s", normal.rateLimits.requests?.reset)
        assertEquals(1000L, normal.rateLimits.tokens?.limit)
        assertEquals(900L, normal.rateLimits.tokens?.remaining)
        assertEquals("45s", normal.rateLimits.tokens?.reset)
        assertEquals("req_stream", stream.requestId)
        assertTrue(stream.url.endsWith("/v1/responses"))
    }

    @Test
    fun `response metadata callback parses http date retry after headers`() = runTest {
        val seenMetadata = mutableListOf<OpenAIResponseMetadata>()
        val client = httpClient {
            respond(
                content = """{"id":"resp_meta_date","object":"response","status":"completed","output":[]}""",
                status = HttpStatusCode.OK,
                headers =
                    headersOf(
                        HttpHeaders.ContentType to listOf(ContentType.Application.Json.toString()),
                        HttpHeaders.RetryAfter to listOf("Wed, 21 Oct 2099 07:28:00 GMT"),
                    ),
            )
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    apiKey = "secret",
                    onResponseMetadata = { seenMetadata += it },
                ),
            )

        api.createResponse(ResponseCreateRequest(model = ModelId("gpt-4.1-mini"), input = ResponseInput.Text("ping")))

        assertEquals(1, seenMetadata.size)
        assertNotNull(seenMetadata.single().retryAfterSeconds)
        assertTrue(seenMetadata.single().retryAfterSeconds!! > 0.0)
    }

    @Test
    fun `chat completions parity fields encode and rich response fields decode`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            respondJson(
                """
                {
                  "id": "chatcmpl_parity",
                  "object": "chat.completion",
                  "model": "gpt-4.1-mini",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "Hello",
                        "annotations": [
                          {
                            "type": "url_citation",
                            "title": "OpenAI Docs",
                            "url": "https://platform.openai.com/docs"
                          }
                        ]
                      },
                      "logprobs": {
                        "content": [
                          {
                            "token": "Hello",
                            "logprob": -0.1,
                            "bytes": [72, 101, 108, 108, 111],
                            "top_logprobs": [
                              {"token":"Hello","logprob":-0.1},
                              {"token":"Hi","logprob":-1.2}
                            ]
                          }
                        ]
                      },
                      "finish_reason": "stop"
                    }
                  ],
                  "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 4,
                    "total_tokens": 14,
                    "prompt_tokens_details": {
                      "cached_tokens": 3,
                      "audio_tokens": 0
                    },
                    "completion_tokens_details": {
                      "reasoning_tokens": 2,
                      "accepted_prediction_tokens": 1,
                      "rejected_prediction_tokens": 0
                    }
                  }
                }
                """.trimIndent(),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val response =
            api.createChatCompletion(
                ChatCompletionRequest(
                    model = ModelId("gpt-4.1-mini"),
                    messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"))),
                    toolChoice = ResponseToolChoice.Required,
                    metadata = mapOf("trace_id" to "abc123"),
                    store = true,
                    reasoningEffort = ReasoningEffort.MEDIUM,
                    verbosity = ChatVerbosity.HIGH,
                    webSearchOptions = ChatWebSearchOptions(searchContextSize = SearchContextSize.HIGH),
                    logprobs = true,
                    topLogprobs = 2,
                ),
            )

        val body = assertNotNull(seenRequest).bodyText()
        assertTrue(body.contains("\"tool_choice\":\"required\""))
        assertTrue(body.contains("\"metadata\":{\"trace_id\":\"abc123\"}"))
        assertTrue(body.contains("\"store\":true"))
        assertTrue(body.contains("\"reasoning_effort\":\"medium\""))
        assertTrue(body.contains("\"verbosity\":\"high\""))
        assertTrue(body.contains("\"web_search_options\":{\"search_context_size\":\"high\"}"))
        assertTrue(body.contains("\"logprobs\":true"))
        assertTrue(body.contains("\"top_logprobs\":2"))

        assertEquals("Hello", response.outputText())
        assertEquals(1, response.annotations().size)
        assertEquals("url_citation", response.annotations().single().jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals(1, response.choices.single().logprobs?.content?.size)
        assertEquals(2, response.choices.single().logprobs?.content?.single()?.topLogprobs?.size)
        assertEquals(3, response.usage?.promptTokensDetails?.cachedTokens)
        assertEquals(2, response.usage?.completionTokensDetails?.reasoningTokens)
    }

    @Test
    fun `chat completion decodes official create example`() {
        val response =
            OpenAIJson.decodeFromString<ChatCompletionResponse>(
                DocumentedChatCompletionCreateExampleJson,
            )

        assertEquals("chatcmpl-B9MBs8CjcvOU2jLn4n570S5qMJKcT", response.id)
        assertEquals("chat.completion", response.objectType)
        assertEquals("gpt-5.4", response.model)
        assertEquals("Hello! How can I assist you today?", response.outputText())
        assertEquals(19, response.usage?.promptTokens)
        assertEquals(10, response.usage?.completionTokens)
    }

    @Test
    fun `chat completions audio output fields encode and audio responses decode`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            respondJson(
                """
                {
                  "id": "chatcmpl_audio",
                  "object": "chat.completion",
                  "model": "gpt-5.4-mini",
                  "choices": [
                    {
                      "index": 0,
                      "message": {
                        "role": "assistant",
                        "content": "Hello from audio",
                        "audio": {
                          "id": "audio_123",
                          "data": "QUJD",
                          "expires_at": 1712699999,
                          "transcript": "Hello from audio"
                        }
                      },
                      "finish_reason": "stop"
                    }
                  ]
                }
                """.trimIndent(),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val response =
            api.createChatCompletion(
                ChatCompletionRequest(
                    model = ModelId("gpt-5.4-mini"),
                    messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"))),
                    modalities = listOf(ChatCompletionModality.TEXT, ChatCompletionModality.AUDIO),
                    audio =
                        ChatCompletionAudioConfig(
                            format = ChatCompletionAudioFormat.MP3,
                            voice = SpeechVoice.Custom("voice_1234"),
                        ),
                ),
            )

        val body = assertNotNull(seenRequest).bodyText()
        assertTrue(body.contains("\"modalities\":[\"text\",\"audio\"]"))
        assertTrue(body.contains("\"audio\":{\"format\":\"mp3\",\"voice\":{\"id\":\"voice_1234\"}}"))

        val audio = response.audioOutputs().single()
        assertEquals("audio_123", audio.id)
        assertEquals("QUJD", audio.data)
        assertEquals(1712699999L, audio.expiresAt)
        assertEquals("Hello from audio", audio.transcript)
        assertEquals(listOf("Hello from audio"), response.audioTranscripts())
    }

    @Test
    fun `assistant chat message audio continuation serializes current shape`() {
        val message =
            ChatMessage(
                role = ChatRole.ASSISTANT,
                content = ChatMessageContent.Text("Continue"),
                audio = ChatMessageAudioReference("audio_prev"),
            )

        val json = message.toJson().toString()

        assertTrue(json.contains("\"role\":\"assistant\""))
        assertTrue(json.contains("\"audio\":{\"id\":\"audio_prev\"}"))
    }

    @Test
    fun `chat completion audio output config requires matching modality`() {
        val missingModality =
            assertFailsWith<IllegalArgumentException> {
                ChatCompletionRequest(
                    model = ModelId("gpt-5.4-mini"),
                    messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"))),
                    audio =
                        ChatCompletionAudioConfig(
                            format = ChatCompletionAudioFormat.WAV,
                            voice = SpeechVoice.BuiltIn("alloy"),
                        ),
                )
            }

        val missingConfig =
            assertFailsWith<IllegalArgumentException> {
                ChatCompletionRequest(
                    model = ModelId("gpt-5.4-mini"),
                    messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"))),
                    modalities = listOf(ChatCompletionModality.AUDIO),
                )
            }

        assertTrue(missingModality.message.orEmpty().contains("modalities"))
        assertTrue(missingConfig.message.orEmpty().contains("audio output config"))
    }

    @Test
    fun `chat completion function tools serialize nested function envelope`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            respondJson("""{"id":"chatcmpl_tools","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""")
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        api.createChatCompletion(
            ChatCompletionRequest(
                model = ModelId("gpt-4.1-mini"),
                messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"))),
                tools =
                    listOf(
                        ResponseTool.Function(
                            name = "get_weather",
                            description = "Look up the weather",
                            parameters = buildJsonObject { put("type", "object") },
                            strict = true,
                        ),
                    ),
                toolChoice = ResponseToolChoice.NamedFunction("get_weather"),
            ),
        )

        val payload = OpenAIJson.parseToJsonElement(assertNotNull(seenRequest).bodyText()).jsonObject
        val tool = payload["tools"]!!.jsonArray.single().jsonObject
        val toolChoice = payload["tool_choice"]!!.jsonObject

        assertEquals("function", tool["type"]?.jsonPrimitive?.content)
        assertEquals("get_weather", tool["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertEquals("Look up the weather", tool["function"]?.jsonObject?.get("description")?.jsonPrimitive?.content)
        assertEquals("object", tool["function"]?.jsonObject?.get("parameters")?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertEquals("true", tool["function"]?.jsonObject?.get("strict")?.jsonPrimitive?.toString())
        assertTrue(tool["name"] == null)
        assertTrue(tool["description"] == null)
        assertTrue(tool["parameters"] == null)
        assertTrue(tool["strict"] == null)

        assertEquals("function", toolChoice["type"]?.jsonPrimitive?.content)
        assertEquals("get_weather", toolChoice["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
    }

    @Test
    fun `chat completion custom tools serialize nested custom envelope and decode custom tool calls`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            respondJson(
                """
                {
                  "id":"chatcmpl_custom",
                  "object":"chat.completion",
                  "choices":[
                    {
                      "index":0,
                      "message":{
                        "role":"assistant",
                        "tool_calls":[
                          {
                            "id":"call_custom",
                            "type":"custom",
                            "custom":{"name":"run_macro","input":"open settings"}
                          }
                        ]
                      },
                      "finish_reason":"tool_calls"
                    }
                  ]
                }
                """.trimIndent(),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val response =
            api.createChatCompletion(
                ChatCompletionRequest(
                    model = ModelId("gpt-5.4-mini"),
                    messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"))),
                    tools =
                        listOf(
                            ResponseTool.Custom(
                                name = "run_macro",
                                description = "Execute a macro",
                            ),
                        ),
                    toolChoice = ResponseToolChoice.Custom("run_macro"),
                ),
            )

        val payload = OpenAIJson.parseToJsonElement(assertNotNull(seenRequest).bodyText()).jsonObject
        val tool = payload["tools"]!!.jsonArray.single().jsonObject
        val toolChoice = payload["tool_choice"]!!.jsonObject
        val call = response.toolCalls().single()

        assertEquals("custom", tool["type"]?.jsonPrimitive?.content)
        assertEquals("run_macro", tool["custom"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertEquals("Execute a macro", tool["custom"]?.jsonObject?.get("description")?.jsonPrimitive?.content)
        assertTrue(tool["name"] == null)
        assertTrue(tool["description"] == null)

        assertEquals("custom", toolChoice["type"]?.jsonPrimitive?.content)
        assertEquals("run_macro", toolChoice["custom"]?.jsonObject?.get("name")?.jsonPrimitive?.content)

        assertEquals(ChatCompletionToolCallType.Custom, call.type)
        assertEquals("run_macro", call.custom?.name)
        assertEquals("open settings", call.custom?.input)
    }

    @Test
    fun `chat completion allowed tools tool choice serializes current shape`() {
        val request =
            ChatCompletionRequest(
                model = ModelId("gpt-5.4-mini"),
                messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"))),
                toolChoice =
                    ResponseToolChoice.AllowedTools(
                        mode = ResponseToolChoice.AllowedTools.Mode.REQUIRED,
                        tools =
                            listOf(
                                ResponseTool.Function(name = "get_weather"),
                                ResponseTool.Custom(name = "run_macro"),
                            ),
                    ),
            )

        val payload = request.toJson()
        val toolChoice = payload["tool_choice"]!!.jsonObject
        val allowedTools = toolChoice["allowed_tools"]!!.jsonObject
        val tools = allowedTools["tools"]!!.jsonArray

        assertEquals("allowed_tools", toolChoice["type"]?.jsonPrimitive?.content)
        assertEquals("required", allowedTools["mode"]?.jsonPrimitive?.content)
        assertEquals("function", tools[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("get_weather", tools[0].jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertEquals("custom", tools[1].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("run_macro", tools[1].jsonObject["custom"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertTrue(payload["tools"] == null)
    }

    @Test
    fun `chat completion allowed tools reject conflicting top level tools and unsupported types`() {
        val conflicting =
            assertFailsWith<IllegalArgumentException> {
                ChatCompletionRequest(
                    model = ModelId("gpt-5.4-mini"),
                    messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"))),
                    tools = listOf(ResponseTool.Function(name = "duplicate")),
                    toolChoice =
                        ResponseToolChoice.AllowedTools(
                            mode = ResponseToolChoice.AllowedTools.Mode.AUTO,
                            tools = listOf(ResponseTool.Function(name = "get_weather")),
                        ),
                )
            }
        assertTrue(conflicting.message.orEmpty().contains("top-level tools"))

        val unsupported =
            assertFailsWith<IllegalArgumentException> {
                ChatCompletionRequest(
                    model = ModelId("gpt-5.4-mini"),
                    messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"))),
                    toolChoice =
                        ResponseToolChoice.AllowedTools(
                            mode = ResponseToolChoice.AllowedTools.Mode.AUTO,
                            tools = listOf(ResponseTool.LocalShell),
                        ),
                )
            }
        assertTrue(unsupported.message.orEmpty().contains("allowed_tools"))
    }

    @Test
    fun `chat function role serializes with required name`() {
        val message =
            ChatMessage(
                role = ChatRole.FUNCTION,
                name = "get_weather",
                content = ChatMessageContent.Text("""{"temp_c":21}"""),
            )

        val json = message.toJson().toString()

        assertTrue(json.contains("\"role\":\"function\""))
        assertTrue(json.contains("\"name\":\"get_weather\""))
    }

    @Test
    fun `chat completions reject responses only hosted tools`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                ChatCompletionRequest(
                    model = ModelId("gpt-4.1-mini"),
                    messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"))),
                    tools = listOf(ResponseTool.ImageGeneration()),
                    toolChoice = ResponseToolChoice.Hosted("image_generation"),
                )
            }

        assertTrue(error.message.orEmpty().contains("chat completions"))
    }

    @Test
    fun `chat completion top logprobs requires logprobs`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                ChatCompletionRequest(
                    model = ModelId("gpt-4.1-mini"),
                    messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"))),
                    topLogprobs = 1,
                )
            }

        assertTrue(error.message?.contains("requires logprobs=true") == true)
    }

    @Test
    fun `groq provider uses bearer auth and compatibility validation`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            assertEquals("Bearer groq-key", request.headers[HttpHeaders.Authorization])
            respondJson(
                """
                {
                  "id": "chatcmpl_groq",
                  "object": "chat.completion",
                  "model": "openai/gpt-oss-20b",
                  "choices": [
                    {
                      "index": 0,
                      "message": {"role": "assistant", "content": "ok"},
                      "finish_reason": "stop"
                    }
                  ]
                }
                """.trimIndent(),
            )
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = OpenAIProvider.Groq,
                    apiKey = "groq-key",
                    baseUrl = OpenAIProvider.Groq.defaultBaseUrl,
                ),
            )

        val response =
            api.createChatCompletion(
                ChatCompletionRequest(
                    model = ModelId("openai/gpt-oss-20b"),
                    messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("Reply with ok."))),
                ),
            )

        assertEquals("/openai/v1/chat/completions", assertNotNull(seenRequest).url.encodedPath)
        assertEquals("ok", response.outputText())

        val validationError =
            assertFailsWith<IllegalArgumentException> {
                ChatCompletionRequest(
                    model = ModelId("openai/gpt-oss-20b"),
                    messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"), name = "named-user")),
                    logprobs = true,
                ).requireCompatibleWith(OpenAIProvider.Groq)
            }

        assertTrue(validationError.message?.contains("Groq compatibility") == true)
    }

    @Test
    fun `groq responses reject store true because compatibility is stateless`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                ResponseCreateRequest(
                    model = ModelId("openai/gpt-oss-20b"),
                    input = ResponseInput.Text("ping"),
                    store = true,
                ).requireCompatibleWith(OpenAIProvider.Groq)
            }

        assertTrue(error.message?.contains("store=true") == true)
    }

    @Test
    fun `response request rejects blank metadata and user fields`() {
        assertFailsWith<IllegalArgumentException> {
            ResponseCreateRequest(
                model = ModelId("gpt-4.1-mini"),
                input = ResponseInput.Text("ping"),
                metadata = mapOf("" to "value"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ResponseCreateRequest(
                model = ModelId("gpt-4.1-mini"),
                input = ResponseInput.Text("ping"),
                user = "   ",
            )
        }
    }

    @Test
    fun `chat and vector store models reject blank optional identifiers`() {
        assertFailsWith<IllegalArgumentException> {
            ChatMessage(
                role = ChatRole.USER,
                content = ChatMessageContent.Text("ping"),
                name = "",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ChatMessagePart.File(fileId = FileId("file_1"), filename = "")
        }
        assertFailsWith<IllegalArgumentException> {
            VectorStoreListQuery(after = "")
        }
        assertFailsWith<IllegalArgumentException> {
            VectorStoreFileCreateRequest(
                fileId = FileId("file_1"),
                attributes = VectorStoreAttributes.of("" to "value"),
            )
        }
    }

    @Test
    fun `secondary request models reject blank metadata keys`() {
        assertFailsWith<IllegalArgumentException> {
            BatchCreateRequest(
                inputFileId = FileId("file_1"),
                endpoint = "/v1/responses",
                metadata = mapOf("" to "value"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            FineTuningJobCreateRequest(
                trainingFile = FileId("file_train"),
                model = ModelId("gpt-4.1-mini"),
                metadata = mapOf("" to "value"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            EvalCreateRequest(
                name = "eval-1",
                dataSourceConfig =
                    buildJsonObject {
                        put("type", "stored_completions")
                    },
                testingCriteria =
                    listOf(
                        buildJsonObject {
                            put("type", "label_model")
                        },
                    ),
                metadata = mapOf("" to "value"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            RealtimeSessionCreateRequest(
                model = ModelId("gpt-realtime"),
                metadata = mapOf("" to "value"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            XAIBatchCreateRequest(
                metadata = mapOf("" to "value"),
            )
        }
    }

    @Test
    fun `resource query models reject blank optional strings`() {
        assertFailsWith<IllegalArgumentException> {
            ResponseInputTokensRequest(
                model = ModelId("gpt-4.1-mini"),
                input = ResponseInput.Text("ping"),
                instructions = "",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StoredChatCompletionListQuery(after = "")
        }
        assertFailsWith<IllegalArgumentException> {
            ConversationItemListQuery(after = "")
        }
        assertFailsWith<IllegalArgumentException> {
            ResponseListQuery(after = "")
        }
        assertFailsWith<IllegalArgumentException> {
            ResponseInputItemListQuery(before = "")
        }
    }

    @Test
    fun `request models reject blank optional text fields`() {
        assertFailsWith<IllegalArgumentException> {
            BinaryUpload(
                filename = "audio.wav",
                bytes = byteArrayOf(1),
                contentType = "",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SpeechRequest(
                model = ModelId("gpt-4o-mini-tts"),
                input = "hello",
                voice = "alloy",
                instructions = "",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TranscriptionRequest(
                file = BinaryUpload("audio.wav", byteArrayOf(1), "audio/wav"),
                model = ModelId("gpt-4o-transcribe"),
                language = "",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TranslationRequest(
                file = BinaryUpload("audio.wav", byteArrayOf(1), "audio/wav"),
                model = ModelId("gpt-4o-mini-transcribe"),
                prompt = "",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ImageGenerateRequest(
                prompt = "cat",
                model = ModelId(""),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ImageVariationRequest(
                image = BinaryUpload("cat.png", byteArrayOf(1), "image/png"),
                model = ModelId(""),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            VideoCreateRequest(
                model = ModelId(""),
                prompt = "cat",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DeepSeekFimCompletionRequest(
                model = ModelId("deepseek-chat"),
                prompt = "hello",
                stop = "",
            )
        }
    }

    @Test
    fun `provider configs reject blank optional strings`() {
        assertFailsWith<IllegalArgumentException> {
            OpenAIProvider.Azure(resourceName = "")
        }
        assertFailsWith<IllegalArgumentException> {
            OpenAIProvider.Azure(apiVersion = "   ")
        }
        assertFailsWith<IllegalArgumentException> {
            OpenAIProvider.Azure(chatCompletionsDeployment = "")
        }
        assertFailsWith<IllegalArgumentException> {
            OpenAIProvider.Azure(embeddingsDeployment = "")
        }
        assertFailsWith<IllegalArgumentException> {
            OpenAIProvider.Azure(imageGenerationDeployment = "")
        }
        assertFailsWith<IllegalArgumentException> {
            OpenAIProvider.OpenRouter(referer = "")
        }
        assertFailsWith<IllegalArgumentException> {
            OpenAIProvider.OpenRouter(title = "   ")
        }
        assertFailsWith<IllegalArgumentException> {
            OpenAIProvider.Ollama(bearerToken = "")
        }
    }

    @Test
    fun `streamChatCompletion assembles content and tool args`() = runTest {
        val client = httpClient {
            respond(
                content =
                    """
                    data: {"id":"chatcmpl_1","object":"chat.completion.chunk","model":"gpt-4.1-mini","choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"},"finish_reason":null}]}
                    
                    data: {"id":"chatcmpl_1","object":"chat.completion.chunk","model":"gpt-4.1-mini","choices":[{"index":0,"delta":{"content":"lo","tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"weather","arguments":"{\"city\":\"Pa"}}]},"finish_reason":null}]}
                    
                    data: {"id":"chatcmpl_1","object":"chat.completion.chunk","model":"gpt-4.1-mini","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"arguments":"ris\"}"}}]},"finish_reason":"tool_calls"}]}
                    
                    data: [DONE]
                    """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val assembly =
            api.streamChatCompletion(
                ChatCompletionRequest(
                    model = ModelId("gpt-4.1-mini"),
                    messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"))),
                ),
            ).collectChatCompletionStream()

        assertEquals("Hello", assembly.outputText)
        assertEquals("""{"city":"Paris"}""", assembly.toolCallArguments["call_1"])
        assertEquals(ChatCompletionFinishReason.ToolCalls, assembly.finishReasons[0])
        assertTrue(assembly.isDone)
    }

    @Test
    fun `chat completion stream assembly orders text and reasoning by choice index instead of arrival order`() {
        val assembly =
            ChatCompletionStreamAccumulator().let { accumulator ->
                accumulator.apply(
                    ChatCompletionStreamEvent.Chunk(
                        ChatCompletionChunk(
                            id = "chatcmpl_order",
                            choices =
                                listOf(
                                    ChatCompletionChunkChoice(
                                        index = 1,
                                        delta = ChatCompletionChunkDelta(content = "world", reasoningContent = "second"),
                                    ),
                                ),
                        ),
                    ),
                )
                accumulator.apply(
                    ChatCompletionStreamEvent.Chunk(
                        ChatCompletionChunk(
                            id = "chatcmpl_order",
                            choices =
                                listOf(
                                    ChatCompletionChunkChoice(
                                        index = 0,
                                        delta = ChatCompletionChunkDelta(content = "Hello ", reasoningContent = "first"),
                                    ),
                                ),
                        ),
                    ),
                )
                accumulator.snapshot()
            }

        assertEquals(listOf(0, 1), assembly.choiceTexts.keys.toList())
        assertEquals(listOf("Hello ", "world"), assembly.choiceTexts.values.toList())
        assertEquals(listOf("first", "second"), assembly.choiceReasoningContents.values.toList())
        val textError = assertFailsWith<IllegalStateException> { assembly.outputText }
        assertTrue(textError.message.orEmpty().contains("choiceTexts"))
        val reasoningError = assertFailsWith<IllegalStateException> { assembly.reasoningContent }
        assertTrue(reasoningError.message.orEmpty().contains("choiceReasoningContents"))
    }

    @Test
    fun `chat completion stream assembly accumulates deprecated function_call arguments`() {
        val assembly =
            ChatCompletionStreamAccumulator().let { accumulator ->
                accumulator.apply(
                    ChatCompletionStreamEvent.Chunk(
                        OpenAIJson.decodeFromString(
                            """
                            {
                              "id":"chatcmpl_fc",
                              "object":"chat.completion.chunk",
                              "created":1741569952,
                              "model":"gpt-5.4",
                              "choices":[
                                {
                                  "index":0,
                                  "delta":{
                                    "role":"assistant",
                                    "function_call":{"name":"lookup_weather","arguments":"{"}
                                  },
                                  "finish_reason":null
                                }
                              ]
                            }
                            """.trimIndent(),
                        ),
                    ),
                )
                accumulator.apply(
                    ChatCompletionStreamEvent.Chunk(
                        OpenAIJson.decodeFromString(
                            """
                            {
                              "id":"chatcmpl_fc",
                              "object":"chat.completion.chunk",
                              "created":1741569952,
                              "model":"gpt-5.4",
                              "choices":[
                                {
                                  "index":0,
                                  "delta":{
                                    "function_call":{"arguments":"\"city\":\"Paris\"}"}
                                  },
                                  "finish_reason":"function_call"
                                }
                              ]
                            }
                            """.trimIndent(),
                        ),
                    ),
                )
                accumulator.snapshot()
            }

        assertEquals("""{"city":"Paris"}""", assembly.toolCallArguments["choice:0:function_call"])
        assertEquals(ChatCompletionFinishReason.FunctionCall, assembly.finishReasons[0])
    }

    @Test
    fun `gemini request options are nested under extra_body google`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            respondJson("""{"id":"chatcmpl_g","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""")
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = OpenAIProvider.Gemini,
                    apiKey = "gemini-key",
                    baseUrl = OpenAIProvider.Gemini.defaultBaseUrl,
                ),
            )

        api.createChatCompletion(
            ChatCompletionRequest(
                model = ModelId("gemini-2.5-pro"),
                messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"))),
                providerOptions =
                    GeminiRequestOptions(
                        cachedContent = "cached/foo",
                        thinkingConfig = buildJsonObject { put("budget_tokens", 256) },
                    ),
            ),
        )

        val body = assertNotNull(seenRequest).bodyText()
        assertTrue(body.contains("\"extra_body\":"))
        assertTrue(body.contains("\"google\":"))
        assertTrue(body.contains("\"cached_content\":\"cached/foo\""))
        assertTrue(body.contains("\"thinking_config\":{\"budget_tokens\":256}"))
    }

    @Test
    fun `xai request options are merged into response body`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            respondJson("""{"id":"resp_xai","object":"response","status":"completed","output":[]}""")
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = OpenAIProvider.XAI,
                    apiKey = "xai-key",
                    baseUrl = OpenAIProvider.XAI.defaultBaseUrl,
                ),
            )

        api.createResponse(
            ResponseCreateRequest(
                model = ModelId("grok-4"),
                input = ResponseInput.Text("ping"),
                providerOptions =
                    XAIRequestOptions(
                        reasoningEffort = ReasoningEffort.HIGH,
                        searchParameters =
                            buildJsonObject {
                                put("mode", "on")
                            },
                        webSearch =
                            buildJsonObject {
                                put("mode", "auto")
                            },
                        imageOptions =
                            buildJsonObject {
                                put("style", "photoreal")
                            },
                    ),
            ),
        )

        val body = assertNotNull(seenRequest).bodyText()
        assertTrue(body.contains("\"reasoning_effort\":\"high\""))
        assertTrue(body.contains("\"search_parameters\":{\"mode\":\"on\"}"))
        assertTrue(body.contains("\"web_search\":{\"mode\":\"auto\"}"))
        assertTrue(body.contains("\"image_options\":{\"style\":\"photoreal\"}"))
    }

    @Test
    fun `xai responses reject instructions in compatibility mode`() {
        val request =
            ResponseCreateRequest(
                model = ModelId("grok-4"),
                input = ResponseInput.Text("ping"),
                instructions = "system",
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                request.requireCompatibleWith(OpenAIProvider.XAI)
            }

        assertTrue(error.message?.contains("does not support responses instructions") == true)
    }

    @Test
    fun `createEmbedding posts to embeddings endpoint and decodes float vectors`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/embeddings", request.url.encodedPath)
            respondJson(
                """
                {
                  "object": "list",
                  "data": [
                    {"object":"embedding","index":0,"embedding":[0.1,0.2,0.3]}
                  ],
                  "model": "text-embedding-3-small",
                  "usage": {"prompt_tokens": 4, "total_tokens": 4}
                }
                """.trimIndent(),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val response =
            api.createEmbedding(
                EmbeddingCreateRequest(
                    model = ModelId("text-embedding-3-small"),
                    input = EmbeddingInput.Text("hello"),
                    dimensions = 256,
                ),
            )

        assertEquals(listOf(0.1, 0.2, 0.3), response.firstFloatEmbeddingOrNull())
        assertEquals(4, response.usage?.totalTokens)
        val body = assertNotNull(seenRequest).bodyText()
        assertTrue(body.contains("\"model\":\"text-embedding-3-small\""))
        assertTrue(body.contains("\"input\":\"hello\""))
        assertTrue(body.contains("\"dimensions\":256"))
    }

    @Test
    fun `openrouter embedding request options are merged into request body`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            respondJson(
                """
                {
                  "object": "list",
                  "data": [{"object":"embedding","index":0,"embedding":"Zm9v"}],
                  "model": "openai/text-embedding-3-small"
                }
                """.trimIndent(),
            )
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = OpenAIProvider.OpenRouter(),
                    apiKey = "or-key",
                    baseUrl = OpenAIProvider.OpenRouter().defaultBaseUrl,
                ),
            )

        val response =
            api.createEmbedding(
                EmbeddingCreateRequest(
                    model = ModelId("openai/text-embedding-3-small"),
                    input = EmbeddingInput.TextBatch(listOf("one", "two")),
                    encodingFormat = EmbeddingEncodingFormat.BASE64,
                    providerOptions = OpenRouterRequestOptions(models = listOf("openai/text-embedding-3-small")),
                ),
            )

        assertEquals("Zm9v", response.firstBase64EmbeddingOrNull())
        val body = assertNotNull(seenRequest).bodyText()
        assertTrue(body.contains("\"encoding_format\":\"base64\""))
        assertTrue(body.contains("\"models\":[\"openai/text-embedding-3-small\"]"))
    }

    @Test
    fun `listModels decodes provider model page`() = runTest {
        val client = httpClient { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/v1/models", request.url.encodedPath)
            respondJson(
                """
                {
                  "object": "list",
                  "data": [
                    {"id":"gpt-4.1-mini","object":"model","created":1741387200,"owned_by":"openai"}
                  ]
                }
                """.trimIndent(),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val page = api.listModels()

        assertEquals("gpt-4.1-mini", page.data.single().id)
        assertEquals("openai", page.data.single().ownedBy)
    }

    @Test
    fun `getModel retrieves model object`() = runTest {
        val client = httpClient { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/v1/models/gpt-4.1-mini", request.url.encodedPath)
            respondJson("""{"id":"gpt-4.1-mini","object":"model","owned_by":"openai"}""")
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val model = api.getModel(ModelId("gpt-4.1-mini"))

        assertEquals("gpt-4.1-mini", model.id)
        assertEquals("openai", model.ownedBy)
    }

    @Test
    fun `deleteModel deletes fine tuned model`() = runTest {
        val client = httpClient { request ->
            assertEquals(HttpMethod.Delete, request.method)
            assertEquals("/v1/models/ft:gpt-4o-mini:acemeco:suffix:abc123", request.url.encodedPath)
            respondJson("""{"id":"ft:gpt-4o-mini:acemeco:suffix:abc123","object":"model","deleted":true}""")
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val deleted = api.deleteModel(ModelId("ft:gpt-4o-mini:acemeco:suffix:abc123"))

        assertEquals("ft:gpt-4o-mini:acemeco:suffix:abc123", deleted.id)
        assertTrue(deleted.deleted)
    }

    @Test
    fun `xai provider supports model listing retrieval file upload and image generation`() = runTest {
        val seenPaths = mutableListOf<String>()
        val client = httpClient { request ->
            seenPaths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/v1/models" ->
                    respondJson("""{"object":"list","data":[{"id":"grok-4","object":"model","owned_by":"xai"}]}""")
                "/v1/models/grok-4" ->
                    respondJson("""{"id":"grok-4","object":"model","owned_by":"xai"}""")
                "/v1/files" ->
                    respondJson("""{"id":"file_x","object":"file","filename":"tiny.txt","purpose":"assistants"}""")
                "/v1/images/generations" ->
                    respondJson("""{"created":1711475054,"data":[{"url":"https://example.com/xai.png"}]}""")
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = OpenAIProvider.XAI,
                    apiKey = "xai-key",
                    baseUrl = OpenAIProvider.XAI.defaultBaseUrl,
                ),
            )

        val models = api.listModels()
        val model = api.getModel(ModelId("grok-4"))
        val file =
            api.uploadFile(
                FileCreateRequest(
                    purpose = FilePurpose.ASSISTANTS,
                    file = BinaryUpload("tiny.txt", "hello".encodeToByteArray(), "text/plain"),
                ),
            )
        val image = api.generateImage(ImageGenerateRequest(prompt = "cat", model = ModelId("grok-2-image")))

        assertEquals("grok-4", models.data.single().id)
        assertEquals("grok-4", model.id)
        assertEquals("file_x", file.id)
        assertEquals(FilePurposeValue.Assistants, file.purpose)
        assertEquals("https://example.com/xai.png", image.urls().single())
        assertEquals(listOf("/v1/models", "/v1/models/grok-4", "/v1/files", "/v1/images/generations"), seenPaths)
    }

    @Test
    fun `ollama provider supports embeddings through compatibility layer`() = runTest {
        val client = httpClient { request ->
            assertEquals("/v1/embeddings", request.url.encodedPath)
            respondJson(
                """
                {
                  "object":"list",
                  "data":[{"object":"embedding","index":0,"embedding":[1.0,2.0]}],
                  "model":"nomic-embed-text"
                }
                """.trimIndent(),
            )
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = OpenAIProvider.Ollama(),
                    baseUrl = OpenAIProvider.Ollama().defaultBaseUrl,
                ),
            )

        val response =
            api.createEmbedding(
                EmbeddingCreateRequest(
                    model = ModelId("nomic-embed-text"),
                    input = EmbeddingInput.Text("hello"),
                    providerOptions =
                        OllamaRequestOptions(
                            options =
                                buildJsonObject {
                                    put("num_ctx", 2048)
                                },
                        ),
                ),
            )

        assertEquals(listOf(1.0, 2.0), response.firstFloatEmbeddingOrNull())
    }

    @Test
    fun `generateImage posts image generation request and decodes base64 results`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/images/generations", request.url.encodedPath)
            respondJson(
                """
                {
                  "created": 1711475054,
                  "background": "transparent",
                  "data": [
                    {"b64_json":"aW1hZ2UtZGF0YQ==","revised_prompt":"A tiny red cube"}
                  ],
                  "output_format": "png",
                  "quality": "high",
                  "size": "1024x1024"
                }
                """.trimIndent(),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val response =
            api.generateImage(
                ImageGenerateRequest(
                    prompt = "A tiny red cube",
                    model = ModelId("gpt-image-1"),
                    background = ImageBackground.TRANSPARENT,
                    outputFormat = ImageOutputFormat.PNG,
                    size = ImageSize.X1024,
                ),
            )

        assertEquals("aW1hZ2UtZGF0YQ==", response.base64Images().single())
        assertEquals(ImageBackgroundValue.Transparent, response.background)
        assertEquals(ImageOutputFormatValue.Png, response.outputFormat)
        assertEquals(ImageQualityValue.High, response.quality)
        assertEquals(ImageSizeValue.X1024, response.size)
        val body = assertNotNull(seenRequest).bodyText()
        assertTrue(body.contains("\"prompt\":\"A tiny red cube\""))
        assertTrue(body.contains("\"background\":\"transparent\""))
        assertTrue(body.contains("\"output_format\":\"png\""))
    }

    @Test
    fun `editImage posts JSON image edit request`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/images/edits", request.url.encodedPath)
            respondJson(
                """
                {
                  "created": 1711475054,
                  "data": [
                    {"b64_json":"ZWRpdGVkLWltYWdl"}
                  ],
                  "size": "1024x1024"
                }
                """.trimIndent(),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val response =
            api.editImage(
                ImageEditRequest(
                    images = listOf(ImageReference.File(FileId("file_123"))),
                    prompt = "Turn it blue",
                    mask = ImageReference.ImageUrl("https://example.com/mask.png"),
                    model = ModelId("gpt-image-1"),
                    quality = ImageQuality.HIGH,
                ),
            )

        assertEquals("ZWRpdGVkLWltYWdl", response.base64Images().single())
        val body = assertNotNull(seenRequest).bodyText()
        assertTrue(body.contains("\"images\":[{\"file_id\":\"file_123\"}]"))
        assertTrue(body.contains("\"mask\":{\"image_url\":\"https://example.com/mask.png\"}"))
        assertTrue(body.contains("\"quality\":\"high\""))
    }

    @Test
    fun `createImageVariation sends multipart variation request`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/images/variations", request.url.encodedPath)
            respondJson(
                """
                {
                  "created": 1711475054,
                  "data": [
                    {"url":"https://example.com/variation.png"}
                  ]
                }
                """.trimIndent(),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val response =
            api.createImageVariation(
                ImageVariationRequest(
                    image = BinaryUpload("seed.png", "PNG".encodeToByteArray(), "image/png"),
                    model = ModelId("dall-e-2"),
                    n = 2,
                    responseFormat = ImageResponseFormat.URL,
                    size = ImageSize.X1024,
                ),
            )

        assertEquals("https://example.com/variation.png", response.urls().single())
        val request = assertNotNull(seenRequest)
        assertTrue((request.body.contentType?.toString() ?: "").startsWith("multipart/form-data"))
        val body = request.bodyText()
        assertTrue(body.contains("seed.png"))
        assertTrue(body.contains("dall-e-2"))
        assertTrue(body.contains("response_format"))
    }

    @Test
    fun `streamGeneratedImage yields partial images and final image`() = runTest {
        val client = httpClient {
            respond(
                content =
                    """
                    data: {"type":"image_generation.partial_image","partial_image_index":0,"b64_json":"cGFydGlhbC0w"}

                    data: {"type":"image_generation.partial_image","partial_image_index":1,"b64_json":"cGFydGlhbC0x"}

                    data: {"type":"image_generation.completed","b64_json":"ZmluYWwtaW1hZ2U=","usage":{"total_tokens":123}}

                    data: [DONE]
                    """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val assembly =
            api
                .streamGeneratedImage(
                    ImageGenerateRequest(
                        prompt = "river",
                        model = ModelId("gpt-image-1"),
                        partialImages = 2,
                    ),
                ).collectImageStream()

        assertEquals("cGFydGlhbC0w", assembly.partialImages[0])
        assertEquals("cGFydGlhbC0x", assembly.partialImages[1])
        assertEquals("ZmluYWwtaW1hZ2U=", assembly.finalImageBase64)
        assertEquals(123, assembly.usage?.totalTokens)
        assertTrue(assembly.isDone)
    }

    @Test
    fun `streamEditedImage yields parsed image edit stream events`() = runTest {
        val client = httpClient {
            respond(
                content =
                    """
                    data: {"type":"image_edit.partial_image","partial_image_index":0,"b64_json":"ZWRpdC1wYXJ0aWFs"}

                    data: {"type":"image_edit.completed","b64_json":"ZWRpdC1maW5hbA=="}

                    data: [DONE]
                    """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val events =
            api
                .streamEditedImage(
                    ImageEditRequest(
                        images = listOf(ImageReference.File(FileId("file_123"))),
                        prompt = "edit",
                    ),
                ).collectImageStream()

        assertEquals("ZWRpdC1wYXJ0aWFs", events.partialImages[0])
        assertEquals("ZWRpdC1maW5hbA==", events.finalImageBase64)
        assertTrue(events.isDone)
    }

    @Test
    fun `createModeration supports multimodal moderation input`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/moderations", request.url.encodedPath)
            respondJson(
                """
                {
                  "id":"modr_123",
                  "model":"omni-moderation-latest",
                  "results":[
                    {
                      "flagged": false,
                      "categories": {"violence": false},
                      "category_scores": {"violence": 0.01}
                    }
                  ]
                }
                """.trimIndent(),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val response =
            api.createModeration(
                ModerationCreateRequest(
                    model = ModelId("omni-moderation-latest"),
                    input =
                        ModerationInput.Items(
                            listOf(
                                ModerationInputItem.Text("hello"),
                                ModerationInputItem.ImageUrl("https://example.com/image.png"),
                            ),
                        ),
                ),
            )

        assertEquals("modr_123", response.id)
        assertTrue(!response.anyFlagged())
        val body = assertNotNull(seenRequest).bodyText()
        assertTrue(body.contains("\"type\":\"text\""))
        assertTrue(body.contains("\"type\":\"image_url\""))
    }

    @Test
    fun `batch lifecycle endpoints encode payload and pagination`() = runTest {
        val seenPaths = mutableListOf<String>()
        val seenBodies = mutableListOf<String>()
        val client = httpClient { request ->
            seenPaths += request.url.encodedPath
            seenBodies += request.bodyText()
            when (request.url.encodedPath) {
                "/v1/batches" ->
                    if (request.method == HttpMethod.Post) {
                        respondJson(
                            """
                            {
                              "id":"batch_1",
                              "object":"batch",
                              "endpoint":"/v1/responses",
                              "input_file_id":"file_input",
                              "completion_window":"24h",
                              "status":"validating"
                            }
                            """.trimIndent(),
                        )
                    } else {
                        assertEquals("10", request.url.parameters["limit"])
                        assertEquals("batch_0", request.url.parameters["after"])
                        respondJson(
                            """
                            {
                              "object":"list",
                              "data":[{"id":"batch_1","object":"batch","status":"completed"}],
                              "first_id":"batch_1",
                              "last_id":"batch_1",
                              "has_more":false
                            }
                            """.trimIndent(),
                        )
                    }
                "/v1/batches/batch_1" ->
                    respondJson("""{"id":"batch_1","object":"batch","status":"completed","output_file_id":"file_out"}""")
                "/v1/batches/batch_1/cancel" ->
                    respondJson("""{"id":"batch_1","object":"batch","status":"cancelling"}""")
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val created =
            api.createBatch(
                BatchCreateRequest(
                    inputFileId = FileId("file_input"),
                    endpoint = "/v1/responses",
                    metadata = mapOf("job" to "nightly"),
                ),
            )
        val fetched = api.getBatch(BatchId("batch_1"))
        val page = api.listBatches(BatchListQuery(limit = 10, after = "batch_0"))
        val cancelled = api.cancelBatch(BatchId("batch_1"))

        assertEquals("batch_1", created.id)
        assertEquals("file_out", fetched.outputFileId)
        assertEquals("batch_1", page.data.single().id)
        assertEquals(BatchStatus.Validating, created.status)
        assertEquals(BatchCompletionWindowValue.Hours24, created.completionWindow)
        assertEquals(BatchStatus.Completed, fetched.status)
        assertEquals(BatchStatus.Completed, page.data.single().status)
        assertEquals(BatchStatus.Cancelling, cancelled.status)
        assertEquals(
            listOf(
                "/v1/batches",
                "/v1/batches/batch_1",
                "/v1/batches",
                "/v1/batches/batch_1/cancel",
            ),
            seenPaths,
        )
        assertTrue(seenBodies.first().contains("\"input_file_id\":\"file_input\""))
        assertTrue(seenBodies.first().contains("\"endpoint\":\"/v1/responses\""))
        assertTrue(seenBodies.first().contains("\"job\":\"nightly\""))
    }

    @Test
    fun `parseBatchOutputLines decodes jsonl output objects`() {
        val lines =
            parseBatchOutputLines(
                """
                {"id":"batch_req_1","custom_id":"request-1","response":{"status_code":200,"request_id":"req_1","body":{"id":"resp_1","object":"response"}},"error":null}
                {"id":"batch_req_2","custom_id":"request-2","response":null,"error":{"code":"batch_cancelled","message":"cancelled"}}
                """.trimIndent(),
            )

        assertEquals(2, lines.size)
        assertEquals("request-1", lines[0].customId)
        assertEquals(200, lines[0].response?.statusCode)
        assertEquals("batch_cancelled", lines[1].error?.code)
    }

    @Test
    fun `fine tuning job lifecycle endpoints encode request bodies and queries`() = runTest {
        val seenPaths = mutableListOf<String>()
        val seenBodies = mutableListOf<String>()
        val client = httpClient { request ->
            seenPaths += request.url.encodedPath + if (request.url.parameters.isEmpty()) "" else "?${request.url.parameters.formUrlEncode()}"
            seenBodies += request.bodyText()
            when (request.url.encodedPath) {
                "/v1/fine_tuning/jobs" ->
                    if (request.method == HttpMethod.Post) {
                        respondJson(
                            """
                            {
                              "id":"ftjob_1",
                              "object":"fine_tuning.job",
                              "model":"gpt-4o-mini",
                              "training_file":"file_train",
                              "status":"queued",
                              "result_files":[]
                            }
                            """.trimIndent(),
                        )
                    } else {
                        respondJson(
                            """
                            {
                              "object":"list",
                              "data":[{"id":"ftjob_1","object":"fine_tuning.job","status":"running"}],
                              "first_id":"ftjob_1",
                              "last_id":"ftjob_1",
                              "has_more":false
                            }
                            """.trimIndent(),
                        )
                    }
                "/v1/fine_tuning/jobs/ftjob_1" ->
                    respondJson("""{"id":"ftjob_1","object":"fine_tuning.job","status":"running","training_file":"file_train"}""")
                "/v1/fine_tuning/jobs/ftjob_1/cancel" ->
                    respondJson("""{"id":"ftjob_1","object":"fine_tuning.job","status":"cancelled"}""")
                "/v1/fine_tuning/jobs/ftjob_1/pause" ->
                    respondJson("""{"id":"ftjob_1","object":"fine_tuning.job","status":"paused"}""")
                "/v1/fine_tuning/jobs/ftjob_1/resume" ->
                    respondJson("""{"id":"ftjob_1","object":"fine_tuning.job","status":"running"}""")
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val created =
            api.createFineTuningJob(
                FineTuningJobCreateRequest(
                    trainingFile = FileId("file_train"),
                    model = ModelId("gpt-4o-mini"),
                    validationFile = FileId("file_valid"),
                    suffix = "support-bot",
                    seed = 7,
                    metadata = mapOf("team" to "support"),
                    integrations = listOf(FineTuningIntegration.Wandb(project = "ft-project", tags = listOf("nightly"))),
                    method =
                        FineTuningMethod.Supervised(
                            FineTuningSupervisedHyperparameters(
                                epochs = FineTuningNumberOrAuto.Auto,
                            ),
                        ),
                ),
            )
        val fetched = api.getFineTuningJob(FineTuningJobId("ftjob_1"))
        val page =
            api.listFineTuningJobs(
                FineTuningJobListQuery(
                    after = "ftjob_0",
                    limit = 5,
                    metadata = FineTuningMetadataFilter.Values(mapOf("team" to "support")),
                ),
            )
        val cancelled = api.cancelFineTuningJob(FineTuningJobId("ftjob_1"))
        val paused = api.pauseFineTuningJob(FineTuningJobId("ftjob_1"))
        val resumed = api.resumeFineTuningJob(FineTuningJobId("ftjob_1"))

        assertEquals("ftjob_1", created.id)
        assertEquals("file_train", fetched.trainingFile)
        assertEquals("ftjob_1", page.data.single().id)
        assertEquals(FineTuningJobStatus.Queued, created.status)
        assertEquals(FineTuningJobStatus.Running, fetched.status)
        assertEquals(FineTuningJobStatus.Running, page.data.single().status)
        assertEquals(FineTuningJobStatus.Cancelled, cancelled.status)
        assertEquals(FineTuningJobStatus.Paused, paused.status)
        assertEquals(FineTuningJobStatus.Running, resumed.status)
        assertTrue(seenBodies.first().contains("\"training_file\":\"file_train\""))
        assertTrue(seenBodies.first().contains("\"validation_file\":\"file_valid\""))
        assertTrue(seenBodies.first().contains("\"suffix\":\"support-bot\""))
        assertTrue(seenBodies.first().contains("\"seed\":7"))
        assertTrue(seenBodies.first().contains("\"team\":\"support\""))
        assertTrue(seenBodies.first().contains("\"type\":\"wandb\""))
        assertTrue(seenBodies.first().contains("\"type\":\"supervised\""))
        assertTrue(seenPaths.any { it.contains("/v1/fine_tuning/jobs?") && it.contains("after=ftjob_0") && it.contains("limit=5") && it.contains("metadata%5Bteam%5D=support") })
    }

    @Test
    fun `fine tuning events and checkpoints endpoints support pagination`() = runTest {
        val seenPaths = mutableListOf<String>()
        val client = httpClient { request ->
            seenPaths += request.url.encodedPath + if (request.url.parameters.isEmpty()) "" else "?${request.url.parameters.formUrlEncode()}"
            when (request.url.encodedPath) {
                "/v1/fine_tuning/jobs/ftjob_1/events" ->
                    respondJson(
                        """
                        {
                          "object":"list",
                          "data":[{"id":"ftevent_1","object":"fine_tuning.job.event","level":"info","message":"started"}],
                          "first_id":"ftevent_1",
                          "last_id":"ftevent_1",
                          "has_more":false
                        }
                        """.trimIndent(),
                    )
                "/v1/fine_tuning/jobs/ftjob_1/checkpoints" ->
                    respondJson(
                        """
                        {
                          "object":"list",
                          "data":[{"id":"ftckpt_1","object":"fine_tuning.job.checkpoint","fine_tuning_job_id":"ftjob_1","step_number":100}],
                          "first_id":"ftckpt_1",
                          "last_id":"ftckpt_1",
                          "has_more":false
                        }
                        """.trimIndent(),
                    )
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val events = api.listFineTuningEvents(FineTuningJobId("ftjob_1"), FineTuningJobEventListQuery(after = "ftevent_0", limit = 3))
        val checkpoints = api.listFineTuningCheckpoints(FineTuningJobId("ftjob_1"), FineTuningCheckpointListQuery(after = "ftckpt_0", limit = 2))

        assertEquals("ftevent_1", events.data.single().id)
        assertEquals("ftckpt_1", checkpoints.data.single().id)
        assertTrue(seenPaths.any { it.contains("/events?") && it.contains("after=ftevent_0") && it.contains("limit=3") })
        assertTrue(seenPaths.any { it.contains("/checkpoints?") && it.contains("after=ftckpt_0") && it.contains("limit=2") })
    }

    @Test
    fun `fine tuning checkpoint permissions endpoints support create list and delete`() = runTest {
        val seenPaths = mutableListOf<String>()
        val seenBodies = mutableListOf<String>()
        val client = httpClient { request ->
            seenPaths += request.url.encodedPath + if (request.url.parameters.isEmpty()) "" else "?${request.url.parameters.formUrlEncode()}"
            seenBodies += request.bodyText()
            when (request.url.encodedPath) {
                "/v1/fine_tuning/checkpoints/ftckpt_model/permissions" ->
                    if (request.method == HttpMethod.Post) {
                        respondJson(
                            """
                            {
                              "object":"list",
                              "data":[{"id":"cp_1","object":"checkpoint.permission","created_at":1,"project_id":"proj_1"}],
                              "first_id":"cp_1",
                              "last_id":"cp_1",
                              "has_more":false
                            }
                            """.trimIndent(),
                        )
                    } else {
                        respondJson(
                            """
                            {
                              "object":"list",
                              "data":[{"id":"cp_1","object":"checkpoint.permission","created_at":1,"project_id":"proj_1"}],
                              "first_id":"cp_1",
                              "last_id":"cp_1",
                              "has_more":false
                            }
                            """.trimIndent(),
                        )
                    }
                "/v1/fine_tuning/checkpoints/ftckpt_model/permissions/cp_1" ->
                    respondJson("""{"id":"cp_1","object":"checkpoint.permission","deleted":true}""")
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val created =
            api.createFineTuningCheckpointPermissions(
                FineTunedModelCheckpointId("ftckpt_model"),
                FineTuningCheckpointPermissionCreateRequest(projectIds = listOf("proj_1")),
            )
        val listed =
            api.listFineTuningCheckpointPermissions(
                FineTunedModelCheckpointId("ftckpt_model"),
                FineTuningCheckpointPermissionListQuery(
                    after = "cp_0",
                    limit = 5,
                    order = FineTuningCheckpointPermissionOrder.ASCENDING,
                    projectId = "proj_1",
                ),
            )
        val deleted =
            api.deleteFineTuningCheckpointPermission(
                FineTunedModelCheckpointId("ftckpt_model"),
                FineTuningCheckpointPermissionId("cp_1"),
            )

        assertEquals("cp_1", created.data.single().id)
        assertEquals("proj_1", listed.data.single().projectId)
        assertTrue(deleted.deleted)
        assertTrue(seenBodies.first().contains("\"project_ids\":[\"proj_1\"]"))
        assertTrue(
            seenPaths.contains(
                "/v1/fine_tuning/checkpoints/ftckpt_model/permissions?after=cp_0&limit=5&order=ascending&project_id=proj_1",
            ),
        )
        assertTrue(seenPaths.contains("/v1/fine_tuning/checkpoints/ftckpt_model/permissions/cp_1"))
    }

    @Test
    fun `fine tuning grader endpoints run and validate typed requests`() = runTest {
        val seenPaths = mutableListOf<String>()
        val seenBodies = mutableListOf<String>()
        val client = httpClient { request ->
            seenPaths += request.url.encodedPath
            seenBodies += request.bodyText()
            when (request.url.encodedPath) {
                "/v1/fine_tuning/alpha/graders/run" ->
                    respondJson(
                        """
                        {
                          "reward":1.0,
                          "metadata":{"name":"Example score model grader","type":"score_model"},
                          "sub_rewards":{"accuracy":1.0},
                          "model_grader_token_usage_per_model":{"gpt-4o-2024-08-06":{"total_tokens":324}}
                        }
                        """.trimIndent(),
                    )
                "/v1/fine_tuning/alpha/graders/validate" ->
                    respondJson(
                        """
                        {
                          "grader":{
                            "type":"string_check",
                            "name":"Example string check grader",
                            "input":"{{sample.output_text}}",
                            "reference":"{{item.label}}",
                            "operation":"eq"
                          }
                        }
                        """.trimIndent(),
                    )
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val runResult =
            api.runFineTuningGrader(
                FineTuningGraderRunRequest(
                    grader =
                        buildJsonObject {
                            put("type", "score_model")
                            put("name", "Example score model grader")
                        },
                    item =
                        buildJsonObject {
                            put("reference_answer", "fuzzy wuzzy was a bear")
                        },
                    modelSample = JsonPrimitive("fuzzy wuzzy was a bear"),
                ),
            )
        val validated =
            api.validateFineTuningGrader(
                FineTuningGraderValidateRequest(
                    grader =
                        buildJsonObject {
                            put("type", "string_check")
                            put("name", "Example string check grader")
                            put("input", "{{sample.output_text}}")
                            put("reference", "{{item.label}}")
                            put("operation", "eq")
                        },
                ),
            )

        assertEquals(1.0, runResult.reward)
        assertEquals("score_model", runResult.metadata?.get("type")?.jsonPrimitive?.content)
        assertEquals("1.0", runResult.subRewards?.get("accuracy")?.jsonPrimitive?.content)
        assertEquals("string_check", validated.grader["type"]?.jsonPrimitive?.content)
        assertTrue(seenBodies[0].contains("\"model_sample\":\"fuzzy wuzzy was a bear\""))
        assertTrue(seenBodies[0].contains("\"reference_answer\":\"fuzzy wuzzy was a bear\""))
        assertTrue(seenBodies[1].contains("\"operation\":\"eq\""))
        assertEquals(
            listOf(
                "/v1/fine_tuning/alpha/graders/run",
                "/v1/fine_tuning/alpha/graders/validate",
            ),
            seenPaths,
        )
    }

    @Test
    fun `createSpeech posts audio speech endpoint and returns bytes`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/audio/speech", request.url.encodedPath)
            respond(
                content = "AUDIO",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "audio/mpeg"),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val bytes =
            api.createSpeech(
                SpeechRequest(
                    model = ModelId("gpt-4o-mini-tts"),
                    input = "Hello",
                    voice = "alloy",
                    responseFormat = SpeechAudioFormat.MP3,
                ),
            )

        assertEquals("AUDIO", bytes.decodeToString())
        val body = assertNotNull(seenRequest).bodyText()
        assertTrue(body.contains("\"voice\":\"alloy\""))
        assertTrue(body.contains("\"response_format\":\"mp3\""))
    }

    @Test
    fun `createSpeech uses pcm wire format and custom voice objects`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/audio/speech", request.url.encodedPath)
            respond(
                content = "PCM",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "audio/pcm"),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val bytes =
            api.createSpeech(
                SpeechRequest(
                    model = ModelId("gpt-4o-mini-tts"),
                    input = "Hello",
                    voice = SpeechVoice.Custom("voice_1234"),
                    responseFormat = SpeechAudioFormat.PCM,
                ),
            )

        assertEquals("PCM", bytes.decodeToString())
        val body = assertNotNull(seenRequest).bodyText()
        assertTrue(body.contains("\"voice\":{\"id\":\"voice_1234\"}"))
        assertTrue(body.contains("\"response_format\":\"pcm\""))
        assertTrue(!body.contains("\"response_format\":\"pcm16\""))
    }

    @Test
    fun `voice endpoints create voice and manage consents`() = runTest {
        val seenPaths = mutableListOf<String>()
        val seenBodies = mutableListOf<String>()
        val client = httpClient { request ->
            seenPaths += request.url.encodedPath + if (request.url.parameters.isEmpty()) "" else "?${request.url.parameters.formUrlEncode()}"
            seenBodies += request.bodyText()
            when (request.url.encodedPath) {
                "/v1/audio/voices" ->
                    respondJson("""{"id":"voice_1","created_at":1,"name":"My new voice","object":"audio.voice"}""")
                "/v1/audio/voice_consents" ->
                    if (request.method == HttpMethod.Post) {
                        respondJson("""{"id":"cons_1","created_at":1,"language":"en-US","name":"John Doe","object":"audio.voice_consent"}""")
                    } else {
                        respondJson(
                            """{"object":"list","data":[{"id":"cons_1","created_at":1,"language":"en-US","name":"John Doe","object":"audio.voice_consent"}],"has_more":false,"first_id":"cons_1","last_id":"cons_1"}""",
                        )
                    }
                "/v1/audio/voice_consents/cons_1" ->
                    when (request.method) {
                        HttpMethod.Get ->
                            respondJson("""{"id":"cons_1","created_at":1,"language":"en-US","name":"John Doe","object":"audio.voice_consent"}""")
                        HttpMethod.Post ->
                            respondJson("""{"id":"cons_1","created_at":1,"language":"en-US","name":"Jane Doe","object":"audio.voice_consent"}""")
                        HttpMethod.Delete ->
                            respondJson("""{"id":"cons_1","deleted":true,"object":"audio.voice_consent"}""")
                        else -> error("unexpected method ${request.method}")
                    }
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val voice =
            api.createVoice(
                VoiceCreateRequest(
                    name = "My new voice",
                    consentId = "cons_1",
                    audioSample = BinaryUpload("sample.wav", "VOICE".encodeToByteArray(), "audio/x-wav"),
                ),
            )
        val createdConsent =
            api.createVoiceConsent(
                VoiceConsentCreateRequest(
                    name = "John Doe",
                    language = "en-US",
                    recording = BinaryUpload("consent.wav", "CONSENT".encodeToByteArray(), "audio/x-wav"),
                ),
            )
        val fetchedConsent = api.getVoiceConsent("cons_1")
        val updatedConsent = api.updateVoiceConsent("cons_1", VoiceConsentUpdateRequest(name = "Jane Doe"))
        val listedConsents = api.listVoiceConsents(VoiceConsentListQuery(limit = 5, after = "cons_0"))
        val deletedConsent = api.deleteVoiceConsent("cons_1")

        assertEquals("voice_1", voice.id)
        assertEquals("cons_1", createdConsent.id)
        assertEquals("cons_1", fetchedConsent.id)
        assertEquals("Jane Doe", updatedConsent.name)
        assertEquals("cons_1", listedConsents.data.single().id)
        assertTrue(deletedConsent.deleted)
        assertTrue(seenBodies[0].contains("My new voice"))
        assertTrue(seenBodies[0].contains("cons_1"))
        assertTrue(seenBodies[0].contains("sample.wav"))
        assertTrue(seenBodies[1].contains("en-US"))
        assertTrue(seenBodies[1].contains("John Doe"))
        assertTrue(seenBodies[1].contains("consent.wav"))
        assertEquals("""{"name":"Jane Doe"}""", seenBodies[3])
        assertTrue(seenPaths[4].contains("/v1/audio/voice_consents?"))
        assertTrue(seenPaths[4].contains("limit=5"))
        assertTrue(seenPaths[4].contains("after=cons_0"))
    }

    @Test
    fun `speech request validates documented speed range`() {
        assertFailsWith<IllegalArgumentException> {
            SpeechRequest(
                model = ModelId("gpt-4o-mini-tts"),
                input = "Hello",
                voice = SpeechVoice.BuiltIn("alloy"),
                speed = 0.2,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SpeechRequest(
                model = ModelId("gpt-4o-mini-tts"),
                input = "Hello",
                voice = SpeechVoice.BuiltIn("alloy"),
                speed = 4.1,
            )
        }

        SpeechRequest(
            model = ModelId("gpt-4o-mini-tts"),
            input = "Hello",
            voice = SpeechVoice.BuiltIn("alloy"),
            speed = 4.0,
        )
    }

    @Test
    fun `createSpeech decodes typed api errors even on binary endpoint`() = runTest {
        val client = httpClient { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/audio/speech", request.url.encodedPath)
            respond(
                content = """{"error":{"message":"bad voice","type":"invalid_request_error","code":"voice_not_found"}}""",
                status = HttpStatusCode.BadRequest,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val error =
            assertFailsWith<OpenAIApiError.Api> {
                api.createSpeech(
                    SpeechRequest(
                        model = ModelId("gpt-4o-mini-tts"),
                        input = "Hello",
                        voice = "missing",
                    ),
                )
            }

        assertEquals(400, error.status)
        assertEquals("invalid_request_error", error.error.type)
        assertEquals(ResponseErrorCode.Unknown("voice_not_found"), error.error.code)
    }

    @Test
    fun `createSpeech rejects successful json bodies on audio endpoint`() = runTest {
        val client = httpClient {
            respond(
                content = """{"error":{"message":"voice synthesis failed","type":"server_error"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/problem+json"),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val error =
            assertFailsWith<OpenAIApiError.Api> {
                api.createSpeech(
                    SpeechRequest(
                        model = ModelId("gpt-4o-mini-tts"),
                        input = "Hello",
                        voice = "alloy",
                    ),
                )
            }

        assertEquals(200, error.status)
        assertEquals("voice synthesis failed", error.error.message)
    }

    @Test
    fun `streamSpeech assembles streamed bytes`() = runTest {
        val client = httpClient {
            respond(
                content = "STREAMED",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "audio/mpeg"),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val bytes =
            api
                .streamSpeech(
                    SpeechRequest(
                        model = ModelId("gpt-4o-mini-tts"),
                        input = "Hello",
                        voice = "alloy",
                    ),
                ).collectSpeechBytes()

        assertEquals("STREAMED", bytes.decodeToString())
    }

    @Test
    fun `streamSpeech tolerates zero byte reads before audio data`() = runTest {
        val client = httpClient {
            val delegate = ByteChannel(autoFlush = true)
            val channel =
                object : ByteReadChannel by delegate {
                    private var returnedZeroRead = false
                    private var wroteData = false

                    override suspend fun awaitContent(min: Int): Boolean {
                        if (!returnedZeroRead) {
                            returnedZeroRead = true
                            return true
                        }
                        if (!wroteData) {
                            wroteData = true
                            delegate.writeFully("STREAMED".encodeToByteArray())
                            delegate.close()
                            return delegate.awaitContent(min)
                        }
                        return delegate.awaitContent(min)
                    }
                }
            respond(
                content = channel,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "audio/mpeg"),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val bytes =
            api
                .streamSpeech(
                    SpeechRequest(
                        model = ModelId("gpt-4o-mini-tts"),
                        input = "Hello",
                        voice = "alloy",
                    ),
                ).collectSpeechBytes()

        assertEquals("STREAMED", bytes.decodeToString())
    }

    @Test
    fun `streamSpeech surfaces sse error events and collectSpeechBytes fails`() = runTest {
        val client = httpClient {
            respond(
                content =
                    """
                    event: error
                    data: {"error":{"message":"voice synthesis failed","type":"server_error"}}

                    data: [DONE]
                    """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val events =
            api
                .streamSpeech(
                    SpeechRequest(
                        model = ModelId("gpt-4o-mini-tts"),
                        input = "Hello",
                        voice = "alloy",
                        streamFormat = SpeechStreamFormat.SSE,
                    ),
                ).toList()

        assertTrue(events[0] is SpeechStreamEvent.Started)
        assertEquals("voice synthesis failed", (events[1] as SpeechStreamEvent.Error).error.message)
        assertTrue(events[2] is SpeechStreamEvent.Done)

        val collectionError =
            assertFailsWith<IllegalStateException> {
                api
                    .streamSpeech(
                        SpeechRequest(
                            model = ModelId("gpt-4o-mini-tts"),
                            input = "Hello",
                            voice = "alloy",
                            streamFormat = SpeechStreamFormat.SSE,
                        ),
                    ).collectSpeechBytes()
            }

        assertTrue(collectionError.message.orEmpty().contains("voice synthesis failed"))
    }

    @Test
    fun `streamSpeech rejects successful json bodies in binary mode`() = runTest {
        val client = httpClient {
            respond(
                content = """{"error":{"message":"voice synthesis failed","type":"server_error"}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val error =
            assertFailsWith<OpenAIApiError.Api> {
                api
                    .streamSpeech(
                        SpeechRequest(
                            model = ModelId("gpt-4o-mini-tts"),
                            input = "Hello",
                            voice = "alloy",
                        ),
                    ).collectSpeechBytes()
            }

        assertEquals(200, error.status)
        assertEquals("voice synthesis failed", error.error.message)
    }

    @Test
    fun `createSpeech wraps binary body read failures as network errors`() = runTest {
        val client = httpClient {
            respond(
                content = failingChannel(message = "audio read failed"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "audio/mpeg"),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val error =
            assertFailsWith<OpenAIApiError.Network> {
                api.createSpeech(
                    SpeechRequest(
                        model = ModelId("gpt-4o-mini-tts"),
                        input = "Hello",
                        voice = "alloy",
                    ),
                )
            }

        assertTrue(error.message.orEmpty().contains("audio read failed"))
    }

    @Test
    fun `createTranscription sends multipart form and decodes result`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/audio/transcriptions", request.url.encodedPath)
            respondJson("""{"text":"hello world","language":"en","duration":1.2,"response_format":"json"}""")
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val result =
            api.createTranscription(
                TranscriptionRequest(
                    file = BinaryUpload("clip.wav", "WAVE".encodeToByteArray(), "audio/wav"),
                    model = ModelId("gpt-4o-mini-transcribe"),
                    responseFormat = AudioTextResponseFormat.JSON,
                ),
            )

        assertEquals("hello world", result.text)
        assertEquals(AudioTextResponseFormatValue.Json, result.responseFormat)
        val request = assertNotNull(seenRequest)
        assertTrue((request.body.contentType?.toString() ?: "").startsWith("multipart/form-data"))
        val body = request.bodyText()
        assertTrue(body.contains("gpt-4o-mini-transcribe"))
        assertTrue(body.contains("clip.wav"))
    }

    @Test
    fun `createTranscription sends typed diarization multipart fields`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/audio/transcriptions", request.url.encodedPath)
            respondJson(
                """
                {
                  "text":"hello there",
                  "task":"transcribe",
                  "duration":1.2,
                  "response_format":"diarized_json",
                  "segments":[
                    {
                      "id":"seg_001",
                      "type":"transcript.text.segment",
                      "start":0.0,
                      "end":1.2,
                      "speaker":"A",
                      "text":"hello there"
                    }
                  ]
                }
                """.trimIndent(),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val result =
            api.createTranscription(
                TranscriptionRequest(
                    file = BinaryUpload("clip.wav", "WAVE".encodeToByteArray(), "audio/wav"),
                    model = ModelId("gpt-4o-transcribe-diarize"),
                    responseFormat = AudioTextResponseFormat.DIARIZED_JSON,
                    chunkingStrategy =
                        AudioChunkingStrategy.ServerVad(
                            threshold = 0.4,
                            prefixPaddingMs = 250,
                            silenceDurationMs = 450,
                        ),
                    knownSpeakerNames = listOf("agent", "customer"),
                    knownSpeakerReferences =
                        listOf(
                            "data:audio/wav;base64,QUdFTlQ=",
                            "data:audio/wav;base64,Q1VTVE9NRVI=",
                        ),
                    stream = false,
                ),
            )

        assertEquals("hello there", result.text)
        assertEquals("seg_001", result.diarizedSegments.single().id)
        val body = assertNotNull(seenRequest).bodyText()
        assertTrue(body.contains("known_speaker_names[]"))
        assertTrue(body.contains("known_speaker_references[]"))
        assertTrue(body.contains("chunking_strategy[type]"))
        assertTrue(body.contains("chunking_strategy[threshold]"))
        assertTrue(body.contains("stream"))
        assertTrue(body.contains("diarized_json"))
    }

    @Test
    fun `createTranscription accepts streaming binary upload`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/audio/transcriptions", request.url.encodedPath)
            respondJson("""{"text":"hello stream","language":"en","duration":1.2,"response_format":"json"}""")
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val result =
            api.createTranscription(
                StreamingTranscriptionRequest(
                    file =
                        StreamingBinaryUpload(
                            filename = "clip.wav",
                            sizeBytes = 4,
                            contentType = "audio/wav",
                        ) { ByteReadChannel("WAVE".encodeToByteArray()) },
                    model = ModelId("gpt-4o-mini-transcribe"),
                    responseFormat = AudioTextResponseFormat.JSON,
                ),
            )

        assertEquals("hello stream", result.text)
        val request = assertNotNull(seenRequest)
        assertTrue((request.body.contentType?.toString() ?: "").startsWith("multipart/form-data"))
        val body = request.bodyText()
        assertTrue(body.contains("clip.wav"))
        assertTrue(body.contains("WAVE"))
        assertTrue(body.contains("gpt-4o-mini-transcribe"))
    }

    @Test
    fun `streamTranscription sends stream flag and parses transcript events`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/audio/transcriptions", request.url.encodedPath)
            assertEquals(ContentType.Text.EventStream.toString(), request.headers[HttpHeaders.Accept])
            respond(
                content =
                    """
                    data: {"type":"transcript.text.delta","delta":"Hello","logprobs":[{"token":"Hello","logprob":-0.1,"bytes":[72,101,108,108,111]}]}

                    data: {"type":"transcript.text.done","text":"Hello world","logprobs":[{"token":"Hello","logprob":-0.1,"bytes":[72,101,108,108,111]}],"usage":{"input_tokens":1,"output_tokens":2,"total_tokens":3}}

                    data: [DONE]
                    """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val events =
            api
                .streamTranscription(
                    TranscriptionRequest(
                        file = BinaryUpload("clip.wav", "WAVE".encodeToByteArray(), "audio/wav"),
                        model = ModelId("gpt-4o-mini-transcribe"),
                        include = listOf(AudioTranscriptionInclude.LOGPROBS),
                    ),
                ).toList()

        assertEquals("Hello", (events[0] as TranscriptionStreamEvent.TextDelta).delta)
        assertEquals("Hello world", (events[1] as TranscriptionStreamEvent.TextDone).text)
        assertEquals(1, (events[1] as TranscriptionStreamEvent.TextDone).usage?.jsonObject?.get("input_tokens")?.jsonPrimitive?.content?.toInt())
        assertTrue(events[2] is TranscriptionStreamEvent.Done)

        val body = assertNotNull(seenRequest).bodyText()
        assertTrue(body.contains("stream"))
        assertTrue(body.contains("\r\ntrue\r\n") || body.contains("\ntrue\n"))
    }

    @Test
    fun `OpenAI transcription validation rejects unsupported model combinations`() = runTest {
        val api =
            KtorOpenAIApi(
                httpClient { error("request should not be made") },
                OpenAIApi.Config(apiKey = "secret"),
            )

        val unsupportedFormat =
            assertFailsWith<IllegalArgumentException> {
                api.createTranscription(
                    TranscriptionRequest(
                        file = BinaryUpload("clip.wav", "WAVE".encodeToByteArray(), "audio/wav"),
                        model = ModelId("gpt-4o-transcribe"),
                        responseFormat = AudioTextResponseFormat.TEXT,
                    ),
                )
            }
        assertTrue(unsupportedFormat.message.orEmpty().contains("gpt-4o-transcribe"))

        val diarizePrompt =
            assertFailsWith<IllegalArgumentException> {
                api.createTranscription(
                    TranscriptionRequest(
                        file = BinaryUpload("clip.wav", "WAVE".encodeToByteArray(), "audio/wav"),
                        model = ModelId("gpt-4o-transcribe-diarize"),
                        prompt = "continue",
                    ),
                )
            }
        assertTrue(diarizePrompt.message.orEmpty().contains("gpt-4o-transcribe-diarize"))

        val whisperStream =
            assertFailsWith<IllegalArgumentException> {
                api
                    .streamTranscription(
                        TranscriptionRequest(
                            file = BinaryUpload("clip.wav", "WAVE".encodeToByteArray(), "audio/wav"),
                            model = ModelId("whisper-1"),
                        ),
                    ).toList()
            }
        assertTrue(whisperStream.message.orEmpty().contains("whisper-1"))
    }

    @Test
    fun `transcription recognizes diarized response format`() {
        val result =
            OpenAIJson.decodeFromString<TranscriptionResult>(
                """
                {
                  "text":"hello there",
                  "response_format":"diarized_json",
                  "segments":[
                    {
                      "id":"seg_001",
                      "type":"transcript.text.segment",
                      "start":0.0,
                      "end":1.2,
                      "text":"hello there",
                      "speaker":"A"
                    }
                  ],
                  "logprobs":[
                    {"token":"hello","bytes":[104,101,108,108,111],"logprob":-0.1}
                  ]
                }
                """.trimIndent(),
            )

        assertTrue(result.responseFormat !is AudioTextResponseFormatValue.Unknown)
    }

    @Test
    fun `transcription decodes diarized segments with string ids`() {
        val result =
            OpenAIJson.decodeFromString<TranscriptionResult>(
                """
                {
                  "text":"hello there",
                  "task":"transcribe",
                  "duration":1.2,
                  "response_format":"diarized_json",
                  "segments":[
                    {
                      "id":"seg_001",
                      "type":"transcript.text.segment",
                      "start":0.0,
                      "end":1.2,
                      "speaker":"agent",
                      "text":"hello there"
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertEquals("seg_001", result.diarizedSegments.single().id)
        assertEquals("agent", result.diarizedSegments.single().speaker)
        assertTrue(result.segments.isEmpty())
    }

    @Test
    fun `transcription explicit non diarized response format keeps regular segments even with string ids`() {
        val result =
            OpenAIJson.decodeFromString<TranscriptionResult>(
                """
                {
                  "text":"hello there",
                  "response_format":"json",
                  "segments":[
                    {
                      "id":"seg_compat_1",
                      "type":"transcript.text.segment",
                      "start":0.0,
                      "end":1.2,
                      "speaker":"agent",
                      "text":"hello there"
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertEquals(TranscriptionSegmentIdValue.Text("seg_compat_1"), result.segments.single().id)
        assertEquals("agent", result.segments.single().speaker)
        assertTrue(result.diarizedSegments.isEmpty())
    }

    @Test
    fun `transcription without response format falls back to diarized segments for speaker tagged string ids`() {
        val result =
            OpenAIJson.decodeFromString<TranscriptionResult>(
                """
                {
                  "text":"hello there",
                  "segments":[
                    {
                      "id":"seg_compat_2",
                      "type":"transcript.text.segment",
                      "start":0.0,
                      "end":1.2,
                      "speaker":"customer",
                      "text":"hello there"
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertEquals("seg_compat_2", result.diarizedSegments.single().id)
        assertEquals("customer", result.diarizedSegments.single().speaker)
        assertTrue(result.segments.isEmpty())
    }

    @Test
    fun `transcription without response format falls back to regular segments for provider style string ids with regular metrics`() {
        val result =
            OpenAIJson.decodeFromString<TranscriptionResult>(
                """
                {
                  "text":"hello there",
                  "segments":[
                    {
                      "id":"seg_compat_3",
                      "start":0.0,
                      "end":1.2,
                      "text":"hello there",
                      "avg_logprob":-0.1,
                      "compression_ratio":1.1,
                      "no_speech_prob":0.02,
                      "seek":0,
                      "temperature":0.0,
                      "tokens":[1,2,3]
                    }
                  ]
                }
                """.trimIndent(),
            )

        assertEquals(TranscriptionSegmentIdValue.Text("seg_compat_3"), result.segments.single().id)
        assertEquals(-0.1, result.segments.single().avgLogprob)
        assertTrue(result.diarizedSegments.isEmpty())
    }

    @Test
    fun `uploadFile sends multipart file upload`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/files", request.url.encodedPath)
            respondJson("""{"id":"file_123","object":"file","filename":"notes.txt","purpose":"assistants"}""")
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val file =
            api.uploadFile(
                FileCreateRequest(
                    purpose = FilePurpose.ASSISTANTS,
                    file = BinaryUpload("notes.txt", "hello".encodeToByteArray(), "text/plain"),
                ),
            )

        assertEquals("file_123", file.id)
        assertEquals(FilePurposeValue.Assistants, file.purpose)
        val request = assertNotNull(seenRequest)
        assertTrue((request.body.contentType?.toString() ?: "").startsWith("multipart/form-data"))
        val body = request.bodyText()
        assertTrue(body.contains("assistants"))
        assertTrue(body.contains("notes.txt"))
    }

    @Test
    fun `uploadFile accepts streaming binary upload`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/files", request.url.encodedPath)
            respondJson("""{"id":"file_stream","object":"file","filename":"notes.txt","purpose":"assistants"}""")
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val file =
            api.uploadFile(
                StreamingFileCreateRequest(
                    purpose = FilePurpose.ASSISTANTS,
                    file =
                        StreamingBinaryUpload(
                            filename = "notes.txt",
                            sizeBytes = 5,
                            contentType = "text/plain",
                        ) { ByteReadChannel("hello".encodeToByteArray()) },
                ),
            )

        assertEquals("file_stream", file.id)
        val request = assertNotNull(seenRequest)
        assertTrue((request.body.contentType?.toString() ?: "").startsWith("multipart/form-data"))
        val body = request.bodyText()
        assertTrue(body.contains("assistants"))
        assertTrue(body.contains("notes.txt"))
        assertTrue(body.contains("hello"))
    }

    @Test
    fun `addUploadPart accepts streaming binary upload`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/uploads/upload_123/parts", request.url.encodedPath)
            respondJson("""{"id":"part_1","object":"upload.part"}""")
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val part =
            api.addUploadPart(
                UploadId("upload_123"),
                StreamingBinaryUpload(
                    filename = "chunk.bin",
                    sizeBytes = 5,
                    contentType = "application/octet-stream",
                ) { ByteReadChannel("abcde".encodeToByteArray()) },
            )

        assertEquals("part_1", part.id)
        val request = assertNotNull(seenRequest)
        assertTrue((request.body.contentType?.toString() ?: "").startsWith("multipart/form-data"))
        val body = request.bodyText()
        assertTrue(body.contains("chunk.bin"))
        assertTrue(body.contains("abcde"))
    }

    @Test
    fun `uploadFile sanitizes multipart filename metadata`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/files", request.url.encodedPath)
            respondJson("""{"id":"file_123","object":"file","filename":"bad_name_.txt","purpose":"assistants"}""")
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        api.uploadFile(
            FileCreateRequest(
                purpose = FilePurpose.ASSISTANTS,
                file = BinaryUpload("..\\private\\bad\r\n\"name\u0000.txt", "hello".encodeToByteArray(), "text/plain"),
            ),
        )

        val body = assertNotNull(seenRequest).bodyText()
        assertTrue(body.contains("""filename="bad_name_.txt""""))
        assertFalse(body.contains("..\\private\\"))
        assertFalse(body.contains('\u0000'))
    }

    @Test
    fun `downloadFile decodes typed api errors even on binary endpoint`() = runTest {
        val client = httpClient { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/v1/files/file_123/content", request.url.encodedPath)
            respond(
                content = """{"error":{"message":"missing file","type":"invalid_request_error","code":"file_not_found"}}""",
                status = HttpStatusCode.NotFound,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val error =
            assertFailsWith<OpenAIApiError.Api> {
                api.downloadFile(FileId("file_123"))
            }

        assertEquals(404, error.status)
        assertEquals("invalid_request_error", error.error.type)
        assertEquals(ResponseErrorCode.Unknown("file_not_found"), error.error.code)
    }

    @Test
    fun `downloadFileTo streams content and bypasses eager size guard`() = runTest {
        val client = httpClient { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/v1/files/file_big/content", request.url.encodedPath)
            respond(
                content = "TOO-LARGE",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/octet-stream"),
            )
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    apiKey = "secret",
                    maxEagerBinaryBytes = 4,
                ),
            )

        val error =
            assertFailsWith<IllegalStateException> {
                api.downloadFile(FileId("file_big"))
            }
        assertTrue(error.message.orEmpty().contains("maxEagerBinaryBytes"))

        val chunks = mutableListOf<ByteArray>()
        api.downloadFileTo(FileId("file_big")) { chunk ->
            chunks += chunk
        }

        assertEquals("TOO-LARGE", chunks.concatByteArrays().decodeToString())
    }

    @Test
    fun `decoded finite response vocabularies preserve unknown wire values`() {
        val file =
            OpenAIJson.decodeFromString<OpenAIFile>(
                """{"id":"file_x","object":"file","purpose":"assistants_output","status":"uploaded"}""",
            )
        val unknownFileStatus =
            OpenAIJson.decodeFromString<OpenAIFile>(
                """{"id":"file_y","object":"file","status":"processing_elsewhere"}""",
            )
        val image =
            OpenAIJson.decodeFromString<ImagesResponse>(
                """{"created":1,"background":"","output_format":"unknown","quality":"weird","size":"strange","data":[]}""",
            )
        val transcription =
            OpenAIJson.decodeFromString<TranscriptionResult>(
                """{"text":"hello","response_format":""}""",
            )
        val responseObject =
            OpenAIJson.decodeFromString<ResponseObject>(
                """{"id":"resp_x","object":"response","status":"waiting_elsewhere","output":[]}""",
            )
        val batch =
            OpenAIJson.decodeFromString<BatchObject>(
                """{"id":"batch_x","object":"batch","status":"stalled","completion_window":"36h"}""",
            )
        val upload =
            OpenAIJson.decodeFromString<UploadObject>(
                """{"id":"upload_x","object":"upload","status":"mystery","purpose":"custom"}""",
            )
        val fineTuningJob =
            OpenAIJson.decodeFromString<FineTuningJob>(
                """{"id":"ftjob_x","object":"fine_tuning.job","status":"waiting_on_moon"}""",
            )
        val video =
            OpenAIJson.decodeFromString<VideoObject>(
                """{"id":"vid_x","object":"video","status":"rendering_elsewhere"}""",
            )
        val realtimeSession =
            OpenAIJson.decodeFromString<RealtimeSessionObject>(
                """{"id":"sess_x","object":"realtime.session","type":"vendor_session","output_modalities":["vendor.modality"],"include":["vendor.include"]}""",
            )
        val evalRun =
            OpenAIJson.decodeFromString<EvalRunObject>(
                """{"id":"run_x","object":"eval.run","status":"waiting_for_review"}""",
            )
        val evalRunOutputItem =
            OpenAIJson.decodeFromString<EvalRunOutputItemObject>(
                """{"id":"out_x","object":"eval.run.output_item","status":"borderline"}""",
            )
        val chunk =
            OpenAIJson.decodeFromString<ChatCompletionChunk>(
                """{"id":"chatcmpl_x","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"type":"vendor_function"}]},"finish_reason":"unexpected"}]}""",
            )

        assertEquals(FilePurposeValue.AssistantsOutput, file.purpose)
        assertEquals(FileStatusValue.Uploaded, file.status)
        assertEquals(FileStatusValue.Unknown("processing_elsewhere"), unknownFileStatus.status)
        assertEquals(ImageBackgroundValue.Unknown(""), image.background)
        assertEquals(ImageOutputFormatValue.Unknown("unknown"), image.outputFormat)
        assertEquals(ImageQualityValue.Unknown("weird"), image.quality)
        assertEquals(ImageSizeValue.Unknown("strange"), image.size)
        assertEquals(AudioTextResponseFormatValue.Unknown(""), transcription.responseFormat)
        assertEquals(ResponseStatus.Unknown("waiting_elsewhere"), responseObject.status)
        assertEquals(BatchStatus.Unknown("stalled"), batch.status)
        assertEquals(BatchCompletionWindowValue.Unknown("36h"), batch.completionWindow)
        assertEquals(UploadStatus.Unknown("mystery"), upload.status)
        assertEquals(FilePurposeValue.Unknown("custom"), upload.purpose)
        assertEquals(FineTuningJobStatus.Unknown("waiting_on_moon"), fineTuningJob.status)
        assertEquals(VideoStatus.Unknown("rendering_elsewhere"), video.status)
        assertEquals(RealtimeSessionTypeValue.Unknown("vendor_session"), realtimeSession.type)
        assertEquals(RealtimeModalityValue.Unknown("vendor.modality"), realtimeSession.outputModalities?.single())
        assertEquals(RealtimeIncludeValue.Unknown("vendor.include"), realtimeSession.include?.single())
        assertEquals(EvalRunStatus.Unknown("waiting_for_review"), evalRun.status)
        assertEquals(EvalRunOutputItemStatus.Unknown("borderline"), evalRunOutputItem.status)
        assertEquals(ChatCompletionFinishReason.Unknown("unexpected"), chunk.choices.single().finishReason)
        assertEquals(ChatCompletionToolCallType.Unknown("vendor_function"), chunk.choices.single().delta?.toolCalls?.single()?.type)
    }

    @Test
    fun `response status recognizes queued`() {
        val response =
            OpenAIJson.decodeFromString<ResponseObject>(
                """{"id":"resp_q","object":"response","status":"queued","output":[]}""",
            )

        assertTrue(response.status !is ResponseStatus.Unknown)
    }

    @Test
    fun `uploads lifecycle encodes create part and complete`() = runTest {
        val seenPaths = mutableListOf<String>()
        val seenBodies = mutableListOf<String>()
        val client = httpClient { request ->
            seenPaths += request.url.encodedPath
            seenBodies += request.bodyText()
            when (request.url.encodedPath) {
                "/v1/uploads" ->
                    respondJson("""{"id":"upload_1","object":"upload","status":"pending","filename":"doc.pdf","purpose":"assistants"}""")
                "/v1/uploads/upload_1/parts" ->
                    respondJson("""{"id":"part_1","object":"upload.part","upload_id":"upload_1"}""")
                "/v1/uploads/upload_1/complete" ->
                    respondJson("""{"id":"upload_1","object":"upload","status":"completed","file_id":"file_1"}""")
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val upload =
            api.createUpload(
                UploadCreateRequest(
                    filename = "doc.pdf",
                    bytes = 1024,
                    mimeType = "application/pdf",
                    purpose = FilePurpose.ASSISTANTS,
                ),
            )
        val part = api.addUploadPart(UploadId("upload_1"), BinaryUpload("chunk.bin", "part".encodeToByteArray(), "application/octet-stream"))
        val completed = api.completeUpload(UploadId("upload_1"), listOf(UploadPartId("part_1")))

        assertEquals("upload_1", upload.id)
        assertEquals("part_1", part.id)
        assertEquals(UploadStatus.Pending, upload.status)
        assertEquals(FilePurposeValue.Assistants, upload.purpose)
        assertEquals(UploadStatus.Completed, completed.status)
        assertEquals(
            listOf(
                "/v1/uploads",
                "/v1/uploads/upload_1/parts",
                "/v1/uploads/upload_1/complete",
            ),
            seenPaths,
        )
        assertTrue(seenBodies[0].contains("\"mime_type\":\"application/pdf\""))
        assertTrue(seenBodies[1].contains("chunk.bin"))
        assertTrue(seenBodies[2].contains("\"part_ids\":[\"part_1\"]"))
    }

    @Test
    fun `vector store endpoints set beta header and encode queries`() = runTest {
        val seenHeaders = mutableListOf<String?>()
        val seenPaths = mutableListOf<String>()
        val seenBodies = mutableListOf<String>()
        val client = httpClient { request ->
            seenHeaders += request.headers["OpenAI-Beta"]
            seenPaths += request.url.encodedPath
            seenBodies += request.bodyText()
            when (request.url.encodedPath) {
                "/v1/vector_stores" ->
                    respondJson("""{"id":"vs_1","object":"vector_store","name":"Docs"}""")
                "/v1/vector_stores/vs_1/search" ->
                    respondJson("""{"object":"vector_store.search","search_query":"hello","data":[{"file_id":"file_1","filename":"notes.txt","score":0.9}]}""")
                "/v1/vector_stores/vs_1/files" ->
                    respondJson(
                        """{"object":"list","data":[{"id":"vsf_1","object":"vector_store.file","vector_store_id":"vs_1","status":"completed","chunking_strategy":{"type":"auto"}}],"has_more":false}""",
                    )
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val store =
            api.createVectorStore(
                VectorStoreCreateRequest(
                    name = "Docs",
                    expiresAfter = VectorStoreExpirationPolicy(days = 7),
                    chunkingStrategy = VectorStoreChunkingStrategy.Auto,
                ),
            )
        val search = api.searchVectorStore(VectorStoreId("vs_1"), VectorStoreSearchRequest(query = "hello"))
        val files =
            api.listVectorStoreFiles(
                VectorStoreId("vs_1"),
                VectorStoreFileListQuery(
                    limit = 5,
                    order = VectorStoreFileListOrder.ASC,
                    filter = VectorStoreFileStatus.COMPLETED,
                ),
            )

        assertEquals("vs_1", store.id)
        assertEquals("file_1", search.data.single().fileId)
        assertEquals("vsf_1", files.data.single().id)
        assertEquals(VectorStoreFileStatusValue.Completed, files.data.single().status)
        assertEquals(VectorStoreChunkingStrategyType.Auto, files.data.single().chunkingStrategy?.type)
        assertTrue(seenHeaders.all { it == "assistants=v2" })
        assertTrue(seenBodies.first().contains("\"expires_after\":{\"anchor\":\"last_active_at\",\"days\":7}"))
        assertTrue(seenBodies.first().contains("\"chunking_strategy\":{\"type\":\"auto\"}"))
        assertEquals(
            listOf(
                "/v1/vector_stores",
                "/v1/vector_stores/vs_1/search",
                "/v1/vector_stores/vs_1/files",
            ),
            seenPaths,
        )
    }

    @Test
    fun `xai provider supports responses create`() = runTest {
        val client = httpClient { request ->
            assertEquals("Bearer xai-key", request.headers[HttpHeaders.Authorization])
            assertEquals("/v1/responses", request.url.encodedPath)
            respondJson("""{"id":"resp_xai","object":"response","status":"completed","output":[]}""")
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = OpenAIProvider.XAI,
                    apiKey = "xai-key",
                    baseUrl = OpenAIProvider.XAI.defaultBaseUrl,
                ),
            )

        val response = api.createResponse(ResponseCreateRequest(model = ModelId("grok-4"), input = ResponseInput.Text("ping")))
        assertEquals("resp_xai", response.id)
    }

    @Test
    fun `anthropic compatibility provider supports chat completions`() = runTest {
        val client = httpClient { request ->
            assertEquals("Bearer anthropic-key", request.headers[HttpHeaders.Authorization])
            assertEquals("/v1/chat/completions", request.url.encodedPath)
            respondJson(
                """
                {
                  "id":"chatcmpl_a",
                  "object":"chat.completion",
                  "choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]
                }
                """.trimIndent(),
            )
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = OpenAIProvider.Anthropic,
                    apiKey = "anthropic-key",
                    baseUrl = OpenAIProvider.Anthropic.defaultBaseUrl,
                ),
            )

        val response =
            api.createChatCompletion(
                ChatCompletionRequest(
                    model = ModelId("claude-sonnet-4-5"),
                    messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"))),
                ),
            )

        assertEquals("ok", response.outputText())
    }

    @Test
    fun `anthropic thinking is serialized and unsupported chat fields are validated`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            respondJson("""{"id":"chatcmpl_a","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}]}""")
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = OpenAIProvider.Anthropic,
                    apiKey = "anthropic-key",
                    baseUrl = OpenAIProvider.Anthropic.defaultBaseUrl,
                ),
            )

        api.createChatCompletion(
            ChatCompletionRequest(
                model = ModelId("claude-3-5-haiku-latest"),
                messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"))),
                providerOptions = AnthropicRequestOptions(thinking = AnthropicThinkingConfig(type = "enabled", budgetTokens = 128)),
            ),
        )

        val body = assertNotNull(seenRequest).bodyText()
        assertTrue(body.contains("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":128}"))

        assertFailsWith<IllegalArgumentException> {
            ChatCompletionRequest(
                model = ModelId("claude-3-5-haiku-latest"),
                messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"))),
                n = 2,
            ).requireCompatibleWith(OpenAIProvider.Anthropic)
        }

        assertFailsWith<IllegalArgumentException> {
            ChatCompletionRequest(
                model = ModelId("claude-3-5-haiku-latest"),
                messages =
                    listOf(
                        ChatMessage(
                            role = ChatRole.USER,
                            content =
                                ChatMessageContent.Parts(
                                    listOf(ChatMessagePart.InputAudio(data = "AA==", format = InputAudioFormat.WAV)),
                                ),
                        ),
                    ),
            ).requireCompatibleWith(OpenAIProvider.Anthropic)
        }

        assertFailsWith<IllegalArgumentException> {
            ChatCompletionRequest(
                model = ModelId("claude-3-5-haiku-latest"),
                messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"))),
                responseFormat = ChatResponseFormat.JsonObjectFormat,
            ).requireCompatibleWith(OpenAIProvider.Anthropic)
        }
    }

    @Test
    fun `gemini files api error message points at native upload flow`() = runTest {
        val client = httpClient { _ ->
            error("request should not be executed")
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = OpenAIProvider.Gemini,
                    apiKey = "gemini-key",
                    baseUrl = OpenAIProvider.Gemini.defaultBaseUrl,
                ),
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                api.uploadFile(
                    FileCreateRequest(
                        purpose = FilePurpose.ASSISTANTS,
                        file = BinaryUpload("smoke.txt", "hello".encodeToByteArray(), "text/plain"),
                    ),
                )
            }

        assertTrue(error.message.orEmpty().contains("native Google file APIs"))
    }

    @Test
    fun `ollama image generation supports only b64_json in this client`() = runTest {
        val client =
            httpClient { request ->
                assertEquals("/v1/images/generations", request.url.encodedPath)
                respondJson("""{"created":1,"data":[{"b64_json":"aGVsbG8="}]}""")
            }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = OpenAIProvider.Ollama(),
                    baseUrl = OpenAIProvider.Ollama().defaultBaseUrl,
                ),
            )

        val ok =
            api.generateImage(
                ImageGenerateRequest(
                    prompt = "cat",
                    model = ModelId("qwen-image"),
                    responseFormat = ImageResponseFormat.B64_JSON,
                ),
            )
        assertEquals(listOf("aGVsbG8="), ok.base64Images())

        assertFailsWith<IllegalArgumentException> {
            api.generateImage(
                ImageGenerateRequest(
                    prompt = "cat",
                    model = ModelId("qwen-image"),
                    responseFormat = ImageResponseFormat.URL,
                ),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            api.editImage(
                ImageEditRequest(
                    images = listOf(ImageReference.ImageUrl("https://example.com/cat.png")),
                    prompt = "cat",
                ),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            api.createImageVariation(
                ImageVariationRequest(
                    image = BinaryUpload("cat.png", byteArrayOf(1, 2, 3), "image/png"),
                ),
            )
        }
    }

    @Test
    fun `deepseek request options prefix and reasoning content are merged into chat body`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            respondJson(
                """
                {
                  "id":"chatcmpl_ds",
                  "object":"chat.completion",
                  "choices":[{"index":0,"message":{"role":"assistant","content":"done","reasoning_content":"because"}}]
                }
                """.trimIndent(),
            )
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = OpenAIProvider.DeepSeek,
                    apiKey = "deepseek-key",
                    baseUrl = OpenAIProvider.DeepSeek.defaultBaseUrl,
                ),
            )

        val response =
            api.createChatCompletion(
                ChatCompletionRequest(
                    model = ModelId("deepseek-reasoner"),
                    messages =
                        listOf(
                            ChatMessage(
                                role = ChatRole.ASSISTANT,
                                content = ChatMessageContent.Text("partial"),
                                prefix = true,
                                reasoningContent = "prior reasoning",
                            ),
                            ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("finish it")),
                        ),
                    providerOptions = DeepSeekRequestOptions(thinking = DeepSeekThinkingConfig("enabled")),
                ),
            )

        assertEquals("done", response.outputText())
        assertEquals("because", response.reasoningContent())
        val body = assertNotNull(seenRequest).bodyText()
        assertTrue(body.contains("\"prefix\":true"))
        assertTrue(body.contains("\"reasoning_content\":\"prior reasoning\""))
        assertTrue(body.contains("\"thinking\":{\"type\":\"enabled\"}"))
    }

    @Test
    fun `chat completion helpers expose structured choice texts and reject merged multi choice output`() {
        val response =
            ChatCompletionResponse(
                id = "chatcmpl_multi",
                choices =
                    listOf(
                        ChatCompletionChoice(
                            index = 0,
                            message =
                                ChatCompletionMessageObject(
                                    role = "assistant",
                                    content = """{"city":"Paris"}""",
                                    reasoningContent = "first",
                                ),
                        ),
                        ChatCompletionChoice(
                            index = 1,
                            message =
                                ChatCompletionMessageObject(
                                    role = "assistant",
                                    content = """{"city":"Tokyo"}""",
                                    reasoningContent = "second",
                                ),
                        ),
                    ),
            )

        assertEquals(listOf("""{"city":"Paris"}""", """{"city":"Tokyo"}"""), response.choiceTexts())
        assertEquals(listOf("first", "second"), response.choiceReasoningContents())

        val textError = assertFailsWith<IllegalStateException> { response.outputText() }
        assertTrue(textError.message.orEmpty().contains("choiceTexts()"))

        val reasoningError = assertFailsWith<IllegalStateException> { response.reasoningContent() }
        assertTrue(reasoningError.message.orEmpty().contains("choiceReasoningContents()"))

        val jsonError = assertFailsWith<IllegalStateException> { response.outputJsonElementOrNull() }
        assertTrue(jsonError.message.orEmpty().contains("choiceTexts()"))
    }

    @Test
    fun `deepseek streaming accumulates reasoning content deltas`() = runTest {
        val client = httpClient {
            respond(
                content =
                    """
                    data: {"id":"chatcmpl_ds","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"reasoning_content":"because "}}]}

                    data: {"id":"chatcmpl_ds","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"ok"}}]}

                    data: [DONE]
                    """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
            )
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = OpenAIProvider.DeepSeek,
                    apiKey = "deepseek-key",
                    baseUrl = OpenAIProvider.DeepSeek.defaultBaseUrl,
                ),
            )

        val assembly =
            api
                .streamChatCompletion(
                    ChatCompletionRequest(
                        model = ModelId("deepseek-reasoner"),
                        messages = listOf(ChatMessage(role = ChatRole.USER, content = ChatMessageContent.Text("ping"))),
                    ),
                ).collectChatCompletionStream()

        assertEquals("because ", assembly.reasoningContent)
        assertEquals("ok", assembly.outputText)
        assertTrue(assembly.isDone)
    }

    @Test
    fun `deepseek fim completion uses beta completions endpoint`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            respondJson(
                """
                {
                  "id":"cmpl_ds",
                  "object":"text_completion",
                  "choices":[{"index":0,"text":" world","finish_reason":"stop"}],
                  "usage":{"total_tokens":12}
                }
                """.trimIndent(),
            )
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = OpenAIProvider.DeepSeek,
                    apiKey = "deepseek-key",
                    baseUrl = OpenAIProvider.DeepSeek.defaultBaseUrl,
                ),
            )

        val response =
            api.createDeepSeekFimCompletion(
                DeepSeekFimCompletionRequest(
                    model = ModelId("deepseek-chat"),
                    prompt = "hello",
                    suffix = "!",
                    maxTokens = 16,
                ),
            )

        assertEquals(" world", response.outputText())
        assertEquals(ChatCompletionFinishReason.Stop, response.choices.single().finishReason)
        assertEquals("/beta/completions", assertNotNull(seenRequest).url.encodedPath)
        assertTrue(assertNotNull(seenRequest).bodyText().contains("\"suffix\":\"!\""))
    }

    @Test
    fun `response input token counting posts dedicated endpoint`() = runTest {
        val client = httpClient { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/responses/input_tokens", request.url.encodedPath)
            respondJson("""{"object":"response.input_tokens","usage":{"input_tokens":42,"total_tokens":42}}""")
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val result =
            api.countResponseInputTokens(
                ResponseInputTokensRequest(
                    model = ModelId("gpt-4.1-mini"),
                    input = ResponseInput.Text("hello"),
                ),
            )

        assertEquals(42, result.usage?.inputTokens)
    }

    @Test
    fun `response lifecycle endpoints support retrieve query delete and compaction`() = runTest {
        val seenQueries = mutableListOf<String>()
        val seenBodies = mutableListOf<String>()
        val seenMethods = mutableListOf<HttpMethod>()
        val client = httpClient { request ->
            seenMethods += request.method
            seenQueries += request.url.encodedQuery.orEmpty()
            seenBodies += request.bodyText()
            when (request.url.encodedPath) {
                "/v1/responses/resp_1" ->
                    if (request.method == HttpMethod.Get) {
                        respondJson("""{"id":"resp_1","object":"response","status":"completed","output":[]}""")
                    } else {
                        respondJson("""{"id":"resp_1","object":"response.deleted","deleted":true}""")
                    }
                "/v1/responses/compact" ->
                    respondJson(
                        """
                        {
                          "id":"resp_cmp_1",
                          "object":"response.compaction",
                          "created_at":1764967971,
                          "output":[
                            {"id":"msg_1","type":"message","status":"completed","role":"user","content":[{"type":"input_text","text":"hello"}]},
                            {"id":"cmp_1","type":"compaction","encrypted_content":"gAAAAA=="}
                          ],
                          "usage":{"input_tokens":139,"output_tokens":438,"total_tokens":577}
                        }
                        """.trimIndent(),
                    )
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val retrieved =
            api.getResponse(
                ResponseId("resp_1"),
                ResponseRetrieveQuery(
                    include = listOf(ResponseInclude.WEB_SEARCH_CALL_RESULTS),
                    includeObfuscation = true,
                    startingAfter = 10,
                ),
            )
        val deleted = api.deleteResponse(ResponseId("resp_1"))
        val compacted =
            api.compactResponse(
                ResponseCompactRequest(
                    model = ModelId("gpt-5"),
                    previousResponseId = ResponseId("resp_1"),
                    promptCacheKey = "cache-key-1",
                ),
            )

        assertEquals("resp_1", retrieved.id)
        assertTrue(deleted.deleted)
        assertEquals(ResponseItemType.Compaction, compacted.output[1].type)
        assertEquals("gAAAAA==", compacted.compactionItems().single().encryptedContent)
        assertTrue(seenQueries.first().contains("include=web_search_call.results"))
        assertTrue(seenQueries.first().contains("include_obfuscation=true"))
        assertTrue(seenQueries.first().contains("starting_after=10"))
        assertTrue(seenBodies.last().contains("\"previous_response_id\":\"resp_1\""))
        assertTrue(seenBodies.last().contains("\"prompt_cache_key\":\"cache-key-1\""))
        assertEquals(listOf(HttpMethod.Get, HttpMethod.Delete, HttpMethod.Post), seenMethods)
    }

    @Test
    fun `conversation endpoints create update and create items`() = runTest {
        val seenPaths = mutableListOf<String>()
        val seenBodies = mutableListOf<String>()
        val seenQueries = mutableListOf<String>()
        val client = httpClient { request ->
            seenPaths += request.url.encodedPath
            seenBodies += request.bodyText()
            seenQueries += request.url.encodedQuery.orEmpty()
            when (request.url.encodedPath) {
                "/v1/conversations" ->
                    respondJson(
                        """{"id":"conv_2","object":"conversation","created_at":1741900000,"metadata":{"topic":"project-x"}}""",
                    )
                "/v1/conversations/conv_2" ->
                    respondJson(
                        """{"id":"conv_2","object":"conversation","created_at":1741900000,"metadata":{"topic":"project-y"}}""",
                    )
                "/v1/conversations/conv_2/items" ->
                    respondJson(
                        """
                        {
                          "object":"list",
                          "data":[
                            {"id":"item_2","object":"conversation.item","type":"message","role":"assistant"},
                            {"id":"item_3","object":"conversation.item","type":"message","role":"user"}
                          ],
                          "has_more":false
                        }
                        """.trimIndent(),
                    )
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val created =
            api.createConversation(
                ConversationCreateRequest(
                    items =
                        listOf(
                            ResponseInputItem.Message(
                                role = ResponseRole.USER,
                                content = listOf(ResponseInputContent.InputText("hello")),
                            ),
                        ),
                    metadata = mapOf("topic" to "project-x"),
                ),
            )
        val updated = api.updateConversation(ConversationId("conv_2"), ConversationUpdateRequest(metadata = mapOf("topic" to "project-y")))
        val items =
            api.createConversationItems(
                ConversationId("conv_2"),
                ConversationItemCreateRequest(
                    items =
                        listOf(
                            ResponseInputItem.Message(
                                role = ResponseRole.ASSISTANT,
                                phase = ResponseMessagePhase.FINAL_ANSWER,
                                content = listOf(ResponseInputContent.InputText("done")),
                            ),
                        ),
                    include = listOf(ResponseInclude.MESSAGE_OUTPUT_TEXT_LOGPROBS),
                ),
            )

        assertEquals("conv_2", created.id)
        assertEquals("conv_2", updated.id)
        assertEquals(2, items.data.size)
        assertTrue(seenBodies[0].contains("\"items\":["))
        assertTrue(seenBodies[0].contains("\"metadata\":{\"topic\":\"project-x\"}"))
        assertEquals("""{"metadata":{"topic":"project-y"}}""", seenBodies[1])
        assertTrue(seenBodies[2].contains("\"phase\":\"final_answer\""))
        assertTrue(seenQueries[2].contains("include=message.output_text.logprobs"))
        assertEquals(
            listOf(
                "/v1/conversations",
                "/v1/conversations/conv_2",
                "/v1/conversations/conv_2/items",
            ),
            seenPaths,
        )
    }

    @Test
    fun `stored chat completion lifecycle endpoints work`() = runTest {
        val seenPaths = mutableListOf<String>()
        val seenBodies = mutableListOf<String>()
        val client = httpClient { request ->
            seenPaths += request.url.encodedPath
            seenBodies += request.bodyText()
            when (request.url.encodedPath) {
                "/v1/chat/completions/chat_1" ->
                    when (request.method) {
                        HttpMethod.Get ->
                            respondJson(DocumentedStoredChatCompletionGetJson)
                        HttpMethod.Post ->
                            respondJson(DocumentedStoredChatCompletionUpdateJson)
                        else ->
                            respondJson(DocumentedStoredChatCompletionDeleteJson)
                    }
                "/v1/chat/completions" ->
                    respondJson(DocumentedStoredChatCompletionListJson)
                "/v1/chat/completions/chat_1/messages" ->
                    respondJson(DocumentedStoredChatCompletionMessagesJson)
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val item = api.getStoredChatCompletion(ChatCompletionId("chat_1"))
        val page = api.listStoredChatCompletions()
        val messages = api.listStoredChatCompletionMessages(ChatCompletionId("chat_1"))
        val updated =
            api.updateStoredChatCompletion(
                ChatCompletionId("chat_1"),
                StoredChatCompletionUpdateRequest(metadata = mapOf("foo" to "bar")),
            )
        val deleted = api.deleteStoredChatCompletion(ChatCompletionId("chat_1"))

        assertEquals("chatcmpl-abc123", item.id)
        assertEquals("chatcmpl-AyPNinnUqUDYo9SAdA52NobMflmj2", page.data.single().id)
        assertEquals("chatcmpl-AyPNinnUqUDYo9SAdA52NobMflmj2-0", messages.firstId)
        assertEquals("user", messages.data.single().role)
        assertEquals("chatcmpl-AyPNinnUqUDYo9SAdA52NobMflmj2", updated.id)
        assertEquals("Mind of circuits hum,  \nLearning patterns in silence—  \nFuture's quiet spark.", item.outputText())
        assertTrue(deleted.deleted)
        assertTrue(seenBodies[3].contains("\"metadata\":{\"foo\":\"bar\"}"))
        assertEquals(
            listOf(
                "/v1/chat/completions/chat_1",
                "/v1/chat/completions",
                "/v1/chat/completions/chat_1/messages",
                "/v1/chat/completions/chat_1",
                "/v1/chat/completions/chat_1",
            ),
            seenPaths,
        )
    }

    @Test
    fun `conversation endpoints retrieve list and delete items`() = runTest {
        val seenPaths = mutableListOf<String>()
        val client = httpClient { request ->
            seenPaths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/v1/conversations/conv_1" ->
                    if (request.method == HttpMethod.Get) {
                        respondJson("""{"id":"conv_1","object":"conversation"}""")
                    } else {
                        respondJson("""{"id":"conv_1","object":"conversation.deleted","deleted":true}""")
                    }
                "/v1/conversations/conv_1/items" ->
                    respondJson("""{"object":"list","data":[{"id":"item_1","object":"conversation.item","type":"message","role":"assistant"}],"has_more":false}""")
                "/v1/conversations/conv_1/items/item_1" ->
                    if (request.method == HttpMethod.Get) {
                        respondJson("""{"id":"item_1","object":"conversation.item","type":"message","role":"assistant"}""")
                    } else {
                        respondJson("""{"id":"item_1","object":"conversation.item.deleted","deleted":true}""")
                    }
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val conversation = api.getConversation(ConversationId("conv_1"))
        val items = api.listConversationItems(ConversationId("conv_1"))
        val item = api.getConversationItem(ConversationId("conv_1"), ConversationItemId("item_1"))
        val deletedItem = api.deleteConversationItem(ConversationId("conv_1"), ConversationItemId("item_1"))
        val deletedConversation = api.deleteConversation(ConversationId("conv_1"))

        assertEquals("conv_1", conversation.id)
        assertEquals("item_1", items.data.single().id)
        assertEquals("assistant", item.role)
        assertTrue(deletedItem.deleted)
        assertTrue(deletedConversation.deleted)
        assertEquals(
            listOf(
                "/v1/conversations/conv_1",
                "/v1/conversations/conv_1/items",
                "/v1/conversations/conv_1/items/item_1",
                "/v1/conversations/conv_1/items/item_1",
                "/v1/conversations/conv_1",
            ),
            seenPaths,
        )
    }

    @Test
    fun `openrouter stateless responses reject previous response id and store`() {
        val request =
            ResponseCreateRequest(
                model = ModelId("openai/gpt-4.1-mini"),
                input = ResponseInput.Text("ping"),
                previousResponseId = ResponseId("resp_prev"),
                store = true,
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                request.requireCompatibleWith(OpenAIProvider.OpenRouter())
            }

        assertTrue(error.message?.contains("stateless") == true)
    }

    @Test
    fun `deepseek prefix chat completions use beta endpoint`() = runTest {
        val client = httpClient { request ->
            assertEquals("/beta/chat/completions", request.url.encodedPath)
            respondJson(
                """
                {
                  "id":"chatcmpl_ds_beta",
                  "object":"chat.completion",
                  "choices":[{"index":0,"message":{"role":"assistant","content":"ok","reasoning_content":"thinking"},"finish_reason":"stop"}]
                }
                """.trimIndent(),
            )
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = OpenAIProvider.DeepSeek,
                    apiKey = "deepseek-key",
                    baseUrl = OpenAIProvider.DeepSeek.defaultBaseUrl,
                ),
            )

        val response =
            api.createChatCompletion(
                ChatCompletionRequest(
                    model = ModelId("deepseek-chat"),
                    messages =
                        listOf(
                            ChatMessage(
                                role = ChatRole.ASSISTANT,
                                content = ChatMessageContent.Text("Continue"),
                                prefix = true,
                            ),
                        ),
                ),
            )

        assertEquals("thinking", response.reasoningContent())
    }

    @Test
    fun `vector store file batch endpoints encode payload and beta header`() = runTest {
        val seenPaths = mutableListOf<String>()
        val seenBodies = mutableListOf<String>()
        val betaHeaders = mutableListOf<String?>()
        val client = httpClient { request ->
            seenPaths += request.url.encodedPath
            seenBodies += request.bodyText()
            betaHeaders += request.headers["OpenAI-Beta"]
            when (request.url.encodedPath) {
                "/v1/vector_stores/vs_1/file_batches" ->
                    if (request.method == HttpMethod.Post) {
                        respondJson(
                            """{"id":"vsfb_1","object":"vector_store.file_batch","vector_store_id":"vs_1","status":"in_progress","chunking_strategy":{"type":"static","static":{"max_chunk_size_tokens":800,"chunk_overlap_tokens":400}}}""",
                        )
                    } else {
                        assertEquals("10", request.url.parameters["limit"])
                        respondJson(
                            """{"object":"list","data":[{"id":"vsfb_1","object":"vector_store.file_batch","status":"completed","chunking_strategy":{"type":"static","static":{"max_chunk_size_tokens":800,"chunk_overlap_tokens":400}}}],"has_more":false}""",
                        )
                    }
                "/v1/vector_stores/vs_1/file_batches/vsfb_1" ->
                    respondJson(
                        """{"id":"vsfb_1","object":"vector_store.file_batch","status":"completed","chunking_strategy":{"type":"static","static":{"max_chunk_size_tokens":800,"chunk_overlap_tokens":400}}}""",
                    )
                "/v1/vector_stores/vs_1/file_batches/vsfb_1/cancel" ->
                    respondJson(
                        """{"id":"vsfb_1","object":"vector_store.file_batch","status":"cancelled","chunking_strategy":{"type":"static","static":{"max_chunk_size_tokens":800,"chunk_overlap_tokens":400}}}""",
                    )
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val created =
            api.createVectorStoreFileBatch(
                VectorStoreId("vs_1"),
                VectorStoreFileBatchCreateRequest(
                    fileIds = listOf(FileId("file_1"), FileId("file_2")),
                    attributes = VectorStoreAttributes.of("team" to "search"),
                    chunkingStrategy =
                        VectorStoreChunkingStrategy.Static(
                            config = VectorStoreStaticChunking(maxChunkSizeTokens = 800, chunkOverlapTokens = 400),
                        ),
                ),
            )
        val fetched = api.getVectorStoreFileBatch(VectorStoreId("vs_1"), VectorStoreFileBatchId("vsfb_1"))
        val page = api.listVectorStoreFileBatches(VectorStoreId("vs_1"), VectorStoreFileBatchListQuery(limit = 10))
        val cancelled = api.cancelVectorStoreFileBatch(VectorStoreId("vs_1"), VectorStoreFileBatchId("vsfb_1"))

        assertEquals("vsfb_1", created.id)
        assertEquals("vsfb_1", fetched.id)
        assertEquals("vsfb_1", page.data.single().id)
        assertEquals(VectorStoreFileStatusValue.Cancelled, cancelled.status)
        assertEquals(800, created.chunkingStrategy?.staticConfig?.maxChunkSizeTokens)
        assertEquals(400, cancelled.chunkingStrategy?.staticConfig?.chunkOverlapTokens)
        assertTrue(seenBodies.first().contains("\"file_ids\":[\"file_1\",\"file_2\"]"))
        assertTrue(seenBodies.first().contains("\"team\":\"search\""))
        assertTrue(
            seenBodies.first().contains(
                "\"chunking_strategy\":{\"type\":\"static\",\"static\":{\"max_chunk_size_tokens\":800,\"chunk_overlap_tokens\":400}}",
            ),
        )
        assertEquals("{}", seenBodies.last())
        assertTrue(betaHeaders.all { it == "assistants=v2" })
    }

    @Test
    fun `vector store file requests encode primitive attributes and per-file batch payloads`() = runTest {
        val seenPaths = mutableListOf<String>()
        val seenBodies = mutableListOf<String>()
        val client = httpClient { request ->
            seenPaths += request.url.encodedPath
            seenBodies += request.bodyText()
            when (request.url.encodedPath) {
                "/v1/vector_stores/vs_1/files" ->
                    respondJson(
                        """{"id":"file_1","object":"vector_store.file","vector_store_id":"vs_1","status":"completed","attributes":{"team":"search","rank":7,"active":true}}""",
                    )
                "/v1/vector_stores/vs_1/files/file_1" ->
                    respondJson(
                        """{"id":"file_1","object":"vector_store.file","vector_store_id":"vs_1","status":"completed","attributes":{"team":"ml","priority":2,"indexed":false}}""",
                    )
                "/v1/vector_stores/vs_1/file_batches" ->
                    respondJson(
                        """{"id":"vsfb_1","object":"vector_store.file_batch","vector_store_id":"vs_1","status":"in_progress"}""",
                    )
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val created =
            api.createVectorStoreFile(
                VectorStoreId("vs_1"),
                VectorStoreFileCreateRequest(
                    fileId = FileId("file_1"),
                    attributes = VectorStoreAttributes.of("team" to "search", "rank" to 7, "active" to true),
                ),
            )
        val updated =
            api.updateVectorStoreFile(
                VectorStoreId("vs_1"),
                FileId("file_1"),
                VectorStoreFileUpdateRequest(
                    attributes = VectorStoreAttributes.of("team" to "ml", "priority" to 2, "indexed" to false),
                ),
            )
        val batch =
            api.createVectorStoreFileBatch(
                VectorStoreId("vs_1"),
                VectorStoreFileBatchCreateRequest(
                    files =
                        listOf(
                            VectorStoreFileBatchInput(
                                fileId = FileId("file_1"),
                                attributes = VectorStoreAttributes.of("category" to "finance", "priority" to 1),
                            ),
                            VectorStoreFileBatchInput(
                                fileId = FileId("file_2"),
                                attributes = VectorStoreAttributes.of("active" to true),
                                chunkingStrategy =
                                    VectorStoreChunkingStrategy.Static(
                                        config = VectorStoreStaticChunking(maxChunkSizeTokens = 800, chunkOverlapTokens = 400),
                                    ),
                            ),
                        ),
                ),
            )

        assertEquals("search", created.attributes?.get("team")?.jsonPrimitive?.content)
        assertEquals(7, created.attributes?.get("rank")?.jsonPrimitive?.intOrNull)
        assertTrue(created.attributes?.get("active")?.jsonPrimitive?.booleanOrNull == true)
        assertEquals(2, updated.attributes?.get("priority")?.jsonPrimitive?.intOrNull)
        assertTrue(updated.attributes?.get("indexed")?.jsonPrimitive?.booleanOrNull == false)
        assertEquals("vsfb_1", batch.id)
        assertEquals(
            """{"file_id":"file_1","attributes":{"team":"search","rank":7,"active":true}}""",
            seenBodies[0],
        )
        assertEquals(
            """{"attributes":{"team":"ml","priority":2,"indexed":false}}""",
            seenBodies[1],
        )
        assertEquals(
            """{"files":[{"file_id":"file_1","attributes":{"category":"finance","priority":1}},{"file_id":"file_2","attributes":{"active":true},"chunking_strategy":{"type":"static","static":{"max_chunk_size_tokens":800,"chunk_overlap_tokens":400}}}]}""",
            seenBodies[2],
        )
        assertEquals(
            listOf(
                "/v1/vector_stores/vs_1/files",
                "/v1/vector_stores/vs_1/files/file_1",
                "/v1/vector_stores/vs_1/file_batches",
            ),
            seenPaths,
        )
    }

    @Test
    fun `vector store file batch requests reject ambiguous file sources and overrides`() {
        assertFailsWith<IllegalArgumentException> {
            VectorStoreFileBatchCreateRequest()
        }
        assertFailsWith<IllegalArgumentException> {
            VectorStoreFileBatchCreateRequest(
                fileIds = listOf(FileId("file_1")),
                files = listOf(VectorStoreFileBatchInput(fileId = FileId("file_2"))),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            VectorStoreFileBatchCreateRequest(
                files = listOf(VectorStoreFileBatchInput(fileId = FileId("file_1"))),
                attributes = VectorStoreAttributes.of("team" to "search"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            VectorStoreFileBatchCreateRequest(
                files = listOf(VectorStoreFileBatchInput(fileId = FileId("file_1"))),
                chunkingStrategy = VectorStoreChunkingStrategy.Auto,
            )
        }
    }

    @Test
    fun `vector store file endpoints retrieve update delete and list batch files`() = runTest {
        val seenPaths = mutableListOf<String>()
        val seenBodies = mutableListOf<String>()
        val seenQueries = mutableListOf<String>()
        val betaHeaders = mutableListOf<String?>()
        val client = httpClient { request ->
            seenPaths += request.url.encodedPath
            seenBodies += request.bodyText()
            seenQueries += request.url.encodedQuery.orEmpty()
            betaHeaders += request.headers["OpenAI-Beta"]
            when (request.url.encodedPath) {
                "/v1/vector_stores/vs_1/files/file_1" ->
                    when (request.method) {
                        HttpMethod.Get ->
                            respondJson(
                                """{"id":"file_1","object":"vector_store.file","vector_store_id":"vs_1","status":"completed","attributes":{"team":"search"}}""",
                            )
                        HttpMethod.Post ->
                            respondJson(
                                """{"id":"file_1","object":"vector_store.file","vector_store_id":"vs_1","status":"completed","attributes":{"team":"ml"}}""",
                            )
                        HttpMethod.Delete ->
                            respondJson("""{"id":"file_1","object":"vector_store.file.deleted","deleted":true}""")
                        else -> error("unexpected method ${request.method}")
                    }
                "/v1/vector_stores/vs_1/file_batches/vsfb_1/files" ->
                    respondJson(
                        """{"object":"list","data":[{"id":"file_1","object":"vector_store.file","status":"completed"}],"has_more":false}""",
                    )
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val file = api.getVectorStoreFile(VectorStoreId("vs_1"), FileId("file_1"))
        val updated =
            api.updateVectorStoreFile(
                VectorStoreId("vs_1"),
                FileId("file_1"),
                VectorStoreFileUpdateRequest(
                    attributes = VectorStoreAttributes.of("team" to "ml"),
                ),
            )
        val deleted = api.deleteVectorStoreFile(VectorStoreId("vs_1"), FileId("file_1"))
        val batchFiles =
            api.listVectorStoreFileBatchFiles(
                VectorStoreId("vs_1"),
                VectorStoreFileBatchId("vsfb_1"),
                VectorStoreFileBatchListQuery(limit = 5, filter = VectorStoreFileStatus.COMPLETED),
            )

        assertEquals("file_1", file.id)
        assertEquals("ml", updated.attributes?.get("team")?.jsonPrimitive?.content)
        assertTrue(deleted.deleted)
        assertEquals("file_1", batchFiles.data.single().id)
        assertEquals("""{"attributes":{"team":"ml"}}""", seenBodies[1])
        assertTrue(seenQueries.last().contains("limit=5"))
        assertTrue(seenQueries.last().contains("filter=completed"))
        assertTrue(betaHeaders.all { it == "assistants=v2" })
        assertEquals(
            listOf(
                "/v1/vector_stores/vs_1/files/file_1",
                "/v1/vector_stores/vs_1/files/file_1",
                "/v1/vector_stores/vs_1/files/file_1",
                "/v1/vector_stores/vs_1/file_batches/vsfb_1/files",
            ),
            seenPaths,
        )
    }

    @Test
    fun `vector store file content endpoint retrieves parsed content`() = runTest {
        val betaHeaders = mutableListOf<String?>()
        val client = httpClient { request ->
            betaHeaders += request.headers["OpenAI-Beta"]
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/v1/vector_stores/vs_1/files/file_1/content", request.url.encodedPath)
            respondJson(
                """
                {
                  "file_id":"file_1",
                  "filename":"example.txt",
                  "attributes":{"team":"search"},
                  "content":[{"type":"text","text":"hello"}],
                  "object":"vector_store.file_content.page",
                  "has_more":false
                }
                """.trimIndent(),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val content = api.getVectorStoreFileContent(VectorStoreId("vs_1"), FileId("file_1"))

        assertEquals("file_1", content.fileId)
        assertEquals("example.txt", content.filename)
        assertEquals("hello", content.content.single().text)
        assertTrue(betaHeaders.all { it == "assistants=v2" })
    }

    @Test
    fun `vector store decode preserves unknown response-side status values`() {
        val store =
            OpenAIJson.decodeFromString<VectorStoreObject>(
                """
                {
                  "id":"vs_1",
                  "object":"vector_store",
                  "status":"warming"
                }
                """.trimIndent(),
            )
        val file =
            OpenAIJson.decodeFromString<VectorStoreFileObject>(
                """
                {
                  "id":"vsf_1",
                  "object":"vector_store.file",
                  "status":"queued_elsewhere",
                  "chunking_strategy":{"type":"vendor_chunking"}
                }
                """.trimIndent(),
            )

        assertEquals("warming", (store.status as VectorStoreStatus.Unknown).wireName)
        assertEquals("queued_elsewhere", (file.status as VectorStoreFileStatusValue.Unknown).wireName)
        assertEquals("vendor_chunking", (file.chunkingStrategy?.type as VectorStoreChunkingStrategyType.Unknown).wireName)
    }

    @Test
    fun `realtime bootstrap endpoints create typed sessions`() = runTest {
        val seenPaths = mutableListOf<String>()
        val seenBodies = mutableListOf<String>()
        val client = httpClient { request ->
            seenPaths += request.url.encodedPath
            seenBodies += request.bodyText()
            when (request.url.encodedPath) {
                "/v1/realtime/sessions" ->
                    respondJson(
                        """
                        {
                          "id":"sess_1",
                          "object":"realtime.session",
                          "model":"gpt-realtime-2025-08-25",
                          "modalities":["audio","text"],
                          "instructions":"You are a friendly assistant.",
                          "voice":"alloy",
                          "input_audio_format":"pcm16",
                          "output_audio_format":"pcm16",
                          "input_audio_transcription":{"model":"whisper-1"},
                          "turn_detection":null,
                          "tools":[],
                          "tool_choice":"none",
                          "temperature":0.7,
                          "max_response_output_tokens":200,
                          "speed":1.1,
                          "include":["item.input_audio_transcription.logprobs"],
                          "client_secret":{"value":"secret","expires_at":1711475000}
                        }
                        """.trimIndent(),
                    )
                "/v1/realtime/transcription_sessions" ->
                    respondJson(
                        """
                        {
                          "id":"sess_2",
                          "object":"realtime.session",
                          "type":"transcription",
                          "model":"gpt-4o-transcribe-diarize",
                          "modalities":["audio"],
                          "input_audio_format":"pcm16",
                          "input_audio_transcription":{"model":"gpt-4o-transcribe-diarize"},
                          "turn_detection":{"type":"server_vad","threshold":0.5,"prefix_padding_ms":300,"silence_duration_ms":500},
                          "include":["item.input_audio_transcription.confidence"],
                          "client_secret":{"value":"secret2","expires_at":1711475001}
                        }
                        """.trimIndent(),
                    )
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val session =
            api.createRealtimeSession(
                RealtimeSessionCreateRequest(
                    model = ModelId("gpt-4o-realtime-preview"),
                    outputModalities = listOf(RealtimeModality.TEXT, RealtimeModality.AUDIO),
                    instructions = "You are a friendly assistant.",
                    audio =
                        RealtimeAudioConfig(
                            input =
                                RealtimeAudioInputConfig(
                                    format = RealtimeAudioFormatConfig.PCM_24K,
                                    transcription = RealtimeInputTranscriptionConfig(model = ModelId("whisper-1")),
                                ),
                            output =
                                RealtimeAudioOutputConfig(
                                    format = RealtimeAudioFormatConfig.PCM_24K,
                                    voice = "alloy",
                                    speed = 1.1,
                                ),
                        ),
                    toolChoice = ResponseToolChoice.None,
                    temperature = 0.7,
                    maxResponseOutputTokens = kotlinx.serialization.json.JsonPrimitive(200),
                    include = listOf(RealtimeInclude.INPUT_AUDIO_TRANSCRIPTION_LOGPROBS),
                ),
            )
        val transcription =
            api.createRealtimeTranscriptionSession(
                RealtimeTranscriptionSessionCreateRequest(
                    model = ModelId("gpt-4o-transcribe-diarize"),
                    audio =
                        RealtimeAudioConfig(
                            input =
                                RealtimeAudioInputConfig(
                                    format = RealtimeAudioFormatConfig.PCM_24K,
                                    transcription = RealtimeInputTranscriptionConfig(model = ModelId("gpt-4o-transcribe-diarize")),
                                    turnDetection =
                                        RealtimeTurnDetection.ServerVad(
                                            threshold = 0.5,
                                            prefixPaddingMs = 300,
                                            silenceDurationMs = 500,
                                        ),
                                ),
                        ),
                ),
            )

        assertEquals("sess_1", session.id)
        assertEquals(listOf(RealtimeModalityValue.Audio, RealtimeModalityValue.Text), session.outputModalities)
        assertEquals(RealtimeIncludeValue.ItemInputAudioTranscriptionLogprobs, session.include?.single())
        assertEquals("secret", session.clientSecret?.value)
        assertEquals("sess_2", transcription.id)
        assertEquals(RealtimeSessionTypeValue.Transcription, transcription.type)
        assertEquals(listOf(RealtimeModalityValue.Audio), transcription.outputModalities)
        assertEquals(RealtimeIncludeValue.ItemInputAudioTranscriptionConfidence, transcription.include?.single())
        assertTrue(seenBodies[0].contains("\"modalities\":[\"text\",\"audio\"]"))
        assertTrue(seenBodies[0].contains("\"voice\":\"alloy\""))
        assertTrue(seenBodies[0].contains("\"input_audio_format\":\"pcm16\""))
        assertTrue(seenBodies[0].contains("\"output_audio_format\":\"pcm16\""))
        assertTrue(seenBodies[0].contains("\"input_audio_transcription\":{\"model\":\"whisper-1\"}"))
        assertTrue(seenBodies[0].contains("\"tool_choice\":\"none\""))
        assertTrue(seenBodies[0].contains("\"speed\":1.1"))
        assertTrue(seenBodies[1].contains("\"input_audio_format\":\"pcm16\""))
        assertTrue(seenBodies[1].contains("\"input_audio_transcription\":{\"model\":\"gpt-4o-transcribe-diarize\"}"))
        assertTrue(seenBodies[1].contains("\"turn_detection\":{\"type\":\"server_vad\",\"threshold\":0.5,\"prefix_padding_ms\":300,\"silence_duration_ms\":500}"))
        assertEquals(listOf("/v1/realtime/sessions", "/v1/realtime/transcription_sessions"), seenPaths)
    }

    @Test
    fun `realtime client secret endpoint uses ga session config and typed mcp tracing truncation fields`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/realtime/client_secrets", request.url.encodedPath)
            respondJson(
                """
                {
                  "value":"ek_123",
                  "expires_at":1756310470,
                  "session":{
                    "type":"realtime",
                    "object":"realtime.session",
                    "id":"sess_secret_1",
                    "model":"gpt-realtime",
                    "output_modalities":["audio"],
                    "instructions":"You are a friendly assistant.",
                    "tools":[
                      {
                        "type":"mcp",
                        "server_label":"knowledge",
                        "connector_id":"connector_dropbox",
                        "allowed_tools":{"read_only":true}
                      }
                    ],
                    "tool_choice":{"type":"mcp","server_label":"knowledge","name":"search"},
                    "max_output_tokens":"inf",
                    "tracing":{"workflow_name":"support","group_id":"grp_1","metadata":{"env":"test"}},
                    "truncation":{"type":"retention_ratio","retention_ratio":0.8,"token_limits":{"post_instructions":5000}},
                    "prompt":null,
                    "expires_at":0,
                    "audio":{
                      "input":{
                        "format":{"type":"audio/pcm","rate":24000},
                        "transcription":null,
                        "noise_reduction":null,
                        "turn_detection":{"type":"server_vad"}
                      },
                      "output":{
                        "format":{"type":"audio/pcm","rate":24000},
                        "voice":{"id":"voice_1234"},
                        "speed":1.0
                      }
                    },
                    "include":null
                  }
                }
                """.trimIndent(),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val secret =
            api.createRealtimeClientSecret(
                session =
                    RealtimeSessionConfig(
                        model = ModelId("gpt-realtime"),
                        outputModalities = listOf(RealtimeModality.AUDIO),
                        instructions = "You are a friendly assistant.",
                        audio =
                            RealtimeAudioConfig(
                                input = RealtimeAudioInputConfig(format = RealtimeAudioFormatConfig.PCM_24K),
                                output =
                                    RealtimeAudioOutputConfig(
                                        format = RealtimeAudioFormatConfig.PCM_24K,
                                        voice = SpeechVoice.Custom("voice_1234"),
                                    ),
                            ),
                        tools =
                            listOf(
                                ResponseTool.Mcp(
                                    serverLabel = "knowledge",
                                    connectorId = "connector_dropbox",
                                    allowedToolFilter = ResponseTool.McpAllowedTools.Filter(readOnly = true),
                                ),
                            ),
                        toolChoice = ResponseToolChoice.Mcp(serverLabel = "knowledge", name = "search"),
                        maxOutputTokens = kotlinx.serialization.json.JsonPrimitive(400),
                        tracing =
                            RealtimeTracingConfig.Configured(
                                workflowName = "support",
                                groupId = "grp_1",
                                metadata =
                                    buildJsonObject {
                                        put("env", "test")
                                    },
                            ),
                        truncation =
                            RealtimeTruncation.RetentionRatio(
                                retentionRatio = 0.8,
                                tokenLimits = RealtimeTruncation.TokenLimits(postInstructions = 5000),
                            ),
                    ),
                expiresAfter = RealtimeClientSecretExpiration(seconds = 600),
            )

        assertEquals("ek_123", secret.value)
        assertEquals("sess_secret_1", secret.session.id)
        assertEquals("inf", secret.session.maxOutputTokens?.jsonPrimitive?.content)
        val body = assertNotNull(seenRequest).bodyText()
        assertTrue(body.contains("\"expires_after\":{\"anchor\":\"created_at\",\"seconds\":600}"))
        assertTrue(body.contains("\"session\":{\"type\":\"realtime\""))
        assertTrue(body.contains("\"output_modalities\":[\"audio\"]"))
        assertTrue(body.contains("\"max_output_tokens\":400"))
        assertTrue(body.contains("\"voice\":{\"id\":\"voice_1234\"}"))
        assertTrue(body.contains("\"tool_choice\":{\"type\":\"mcp\",\"server_label\":\"knowledge\",\"name\":\"search\"}"))
        assertTrue(body.contains("\"tools\":[{\"type\":\"mcp\",\"server_label\":\"knowledge\",\"connector_id\":\"connector_dropbox\",\"allowed_tools\":{\"read_only\":true}}]"))
        assertTrue(body.contains("\"tracing\":{\"workflow_name\":\"support\",\"group_id\":\"grp_1\",\"metadata\":{\"env\":\"test\"}}"))
        assertTrue(body.contains("\"truncation\":{\"type\":\"retention_ratio\",\"retention_ratio\":0.8,\"token_limits\":{\"post_instructions\":5000}}"))
        assertTrue(!body.contains("\"temperature\""))
        assertTrue(body.contains("\"audio\":{\"input\":{\"format\":{\"type\":\"audio/pcm\",\"rate\":24000}}"))
    }

    @Test
    fun `realtime ga session config rejects dual text and audio outputs`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                RealtimeSessionConfig(
                    model = ModelId("gpt-realtime"),
                    outputModalities = listOf(RealtimeModality.TEXT, RealtimeModality.AUDIO),
                )
            }

        assertTrue(error.message.orEmpty().contains("text"))
        assertTrue(error.message.orEmpty().contains("audio"))
    }

    @Test
    fun `deprecated realtime transcription sessions use input audio noise reduction field name`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/realtime/transcription_sessions", request.url.encodedPath)
            respondJson(
                """
                {
                  "id":"sess_tx_1",
                  "object":"realtime.transcription_session",
                  "type":"transcription",
                  "modalities":["audio","text"],
                  "input_audio_format":"pcm16",
                  "input_audio_transcription":{"model":"gpt-4o-transcribe"},
                  "turn_detection":{"type":"server_vad","threshold":0.5},
                  "client_secret":null
                }
                """.trimIndent(),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        api.createRealtimeTranscriptionSession(
            RealtimeTranscriptionSessionCreateRequest(
                model = ModelId("gpt-4o-transcribe"),
                audio =
                    RealtimeAudioConfig(
                        input =
                            RealtimeAudioInputConfig(
                                format = RealtimeAudioFormatConfig.PCM_24K,
                                noiseReduction = RealtimeNoiseReduction.Enabled(RealtimeNoiseReductionType.NEAR_FIELD),
                                transcription = RealtimeInputTranscriptionConfig(model = ModelId("gpt-4o-transcribe")),
                            ),
                    ),
            ),
        )

        val body = assertNotNull(seenRequest).bodyText()
        assertTrue(body.contains("\"input_audio_noise_reduction\":{\"type\":\"near_field\"}"))
        assertTrue(!body.contains("\"noise_reduction\""))
    }

    @Test
    fun `realtime call control endpoints accept hangup refer and reject`() = runTest {
        val requestsByPath = linkedMapOf<String, HttpRequestData>()
        val client = httpClient { request ->
            requestsByPath[request.url.encodedPath] = request
            when (request.url.encodedPath) {
                "/v1/realtime/calls/call_1/accept",
                "/v1/realtime/calls/call_1/hangup",
                "/v1/realtime/calls/call_1/refer",
                "/v1/realtime/calls/call_1/reject",
                -> respond(
                    content = "",
                    status = HttpStatusCode.NoContent,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        api.acceptRealtimeCall(
            RealtimeCallId("call_1"),
            RealtimeCallAcceptRequest(
                session =
                    RealtimeSessionConfig(
                        model = ModelId("gpt-realtime"),
                        instructions = "Be helpful",
                        outputModalities = listOf(RealtimeModality.AUDIO),
                    ),
            ),
        )
        api.hangupRealtimeCall(RealtimeCallId("call_1"))
        api.referRealtimeCall(
            RealtimeCallId("call_1"),
            RealtimeCallReferRequest(targetUri = "tel:+14155550123"),
        )
        api.rejectRealtimeCall(
            RealtimeCallId("call_1"),
            RealtimeCallRejectRequest(statusCode = 486),
        )

        val acceptRequest = assertNotNull(requestsByPath["/v1/realtime/calls/call_1/accept"])
        val hangupRequest = assertNotNull(requestsByPath["/v1/realtime/calls/call_1/hangup"])
        val referRequest = assertNotNull(requestsByPath["/v1/realtime/calls/call_1/refer"])
        val rejectRequest = assertNotNull(requestsByPath["/v1/realtime/calls/call_1/reject"])

        assertEquals(HttpMethod.Post, acceptRequest.method)
        assertEquals(HttpMethod.Post, hangupRequest.method)
        assertEquals(HttpMethod.Post, referRequest.method)
        assertEquals(HttpMethod.Post, rejectRequest.method)

        val acceptBody = acceptRequest.bodyText()
        assertTrue(acceptBody.contains("\"type\":\"realtime\""))
        assertTrue(acceptBody.contains("\"model\":\"gpt-realtime\""))
        assertTrue(acceptBody.contains("\"instructions\":\"Be helpful\""))
        assertTrue(acceptBody.contains("\"output_modalities\":[\"audio\"]"))
        assertTrue(!acceptBody.contains("\"session\""))

        assertEquals("{}", hangupRequest.bodyText())
        assertEquals("{\"target_uri\":\"tel:+14155550123\"}", referRequest.bodyText())
        assertEquals("{\"status_code\":486}", rejectRequest.bodyText())
    }

    @Test
    fun `azure v1 gates allow files vector stores conversations and realtime client secrets`() = runTest {
        val seenPaths = mutableListOf<String>()
        val provider = OpenAIProvider.Azure(resourceName = "demo", apiVersion = "v1")
        val client = httpClient { request ->
            seenPaths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/openai/v1/files" ->
                    respondJson(
                        """
                        {"id":"file_1","object":"file","bytes":4,"created_at":1,"filename":"clip.wav","purpose":"assistants","status":"processed"}
                        """.trimIndent(),
                    )
                "/openai/v1/vector_stores" ->
                    respondJson(
                        """
                        {"id":"vs_1","object":"vector_store","name":"kb","created_at":1,"status":"completed","file_counts":{"in_progress":0,"completed":0,"failed":0,"cancelled":0,"total":0}}
                        """.trimIndent(),
                    )
                "/openai/v1/conversations/conv_1" ->
                    respondJson(
                        """
                        {"id":"conv_1","object":"conversation","created_at":1,"metadata":{}}
                        """.trimIndent(),
                    )
                "/openai/v1/realtime/client_secrets" ->
                    respondJson(
                        """
                        {"value":"ek_azure","expires_at":1756310470,"session":{"type":"realtime","object":"realtime.session","id":"sess_azure","model":"gpt-realtime","output_modalities":["audio"],"tools":[],"tool_choice":"auto","max_output_tokens":"inf"}}
                        """.trimIndent(),
                    )
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = provider,
                    apiKey = "azure-key",
                    baseUrl = provider.defaultBaseUrl,
                ),
            )

        val file =
            api.uploadFile(
                FileCreateRequest(
                    purpose = FilePurpose.ASSISTANTS,
                    file = BinaryUpload("clip.wav", "WAVE".encodeToByteArray(), "audio/wav"),
                ),
            )
        val vectorStore = api.createVectorStore(VectorStoreCreateRequest(name = "kb"))
        val conversation = api.getConversation(ConversationId("conv_1"))
        val secret = api.createRealtimeClientSecret(session = RealtimeSessionConfig(model = ModelId("gpt-realtime")))

        assertEquals("file_1", file.id)
        assertEquals("vs_1", vectorStore.id)
        assertEquals("conv_1", conversation.id)
        assertEquals("ek_azure", secret.value)
        assertEquals(
            listOf(
                "/openai/v1/files",
                "/openai/v1/vector_stores",
                "/openai/v1/conversations/conv_1",
                "/openai/v1/realtime/client_secrets",
            ),
            seenPaths,
        )
    }

    @Test
    fun `eval endpoints create list update and delete evals and runs`() = runTest {
        val seenPaths = mutableListOf<String>()
        val seenBodies = mutableListOf<String>()
        val client = httpClient { request ->
            seenPaths += request.url.encodedPath + if (request.url.parameters.isEmpty()) "" else "?${request.url.parameters.formUrlEncode()}"
            seenBodies += request.bodyText()
            when (request.url.encodedPath) {
                "/v1/evals" ->
                    if (request.method == HttpMethod.Post) {
                        respondJson("""{"id":"eval_1","object":"eval","name":"baseline"}""")
                    } else {
                        respondJson("""{"object":"list","data":[{"id":"eval_1","object":"eval","name":"baseline"}],"has_more":false}""")
                    }
                "/v1/evals/eval_1" ->
                    when (request.method) {
                        HttpMethod.Get -> respondJson("""{"id":"eval_1","object":"eval","name":"baseline"}""")
                        HttpMethod.Post -> respondJson("""{"id":"eval_1","object":"eval","name":"updated"}""")
                        HttpMethod.Delete -> respondJson("""{"id":"eval_1","object":"eval.deleted","deleted":true}""")
                        else -> error("unexpected method ${request.method}")
                    }
                "/v1/evals/eval_1/runs" ->
                    if (request.method == HttpMethod.Post) {
                        respondJson(
                            """
                            {
                              "id":"run_1",
                              "object":"eval.run",
                              "eval_id":"eval_1",
                              "status":"queued",
                              "data_source":{"type":"completions"},
                              "metadata":{"suite":"smoke"}
                            }
                            """.trimIndent(),
                        )
                    } else {
                        respondJson("""{"object":"list","data":[{"id":"run_1","object":"eval.run","status":"completed"}],"has_more":false}""")
                    }
                "/v1/evals/eval_1/runs/run_1" ->
                    when (request.method) {
                        HttpMethod.Get -> respondJson("""{"id":"run_1","object":"eval.run","status":"completed"}""")
                        HttpMethod.Post ->
                            if (request.bodyText() == "{}") {
                                respondJson("""{"id":"run_1","object":"eval.run","status":"canceled"}""")
                            } else {
                                respondJson("""{"id":"run_1","object":"eval.run","name":"rerun"}""")
                            }
                        HttpMethod.Delete -> respondJson("""{"id":"run_1","object":"eval.run.deleted","deleted":true}""")
                        else -> error("unexpected method ${request.method}")
                    }
                "/v1/evals/eval_1/runs/run_1/output_items" ->
                    respondJson("""{"object":"list","data":[{"id":"out_1","object":"eval.run.output_item","status":"pass"}],"has_more":false}""")
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val created =
            api.createEval(
                EvalCreateRequest(
                    name = "baseline",
                    dataSourceConfig =
                        buildJsonObject {
                            put("type", "stored_completions")
                            put(
                                "metadata",
                                buildJsonObject {
                                    put("usecase", "chatbot")
                                },
                            )
                        },
                    testingCriteria =
                        listOf(
                            buildJsonObject {
                                put("type", "label_model")
                                put("model", "o3-mini")
                                put(
                                    "input",
                                    kotlinx.serialization.json.JsonArray(
                                        listOf(
                                            buildJsonObject {
                                                put("role", "developer")
                                                put("content", "Classify sentiment")
                                            },
                                            buildJsonObject {
                                                put("role", "user")
                                                put("content", "Statement: {{item.input}}")
                                            },
                                        ),
                                    ),
                                )
                                put(
                                    "passing_labels",
                                    kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("positive"))),
                                )
                                put(
                                    "labels",
                                    kotlinx.serialization.json.JsonArray(
                                        listOf(
                                            JsonPrimitive("positive"),
                                            JsonPrimitive("neutral"),
                                            JsonPrimitive("negative"),
                                        ),
                                    ),
                                )
                                put("name", "sentiment")
                            },
                        ),
                    metadata = mapOf("suite" to "smoke"),
                ),
            )
        val fetched = api.getEval(EvalId("eval_1"))
        val page = api.listEvals(EvalListQuery(limit = 5))
        val updated = api.updateEval(EvalId("eval_1"), EvalUpdateRequest(name = "updated"))
        val run =
            api.createEvalRun(
                EvalId("eval_1"),
                EvalRunCreateRequest(
                    name = "smoke-run",
                    dataSource =
                        buildJsonObject {
                            put("type", "completions")
                            put(
                                "source",
                                buildJsonObject {
                                    put(
                                        "type",
                                        "file_content",
                                    )
                                    put(
                                        "content",
                                        kotlinx.serialization.json.JsonArray(
                                            listOf(
                                                buildJsonObject {
                                                    put(
                                                        "item",
                                                        buildJsonObject {
                                                            put("input", "Tech Company Launches Advanced Artificial Intelligence Platform")
                                                            put("ground_truth", "Technology")
                                                        },
                                                    )
                                                },
                                            ),
                                        ),
                                    )
                                },
                            )
                            put(
                                "input_messages",
                                buildJsonObject {
                                    put("type", "template")
                                    put(
                                        "template",
                                        kotlinx.serialization.json.JsonArray(
                                            listOf(
                                                buildJsonObject {
                                                    put("role", "developer")
                                                    put("content", "Categorize the headline")
                                                },
                                                buildJsonObject {
                                                    put("role", "user")
                                                    put("content", "{{item.input}}")
                                                },
                                            ),
                                        ),
                                    )
                                },
                            )
                            put("model", "gpt-4o-mini")
                        },
                    metadata = mapOf("suite" to "smoke"),
                ),
            )
        val fetchedRun = api.getEvalRun(EvalId("eval_1"), EvalRunId("run_1"))
        val runPage =
            api.listEvalRuns(
                EvalId("eval_1"),
                EvalRunListQuery(
                    limit = 5,
                    after = "run_0",
                    order = EvalListOrder.DESC,
                    status = EvalRunStatus.Completed,
                ),
            )
        val updatedRun = api.updateEvalRun(EvalId("eval_1"), EvalRunId("run_1"), EvalRunUpdateRequest(name = "rerun"))
        val outputs =
            api.listEvalRunOutputItems(
                EvalId("eval_1"),
                EvalRunId("run_1"),
                EvalRunOutputItemListQuery(
                    limit = 5,
                    after = "out_0",
                    order = EvalListOrder.DESC,
                    status = EvalRunOutputItemStatus.Pass,
                ),
            )
        val deletedRun = api.deleteEvalRun(EvalId("eval_1"), EvalRunId("run_1"))
        val deletedEval = api.deleteEval(EvalId("eval_1"))

        assertEquals("eval_1", created.id)
        assertEquals("eval_1", fetched.id)
        assertEquals("eval_1", page.data.single().id)
        assertEquals("updated", updated.name)
        assertEquals("run_1", run.id)
        assertEquals("run_1", fetchedRun.id)
        assertEquals("run_1", runPage.data.single().id)
        assertEquals(EvalRunStatus.Queued, run.status)
        assertEquals(EvalRunStatus.Completed, fetchedRun.status)
        assertEquals(EvalRunStatus.Completed, runPage.data.single().status)
        assertEquals("rerun", updatedRun.name)
        assertEquals("out_1", outputs.data.single().id)
        assertEquals(EvalRunOutputItemStatus.Pass, outputs.data.single().status)
        assertTrue(deletedRun.deleted)
        assertTrue(deletedEval.deleted)
        assertTrue(seenBodies.first().contains("\"name\":\"baseline\""))
        assertTrue(seenBodies.first().contains("\"data_source_config\""))
        assertTrue(seenBodies.first().contains("\"testing_criteria\""))
        assertTrue(seenBodies.first().contains("\"suite\":\"smoke\""))
        assertTrue(seenBodies[4].contains("\"data_source\""))
        assertTrue(seenBodies[4].contains("\"type\":\"completions\""))
        assertTrue(seenBodies[4].contains("\"model\":\"gpt-4o-mini\""))
        assertTrue(seenPaths.contains("/v1/evals/eval_1/runs?limit=5&order=desc&after=run_0&status=completed"))
        assertTrue(seenPaths.contains("/v1/evals/eval_1/runs/run_1/output_items?limit=5&order=desc&after=out_0&status=pass"))
    }

    @Test
    fun `eval run cancel and retrieve output item hit documented endpoints`() = runTest {
        val seenPaths = mutableListOf<String>()
        val client = httpClient { request ->
            seenPaths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/v1/evals/eval_1/runs/run_1" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("{}", request.bodyText())
                    respondJson("""{"id":"run_1","object":"eval.run","status":"canceled"}""")
                }
                "/v1/evals/eval_1/runs/run_1/output_items/out_1" -> {
                    assertEquals(HttpMethod.Get, request.method)
                    respondJson(DocumentedEvalRunOutputItemJson)
                }
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val cancelled = api.cancelEvalRun(EvalId("eval_1"), EvalRunId("run_1"))
        val output = api.getEvalRunOutputItem(EvalId("eval_1"), EvalRunId("run_1"), EvalRunOutputItemId("out_1"))

        assertEquals(EvalRunStatus.Canceled, cancelled.status)
        assertEquals("outputitem_67e5796c28e081909917bf79f6e6214d", output.id)
        assertEquals(EvalRunOutputItemStatus.Pass, output.status)
        assertEquals("eval_67abd54d9b0081909a86353f6fb9317a", output.evalId)
        assertEquals("evalrun_67abd54d60ec8190832b46859da808f7", output.runId)
        assertEquals(true, output.results.singleOrNull()?.passed)
        assertEquals("gpt-4o-mini-2024-07-18", output.sample?.model)
        assertEquals(
            listOf(
                "/v1/evals/eval_1/runs/run_1",
                "/v1/evals/eval_1/runs/run_1/output_items/out_1",
            ),
            seenPaths,
        )
    }

    @Test
    fun `eval run output item failed filter serializes as fail`() {
        val parameters =
            EvalRunOutputItemListQuery(
                limit = 5,
                order = EvalListOrder.DESC,
                status = EvalRunOutputItemStatus.Failed,
            ).toParameters()

        assertEquals(
            listOf(
                "limit" to "5",
                "order" to "desc",
                "status" to "fail",
            ),
            parameters,
        )
    }

    @Test
    fun `eval object decodes documented data source and testing criteria fields`() {
        val eval =
            OpenAIJson.decodeFromString<EvalObject>(
                DocumentedEvalObjectJson,
            )

        assertEquals("eval_67b7fa9a81a88190ab4aa417e397ea21", eval.id)
        assertEquals("stored_completions", eval.dataSourceConfig?.type)
        assertEquals("chatbot", eval.dataSourceConfig?.metadata?.get("usecase")?.jsonPrimitive?.content)
        assertEquals("object", eval.dataSourceConfig?.schema?.get("type")?.jsonPrimitive?.content)
        assertEquals("label_model", eval.testingCriteria.singleOrNull()?.type)
        assertEquals("o3-mini", eval.testingCriteria.singleOrNull()?.model)
        assertEquals("positive", eval.testingCriteria.singleOrNull()?.passingLabels?.singleOrNull())
        assertEquals("Sentiment", eval.name)
    }

    @Test
    fun `eval run object decodes documented result and data source fields`() {
        val run =
            OpenAIJson.decodeFromString<EvalRunObject>(
                DocumentedEvalRunObjectJson,
            )

        assertEquals("evalrun_67e57965b480819094274e3a32235e4c", run.id)
        assertEquals(EvalRunStatus.Queued, run.status)
        assertEquals("https://platform.openai.com/evaluations/eval_67e579652b548190aaa83ada4b125f47&run_id=evalrun_67e57965b480819094274e3a32235e4c", run.reportUrl)
        assertEquals("completions", run.dataSource?.type)
        assertEquals("file_content", run.dataSource?.source?.get("type")?.jsonPrimitive?.content)
        assertEquals("template", run.dataSource?.inputMessages?.get("type")?.jsonPrimitive?.content)
        assertEquals("gpt-4o-mini", run.dataSource?.model)
        assertEquals(0, run.resultCounts?.failed)
        assertEquals(0, run.resultCounts?.passed)
        assertNull(run.perModelUsage)
        assertNull(run.perTestingCriteriaResults)
    }

    @Test
    fun `eval run output item decodes documented sample and results fields`() {
        val outputItem =
            OpenAIJson.decodeFromString<EvalRunOutputItemObject>(
                DocumentedEvalRunOutputItemJson,
            )

        assertEquals("outputitem_67e5796c28e081909917bf79f6e6214d", outputItem.id)
        assertEquals(EvalRunOutputItemStatus.Pass, outputItem.status)
        assertEquals("grader", outputItem.results.singleOrNull()?.name)
        assertEquals(true, outputItem.results.singleOrNull()?.passed)
        assertEquals(1.0, outputItem.results.singleOrNull()?.score)
        assertEquals("gpt-4o-mini-2024-07-18", outputItem.sample?.model)
        assertEquals("user", outputItem.sample?.input?.singleOrNull()?.jsonObject?.get("role")?.jsonPrimitive?.content)
        assertEquals("assistant", outputItem.sample?.output?.singleOrNull()?.jsonObject?.get("role")?.jsonPrimitive?.content)
    }

    @Test
    fun `video endpoints create remix list retrieve delete and download`() = runTest {
        val seenPaths = mutableListOf<String>()
        val seenBodies = mutableListOf<String>()
        val client = httpClient { request ->
            seenPaths += request.url.encodedPath + if (request.url.parameters.isEmpty()) "" else "?${request.url.parameters.formUrlEncode()}"
            seenBodies += request.bodyText()
            when (request.url.encodedPath) {
                "/v1/videos" ->
                    if (request.method == HttpMethod.Post) {
                        respondJson(DocumentedVideoObjectJson)
                    } else {
                        respondJson(DocumentedVideoListPageJson)
                    }
                "/v1/videos/video_123/remix" ->
                    respondJson("""{"id":"vid_2","object":"video","status":"queued"}""")
                "/v1/videos/video_123" ->
                    when (request.method) {
                        HttpMethod.Get -> respondJson(DocumentedVideoRetrieveJson)
                        HttpMethod.Delete -> respondJson(DocumentedVideoDeleteJson)
                        else -> error("unexpected method ${request.method}")
                    }
                "/v1/videos/video_123/content" ->
                    respond(content = "VIDEO", status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "video/mp4"))
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val created = api.createVideo(VideoCreateRequest(model = null, prompt = "A red cube"))
        val remixed = api.remixVideo(VideoId("video_123"), VideoRemixRequest(prompt = "Make it blue"))
        val page = api.listVideos(VideoListQuery(limit = 5, order = VideoListOrder.DESC))
        val fetched = api.getVideo(VideoId("video_123"))
        val content = api.downloadVideoContent(VideoId("video_123"), VideoContentVariant.THUMBNAIL)
        val deleted = api.deleteVideo(VideoId("video_123"))

        assertEquals("video_123", created.id)
        assertEquals("vid_2", remixed.id)
        assertEquals("video_123", page.data.single().id)
        assertEquals("id", fetched.id)
        assertEquals(VideoStatus.Queued, created.status)
        assertEquals(VideoStatus.Queued, remixed.status)
        assertEquals(VideoStatus.Completed, page.data.single().status)
        assertEquals(VideoStatus.Queued, fetched.status)
        assertEquals("prompt", fetched.prompt)
        assertEquals("VIDEO", content.decodeToString())
        assertTrue(deleted.deleted)
        assertTrue(seenBodies.first().contains("\"prompt\":\"A red cube\""))
        assertTrue(seenBodies[1].contains("\"prompt\":\"Make it blue\""))
        assertFalse(seenBodies.first().contains("\"model\":"))
        assertTrue(seenPaths.contains("/v1/videos?limit=5&order=desc"))
        assertTrue(seenPaths.contains("/v1/videos/video_123/content?variant=thumbnail"))
    }

    @Test
    fun `downloadVideoContentTo streams binary payload with current variant query`() = runTest {
        val seenPaths = mutableListOf<String>()
        val client = httpClient { request ->
            seenPaths +=
                buildString {
                    append(request.url.encodedPath)
                    request.url.encodedQuery.takeIf { it.isNotEmpty() }?.let {
                        append('?')
                        append(it)
                    }
                }
            assertEquals(HttpMethod.Get, request.method)
            respond(
                content = "VIDEO-STREAM",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "video/mp4"),
            )
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val chunks = mutableListOf<ByteArray>()

        api.downloadVideoContentTo(VideoId("vid_1"), VideoContentVariant.THUMBNAIL) { chunk ->
            chunks += chunk
        }

        assertEquals(listOf("/v1/videos/vid_1/content?variant=thumbnail"), seenPaths)
        assertEquals("VIDEO-STREAM", chunks.concatByteArrays().decodeToString())
    }

    @Test
    fun `video request serializes current input reference size and seconds`() {
        val request =
            VideoCreateRequest(
                model = null,
                prompt = "A red cube",
                inputReference = VideoInputReference(fileId = FileId("file_img")),
                size = VideoSize.P720X1280,
                seconds = VideoSeconds.S8,
            )

        val json = request.toJson().toString()
        assertTrue(json.contains("\"input_reference\":{\"file_id\":\"file_img\"}"))
        assertTrue(json.contains("\"size\":\"720x1280\""))
        assertTrue(json.contains("\"seconds\":\"8\""))
        assertFalse(json.contains("\"model\":"))
        assertFalse(json.contains("\"reference_assets\":"))
    }

    @Test
    fun `video create rejects legacy reference assets and extension only durations`() {
        assertFailsWith<IllegalArgumentException> {
            VideoCreateRequest(
                model = null,
                prompt = "A red cube",
                referenceAssets = kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("legacy"))),
            )
        }

        assertFailsWith<IllegalArgumentException> {
            VideoCreateRequest(
                model = null,
                prompt = "A red cube",
                seconds = VideoSeconds.S16,
            )
        }
    }

    @Test
    fun `video remix rejects legacy reference assets`() {
        assertFailsWith<IllegalArgumentException> {
            VideoRemixRequest(
                prompt = "Make it blue",
                referenceAssets = kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("legacy"))),
            )
        }
    }

    @Test
    fun `video list query accepts zero limit and serializes order`() {
        val query = VideoListQuery(limit = 0, order = VideoListOrder.ASC)

        assertEquals(listOf("limit" to "0", "order" to "asc"), query.toParameters())
    }

    @Test
    fun `video object decodes current stable response fields`() {
        val video =
            OpenAIJson.decodeFromString<VideoObject>(
                DocumentedVideoObjectJson,
            )

        assertEquals("video_123", video.id)
        assertEquals("video", video.objectType)
        assertEquals(VideoStatus.Queued, video.status)
        assertEquals(1712697600L, video.createdAt)
        assertNull(video.completedAt)
        assertNull(video.expiresAt)
        assertEquals("sora-2", video.model)
        assertEquals(0, video.progress)
        assertNull(video.prompt)
        assertNull(video.remixedFromVideoId)
        assertEquals("8", video.seconds)
        assertEquals("1024x1792", video.size)
        assertEquals("standard", video.quality)
        assertNull(video.error)
    }

    @Test
    fun `video character edit and extend endpoints use current paths and payloads`() = runTest {
        val requestsByPath = mutableMapOf<String, HttpRequestData>()
        val client = httpClient { request ->
            requestsByPath[request.url.encodedPath] = request
            when (request.url.encodedPath) {
                "/v1/videos/characters" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    respondJson("""{"id":"char_1","created_at":1,"name":"Hero"}""")
                }
                "/v1/videos/characters/char_1" -> {
                    assertEquals(HttpMethod.Get, request.method)
                    respondJson("""{"id":"char_1","created_at":1,"name":"Hero"}""")
                }
                "/v1/videos/edits" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    respondJson("""{"id":"vid_edit","object":"video","status":"queued","size":"720x1280","seconds":"8"}""")
                }
                "/v1/videos/extensions" -> {
                    assertEquals(HttpMethod.Post, request.method)
                    respondJson("""{"id":"vid_ext","object":"video","status":"queued","size":"720x1280","seconds":"12"}""")
                }
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val createdCharacter =
            api.createVideoCharacter(
                VideoCharacterCreateRequest(
                    name = "Hero",
                    video = BinaryUpload("hero.mp4", "VIDEO".encodeToByteArray(), "video/mp4"),
                ),
            )
        val fetchedCharacter = api.getVideoCharacter(VideoCharacterId("char_1"))
        val edited =
            api.editVideo(
                VideoEditRequest(
                    prompt = "Make it blue",
                    video = VideoReference(VideoId("vid_1")),
                ),
            )
        val extended =
            api.extendVideo(
                VideoExtendRequest(
                    prompt = "Continue the shot",
                    video = VideoReference(VideoId("vid_1")),
                    seconds = VideoSeconds.S12,
                ),
            )

        assertEquals("char_1", createdCharacter.id)
        assertEquals("Hero", fetchedCharacter.name)
        assertEquals("vid_edit", edited.id)
        assertEquals("vid_ext", extended.id)
        val createCharacterRequest = assertNotNull(requestsByPath["/v1/videos/characters"])
        assertTrue((createCharacterRequest.body.contentType?.toString() ?: "").startsWith("multipart/form-data"))
        assertTrue(createCharacterRequest.bodyText().contains("Hero"))
        assertTrue(createCharacterRequest.bodyText().contains("hero.mp4"))
        assertTrue(assertNotNull(requestsByPath["/v1/videos/edits"]).bodyText().contains("\"video\":{\"id\":\"vid_1\"}"))
        assertTrue(assertNotNull(requestsByPath["/v1/videos/edits"]).bodyText().contains("\"prompt\":\"Make it blue\""))
        assertTrue(assertNotNull(requestsByPath["/v1/videos/extensions"]).bodyText().contains("\"seconds\":\"12\""))
        assertTrue(assertNotNull(requestsByPath["/v1/videos/extensions"]).bodyText().contains("\"video\":{\"id\":\"vid_1\"}"))
    }

    @Test
    fun `createVideoCharacter accepts streaming binary upload`() = runTest {
        var seenRequest: HttpRequestData? = null
        val client = httpClient { request ->
            seenRequest = request
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/v1/videos/characters", request.url.encodedPath)
            respondJson("""{"id":"char_stream","created_at":1,"name":"Hero"}""")
        }

        val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "secret"))
        val character =
            api.createVideoCharacter(
                StreamingVideoCharacterCreateRequest(
                    name = "Hero",
                    video =
                        StreamingBinaryUpload(
                            filename = "hero.mp4",
                            sizeBytes = 5,
                            contentType = "video/mp4",
                        ) { ByteReadChannel("VIDEO".encodeToByteArray()) },
                ),
            )

        assertEquals("char_stream", character.id)
        val request = assertNotNull(seenRequest)
        assertTrue((request.body.contentType?.toString() ?: "").startsWith("multipart/form-data"))
        val body = request.bodyText()
        assertTrue(body.contains("Hero"))
        assertTrue(body.contains("hero.mp4"))
        assertTrue(body.contains("VIDEO"))
    }

    @Test
    fun `xai batch endpoints create append list cancel and list results`() = runTest {
        val seenPaths = mutableListOf<String>()
        val seenBodies = mutableListOf<String>()
        val client = httpClient { request ->
            seenPaths += request.url.encodedPath + if (request.url.parameters.isEmpty()) "" else "?${request.url.parameters.formUrlEncode()}"
            seenBodies += request.bodyText()
            when (request.url.encodedPath) {
                "/v1/batches" ->
                    if (request.method == HttpMethod.Post) {
                        respondJson(
                            """{"batch_id":"xbatch_1","object":"batch","state":{"num_requests":1,"num_pending":1,"num_success":0,"num_error":0,"num_cancelled":0}}""",
                        )
                    } else {
                        respondJson(
                            """{"object":"list","batches":[{"batch_id":"xbatch_1","object":"batch","state":{"num_requests":1,"num_pending":0,"num_success":1,"num_error":0,"num_cancelled":0}}],"pagination_token":"token_2"}""",
                        )
                    }
                "/v1/batches/xbatch_1" ->
                    respondJson(
                        """{"batch_id":"xbatch_1","object":"batch","state":{"num_requests":1,"num_pending":0,"num_success":1,"num_error":0,"num_cancelled":0}}""",
                    )
                "/v1/batches/xbatch_1/requests" ->
                    if (request.method == HttpMethod.Post) {
                        respondJson(
                            """{"object":"list","batch_request_metadata":[{"id":"req_1","object":"batch.request","state":"pending"}],"pagination_token":"token_req_2"}""",
                        )
                    } else {
                        respondJson(
                            """{"object":"list","batch_request_metadata":[{"id":"req_1","object":"batch.request","state":"succeeded"}],"pagination_token":"token_req_3"}""",
                        )
                    }
                "/v1/batches/xbatch_1/results" ->
                    respondJson("""{"object":"list","results":[{"id":"res_1","object":"batch.result"}],"pagination_token":"token_res_2"}""")
                "/v1/batches/xbatch_1:cancel" ->
                    respondJson(
                        """{"batch_id":"xbatch_1","object":"batch","state":{"num_requests":1,"num_pending":0,"num_success":0,"num_error":0,"num_cancelled":1}}""",
                    )
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = OpenAIProvider.XAI,
                    apiKey = "xai-key",
                    baseUrl = OpenAIProvider.XAI.defaultBaseUrl,
                ),
            )

        val created = api.createXaiBatch(XAIBatchCreateRequest(name = "nightly"))
        val appended =
            api.addXaiBatchRequests(
                XAIBatchId("xbatch_1"),
                listOf(
                    XAIBatchRequestInput(
                        customId = "job-1",
                        url = "/v1/responses",
                        body = buildJsonObject { put("model", "grok-4-fast-non-reasoning") },
                    ),
                ),
            )
        val fetched = api.getXaiBatch(XAIBatchId("xbatch_1"))
        val page = api.listXaiBatches(XAIBatchListQuery(limit = 5, paginationToken = "token_1"))
        val requests = api.listXaiBatchRequests(XAIBatchId("xbatch_1"), XAIBatchRequestListQuery(limit = 5, paginationToken = "token_req_1"))
        val results = api.listXaiBatchResults(XAIBatchId("xbatch_1"), XAIBatchResultListQuery(limit = 5, paginationToken = "token_res_1"))
        val cancelled = api.cancelXaiBatch(XAIBatchId("xbatch_1"))

        assertEquals("xbatch_1", created.id)
        assertEquals("req_1", appended.data.single().id)
        assertEquals("xbatch_1", fetched.id)
        assertEquals("xbatch_1", page.data.single().id)
        assertEquals("req_1", requests.data.single().id)
        assertEquals("res_1", results.data.single().id)
        assertEquals(1, created.state?.numPending)
        assertEquals(1, fetched.state?.numSuccess)
        assertEquals("token_2", page.paginationToken)
        assertEquals(XAIBatchRequestState.Pending, appended.data.single().state)
        assertEquals(XAIBatchRequestState.Succeeded, requests.data.single().state)
        assertEquals("token_req_3", requests.paginationToken)
        assertEquals("token_res_2", results.paginationToken)
        assertEquals(1, cancelled.state?.numCancelled)
        assertTrue(seenBodies[1].contains("\"custom_id\":\"job-1\""))
        assertTrue(seenBodies[1].contains("\"url\":\"/v1/responses\""))
        assertTrue(seenPaths.contains("/v1/batches?limit=5&pagination_token=token_1"))
        assertTrue(seenPaths.contains("/v1/batches/xbatch_1/requests?limit=5&pagination_token=token_req_1"))
        assertTrue(seenPaths.contains("/v1/batches/xbatch_1/results?limit=5&pagination_token=token_res_1"))
    }

    @Test
    fun `xai speech and voices endpoints are exposed separately from OpenAI audio api`() = runTest {
        val seenPaths = mutableListOf<String>()
        val seenBodies = mutableListOf<String>()
        val client = httpClient { request ->
            seenPaths += request.url.encodedPath
            seenBodies += request.bodyText()
            when (request.url.encodedPath) {
                "/v1/tts" ->
                    respond(content = "VOICE", status = HttpStatusCode.OK, headers = headersOf(HttpHeaders.ContentType, "audio/mpeg"))
                "/v1/tts/voices" ->
                    respondJson("""{"object":"list","data":[{"id":"voice_1","object":"voice","name":"Ava"}],"has_more":false}""")
                "/v1/tts/voices/voice_1" ->
                    respondJson("""{"id":"voice_1","object":"voice","name":"Ava"}""")
                else -> error("unexpected path ${request.url.encodedPath}")
            }
        }

        val api =
            KtorOpenAIApi(
                client,
                OpenAIApi.Config(
                    provider = OpenAIProvider.XAI,
                    apiKey = "xai-key",
                    baseUrl = OpenAIProvider.XAI.defaultBaseUrl,
                ),
            )

        val audio =
            api.createXaiSpeech(
                XAISpeechRequest(
                    text = "hello",
                    voiceId = XAIVoiceId("voice_1"),
                    language = "en",
                ),
            )
        val voices = api.listXaiVoices()
        val voice = api.getXaiVoice(XAIVoiceId("voice_1"))

        assertEquals("VOICE", audio.decodeToString())
        assertEquals("voice_1", voices.data.single().id)
        assertEquals("Ava", voice.name)
        assertTrue(seenBodies.first().contains("\"voice_id\":\"voice_1\""))
        assertEquals(listOf("/v1/tts", "/v1/tts/voices", "/v1/tts/voices/voice_1"), seenPaths)
    }

    @Test
    fun `webhook verifier validates signed payload and decodes event`() = runTest {
        val payload =
            """
            {"id":"evt_1","object":"event","type":"response.completed","created_at":1711475054,"data":{"id":"resp_1"}}
            """.trimIndent().encodeToByteArray()
        val signature = signWebhook("plain-secret", payload, "wh_1", "1711475054")

        val event =
            OpenAIWebhookVerifier.verify(
                payload = payload,
                headers =
                    mapOf(
                        "webhook-id" to "wh_1",
                        "webhook-timestamp" to "1711475054",
                        "webhook-signature" to "v1,$signature",
                    ),
                secret = "plain-secret",
                nowEpochSeconds = 1711475055,
            )

        assertEquals("evt_1", event.id)
        assertEquals("response.completed", event.type)
    }

    @Test
    fun `speech audio chunks compare by content`() {
        val left = SpeechStreamEvent.AudioChunk("hello".encodeToByteArray())
        val right = SpeechStreamEvent.AudioChunk("hello".encodeToByteArray())

        assertEquals(left, right)
        assertEquals(left.hashCode(), right.hashCode())
    }
}

private fun httpClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
    HttpClient(MockEngine(handler)) {
        install(HttpTimeout)
    }

private fun singleImmediateRetryPolicy(): OpenAIRetryPolicy =
    RetryPolicy(Schedule.fixed(Duration.ZERO, times = 1)) { error, _ ->
        when (error) {
            is OpenAIApiError.Network -> RetryAction.Retry()
            is OpenAIApiError.Http -> if (error.status in 500..599) RetryAction.Retry() else RetryAction.Stop
            is OpenAIApiError.Api -> if (error.status in 500..599) RetryAction.Retry() else RetryAction.Stop
            is OpenAIApiError.Parse -> RetryAction.Stop
        }
    }

// Source fixture: OpenAI API reference "Create chat completion" response example, accessed 2026-04-05.
// https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create
private val DocumentedChatCompletionCreateExampleJson =
    """
    {
      "id": "chatcmpl-B9MBs8CjcvOU2jLn4n570S5qMJKcT",
      "object": "chat.completion",
      "created": 1741569952,
      "model": "gpt-5.4",
      "choices": [
        {
          "index": 0,
          "message": {
            "role": "assistant",
            "content": "Hello! How can I assist you today?",
            "refusal": null,
            "annotations": []
          },
          "logprobs": null,
          "finish_reason": "stop"
        }
      ],
      "usage": {
        "prompt_tokens": 19,
        "completion_tokens": 10,
        "total_tokens": 29,
        "prompt_tokens_details": {
          "cached_tokens": 0,
          "audio_tokens": 0
        },
        "completion_tokens_details": {
          "reasoning_tokens": 0,
          "audio_tokens": 0,
          "accepted_prediction_tokens": 0,
          "rejected_prediction_tokens": 0
        }
      },
      "service_tier": "default"
    }
    """.trimIndent()

// Source fixture: OpenAI API reference "Create eval" response example, accessed 2026-04-05.
// https://developers.openai.com/api/reference/resources/evals/methods/create
private val DocumentedEvalObjectJson =
    """
    {
      "object":"eval",
      "id":"eval_67b7fa9a81a88190ab4aa417e397ea21",
      "data_source_config":{
        "type":"stored_completions",
        "metadata":{"usecase":"chatbot"},
        "schema":{
          "type":"object",
          "properties":{"item":{"type":"object"},"sample":{"type":"object"}},
          "required":["item","sample"]
        }
      },
      "testing_criteria":[
        {
          "name":"Example label grader",
          "type":"label_model",
          "model":"o3-mini",
          "input":[
            {
              "type":"message",
              "role":"developer",
              "content":{
                "type":"input_text",
                "text":"Classify the sentiment of the following statement as one of positive, neutral, or negative"
              }
            },
            {
              "type":"message",
              "role":"user",
              "content":{
                "type":"input_text",
                "text":"Statement: {{item.input}}"
              }
            }
          ],
          "passing_labels":["positive"],
          "labels":["positive","neutral","negative"]
        }
      ],
      "name":"Sentiment",
      "created_at":1740110490,
      "metadata":{"description":"An eval for sentiment analysis"}
    }
    """.trimIndent()

// Source fixture: OpenAI API reference "Create eval run" response example, accessed 2026-04-05.
// https://developers.openai.com/api/reference/resources/evals/subresources/runs/methods/create
private val DocumentedEvalRunObjectJson =
    """
    {
      "object":"eval.run",
      "id":"evalrun_67e57965b480819094274e3a32235e4c",
      "eval_id":"eval_67e579652b548190aaa83ada4b125f47",
      "report_url":"https://platform.openai.com/evaluations/eval_67e579652b548190aaa83ada4b125f47&run_id=evalrun_67e57965b480819094274e3a32235e4c",
      "status":"queued",
      "model":"gpt-4o-mini",
      "name":"gpt-4o-mini",
      "created_at":1743092069,
      "result_counts":{"total":0,"errored":0,"failed":0,"passed":0},
      "per_model_usage":null,
      "per_testing_criteria_results":null,
      "data_source":{
        "type":"completions",
        "source":{
          "type":"file_content",
          "content":[{"item":{"input":"Tech Company Launches Advanced Artificial Intelligence Platform","ground_truth":"Technology"}}]
        },
        "input_messages":{
          "type":"template",
          "template":[
            {
              "type":"message",
              "role":"developer",
              "content":{"type":"input_text","text":"Categorize the headline"}
            }
          ]
        },
        "sampling_params":{"temperature":1,"max_completions_tokens":2048,"top_p":1,"seed":42},
        "model":"gpt-4o-mini"
      }
    }
    """.trimIndent()

// Source fixture: adapted from OpenAI API reference "Retrieve eval run output item", accessed 2026-04-05.
// https://developers.openai.com/api/reference/resources/evals/subresources/runs/subresources/output_items/methods/retrieve
private val DocumentedEvalRunOutputItemJson =
    """
    {
      "id":"outputitem_67e5796c28e081909917bf79f6e6214d",
      "object":"eval.run.output_item",
      "status":"pass",
      "eval_id":"eval_67abd54d9b0081909a86353f6fb9317a",
      "run_id":"evalrun_67abd54d60ec8190832b46859da808f7",
      "results":[{"name":"grader","passed":true,"score":1.0}],
      "sample":{
        "model":"gpt-4o-mini-2024-07-18",
        "input":[{"role":"user","content":"hello"}],
        "output":[{"role":"assistant","content":"hi"}]
      }
    }
    """.trimIndent()

// Source fixture: OpenAI API reference "Get chat completion" response example, accessed 2026-04-05.
// https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/retrieve
private val DocumentedStoredChatCompletionGetJson =
    """
    {
      "object": "chat.completion",
      "id": "chatcmpl-abc123",
      "model": "gpt-4o-2024-08-06",
      "created": 1738960610,
      "request_id": "req_ded8ab984ec4bf840f37566c1011c417",
      "tool_choice": null,
      "usage": {
        "total_tokens": 31,
        "completion_tokens": 18,
        "prompt_tokens": 13
      },
      "seed": 4944116822809979520,
      "top_p": 1.0,
      "temperature": 1.0,
      "presence_penalty": 0.0,
      "frequency_penalty": 0.0,
      "system_fingerprint": "fp_50cad350e4",
      "input_user": null,
      "service_tier": "default",
      "tools": null,
      "metadata": {},
      "choices": [
        {
          "index": 0,
          "message": {
            "content": "Mind of circuits hum,  \nLearning patterns in silence—  \nFuture's quiet spark.",
            "role": "assistant",
            "tool_calls": null,
            "function_call": null
          },
          "finish_reason": "stop",
          "logprobs": null
        }
      ],
      "response_format": null
    }
    """.trimIndent()

// Source fixture: OpenAI API reference "List chat completions" response example, accessed 2026-04-05.
// https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/list
private val DocumentedStoredChatCompletionListJson =
    """
    {
      "object": "list",
      "data": [
        {
          "id": "chatcmpl-AyPNinnUqUDYo9SAdA52NobMflmj2",
          "object": "chat.completion",
          "created": 1738960610,
          "model": "gpt-4o-2024-08-06",
          "metadata": {}
        }
      ],
      "first_id": "chatcmpl-AyPNinnUqUDYo9SAdA52NobMflmj2",
      "last_id": "chatcmpl-AyPNinnUqUDYo9SAdA52NobMflmj2",
      "has_more": false
    }
    """.trimIndent()

// Source fixture: OpenAI API reference "Update chat completion" response example, accessed 2026-04-05.
// https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/update
private val DocumentedStoredChatCompletionUpdateJson =
    """
    {
      "id": "chatcmpl-AyPNinnUqUDYo9SAdA52NobMflmj2",
      "object": "chat.completion",
      "created": 1738960610,
      "model": "gpt-4o-2024-08-06",
      "choices": [
        {
          "index": 0,
          "message": {
            "role": "assistant",
            "content": "Hello! How can I assist you today?"
          },
          "finish_reason": "stop"
        }
      ],
      "metadata": {
        "foo": "bar"
      }
    }
    """.trimIndent()

// Source fixture: OpenAI API reference "Get chat messages" response example, accessed 2026-04-05.
// https://developers.openai.com/api/reference/resources/chat/subresources/completions/subresources/messages/methods/list
private val DocumentedStoredChatCompletionMessagesJson =
    """
    {
      "object": "list",
      "data": [
        {
          "id": "chatcmpl-AyPNinnUqUDYo9SAdA52NobMflmj2-0",
          "role": "user",
          "content": "Hello!"
        }
      ],
      "first_id": "chatcmpl-AyPNinnUqUDYo9SAdA52NobMflmj2-0",
      "last_id": "chatcmpl-AyPNinnUqUDYo9SAdA52NobMflmj2-0",
      "has_more": false
    }
    """.trimIndent()

// Source fixture: OpenAI API reference "Delete chat completion" response example, accessed 2026-04-05.
// https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/delete
private val DocumentedStoredChatCompletionDeleteJson =
    """
    {
      "object": "chat.completion.deleted",
      "id": "chatcmpl-AyPNinnUqUDYo9SAdA52NobMflmj2",
      "deleted": true
    }
    """.trimIndent()

// Source fixture: OpenAI API reference "Create video" response example, accessed 2026-04-05.
// https://developers.openai.com/api/reference/resources/videos/methods/create
private val DocumentedVideoObjectJson =
    """
    {
      "id": "video_123",
      "object": "video",
      "model": "sora-2",
      "status": "queued",
      "progress": 0,
      "created_at": 1712697600,
      "size": "1024x1792",
      "seconds": "8",
      "quality": "standard"
    }
    """.trimIndent()

// Source fixture: OpenAI API reference "Retrieve video" response example, accessed 2026-04-05.
// https://developers.openai.com/api/reference/resources/videos/methods/retrieve
private val DocumentedVideoRetrieveJson =
    """
    {
      "id": "id",
      "completed_at": 0,
      "created_at": 0,
      "error": {
        "code": "code",
        "message": "message"
      },
      "expires_at": 0,
      "model": "string",
      "object": "video",
      "progress": 0,
      "prompt": "prompt",
      "remixed_from_video_id": "remixed_from_video_id",
      "seconds": "seconds",
      "size": "720x1280",
      "status": "queued"
    }
    """.trimIndent()

// Source fixture: OpenAI API reference "List videos" response example, accessed 2026-04-05.
// https://developers.openai.com/api/reference/resources/videos/methods/list
private val DocumentedVideoListPageJson =
    """
    {
      "data": [
        {
          "id": "video_123",
          "object": "video",
          "model": "sora-2",
          "status": "completed"
        }
      ],
      "object": "list"
    }
    """.trimIndent()

// Source fixture: OpenAI API reference "Delete video" response example, accessed 2026-04-05.
// https://developers.openai.com/api/reference/resources/videos/methods/delete
private val DocumentedVideoDeleteJson =
    """
    {
      "id": "id",
      "deleted": true,
      "object": "video.deleted"
    }
    """.trimIndent()

private fun MockRequestHandleScope.respondJson(body: String) =
    respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

private suspend fun HttpRequestData.bodyBytes(): ByteArray =
    when (val value = body) {
        is OutgoingContent.ByteArrayContent -> value.bytes()
        is OutgoingContent.ReadChannelContent -> readAll(value.readFrom())
        is OutgoingContent.WriteChannelContent -> {
            val channel = ByteChannel()
            value.writeTo(channel)
            channel.close()
            readAll(channel)
        }
        else -> value.toString().encodeToByteArray()
    }

private suspend fun HttpRequestData.bodyText(): String =
    when (val value = body) {
        is OutgoingContent.ByteArrayContent -> value.bytes().decodeToString()
        else -> bodyBytes().decodeToString()
    }

private fun List<ByteArray>.concatByteArrays(): ByteArray {
    val total = sumOf { it.size }
    val result = ByteArray(total)
    var offset = 0
    forEach { chunk ->
        chunk.copyInto(result, destinationOffset = offset)
        offset += chunk.size
    }
    return result
}

private suspend fun readAll(channel: ByteReadChannel): ByteArray {
    val chunks = mutableListOf<ByteArray>()
    val buffer = ByteArray(4096)
    var total = 0
    while (!channel.isClosedForRead) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read < 0) break
        if (read == 0) continue
        chunks += buffer.copyOf(read)
        total += read
    }
    val result = ByteArray(total)
    var offset = 0
    chunks.forEach { chunk ->
        chunk.copyInto(result, destinationOffset = offset)
        offset += chunk.size
    }
    return result
}

private suspend fun failingChannel(
    prefix: String = "",
    message: String,
): ByteReadChannel =
    ByteChannel(autoFlush = true).also { channel ->
        if (prefix.isNotEmpty()) {
            channel.writeFully(prefix.encodeToByteArray())
        }
        channel.cancel(IllegalStateException(message))
    }

private suspend fun signWebhook(secret: String, payload: ByteArray, webhookId: String, webhookTimestamp: String): String {
    val hmac = CryptographyProvider.Default.get(HMAC)
    val key = hmac.keyDecoder(SHA256).decodeFromByteArray(HMAC.Key.Format.RAW, secret.encodeToByteArray())
    val signature =
        key
            .signatureGenerator()
            .generateSignature("$webhookId.$webhookTimestamp.${payload.decodeToString()}".encodeToByteArray())
    return Base64.Default.encode(signature)
}

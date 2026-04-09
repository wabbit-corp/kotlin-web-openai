// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.web.openai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadmeExamplesTest {
    @Test
    fun `README minimal example returns typed response output`() = runTest {
        val api =
            KtorOpenAIApi(
                readmeHttpClient {
                    respondJson(
                        """
                        {
                          "id": "resp_readme_1",
                          "object": "response",
                          "status": "completed",
                          "output": [
                            {
                              "id": "msg_1",
                              "type": "message",
                              "role": "assistant",
                              "content": [
                                {"type": "output_text", "text": "Hello from kotlin-web-openai."}
                              ]
                            }
                          ]
                        }
                        """.trimIndent(),
                    )
                },
                config = OpenAIApi.Config(apiKey = "secret"),
            )

        assertEquals(
            "Hello from kotlin-web-openai.",
            readmeMinimalExample(api),
        )
    }

    @Test
    fun `README streaming example collects streamed output`() = runTest {
        val api =
            KtorOpenAIApi(
                readmeHttpClient {
                    respond(
                        content =
                            """
                            data: {"type":"response.created","response":{"id":"resp_readme_stream","object":"response","status":"in_progress","output":[]}}

                            data: {"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"Streamed "}

                            data: {"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"answer"}

                            data: {"type":"response.completed","response":{"id":"resp_readme_stream","object":"response","status":"completed","output":[]}}

                            data: [DONE]
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                    )
                },
                config = OpenAIApi.Config(apiKey = "secret"),
            )

        assertEquals(
            "Streamed answer",
            readmeStreamingExample(api),
        )
    }
}

private suspend fun readmeMinimalExample(api: OpenAIApi): String {
    val response =
        api.createResponse(
            ResponseCreateRequest(
                model = ModelId("gpt-5.4-mini"),
                input = ResponseInput.Text("Say hello in one short sentence."),
            ),
        )

    return response.outputText()
}

private suspend fun readmeStreamingExample(api: OpenAIApi): String {
    val assembly =
        api.streamResponse(
            ResponseCreateRequest(
                model = ModelId("gpt-5.4-mini"),
                input = ResponseInput.Text("Summarize this build status in one sentence."),
            ),
        ).collectResponseStream()

    return assembly.outputText
}

private fun readmeHttpClient(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HttpClient =
    HttpClient(MockEngine(handler)) {
        install(HttpTimeout)
    }

private fun MockRequestHandleScope.respondJson(body: String) =
    respond(
        content = body,
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
    )

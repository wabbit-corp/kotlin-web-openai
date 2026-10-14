// SPDX-License-Identifier: LicenseRef-Wabbit-Public-Test-License

package one.wabbit.web.openai

import java.io.IOException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Containment translates ordinary transport/wire failures, never coroutine or JVM abort signals. */
class OpenAIResponseFailureBoundaryTest {
    @Test
    fun directAndWrappedCancellationEscapeThePostBoundaryUnchanged() = runTest {
        for (wrapped in listOf(false, true)) {
            val cancellation = CancellationException("Synthetic cancelled request")
            val thrown = if (wrapped) IOException("Transport wrapper", cancellation) else cancellation
            failingClient(thrown).use { client ->
                val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "test-key", retryPolicy = null))
                assertPreservedSignal(cancellation, assertFailsWith<CancellationException> { api.createResponse(request()) })
            }
        }
    }

    @Test
    fun directAndWrappedFatalSignalsEscapeThePostBoundaryUnchanged() = runTest {
        for (wrapped in listOf(false, true)) {
            val fatal = OutOfMemoryError("Synthetic VM failure; no allocation pressure")
            val thrown = if (wrapped) IOException("Transport wrapper", fatal) else fatal
            failingClient(thrown).use { client ->
                val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "test-key", retryPolicy = null))
                assertPreservedSignal(fatal, assertFailsWith<OutOfMemoryError> { api.createResponse(request()) })
            }
        }
    }

    @Test
    fun interruptionEscapesThePostBoundaryAndRestoresTheThreadFlag() = runTest {
        val interruption = InterruptedException("Synthetic interrupted request")
        try {
            failingClient(IOException("Transport wrapper", interruption)).use { client ->
                val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "test-key", retryPolicy = null))
                assertSame(interruption, assertFailsWith<InterruptedException> { api.createResponse(request()) })
                assertTrue(Thread.currentThread().isInterrupted)
            }
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun anOrdinaryFailureCannotHideTheOwningJobsCancellation() = runTest {
        val observed = CompletableDeferred<Throwable>()
        val cancellation = CancellationException("Owner cancelled during response metadata handling")
        successfulClient("""{"id":"resp_1","status":"completed","output":[]}""").use { client ->
            supervisorScope {
                val operation = async {
                    val owner = currentCoroutineContext().job
                    val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "test-key", retryPolicy = null, onResponseMetadata = {
                        owner.cancel(cancellation)
                        throw IOException("Transport completion raced cancellation")
                    }))
                    try {
                        api.createResponse(request())
                    } catch (failure: Throwable) {
                        observed.complete(failure)
                        throw failure
                    }
                }
                assertFailsWith<CancellationException> { operation.await() }
                assertSame(cancellation, observed.await())
            }
        }
    }

    @Test
    fun ordinaryIoFailureIsTypedNetworkFailureWithoutImplicitRetry() = runTest {
        val transport = IOException("Synthetic connection reset")
        var attempts = 0
        HttpClient(MockEngine { attempts++; throw transport }) { install(HttpTimeout) }.use { client ->
            val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "test-key"))
            val failure = assertFailsWith<OpenAIApiError.Network> { api.createResponse(request()) }
            assertPreservedSignal(transport, requireNotNull(failure.cause))
            assertEquals(1, attempts)
        }
    }

    @Test
    fun malformedWireJsonRemainsATypedParseFailure() = runTest {
        successfulClient("{not valid JSON").use { client ->
            val api = KtorOpenAIApi(client, OpenAIApi.Config(apiKey = "test-key", retryPolicy = null))
            val failure = assertFailsWith<OpenAIApiError.Parse> { api.createResponse(request()) }
            assertTrue(failure.cause is kotlinx.serialization.SerializationException)
        }
    }

    private fun request() = ResponseCreateRequest(ModelId("gpt-4.1-mini"), ResponseInput.Text("Synthetic request"))

    private fun assertPreservedSignal(expected: Throwable, actual: Throwable) {
        assertEquals(expected::class, actual::class)
        assertEquals(expected.message, actual.message)
        assertTrue(
            generateSequence(actual) { it.cause }.take(32).any { it === expected },
            "Coroutine stack-trace recovery may copy a signal, but must retain the original in its cause chain.",
        )
    }

    private fun failingClient(failure: Throwable) = HttpClient(MockEngine { throw failure }) { install(HttpTimeout) }

    private fun successfulClient(body: String) = HttpClient(MockEngine {
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
    }) { install(HttpTimeout) }
}

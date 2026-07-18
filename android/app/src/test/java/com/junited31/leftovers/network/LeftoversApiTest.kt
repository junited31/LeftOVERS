package com.junited31.leftovers.network

import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.tasks.TaskCompletionSource
import com.junited31.leftovers.photo.PhotoLifecycle
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class LeftoversApiTest {
    private lateinit var server: MockWebServer
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val lifecycle = PhotoLifecycle(context)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun success_when_json_request_is_authenticated() {
        // Given
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true}"))
        val api = api()

        // When
        val result = api.executeJson("/v1/recipes/generate", "{\"pantry\":[]}")

        // Then
        assertEquals(ApiResult.Success("{\"ok\":true}"), result)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertEquals("/v1/recipes/generate", request.path)
        assertEquals("{\"pantry\":[]}", request.body.readUtf8())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun typed_error_when_http_status_is_known() {
        // Given
        val fixtures = listOf(
            401 to ApiResult.Unauthorized,
            413 to ApiResult.PayloadTooLarge,
            422 to ApiResult.InvalidRequest,
            429 to ApiResult.QuotaLimited(37),
            502 to ApiResult.UpstreamUnavailable,
        )
        fixtures.forEach { (status, _) ->
            server.enqueue(
                MockResponse().setResponseCode(status).setHeader("Retry-After", "37")
                    .setBody("{\"error\":{\"code\":\"typed\"}}"),
            )
        }
        val api = api()

        // When / Then
        fixtures.forEach { (_, expected) ->
            assertEquals(expected, api.executeJson("/fixture", "{}"))
        }
        assertEquals(fixtures.size, server.requestCount)
    }

    @Test
    fun cancel_aborts_one_upload_and_never_retries() {
        // Given
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        val source = managedPhoto(ByteArray(1024))
        val call = api().newPhotoAdviceCall("{\"step\":\"one\"}", source)
        val finished = CountDownLatch(1)
        var result: ApiResult? = null
        val worker = Thread {
            result = call.execute()
            finished.countDown()
        }

        // When
        worker.start()
        assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)
        call.cancel()

        // Then
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        assertEquals(ApiResult.Cancelled, result)
        assertEquals(1, server.requestCount)
        assertFalse(source.file.exists())
    }

    @Test
    fun no_retry_when_server_disconnects() {
        // Given
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))

        // When
        val result = api().executeJson("/fixture", "{}")

        // Then
        assertTrue(result is ApiResult.NetworkFailure)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun error_response_deletes_transient_upload() {
        // Given
        server.enqueue(MockResponse().setResponseCode(502).setBody("{}"))
        val source = managedPhoto(ByteArray(32))

        // When
        val result = api().newPhotoAdviceCall("{}", source).execute()

        // Then
        assertEquals(ApiResult.UpstreamUnavailable, result)
        assertFalse(source.file.exists())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun oversized_json_and_photo_context_fail_before_auth_or_network() {
        // Given
        val tokenCalls = AtomicInteger()
        val api = LeftoversApi(
            server.url("/").toString(),
            TokenProvider {
                tokenCalls.incrementAndGet()
                "test-token"
            },
        )
        val oversized = "a".repeat(LeftoversApi.MAX_REQUEST_BYTES + 1)
        val photo = managedPhoto(ByteArray(32))

        // When
        val jsonResult = api.executeJson("/fixture", oversized)
        val photoResult = api.newPhotoAdviceCall(oversized, photo).execute()

        // Then
        assertEquals(ApiResult.PayloadTooLarge, jsonResult)
        assertEquals(ApiResult.PayloadTooLarge, photoResult)
        assertEquals(0, tokenCalls.get())
        assertEquals(0, server.requestCount)
        assertFalse(photo.file.exists())
    }

    @Test
    fun one_photo_call_executes_once_when_two_threads_race() {
        // Given
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val tokenEntered = CountDownLatch(1)
        val releaseToken = CountDownLatch(1)
        val api = LeftoversApi(
            server.url("/").toString(),
            TokenProvider {
                tokenEntered.countDown()
                check(releaseToken.await(2, TimeUnit.SECONDS))
                "test-token"
            },
        )
        val call = api.newPhotoAdviceCall("{}", managedPhoto(ByteArray(32)))
        var first: ApiResult? = null
        val worker = Thread { first = call.execute() }
        worker.start()
        assertTrue(tokenEntered.await(2, TimeUnit.SECONDS))

        // When
        val second = call.execute()
        releaseToken.countDown()
        worker.join(2_000)

        // Then
        assertEquals(ApiResult.AlreadyExecuted, second)
        assertTrue(first is ApiResult.Success)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun firebase_task_wait_times_out_with_typed_failure() {
        // Given
        val waiter = BoundedTaskWaiter(25, TimeUnit.MILLISECONDS)
        val task = TaskCompletionSource<String>().task
        val executor = Executors.newSingleThreadExecutor()

        // When
        val started = System.nanoTime()
        val failure = executor.submit<Throwable?> {
            runCatching { waiter.await(task) }.exceptionOrNull()
        }.get(1, TimeUnit.SECONDS)
        executor.shutdownNow()

        // Then
        assertTrue(failure is TokenUnavailableException)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
    }

    private fun api(): LeftoversApi = LeftoversApi(
        baseUrl = server.url("/").toString(),
        tokenProvider = TokenProvider { "test-token" },
    )

    private fun managedPhoto(bytes: ByteArray): PhotoLifecycle.ManagedPhoto =
        lifecycle.createManagedPhoto().also { it.file.writeBytes(bytes) }
}

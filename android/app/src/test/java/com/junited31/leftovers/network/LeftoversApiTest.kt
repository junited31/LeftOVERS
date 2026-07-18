package com.junited31.leftovers.network

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LeftoversApiTest {
    private lateinit var server: MockWebServer

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
        val source = File.createTempFile("leftovers-photo-", ".jpg").apply { writeBytes(ByteArray(1024)) }
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
        assertFalse(source.exists())
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
        val source = File.createTempFile("leftovers-photo-", ".jpg").apply { writeBytes(ByteArray(32)) }

        // When
        val result = api().newPhotoAdviceCall("{}", source).execute()

        // Then
        assertEquals(ApiResult.UpstreamUnavailable, result)
        assertFalse(source.exists())
        assertEquals(1, server.requestCount)
    }

    private fun api(): LeftoversApi = LeftoversApi(
        baseUrl = server.url("/").toString(),
        tokenProvider = TokenProvider { "test-token" },
    )
}

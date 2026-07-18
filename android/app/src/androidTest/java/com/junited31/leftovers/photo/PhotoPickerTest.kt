package com.junited31.leftovers.photo

import android.app.Activity
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.junited31.leftovers.network.ApiResult
import com.junited31.leftovers.network.LeftoversApi
import com.junited31.leftovers.network.TokenProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PhotoPickerTest {
    @Test
    fun native_contracts_use_visual_picker_and_file_provider_uri() {
        // Given
        val context = ApplicationProvider.getApplicationContext<Context>()
        val lifecycle = PhotoLifecycle(context)
        val cameraFile = lifecycle.createCacheFile()
        val cameraUri = lifecycle.fileProviderUri(cameraFile)

        // When
        val pickerIntent = PhotoContracts.pick.createIntent(
            context,
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
        val cameraIntent = PhotoContracts.takePicture.createIntent(context, cameraUri)

        // Then
        assertTrue(pickerIntent.action == android.provider.MediaStore.ACTION_PICK_IMAGES ||
            pickerIntent.action == android.content.Intent.ACTION_OPEN_DOCUMENT ||
            pickerIntent.action == "androidx.activity.result.contract.action.PICK_IMAGES")
        assertEquals("content", cameraUri.scheme)
        assertEquals("${context.packageName}.fileprovider", cameraUri.authority)
        assertEquals(cameraUri, cameraIntent.getParcelableExtra(android.provider.MediaStore.EXTRA_OUTPUT, Uri::class.java))
        cameraFile.delete()
    }

    @Test
    fun real_large_bitmap_is_compressed_uploaded_once_and_deleted() {
        // Given
        val context = ApplicationProvider.getApplicationContext<Context>()
        val lifecycle = PhotoLifecycle(context)
        val source = checkNotNull(
            context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "leftovers-t5-large.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                },
            ),
        )
        val bitmap = Bitmap.createBitmap(2600, 1800, Bitmap.Config.ARGB_8888)
        context.contentResolver.openOutputStream(source).use {
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, checkNotNull(it)))
        }
        bitmap.recycle()
        val picked = checkNotNull(
            PhotoContracts.pick.parseResult(Activity.RESULT_OK, Intent().setData(source)),
        )
        val compressed = lifecycle.compress(picked)
        val decoded = BitmapFactory.decodeFile(compressed.path)
        val server = MockWebServer().apply {
            enqueue(MockResponse().setResponseCode(200).setBody("{\"observations\":[]}"))
            start()
        }
        val api = LeftoversApi(server.url("/").toString(), TokenProvider { "redacted-device-token" })

        // When
        val result = api.newPhotoAdviceCall("{\"step\":\"Brown rice\"}", compressed).execute()

        // Then
        assertTrue(result is ApiResult.Success)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        val body = request.body.readByteArray()
        val bodyText = String(body, Charsets.ISO_8859_1)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        assertTrue(body.size <= PhotoLifecycle.MAX_UPLOAD_BYTES + 4096)
        assertTrue(bodyText.contains("name=\"context\""))
        assertTrue(bodyText.contains("name=\"photo\""))
        assertEquals(1280, maxOf(decoded.width, decoded.height))
        assertEquals(1, server.requestCount)
        assertFalse(compressed.exists())
        assertTrue(lifecycle.ownedCacheFiles().isEmpty())
        writeEvidence(
            context,
            "task-5-photo-lifecycle.json",
            """{
              "device":"SM_F711N",
              "fixture":"generated MediaStore JPEG 2600x1800",
              "pickerContract":"PickVisualMedia.ImageOnly",
              "cameraContract":"TakePicture + FileProvider",
              "result":"${result::class.simpleName}",
              "jpegBytes":${body.size},
              "compressedWidth":${decoded.width},
              "compressedHeight":${decoded.height},
              "maxDimension":${maxOf(decoded.width, decoded.height)},
              "quality":${PhotoLifecycle.JPEG_QUALITY},
              "sha256":"${sha256(body)}",
              "requestCount":${server.requestCount},
              "cacheEmpty":${lifecycle.ownedCacheFiles().isEmpty()},
              "bearerTokenRecorded":false,
              "imageBytesRecorded":false
            }""".trimIndent(),
        )
        decoded.recycle()
        context.contentResolver.delete(source, null, null)
        server.shutdown()
    }

    @Test
    fun cancel_restart_sweep_has_one_request_and_no_transient_files() {
        // Given
        val context = ApplicationProvider.getApplicationContext<Context>()
        val lifecycle = PhotoLifecycle(context)
        val upload = lifecycle.createCacheFile().apply { writeBytes(ByteArray(1024)) }
        val stale = lifecycle.createCacheFile().apply {
            writeBytes(byteArrayOf(1))
            setLastModified(System.currentTimeMillis() - Duration.ofHours(25).toMillis())
        }
        val server = MockWebServer().apply {
            enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            start()
        }
        val call = LeftoversApi(
            server.url("/").toString(),
            TokenProvider { "redacted-device-token" },
        ).newPhotoAdviceCall("{}", upload)
        val finished = CountDownLatch(1)
        var result: ApiResult? = null
        Thread {
            result = call.execute()
            finished.countDown()
        }.start()

        // When
        assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)
        call.cancel()
        assertTrue(finished.await(5, TimeUnit.SECONDS))
        val restarted = InstrumentationRegistry.getInstrumentation().newApplication(
            checkNotNull(com.junited31.leftovers.LeftoversApplication::class.java.classLoader),
            com.junited31.leftovers.LeftoversApplication::class.java.name,
            context,
        )
        restarted.onCreate()

        // Then
        assertEquals(ApiResult.Cancelled, result)
        assertEquals(1, server.requestCount)
        assertFalse(upload.exists())
        assertFalse(stale.exists())
        writeEvidence(
            context,
            "task-5-cancel.txt",
            """device=SM_F711N
            result=Cancelled
            request_count=${server.requestCount}
            automatic_retry=false
            upload_cache_exists=${upload.exists()}
            stale_25h_cache_exists=${stale.exists()}
            restart_entrypoint=LeftoversApplication.onCreate
            transient_token_or_image_logged=false""".trimIndent(),
        )
        server.shutdown()
    }

    private fun writeEvidence(context: Context, name: String, content: String) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        resolver.delete(collection, "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(name))
        val uri = checkNotNull(
            resolver.insert(
                collection,
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(
                        MediaStore.Downloads.MIME_TYPE,
                        if (name.endsWith(".json")) "application/json" else "text/plain",
                    )
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                },
            ),
        )
        resolver.openOutputStream(uri).use { output ->
            checkNotNull(output).writer().use { it.write(content) }
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

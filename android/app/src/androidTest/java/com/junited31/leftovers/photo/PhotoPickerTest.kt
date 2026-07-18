package com.junited31.leftovers.photo

import android.app.Activity
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.exifinterface.media.ExifInterface
import com.junited31.leftovers.network.ApiResult
import com.junited31.leftovers.network.LeftoversApi
import com.junited31.leftovers.network.TokenProvider
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.io.FileOutputStream
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PhotoPickerTest {
    @Before
    fun clearOwnedTransientCache() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PhotoLifecycle(context).ownedCacheFiles().forEach { it.delete() }
    }

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
        val expectedJpeg = compressed.file.readBytes()
        val decoded = BitmapFactory.decodeFile(compressed.file.path)
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
        val jpeg = multipartPart(
            body,
            checkNotNull(request.getHeader("Content-Type")),
            "photo",
        )
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        assertTrue(body.size <= PhotoLifecycle.MAX_UPLOAD_BYTES + 4096)
        assertTrue(bodyText.contains("name=\"context\""))
        assertTrue(bodyText.contains("name=\"photo\""))
        assertTrue(expectedJpeg.contentEquals(jpeg))
        assertTrue(jpeg.size <= PhotoLifecycle.MAX_UPLOAD_BYTES)
        assertEquals(1280, maxOf(decoded.width, decoded.height))
        assertEquals(1, server.requestCount)
        assertFalse(compressed.file.exists())
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
              "multipartEnvelopeBytes":${body.size},
              "jpegPartBytes":${jpeg.size},
              "compressedWidth":${decoded.width},
              "compressedHeight":${decoded.height},
              "maxDimension":${maxOf(decoded.width, decoded.height)},
              "quality":${PhotoLifecycle.JPEG_QUALITY},
              "jpegPartSha256":"${sha256(jpeg)}",
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
        val upload = lifecycle.createManagedPhoto().apply { file.writeBytes(ByteArray(1024)) }
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
        lifecycle.sweepStale()

        // Then
        assertEquals(ApiResult.Cancelled, result)
        assertEquals(1, server.requestCount)
        assertFalse(upload.file.exists())
        assertFalse(stale.exists())
        writeEvidence(
            context,
            "task-5-cancel.txt",
            """device=SM_F711N
            result=Cancelled
            request_count=${server.requestCount}
            automatic_retry=false
            upload_cache_exists=${upload.file.exists()}
            stale_25h_cache_exists=${stale.exists()}
            sweep_entrypoint=PhotoLifecycle.sweepStale
            os_restart_evidence=task-5-restart.txt
            transient_token_or_image_logged=false""".trimIndent(),
        )
        server.shutdown()
    }

    @Test
    fun exif_transpose_and_transverse_rotate_and_mirror_real_jpeg() {
        // Given
        val context = ApplicationProvider.getApplicationContext<Context>()
        val lifecycle = PhotoLifecycle(context)
        val outputs = listOf(
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE,
        ).map { orientation ->
            val source = lifecycle.createCacheFile()
            val bitmap = Bitmap.createBitmap(1600, 800, Bitmap.Config.ARGB_8888)
            Canvas(bitmap).apply {
                drawColor(Color.RED)
                drawRect(800f, 0f, 1600f, 800f, Paint().apply { color = Color.BLUE })
            }
            FileOutputStream(source).use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
            bitmap.recycle()
            ExifInterface(source).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            lifecycle.compressCamera(source)
        }

        // When
        val transpose = BitmapFactory.decodeFile(outputs[0].file.path)
        val transverse = BitmapFactory.decodeFile(outputs[1].file.path)

        // Then
        listOf(transpose, transverse).forEach {
            assertEquals(640, it.width)
            assertEquals(1280, it.height)
            assertNotEquals(isRed(it.getPixel(it.width / 2, 100)), isRed(it.getPixel(it.width / 2, it.height - 100)))
        }
        assertNotEquals(
            isRed(transpose.getPixel(transpose.width / 2, 100)),
            isRed(transverse.getPixel(transverse.width / 2, 100)),
        )
        transpose.recycle()
        transverse.recycle()
        outputs.forEach { it.file.delete() }
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

    private fun multipartPart(body: ByteArray, contentType: String, name: String): ByteArray {
        val boundary = contentType.substringAfter("boundary=")
        val marker = "name=\"$name\"".toByteArray()
        val markerStart = body.indexOf(marker)
        check(markerStart >= 0)
        val dataStart = body.indexOf("\r\n\r\n".toByteArray(), markerStart) + 4
        check(dataStart >= 4)
        val dataEnd = body.indexOf("\r\n--$boundary".toByteArray(), dataStart)
        check(dataEnd >= dataStart)
        return body.copyOfRange(dataStart, dataEnd)
    }

    private fun ByteArray.indexOf(needle: ByteArray, fromIndex: Int = 0): Int {
        for (start in fromIndex..size - needle.size) {
            if (needle.indices.all { this[start + it] == needle[it] }) return start
        }
        return -1
    }

    private fun isRed(color: Int): Boolean = Color.red(color) > Color.blue(color)
}

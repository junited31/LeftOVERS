package com.junited31.leftovers.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class PhotoLifecycleTest {
    @Test
    fun compression_limits_dimensions_quality_and_size() {
        // Given
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "source-large.png")
        val bitmap = Bitmap.createBitmap(2400, 1600, Bitmap.Config.ARGB_8888)
        FileOutputStream(source).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        // When
        val compressed = PhotoLifecycle(context).compress(android.net.Uri.fromFile(source))

        // Then
        val decoded = BitmapFactory.decodeFile(compressed.path)
        assertEquals(1280, maxOf(decoded.width, decoded.height))
        assertTrue(compressed.length() in 1..PhotoLifecycle.MAX_UPLOAD_BYTES)
        assertTrue(compressed.name.startsWith(PhotoLifecycle.CACHE_PREFIX))
        assertTrue(compressed.readBytes().take(2) == listOf(0xFF.toByte(), 0xD8.toByte()))
        decoded.recycle()
        source.delete()
        compressed.delete()
    }

    @Test
    fun stale_sweep_deletes_only_owned_files_older_than_24_hours() {
        // Given
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val lifecycle = PhotoLifecycle(context)
        val now = 2_000_000_000_000L
        val stale = lifecycle.createCacheFile().apply {
            writeText("stale")
            setLastModified(now - Duration.ofHours(25).toMillis())
        }
        val fresh = lifecycle.createCacheFile().apply {
            writeText("fresh")
            setLastModified(now - Duration.ofHours(23).toMillis())
        }
        val unrelated = File(context.cacheDir, "keep-me").apply {
            writeText("keep")
            setLastModified(now - Duration.ofDays(7).toMillis())
        }

        // When
        val deleted = lifecycle.sweepStale(now)

        // Then
        assertEquals(1, deleted)
        assertFalse(stale.exists())
        assertTrue(fresh.exists())
        assertTrue(unrelated.exists())
        fresh.delete()
        unrelated.delete()
    }

    @Test
    fun camera_compression_applies_orientation_and_deletes_transient_input() {
        // Given
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val lifecycle = PhotoLifecycle(context)
        val cameraFile = lifecycle.createCacheFile()
        val bitmap = Bitmap.createBitmap(1600, 800, Bitmap.Config.ARGB_8888)
        FileOutputStream(cameraFile).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        ExifInterface(cameraFile).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        // When
        val compressed = lifecycle.compressCamera(cameraFile)

        // Then
        val decoded = BitmapFactory.decodeFile(compressed.path)
        assertEquals(640, decoded.width)
        assertEquals(1280, decoded.height)
        assertFalse(cameraFile.exists())
        decoded.recycle()
        compressed.delete()
    }

    @Test(expected = InvalidPhotoException::class)
    fun malformed_input_is_rejected_without_new_cache_artifact() {
        // Given
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val lifecycle = PhotoLifecycle(context)
        val source = File(context.cacheDir, "malformed.jpg").apply { writeBytes(byteArrayOf()) }
        val before = lifecycle.ownedCacheFiles().map(File::getName)

        // When
        try {
            lifecycle.compress(android.net.Uri.fromFile(source))
        } finally {
            // Then
            assertEquals(before, lifecycle.ownedCacheFiles().map(File::getName))
            source.delete()
        }
    }

    @Test(expected = PhotoTooLargeException::class)
    fun oversize_input_is_rejected_before_decode() {
        // Given
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "oversize.jpg")
        RandomAccessFile(source, "rw").use { it.setLength(PhotoLifecycle.MAX_SOURCE_BYTES + 1) }

        // When
        try {
            PhotoLifecycle(context).compress(android.net.Uri.fromFile(source))
        } finally {
            // Then
            source.delete()
        }
    }
}

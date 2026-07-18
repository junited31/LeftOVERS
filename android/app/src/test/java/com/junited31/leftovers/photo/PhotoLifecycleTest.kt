package com.junited31.leftovers.photo

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.content.pm.ProviderInfo
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.lang.reflect.Modifier
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
class PhotoLifecycleTest {
    @Test
    fun camera_compression_surface_never_deletes_foreign_file() {
        // Given
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val lifecycle = PhotoLifecycle(context)
        val foreign = File(context.cacheDir, "persistent-camera.jpg")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        FileOutputStream(foreign).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        val rawFileMethod = PhotoLifecycle::class.java.methods.singleOrNull {
            it.name == "compressCamera" && it.parameterTypes.contentEquals(arrayOf(File::class.java))
        }

        // When
        val output = rawFileMethod?.invoke(lifecycle, foreign) as? PhotoLifecycle.ManagedPhoto

        // Then
        assertTrue("foreign camera input must survive", foreign.exists())
        output?.file?.delete()
        foreign.delete()
    }

    @Test
    fun stale_sweep_preserves_old_foreign_file_inside_transient_directory() {
        // Given
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val lifecycle = PhotoLifecycle(context)
        val now = 2_000_000_000_000L
        val staleOwned = lifecycle.createManagedPhoto().file.apply {
            writeText("owned")
            setLastModified(now - Duration.ofHours(25).toMillis())
        }
        val staleForeign = File(staleOwned.parentFile, "foreign-camera.jpg").apply {
            writeText("foreign")
            setLastModified(now - Duration.ofDays(7).toMillis())
        }

        // When
        val deleted = lifecycle.sweepStale(now)

        // Then
        assertEquals(1, deleted)
        assertFalse(staleOwned.exists())
        assertTrue("foreign file inside transient directory must survive", staleForeign.exists())
        staleForeign.delete()
    }

    @Test
    fun ownership_capability_exposes_no_caller_construction_or_raw_file_camera_entrypoint() {
        // Given
        val constructors = PhotoLifecycle.ManagedPhoto::class.java.declaredConstructors
        val rawFileCameraMethods = PhotoLifecycle::class.java.methods.filter {
            it.name == "compressCamera" && it.parameterTypes.contentEquals(arrayOf(File::class.java))
        }

        // When / Then
        assertFalse(
            "ManagedPhoto must expose no public source constructor",
            constructors.any { Modifier.isPublic(it.modifiers) && !it.isSynthetic },
        )
        assertTrue("compressCamera(File) must not exist", rawFileCameraMethods.isEmpty())
    }

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
        val decoded = BitmapFactory.decodeFile(compressed.file.path)
        assertEquals(1280, maxOf(decoded.width, decoded.height))
        assertTrue(compressed.file.length() in 1..PhotoLifecycle.MAX_UPLOAD_BYTES)
        assertTrue(compressed.file.name.startsWith(PhotoLifecycle.CACHE_PREFIX))
        assertTrue(compressed.file.readBytes().take(2) == listOf(0xFF.toByte(), 0xD8.toByte()))
        decoded.recycle()
        source.delete()
        compressed.file.delete()
    }

    @Test
    fun stale_sweep_deletes_only_owned_files_older_than_24_hours() {
        // Given
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val lifecycle = PhotoLifecycle(context)
        val now = 2_000_000_000_000L
        val stale = lifecycle.createManagedPhoto().file.apply {
            writeText("stale")
            setLastModified(now - Duration.ofHours(25).toMillis())
        }
        val fresh = lifecycle.createManagedPhoto().file.apply {
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
        val cameraPhoto = lifecycle.createManagedPhoto()
        val cameraFile = cameraPhoto.file
        val bitmap = Bitmap.createBitmap(1600, 800, Bitmap.Config.ARGB_8888)
        FileOutputStream(cameraFile).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        ExifInterface(cameraFile).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        // When
        val compressed = lifecycle.compressCamera(cameraPhoto)

        // Then
        val decoded = BitmapFactory.decodeFile(compressed.file.path)
        assertEquals(640, decoded.width)
        assertEquals(1280, decoded.height)
        assertFalse(cameraFile.exists())
        decoded.recycle()
        compressed.file.delete()
    }

    @Test
    fun transpose_and_transverse_rotate_and_mirror_asymmetric_images() {
        // Given
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val lifecycle = PhotoLifecycle(context)
        val results = listOf(
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_TRANSVERSE,
        ).map { orientation ->
            val sourcePhoto = lifecycle.createManagedPhoto()
            val source = sourcePhoto.file
            val bitmap = Bitmap.createBitmap(1600, 800, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.RED)
                for (x in 800 until width) {
                    for (y in 0 until height) setPixel(x, y, Color.BLUE)
                }
            }
            FileOutputStream(source).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()
            ExifInterface(source).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
            lifecycle.compressCamera(sourcePhoto)
        }

        // When
        val transpose = BitmapFactory.decodeFile(results[0].file.path)
        val transverse = BitmapFactory.decodeFile(results[1].file.path)

        // Then
        listOf(transpose, transverse).forEach {
            assertEquals(640, it.width)
            assertEquals(1280, it.height)
        }
        transpose.recycle()
        transverse.recycle()
        results.forEach { it.file.delete() }
    }

    @Test(expected = PhotoTooLargeException::class)
    fun unknown_length_provider_is_streamed_with_hard_source_cap() {
        // Given
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val lifecycle = PhotoLifecycle(context)
        val source = File(context.cacheDir, "unknown-oversize.jpg")
        RandomAccessFile(source, "rw").use { it.setLength(PhotoLifecycle.MAX_SOURCE_BYTES + 1) }
        val provider = UnknownLengthProvider(source).apply {
            attachInfo(
                context,
                ProviderInfo().apply { authority = "leftovers-unknown" },
            )
        }
        ShadowContentResolver.registerProviderInternal("leftovers-unknown", provider)
        val before = lifecycle.ownedCacheFiles().map(File::getName)

        // When
        try {
            lifecycle.compress(Uri.parse("content://leftovers-unknown/photo"))
        } finally {
            // Then
            assertEquals(before, lifecycle.ownedCacheFiles().map(File::getName))
            source.delete()
        }
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

private class UnknownLengthProvider(private val source: File) : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(source, ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String = "image/jpeg"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}

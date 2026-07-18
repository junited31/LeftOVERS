package com.junited31.leftovers.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Duration

object PhotoContracts {
    val pick = ActivityResultContracts.PickVisualMedia()
    val takePicture = ActivityResultContracts.TakePicture()
}

class PhotoLifecycle(private val context: Context) {
    private val cacheDirectory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }

    class ManagedPhoto internal constructor(
        internal val file: File,
        private val cacheDirectory: File,
    ) {
        internal fun delete() {
            val ownedDirectory = cacheDirectory.canonicalFile
            val candidate = file.canonicalFile
            if (candidate.parentFile == ownedDirectory && candidate.name.startsWith(CACHE_PREFIX)) {
                candidate.delete()
            }
        }
    }

    fun createCacheFile(): File = File.createTempFile(CACHE_PREFIX, ".jpg", cacheDirectory)

    fun createManagedPhoto(): ManagedPhoto = ManagedPhoto(createCacheFile(), cacheDirectory)

    fun manage(file: File): ManagedPhoto? {
        val candidate = file.canonicalFile
        return if (
            candidate.parentFile == cacheDirectory.canonicalFile &&
            candidate.name.startsWith(CACHE_PREFIX)
        ) {
            ManagedPhoto(candidate, cacheDirectory)
        } else {
            null
        }
    }

    fun fileProviderUri(file: File): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )

    fun compressCamera(file: File): ManagedPhoto = try {
        compress(Uri.fromFile(file))
    } finally {
        file.delete()
    }

    fun compress(source: Uri): ManagedPhoto {
        val sourceLength = sourceLength(source)
        if (sourceLength < 0) return compressUnknownLength(source)
        if (sourceLength == 0L) throw InvalidPhotoException()
        if (sourceLength > MAX_SOURCE_BYTES) throw PhotoTooLargeException()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(source).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw InvalidPhotoException()
        val sampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        val decoded = open(source).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sampleSize })
        } ?: throw InvalidPhotoException()
        val oriented = orient(decoded, orientation(source))
        if (oriented !== decoded) decoded.recycle()
        val scaled = scale(oriented)
        if (scaled !== oriented) oriented.recycle()
        val output = createManagedPhoto()
        try {
            FileOutputStream(output.file).use {
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) {
                    throw InvalidPhotoException()
                }
            }
            if (output.file.length() !in 1..MAX_UPLOAD_BYTES) throw PhotoTooLargeException()
            return output
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            scaled.recycle()
        }
    }

    fun sweepStale(nowMillis: Long = System.currentTimeMillis()): Int {
        val cutoff = nowMillis - MAX_CACHE_AGE.toMillis()
        return ownedCacheFiles().count { it.lastModified() < cutoff && it.delete() }
    }

    fun ownedCacheFiles(): List<File> = cacheDirectory.listFiles()?.filter(File::isFile).orEmpty()

    private fun sourceLength(source: Uri): Long = when (source.scheme) {
        "file" -> source.path?.let(::File)?.length() ?: 0L
        else -> context.contentResolver.openAssetFileDescriptor(source, "r")?.use { it.length } ?: -1L
    }

    private fun compressUnknownLength(source: Uri): ManagedPhoto {
        val boundedCopy = createCacheFile()
        try {
            open(source).use { input ->
                FileOutputStream(boundedCopy).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_SOURCE_BYTES) throw PhotoTooLargeException()
                        output.write(buffer, 0, read)
                    }
                    if (total == 0L) throw InvalidPhotoException()
                }
            }
            return compress(Uri.fromFile(boundedCopy))
        } finally {
            boundedCopy.delete()
        }
    }

    private fun open(source: Uri) = context.contentResolver.openInputStream(source)
        ?: throw InvalidPhotoException()

    private fun orientation(source: Uri): Int = try {
        open(source).use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    } catch (_: IOException) {
        ExifInterface.ORIENTATION_NORMAL
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width, height) / sample > MAX_DIMENSION * 2) sample *= 2
        return sample
    }

    private fun orient(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    postRotate(90f)
                    postScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    postRotate(-90f)
                    postScale(-1f, 1f)
                }
            }
        }
        return if (matrix.isIdentity) bitmap else Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true,
        )
    }

    private fun scale(bitmap: Bitmap): Bitmap {
        val largest = maxOf(bitmap.width, bitmap.height)
        if (largest <= MAX_DIMENSION) return bitmap
        val scale = MAX_DIMENSION.toFloat() / largest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt(),
            (bitmap.height * scale).toInt(),
            true,
        )
    }

    companion object {
        const val MAX_DIMENSION = 1280
        const val JPEG_QUALITY = 80
        const val MAX_UPLOAD_BYTES = 8L * 1024 * 1024
        const val MAX_SOURCE_BYTES = 64L * 1024 * 1024
        const val CACHE_PREFIX = "leftovers-photo-"
        private const val CACHE_DIRECTORY = "leftovers-photos"
        private val MAX_CACHE_AGE = Duration.ofHours(24)
    }
}

class InvalidPhotoException : Exception()
class PhotoTooLargeException : Exception()

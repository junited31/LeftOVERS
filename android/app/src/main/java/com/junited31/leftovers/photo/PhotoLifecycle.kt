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
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object PhotoContracts {
    val pick = ActivityResultContracts.PickVisualMedia()
    val takePicture = ActivityResultContracts.TakePicture()
}

class PhotoLifecycle internal constructor(
    private val context: Context,
    private val deleteRetainedFile: (File) -> Boolean = File::delete,
    private val canonicalize: (File) -> File = { it.canonicalFile },
) {
    private val cacheDirectory = File(context.cacheDir, CACHE_DIRECTORY).apply { mkdirs() }
    private val finalPhotoDirectory = File(context.filesDir, FINAL_DIRECTORY).apply { mkdirs() }

    class ManagedPhoto private constructor(
        internal val file: File,
        private val cacheDirectory: File,
    ) {
        internal fun delete() {
            val ownedDirectory = cacheDirectory.canonicalFile
            val candidate = file.canonicalFile
            if (
                candidate.isFile &&
                candidate.parentFile == ownedDirectory &&
                candidate.name.startsWith(CACHE_PREFIX)
            ) {
                candidate.delete()
            }
        }

        internal companion object {
            fun create(lifecycle: PhotoLifecycle): ManagedPhoto = ManagedPhoto(
                File.createTempFile(CACHE_PREFIX, ".jpg", lifecycle.cacheDirectory),
                lifecycle.cacheDirectory,
            )

            fun restore(lifecycle: PhotoLifecycle, file: File): ManagedPhoto =
                ManagedPhoto(file, lifecycle.cacheDirectory)
        }
    }

    fun createManagedPhoto(): ManagedPhoto = ManagedPhoto.create(this)

    fun restoreManagedPhoto(path: String): ManagedPhoto? = ownedFile(File(path))?.let {
        ManagedPhoto.restore(this, it)
    }

    fun discard(photo: ManagedPhoto) = photo.delete()

    suspend fun <T> withRetainedOwnership(block: suspend () -> T): T =
        RETAINED_OWNERSHIP.withLock { block() }

    fun retainFinal(photo: ManagedPhoto, mealLogId: String): String {
        val directory = checkNotNull(ownedFinalDirectory()) { "Invalid final photo directory" }
        val destination = File(directory, "$FINAL_PREFIX${sha256(mealLogId)}.jpg")
        check(!isLink(destination) && !destination.exists()) {
            "Final photo already exists"
        }
        return try {
            photo.file.copyTo(destination)
            photo.delete()
            destination.absolutePath
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    fun discardRetained(path: String): Boolean = runCatching {
        ownedFinalFile(File(path))?.let(deleteRetainedFile) ?: false
    }.getOrDefault(false)

    fun retainedFinalPhotos(): List<File> = finalPhotoDirectory.listFiles()?.mapNotNull(::ownedFinalFile).orEmpty()

    fun retainedExists(path: String): Boolean = ownedFinalFile(File(path)) != null

    suspend fun reconcileRetained(loadReferencePaths: suspend () -> Collection<String>): Int =
        RETAINED_OWNERSHIP.withLock {
            reconcileRetainedPaths(loadReferencePaths())
        }

    private fun reconcileRetainedPaths(referencePaths: Collection<String>): Int {
        val references = referencePaths.mapNotNull { path ->
            runCatching { canonicalize(File(path)) }.getOrNull()
        }.toSet()
        val directory = ownedFinalDirectory() ?: return 0
        return directory.listFiles().orEmpty().count { file ->
            val candidate = runCatching { canonicalize(file) }.getOrNull()
            candidate != null &&
                !isLink(file) &&
                candidate.isFile &&
                candidate.parentFile == directory &&
                FINAL_NAME.matches(candidate.name) &&
                candidate !in references &&
                runCatching { deleteRetainedFile(candidate) }.getOrDefault(false)
        }
    }

    fun fileProviderUri(photo: ManagedPhoto): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        photo.file,
    )

    fun compressCamera(photo: ManagedPhoto): ManagedPhoto = try {
        compress(Uri.fromFile(photo.file))
    } finally {
        photo.delete()
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

    fun ownedCacheFiles(): List<File> = cacheDirectory.listFiles()?.mapNotNull(::ownedFile).orEmpty()

    private fun sourceLength(source: Uri): Long = when (source.scheme) {
        "file" -> source.path?.let(::File)?.length() ?: 0L
        else -> context.contentResolver.openAssetFileDescriptor(source, "r")?.use { it.length } ?: -1L
    }

    private fun compressUnknownLength(source: Uri): ManagedPhoto {
        val boundedCopy = createManagedPhoto()
        try {
            open(source).use { input ->
                FileOutputStream(boundedCopy.file).use { output ->
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
            return compress(Uri.fromFile(boundedCopy.file))
        } finally {
            boundedCopy.delete()
        }
    }

    private fun ownedFile(file: File): File? {
        val candidate = file.canonicalFile
        return candidate.takeIf {
            candidate.isFile &&
                candidate.parentFile == cacheDirectory.canonicalFile &&
                candidate.name.startsWith(CACHE_PREFIX)
        }
    }

    private fun ownedFinalFile(file: File): File? {
        val directory = ownedFinalDirectory() ?: return null
        val candidate = runCatching { canonicalize(file) }.getOrNull() ?: return null
        return candidate.takeIf {
            !isLink(file) &&
            candidate.isFile &&
                candidate.parentFile == directory &&
                FINAL_NAME.matches(candidate.name)
        }
    }

    private fun ownedFinalDirectory(): File? = runCatching {
        val filesRoot = canonicalize(context.filesDir)
        val expected = File(filesRoot, FINAL_DIRECTORY).absoluteFile
        val candidate = canonicalize(finalPhotoDirectory)
        candidate.takeIf {
            !isLink(finalPhotoDirectory) &&
                candidate == expected &&
                candidate.parentFile == filesRoot &&
                candidate.isDirectory
        }
    }.getOrNull()

    private fun isLink(file: File): Boolean = runCatching {
        Files.isSymbolicLink(file.toPath()) || Files.readAttributes(
            file.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        ).isOther
    }.getOrDefault(false)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

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
        private const val FINAL_DIRECTORY = "leftovers-final-photos"
        private const val FINAL_PREFIX = "leftovers-final-"
        private val FINAL_NAME = Regex("${FINAL_PREFIX}[0-9a-f]{64}\\.jpg")
        private val MAX_CACHE_AGE = Duration.ofHours(24)
        // ponytail: one in-process lock; use a DB-coordinated lease only if photos move cross-process.
        private val RETAINED_OWNERSHIP = Mutex()
    }
}

class InvalidPhotoException : Exception()
class PhotoTooLargeException : Exception()

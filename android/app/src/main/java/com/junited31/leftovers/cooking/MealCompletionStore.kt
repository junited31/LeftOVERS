package com.junited31.leftovers.cooking

import com.junited31.leftovers.data.CompleteCookSessionCommand
import com.junited31.leftovers.data.CompletionResult
import com.junited31.leftovers.data.InventoryCompletionDao
import com.junited31.leftovers.photo.PhotoLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class MealCompletionStore(
    private val completion: InventoryCompletionDao,
    private val photos: PhotoLifecycle,
) {
    suspend fun complete(
        command: CompleteCookSessionCommand,
        finalPhoto: PhotoLifecycle.ManagedPhoto?,
    ): CompletionResult = photos.withRetainedOwnership {
        currentCoroutineContext().ensureActive()
        try {
            withContext(NonCancellable) {
                var retainedPath: String? = null
                try {
                    retainedPath = finalPhoto?.let { photos.retainFinal(it, command.mealLogId) }
                    when (val result = completion.complete(
                        command.copy(feedback = command.feedback.copy(finalPhotoPath = retainedPath)),
                    )) {
                        is CompletionResult.Success -> {
                            retainedPath?.let { check(photos.retainedExists(it)) }
                            result.copy(retainedPhotoPath = retainedPath)
                        }
                        else -> {
                            retainedPath?.let(photos::discardRetained)
                            result
                        }
                    }
                } catch (error: Exception) {
                    retainedPath?.let(photos::discardRetained)
                        ?: runCatching { finalPhoto?.let(photos::discard) }
                    throw error
                }
            }
        } catch (cancelled: CancellationException) {
            runCatching { finalPhoto?.let(photos::discard) }
            throw cancelled
        }
    }
}

package com.junited31.leftovers.cooking

import com.junited31.leftovers.data.CompleteCookSessionCommand
import com.junited31.leftovers.data.CompletionResult
import com.junited31.leftovers.data.InventoryCompletionDao
import com.junited31.leftovers.photo.PhotoLifecycle

class MealCompletionStore(
    private val completion: InventoryCompletionDao,
    private val photos: PhotoLifecycle,
) {
    suspend fun complete(
        command: CompleteCookSessionCommand,
        finalPhoto: PhotoLifecycle.ManagedPhoto?,
    ): CompletionResult {
        var retainedPath: String? = null
        return try {
            retainedPath = finalPhoto?.let { photos.retainFinal(it, command.mealLogId) }
            val result = completion.complete(
                command.copy(feedback = command.feedback.copy(finalPhotoPath = retainedPath)),
            )
            if (result !is CompletionResult.Success && retainedPath != null) {
                photos.discardRetained(retainedPath)
            }
            result
        } catch (error: Exception) {
            if (retainedPath != null) photos.discardRetained(retainedPath)
            finalPhoto?.let(photos::discard)
            throw error
        }
    }
}

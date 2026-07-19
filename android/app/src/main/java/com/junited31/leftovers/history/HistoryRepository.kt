package com.junited31.leftovers.history

import com.junited31.leftovers.data.MealLogDao
import com.junited31.leftovers.data.MealLogEntity
import java.io.File

data class HistoryDetail(
    val mealLog: MealLogEntity,
    val availablePhotoPath: String?,
    val referencedPhotoMissing: Boolean,
)

class HistoryRepository(
    private val mealLogs: MealLogDao,
    private val photoExists: (String) -> Boolean = { File(it).isFile },
) {
    suspend fun timeline(): List<MealLogEntity> = mealLogs.latest()

    suspend fun detail(id: String): HistoryDetail? = mealLogs.get(id)?.let { log ->
        val path = log.recipeSnapshot.feedback.finalPhotoPath
        val available = path?.takeIf(photoExists)
        HistoryDetail(log, available, path != null && available == null)
    }
}

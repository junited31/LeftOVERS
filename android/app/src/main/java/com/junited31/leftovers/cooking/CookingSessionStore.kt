package com.junited31.leftovers.cooking

import com.junited31.leftovers.data.CookSessionDao
import com.junited31.leftovers.data.CookSessionEntity
import com.junited31.leftovers.data.RecipeSnapshotDao
import com.junited31.leftovers.data.RecipeSnapshotEntity

data class ActiveCookingSession(
    val session: CookSessionEntity,
    val recipe: RecipeSnapshotEntity,
)

class CookingSessionStore(
    private val snapshots: RecipeSnapshotDao,
    private val sessions: CookSessionDao,
) {
    suspend fun start(
        recipeSnapshotId: String,
        sessionId: String,
        nowEpochMillis: Long,
    ): ActiveCookingSession? {
        sessions.active()?.let { return load(it) }
        val recipe = snapshots.get(recipeSnapshotId) ?: return null
        val session = CookSessionEntity(sessionId, recipe.id, nowEpochMillis, 0, null)
        sessions.insert(session)
        return ActiveCookingSession(session, recipe)
    }

    suspend fun resume(): ActiveCookingSession? = sessions.active()?.let { load(it) }

    suspend fun next(sessionId: String): ActiveCookingSession? = move(sessionId, 1)

    suspend fun previous(sessionId: String): ActiveCookingSession? = move(sessionId, -1)

    private suspend fun move(sessionId: String, offset: Int): ActiveCookingSession? {
        val active = sessions.get(sessionId)?.let { load(it) } ?: return null
        val nextIndex = (active.session.currentStepIndex + offset)
            .coerceIn(0, active.recipe.steps.values.lastIndex)
        sessions.updateStep(sessionId, nextIndex)
        return active.copy(session = active.session.copy(currentStepIndex = nextIndex))
    }

    private suspend fun load(session: CookSessionEntity): ActiveCookingSession? =
        snapshots.get(session.recipeSnapshotId)?.let { ActiveCookingSession(session, it) }
}

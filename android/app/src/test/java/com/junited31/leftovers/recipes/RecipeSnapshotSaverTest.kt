package com.junited31.leftovers.recipes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.junited31.leftovers.data.LeftoversDatabase
import com.junited31.leftovers.data.PantryItemEntity
import com.junited31.leftovers.data.PantryItemId
import com.junited31.leftovers.data.PantryUnit
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecipeSnapshotSaverTest {
    private lateinit var database: LeftoversDatabase
    private val pantryId = requireNotNull(
        PantryItemId.parse("10000000-0000-4000-8000-000000000001"),
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            LeftoversDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun saves_immutable_snapshot_only_from_complete_valid_set() = runTest {
        // Given
        val pantry = listOf(PantryItemEntity(pantryId, "Rice", 500_000, PantryUnit.GRAM, null, 1))
        val valid = RecommendationResult.Valid(
            listOf(
                ranked("one", "A"),
                ranked("two", "B"),
                ranked("three", "C"),
            ),
        )
        val saver = RecipeSnapshotSaver(database.recipeSnapshotDao())

        // When
        val first = saver.save(valid, "one", 1_000)
        val duplicate = saver.save(valid, "one", 2_000)
        val invalid = saver.save(
            RecommendationResult.Invalid(RecommendationInvalidReason.CANDIDATE_COUNT),
            "two",
            3_000,
        )

        // Then
        assertTrue(first)
        assertFalse(duplicate)
        assertFalse(invalid)
        assertEquals(1, database.recipeSnapshotDao().count())
        val snapshot = database.recipeSnapshotDao().get("one")
        assertEquals("A", snapshot?.title)
        assertEquals(1_000L, snapshot?.createdAtEpochMillis)
        assertEquals(pantry.single().id, snapshot?.pantryBindings?.values?.single()?.pantryItemId)
    }

    private fun ranked(id: String, title: String): RankedRecommendation {
        val candidate = RecommendationCandidate(
            title,
            if (id == "two") "Japanese" else "Korean",
            if (id == "three") "bake" else "stir fry",
            listOf("basic cookware"),
            listOf(ProposedPantryUse(pantryId, 1, PantryUnit.GRAM, 100_000)),
            emptyList(),
            listOf("Cook"),
        )
        return RankedRecommendation(id, candidate, 1.0, 1.0, 1.0, 1.0, 1.0)
    }
}

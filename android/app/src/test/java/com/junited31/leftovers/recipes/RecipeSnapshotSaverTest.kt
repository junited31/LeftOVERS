package com.junited31.leftovers.recipes

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.junited31.leftovers.data.LeftoversDatabase
import com.junited31.leftovers.data.PantryItemEntity
import com.junited31.leftovers.data.PantryItemId
import com.junited31.leftovers.data.PantryUnit
import com.junited31.leftovers.data.RecipePreferenceMetadata
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
        val first = saver.save(valid, "one", pantry.associateBy { it.id }, 1_000)
        val duplicate = saver.save(valid, "one", pantry.associateBy { it.id }, 2_000)
        val invalid = saver.save(
            RecommendationResult.Invalid(RecommendationInvalidReason.CANDIDATE_COUNT),
            "two",
            pantry.associateBy { it.id },
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
        assertEquals(
            RecipePreferenceMetadata("Korean", "stir fry", setOf("Rice")),
            snapshot?.steps?.metadata,
        )
        val savedSteps = requireNotNull(snapshot).steps
        assertEquals(
            "DRINK",
            requireNotNull(savedSteps.javaClass.getMethod("getRecipeKind").invoke(savedSteps)).toString(),
        )
    }

    private fun ranked(id: String, title: String): RankedRecommendation {
        val candidate = candidateWithKind(id, title)
        return RankedRecommendation(id, candidate, 1.0, 1.0, 1.0, 1.0, 1.0)
    }

    private fun candidateWithKind(id: String, title: String): RecommendationCandidate = try {
        val kindClass = Class.forName("com.junited31.leftovers.data.RecipeKind")
        val drink = requireNotNull(kindClass.enumConstants).single { (it as Enum<*>).name == "DRINK" }
        RecommendationCandidate::class.java.constructors.single { it.parameterCount == 8 }.newInstance(
            title,
            if (id == "two") "Japanese" else "Korean",
            if (id == "three") "bake" else "stir fry",
            listOf("basic cookware"),
            listOf(ProposedPantryUse(pantryId, 1, PantryUnit.GRAM, 100_000)),
            emptyList<MissingRecipeIngredient>(),
            listOf("Cook"),
            drink,
        ) as RecommendationCandidate
    } catch (error: Throwable) {
        throw AssertionError("RecommendationCandidate must require RecipeKind", error)
    }
}

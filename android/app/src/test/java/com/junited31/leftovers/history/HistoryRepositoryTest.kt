package com.junited31.leftovers.history

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.junited31.leftovers.data.ActualPantryUse
import com.junited31.leftovers.data.ActualPantryUses
import com.junited31.leftovers.data.JsonConverters
import com.junited31.leftovers.data.LeftoversDatabase
import com.junited31.leftovers.data.MealFeedback
import com.junited31.leftovers.data.MealLogEntity
import com.junited31.leftovers.data.MeasurementAdjustment
import com.junited31.leftovers.data.PantryBinding
import com.junited31.leftovers.data.PantryBindings
import com.junited31.leftovers.data.PantryItemId
import com.junited31.leftovers.data.PantryRemaining
import com.junited31.leftovers.data.PantryRemainingSnapshots
import com.junited31.leftovers.data.PantryUnit
import com.junited31.leftovers.data.RecipePreferenceMetadata
import com.junited31.leftovers.data.RecipeSnapshotRecord
import com.junited31.leftovers.data.RecipeSteps
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.json.JSONArray
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
class HistoryRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: LeftoversDatabase
    private val converters = JsonConverters()
    private lateinit var repository: HistoryRepository
    private val pantryId = requireNotNull(
        PantryItemId.parse("00000000-0000-4000-8000-000000000091"),
    )

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, LeftoversDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = HistoryRepository(database.mealLogDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun emptyMealLogTableReturnsEmptyHistory() = runTest {
        assertEquals(emptyList<MealLogEntity>(), repository.timeline())
        assertNull(repository.detail("missing"))
    }

    @Test
    fun completedAtDescendingUsesIdDescendingAsDeterministicTieBreak() = runTest {
        insertLog("00000000-0000-4000-8000-000000000091", 1_000)
        insertLog("00000000-0000-4000-8000-000000000092", 2_000)
        insertLog("00000000-0000-4000-8000-000000000093", 2_000)

        assertEquals(
            listOf(
                "00000000-0000-4000-8000-000000000093",
                "00000000-0000-4000-8000-000000000092",
                "00000000-0000-4000-8000-000000000091",
            ),
            repository.timeline().map { it.id },
        )
    }

    @Test
    fun legacySnapshotWithoutFeedbackUsesT8Defaults() {
        val legacy = """
            {
              "id":"recipe",
              "title":"볶음밥",
              "pantryBindings":[],
              "steps":["볶기"],
              "createdAtEpochMillis":1000
            }
        """.trimIndent()

        val snapshot = converters.jsonToRecipeSnapshot(legacy)
        assertEquals(MealFeedback(), snapshot.feedback)
        assertEquals("MEAL", recipeKind(snapshot.steps))
    }

    @Test
    fun legacyLocalArrayMissingKindDefaultsOnlyToMeal() {
        assertEquals("MEAL", recipeKind(converters.jsonToRecipeSteps("""["Cook"]""")))
    }

    @Test
    fun normalLocalJsonAndRepositoryHistoryRetainRequiredKind() = runTest {
        val steps = stepsWithKind(
            listOf("Mix", "Serve"),
            RecipePreferenceMetadata("Korean", "mix", setOf("Rice")),
            "DESSERT",
        )
        val stepsJson = converters.recipeStepsToJson(steps)
        assertEquals("dessert", JSONObject(stepsJson).getString("recipeKind"))
        assertEquals("DESSERT", recipeKind(converters.jsonToRecipeSteps(stepsJson)))

        val source = completeLog("kind", 2_500).let { log ->
            log.copy(recipeSnapshot = log.recipeSnapshot.copy(steps = steps))
        }
        val snapshotJson = converters.recipeSnapshotToJson(source.recipeSnapshot)
        assertEquals("dessert", JSONObject(snapshotJson).getString("recipeKind"))
        insertRawLog(source.id, source.completedAtEpochMillis, snapshotJson, source)

        assertEquals(
            "DESSERT",
            recipeKind(requireNotNull(repository.detail(source.id)).mealLog.recipeSnapshot.steps),
        )
    }

    @Test
    fun completeT8DetailMapsEveryPersistedFieldAndExistingPhoto() = runTest {
        val expected = completeLog("meal", 3_000)
        val photo = java.io.File(context.cacheDir, "history-existing-photo.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        insertLog(expected.copy(
            recipeSnapshot = expected.recipeSnapshot.copy(
                feedback = expected.recipeSnapshot.feedback.copy(finalPhotoPath = photo.absolutePath),
            ),
        ))

        val detail = requireNotNull(repository.detail("meal"))

        assertEquals(expected.recipeSnapshot.copy(
            feedback = expected.recipeSnapshot.feedback.copy(finalPhotoPath = photo.absolutePath),
        ), detail.mealLog.recipeSnapshot)
        assertEquals(expected.actualUses, detail.mealLog.actualUses)
        assertEquals(expected.remainingPantry, detail.mealLog.remainingPantry)
        assertEquals(expected.completedAtEpochMillis, detail.mealLog.completedAtEpochMillis)
        assertEquals(photo.absolutePath, detail.availablePhotoPath)
        assertFalse(detail.referencedPhotoMissing)
        photo.delete()
    }

    @Test
    fun persistedIngredientDisplayNameSurvivesRepositoryReload() = runTest {
        val log = completeLog("named", 3_500)
        val namedUsesJson = JSONArray(converters.actualUsesToJson(log.actualUses)).apply {
            getJSONObject(0).put("displayName", "김치")
        }.toString()
        insertRawLog(
            id = log.id,
            completedAt = log.completedAtEpochMillis,
            snapshotJson = converters.recipeSnapshotToJson(log.recipeSnapshot),
            source = log,
            actualUsesJson = namedUsesJson,
        )

        val detail = requireNotNull(repository.detail(log.id))
        val reserializedUses = JSONArray(converters.actualUsesToJson(detail.mealLog.actualUses))

        assertEquals("김치", reserializedUses.getJSONObject(0).opt("displayName") as? String)
    }

    @Test
    fun absentOrMalformedIngredientDisplayNameLoadsWithoutDatabaseMutation() = runTest {
        val legacy = completeLog("legacy-use-name", 3_600)
        val malformedUsesJson = JSONArray(converters.actualUsesToJson(legacy.actualUses)).apply {
            getJSONObject(0).put("displayName", JSONObject().put("unexpected", true))
        }.toString()
        insertRawLog(
            id = legacy.id,
            completedAt = legacy.completedAtEpochMillis,
            snapshotJson = converters.recipeSnapshotToJson(legacy.recipeSnapshot),
            source = legacy,
            actualUsesJson = malformedUsesJson,
        )
        val before = storedActualUses(legacy.id)

        val detail = requireNotNull(repository.detail(legacy.id))

        assertEquals(before, storedActualUses(legacy.id))
        assertEquals(
            null,
            JSONArray(converters.actualUsesToJson(detail.mealLog.actualUses))
                .getJSONObject(0).opt("displayName") as? String,
        )
    }

    @Test
    fun malformedOptionalFeedbackFallsBackWithoutCrashOrDatabaseMutation() = runTest {
        val malformedSnapshot = JSONObject(
            converters.recipeSnapshotToJson(completeLog("legacy", 4_000).recipeSnapshot),
        ).apply {
            getJSONObject("feedback").apply {
                put("rating", "broken")
                put("notes", 42)
                put("recommendAgain", JSONArray())
                put("measurementAdjustments", JSONArray().put(JSONObject().put("unit", "unknown")))
                put("finalPhotoPath", JSONObject())
            }
        }.toString()
        insertRawLog("legacy", 4_000, malformedSnapshot)

        val before = storedSnapshot("legacy")
        val detail = requireNotNull(repository.detail("legacy"))

        assertEquals(MealFeedback(), detail.mealLog.recipeSnapshot.feedback)
        assertNull(detail.availablePhotoPath)
        assertFalse(detail.referencedPhotoMissing)
        assertEquals(before, storedSnapshot("legacy"))
    }

    @Test
    fun externallyMissingPhotoShowsFallbackWithoutChangingStoredPath() = runTest {
        val log = completeLog("missing-photo", 5_000)
        insertLog(log)
        val before = storedSnapshot(log.id)

        val detail = requireNotNull(repository.detail(log.id))

        assertNull(detail.availablePhotoPath)
        assertTrue(detail.referencedPhotoMissing)
        assertEquals(log.recipeSnapshot.feedback.finalPhotoPath, detail.mealLog.recipeSnapshot.feedback.finalPhotoPath)
        assertEquals(before, storedSnapshot(log.id))
    }

    private fun insertLog(id: String, completedAt: Long) {
        insertLog(completeLog(id, completedAt))
    }

    private fun insertLog(log: MealLogEntity) {
        insertRawLog(log.id, log.completedAtEpochMillis, converters.recipeSnapshotToJson(log.recipeSnapshot), log)
    }

    private fun insertRawLog(
        id: String,
        completedAt: Long,
        snapshotJson: String,
        source: MealLogEntity = completeLog(id, completedAt),
        actualUsesJson: String = converters.actualUsesToJson(source.actualUses),
    ) {
        database.openHelper.writableDatabase.execSQL(
            """
                INSERT INTO meal_logs
                    (id, cookSessionId, recipeSnapshot, actualUses, remainingPantry, completedAtEpochMillis)
                VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf(
                id,
                source.cookSessionId,
                snapshotJson,
                actualUsesJson,
                converters.remainingToJson(source.remainingPantry),
                completedAt,
            ),
        )
    }

    private fun storedSnapshot(id: String): String = database.openHelper.readableDatabase.query(
        "SELECT recipeSnapshot FROM meal_logs WHERE id = ?",
        arrayOf(id),
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getString(0)
    }

    private fun storedActualUses(id: String): String = database.openHelper.readableDatabase.query(
        "SELECT actualUses FROM meal_logs WHERE id = ?",
        arrayOf(id),
    ).use { cursor ->
        check(cursor.moveToFirst())
        cursor.getString(0)
    }

    private fun stepsWithKind(
        values: List<String>,
        metadata: RecipePreferenceMetadata?,
        kind: String,
    ): RecipeSteps = try {
        val kindClass = Class.forName("com.junited31.leftovers.data.RecipeKind")
        val recipeKind = requireNotNull(kindClass.enumConstants).single { (it as Enum<*>).name == kind }
        RecipeSteps::class.java.constructors.single { it.parameterCount == 3 }
            .newInstance(values, metadata, recipeKind) as RecipeSteps
    } catch (error: Throwable) {
        throw AssertionError("RecipeSteps must require RecipeKind", error)
    }

    private fun recipeKind(steps: RecipeSteps): String = try {
        requireNotNull(steps.javaClass.getMethod("getRecipeKind").invoke(steps)).toString()
    } catch (error: Throwable) {
        throw AssertionError("RecipeSteps must retain RecipeKind", error)
    }

    private fun completeLog(id: String, completedAt: Long) = MealLogEntity(
        id = id,
        cookSessionId = "session-$id",
        recipeSnapshot = RecipeSnapshotRecord(
            id = "recipe-$id",
            title = "김치볶음밥",
            pantryBindings = PantryBindings(
                listOf(PantryBinding(pantryId, 2, PantryUnit.GRAM, 5_000)),
            ),
            steps = RecipeSteps(
                listOf("재료를 볶아요", "밥을 넣어요"),
                RecipePreferenceMetadata("Korean", "stir-fry", setOf("김치", "밥")),
                com.junited31.leftovers.data.RecipeKind.MEAL,
            ),
            createdAtEpochMillis = 500,
            feedback = MealFeedback(
                rating = 5,
                notes = "다음에는 덜 짜게",
                recommendAgain = false,
                measurementAdjustments = listOf(
                    MeasurementAdjustment("김치", 4_000, PantryUnit.GRAM, "한 숟갈 적게", completedAt),
                ),
                finalPhotoPath = "/data/user/0/com.junited31.leftovers/files/final.jpg",
            ),
        ),
        actualUses = ActualPantryUses(
            listOf(ActualPantryUse(pantryId, 2, PantryUnit.GRAM, 4_000)),
        ),
        remainingPantry = PantryRemainingSnapshots(
            listOf(PantryRemaining(pantryId, PantryUnit.GRAM, 6_000, 3)),
        ),
        completedAtEpochMillis = completedAt,
    )
}

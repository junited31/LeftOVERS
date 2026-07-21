package com.junited31.leftovers.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.junited31.leftovers.data.PantryItemEntity
import com.junited31.leftovers.data.PantryItemId
import com.junited31.leftovers.data.RecipeSnapshotDao
import com.junited31.leftovers.data.CookSessionDao
import com.junited31.leftovers.data.MealLogDao
import com.junited31.leftovers.cooking.CookingSessionStore
import com.junited31.leftovers.R
import com.junited31.leftovers.pantryDisplayName
import com.junited31.leftovers.network.ApiResult
import com.junited31.leftovers.network.LeftoversApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

private sealed interface RecipeUiState {
    data object Idle : RecipeUiState
    data object Loading : RecipeUiState
    data class Ready(val result: RecommendationResult.Valid) : RecipeUiState
    data class Error(val messageRes: Int) : RecipeUiState
}

@Composable
fun RecipeScreen(
    pantry: List<PantryItemEntity>,
    equipment: Set<String>,
    api: LeftoversApi,
    snapshotDao: RecipeSnapshotDao,
    cookSessionDao: CookSessionDao,
    mealLogDao: MealLogDao,
    onCookingStarted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val saver = remember(snapshotDao) { RecipeSnapshotSaver(snapshotDao) }
    val cooking = remember(snapshotDao, cookSessionDao) { CookingSessionStore(snapshotDao, cookSessionDao) }
    var state by remember { mutableStateOf<RecipeUiState>(RecipeUiState.Idle) }
    var savedTitle by remember { mutableStateOf<String?>(null) }
    val pantryById = remember(pantry) { pantry.associateBy { it.id } }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(20.dp).testTag("recipe-screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.recipe_heading),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.recipe_subtitle))
        Button(
            onClick = {
                state = RecipeUiState.Loading
                savedTitle = null
                scope.launch {
                    val (profile, apiResult) = withContext(Dispatchers.IO) {
                        val loadedProfile = loadPreferenceProfile(mealLogDao)
                        loadedProfile to api.executeJson(
                            "/v1/recipes/generate",
                            RecipeJson.request(
                                pantry,
                                equipment,
                                loadedProfile.history,
                                loadedProfile.measurementHints,
                            ),
                        )
                    }
                    state = rankedState(apiResult, pantry, equipment, profile.history)
                }
            },
            enabled = state != RecipeUiState.Loading && pantry.isNotEmpty() && equipment.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag("generate-recipes"),
        ) {
            Text(stringResource(if (state == RecipeUiState.Loading) R.string.recipe_generating else R.string.recipe_generate))
        }
        when (val current = state) {
            RecipeUiState.Idle -> Text(stringResource(R.string.recipe_idle))
            RecipeUiState.Loading -> Text(stringResource(R.string.recipe_loading))
            is RecipeUiState.Error -> Text(
                stringResource(current.messageRes),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                    .testTag("recipe-error"),
            )
            is RecipeUiState.Ready -> current.result.ranked.forEachIndexed { index, ranked ->
                RecipeCard(ranked, pantryById, index) {
                    scope.launch {
                        val saved = saver.save(current.result, ranked.id, pantryById, System.currentTimeMillis()) ||
                            snapshotDao.get(ranked.id) != null
                        if (saved) {
                            savedTitle = ranked.candidate.title
                            cooking.start(
                                ranked.id,
                                UUID.randomUUID().toString(),
                                System.currentTimeMillis(),
                            )
                            onCookingStarted()
                        }
                    }
                }
            }
        }
        savedTitle?.let { title -> Text(stringResource(R.string.recipe_saved, title)) }
    }
}

private fun rankedState(
    apiResult: ApiResult,
    pantry: List<PantryItemEntity>,
    equipment: Set<String>,
    history: List<RecommendationHistory>,
): RecipeUiState = when (apiResult) {
    is ApiResult.Success -> when (val decoded = RecipeJson.response(apiResult.body)) {
        is RecipeDecodeResult.Success -> when (
            val ranked = RecommendationRanker.rank(
                decoded.candidates,
                pantry,
                equipment,
                history,
                Instant.now(),
                LocalDate.now(ZoneOffset.UTC),
            )
        ) {
            is RecommendationResult.Valid -> RecipeUiState.Ready(ranked)
            is RecommendationResult.Invalid -> RecipeUiState.Error(R.string.recipe_error_invalid)
        }
        RecipeDecodeResult.Invalid -> RecipeUiState.Error(R.string.recipe_error_invalid)
    }
    ApiResult.InvalidRequest -> RecipeUiState.Error(R.string.recipe_error_invalid)
    ApiResult.AuthUnavailable, ApiResult.Unauthorized -> RecipeUiState.Error(R.string.error_auth)
    is ApiResult.QuotaLimited -> RecipeUiState.Error(R.string.recipe_error_quota)
    ApiResult.PayloadTooLarge -> RecipeUiState.Error(R.string.recipe_error_payload)
    ApiResult.UpstreamUnavailable, ApiResult.NetworkFailure -> RecipeUiState.Error(R.string.recipe_error_network)
    ApiResult.Cancelled -> RecipeUiState.Error(R.string.recipe_error_cancelled)
    ApiResult.AlreadyExecuted, ApiResult.InvalidPhoto, is ApiResult.UnexpectedHttp ->
        RecipeUiState.Error(R.string.recipe_error_unknown)
}

internal suspend fun loadPreferenceProfile(mealLogDao: MealLogDao): PreferenceProfile =
    PreferenceProfile.from(mealLogDao.latest())

@Composable
private fun RecipeCard(
    ranked: RankedRecommendation,
    pantryById: Map<PantryItemId, PantryItemEntity>,
    index: Int,
    onSave: () -> Unit,
) {
    val candidate = ranked.candidate
    Card(modifier = Modifier.fillMaxWidth().testTag("recipe-card")) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(candidate.title, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.recipe_meta, candidate.cuisine, candidate.primaryTechnique))
            Text(stringResource(R.string.recipe_uses, uses(candidate, pantryById)))
            val equipment = buildList {
                candidate.requiredEquipment.forEach { add(displayEquipment(it)) }
            }.joinToString().ifEmpty { stringResource(R.string.none) }
            Text(stringResource(R.string.recipe_equipment, equipment))
            Text(stringResource(R.string.why_recommended), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(metric(R.string.metric_coverage, ranked.coverage), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(metric(R.string.metric_expiry, ranked.expiry), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(metric(R.string.metric_preference, ranked.preference), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(metric(R.string.metric_novelty, ranked.novelty), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            Text(stringResource(R.string.missing_ingredients, missing(candidate)))
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().testTag("save-recipe-$index"),
            ) { Text(stringResource(R.string.save_and_start)) }
        }
    }
}

@Composable
private fun uses(
    candidate: RecommendationCandidate,
    pantryById: Map<PantryItemId, PantryItemEntity>,
): String = buildList {
    candidate.trackedUses.forEach { use ->
        add(stringResource(
            R.string.ingredient_amount_format,
            pantryDisplayName(pantryById.getValue(use.pantryItemId).name),
            amount(use.proposedMilliUnits),
            displayUnit(use.unit.value),
        ))
    }
}.joinToString("\n").ifEmpty { stringResource(R.string.no_pantry_used) }

@Composable
private fun missing(candidate: RecommendationCandidate): String = buildList {
    candidate.missingIngredients.forEach {
        add(stringResource(
            R.string.ingredient_amount_format,
            it.name,
            amount(it.amountMilliUnits),
            displayUnit(it.unit.value),
        ))
    }
}.joinToString().ifEmpty { stringResource(R.string.none) }

@Composable
private fun displayEquipment(value: String): String = when (RecipeNormalizer.normalize(value.replace('_', ' '))) {
    "induction" -> stringResource(R.string.equipment_induction)
    "gas burner" -> stringResource(R.string.equipment_gas_burner)
    "microwave" -> stringResource(R.string.equipment_microwave)
    "oven" -> stringResource(R.string.equipment_oven)
    "air fryer" -> stringResource(R.string.equipment_air_fryer)
    "blender" -> stringResource(R.string.equipment_blender)
    "rice cooker" -> stringResource(R.string.equipment_rice_cooker)
    "toaster" -> stringResource(R.string.equipment_toaster)
    "basic cookware" -> stringResource(R.string.equipment_basic_cookware)
    else -> value
}

@Composable
private fun displayUnit(value: String): String = if (value == "count") stringResource(R.string.unit_count) else value

private fun amount(milliUnits: Long): String = BigDecimal.valueOf(milliUnits)
    .movePointLeft(3)
    .stripTrailingZeros()
    .toPlainString()

@Composable
private fun metric(labelRes: Int, value: Double): String =
    stringResource(R.string.metric_format, stringResource(labelRes), value * 100)

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
import androidx.compose.ui.unit.dp
import com.junited31.leftovers.data.PantryItemEntity
import com.junited31.leftovers.data.PantryItemId
import com.junited31.leftovers.data.RecipeSnapshotDao
import com.junited31.leftovers.network.ApiResult
import com.junited31.leftovers.network.LeftoversApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

private sealed interface RecipeUiState {
    data object Idle : RecipeUiState
    data object Loading : RecipeUiState
    data class Ready(val result: RecommendationResult.Valid) : RecipeUiState
    data class Error(val message: String) : RecipeUiState
}

@Composable
fun RecipeScreen(
    pantry: List<PantryItemEntity>,
    equipment: Set<String>,
    api: LeftoversApi,
    snapshotDao: RecipeSnapshotDao,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val saver = remember(snapshotDao) { RecipeSnapshotSaver(snapshotDao) }
    var state by remember { mutableStateOf<RecipeUiState>(RecipeUiState.Idle) }
    var savedTitle by remember { mutableStateOf<String?>(null) }
    val pantryById = remember(pantry) { pantry.associateBy { it.id } }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(20.dp).testTag("recipe-screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Three pantry-first ideas", style = MaterialTheme.typography.titleMedium)
        Text("Only complete equipment-compatible sets are shown.")
        Button(
            onClick = {
                state = RecipeUiState.Loading
                savedTitle = null
                scope.launch {
                    val apiResult = withContext(Dispatchers.IO) {
                        api.executeJson(
                            "/v1/recipes/generate",
                            RecipeJson.request(pantry, equipment, emptyList()),
                        )
                    }
                    state = rankedState(apiResult, pantry, equipment)
                }
            },
            enabled = state != RecipeUiState.Loading && pantry.isNotEmpty() && equipment.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().testTag("generate-recipes"),
        ) {
            Text(if (state == RecipeUiState.Loading) "Generating…" else "Generate recipes")
        }
        when (val current = state) {
            RecipeUiState.Idle -> Text("Generate when your pantry and equipment are ready.")
            RecipeUiState.Loading -> Text("Checking variety and pantry fit…")
            is RecipeUiState.Error -> Text(current.message, color = MaterialTheme.colorScheme.error)
            is RecipeUiState.Ready -> current.result.ranked.forEachIndexed { index, ranked ->
                RecipeCard(ranked, pantryById, index) {
                    scope.launch {
                        if (saver.save(current.result, ranked.id, System.currentTimeMillis())) {
                            savedTitle = ranked.candidate.title
                        }
                    }
                }
            }
        }
        savedTitle?.let { title -> Text("Saved $title") }
    }
}

private fun rankedState(
    apiResult: ApiResult,
    pantry: List<PantryItemEntity>,
    equipment: Set<String>,
): RecipeUiState = when (apiResult) {
    is ApiResult.Success -> when (val decoded = RecipeJson.response(apiResult.body)) {
        is RecipeDecodeResult.Success -> when (
            val ranked = RecommendationRanker.rank(
                decoded.candidates,
                pantry,
                equipment,
                emptyList(),
                Instant.now(),
                LocalDate.now(ZoneOffset.UTC),
            )
        ) {
            is RecommendationResult.Valid -> RecipeUiState.Ready(ranked)
            is RecommendationResult.Invalid -> RecipeUiState.Error(
                "Could not produce three valid, diverse recipes",
            )
        }
        RecipeDecodeResult.Invalid -> RecipeUiState.Error("Could not produce three valid, diverse recipes")
    }
    ApiResult.InvalidRequest -> RecipeUiState.Error("Could not produce three valid, diverse recipes")
    ApiResult.AuthUnavailable, ApiResult.Unauthorized -> RecipeUiState.Error("Authentication unavailable")
    is ApiResult.QuotaLimited -> RecipeUiState.Error("Daily recipe limit reached")
    ApiResult.PayloadTooLarge -> RecipeUiState.Error("Recipe request is too large")
    ApiResult.UpstreamUnavailable, ApiResult.NetworkFailure -> RecipeUiState.Error("Recipe service unavailable")
    ApiResult.Cancelled -> RecipeUiState.Error("Recipe request cancelled")
    ApiResult.AlreadyExecuted, ApiResult.InvalidPhoto, is ApiResult.UnexpectedHttp ->
        RecipeUiState.Error("Recipe request failed")
}

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
            Text("${candidate.cuisine} · ${candidate.primaryTechnique}")
            Text("Uses:\n${uses(candidate, pantryById)}")
            Text("Equipment: ${candidate.requiredEquipment.joinToString { displayEquipment(it) }.ifEmpty { "None" }}")
            Text("Why this ranks", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(metric("Coverage", ranked.coverage), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(metric("Expiry", ranked.expiry), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(metric("Preference", ranked.preference), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(metric("Novelty", ranked.novelty), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            Text("Missing: ${missing(candidate)}")
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().testTag("save-recipe-$index"),
            ) { Text("Save recipe") }
        }
    }
}

private fun uses(
    candidate: RecommendationCandidate,
    pantryById: Map<PantryItemId, PantryItemEntity>,
): String = candidate.trackedUses.joinToString { use ->
    "${pantryById.getValue(use.pantryItemId).name} ${amount(use.proposedMilliUnits)} ${use.unit.value}"
}.replace(", ", "\n").ifEmpty { "No pantry items" }

private fun missing(candidate: RecommendationCandidate): String =
    candidate.missingIngredients.joinToString { "${it.name} ${amount(it.amountMilliUnits)} ${it.unit.value}" }
        .ifEmpty { "None" }

private fun displayEquipment(value: String): String = value.replace('_', ' ')
    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }

private fun amount(milliUnits: Long): String = BigDecimal.valueOf(milliUnits)
    .movePointLeft(3)
    .stripTrailingZeros()
    .toPlainString()

private fun metric(label: String, value: Double): String =
    String.format(Locale.ROOT, "%s %.0f%%", label, value * 100)

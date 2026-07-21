package com.junited31.leftovers.history

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.junited31.leftovers.R
import com.junited31.leftovers.data.MealLogEntity
import com.junited31.leftovers.data.PantryItemId
import com.junited31.leftovers.data.PantryUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HistoryScreen(
    repository: HistoryRepository,
    modifier: Modifier = Modifier,
) {
    var logs by remember { mutableStateOf<List<MealLogEntity>?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<HistoryDetail?>(null) }

    LaunchedEffect(selectedId) {
        if (selectedId == null) {
            logs = withContext(Dispatchers.IO) { repository.timeline() }
            detail = null
        } else {
            detail = withContext(Dispatchers.IO) { repository.detail(checkNotNull(selectedId)) }
        }
    }

    when (val current = detail) {
        null -> HistoryTimeline(logs, { selectedId = it }, modifier)
        else -> HistoryDetailScreen(current, { selectedId = null }, modifier)
    }
}

@Composable
private fun HistoryTimeline(
    logs: List<MealLogEntity>?,
    onOpen: (String) -> Unit,
    modifier: Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp).testTag("history-screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                stringResource(R.string.history_heading),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp).semantics { heading() },
            )
        }
        when {
            logs == null -> item { Text(stringResource(R.string.history_loading)) }
            logs.isEmpty() -> item {
                Text(stringResource(R.string.history_empty), modifier = Modifier.testTag("history-empty"))
            }
            else -> itemsIndexed(logs, key = { _, log -> log.id }) { _, log ->
                val openDescription = stringResource(R.string.history_open_cd, log.recipeSnapshot.title)
                Card(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onOpen(log.id) }
                        .semantics { contentDescription = openDescription }
                        .testTag("history-row-${log.id}"),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(log.recipeSnapshot.title, style = MaterialTheme.typography.titleMedium)
                        Text(completedAt(log.completedAtEpochMillis))
                        Text(stringResource(R.string.rating_value, log.recipeSnapshot.feedback.rating))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(4.dp)) }
    }
}

@Composable
private fun HistoryDetailScreen(
    detail: HistoryDetail,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val log = detail.mealLog
    val feedback = log.recipeSnapshot.feedback
    val displayNamesByPantryId = log.actualUses.values.mapNotNull { use ->
        use.displayName?.let { use.pantryItemId to it }
    }.toMap()
    val bitmap = remember(detail.availablePhotoPath) {
        detail.availablePhotoPath?.let(BitmapFactory::decodeFile)?.asImageBitmap()
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp).testTag("history-detail"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            TextButton(onClick = onBack, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.history_list))
            }
        }
        item {
            Text(
                log.recipeSnapshot.title,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(completedAt(log.completedAtEpochMillis))
        }
        item {
            when {
                bitmap != null -> Image(
                    bitmap = bitmap,
                    contentDescription = stringResource(R.string.final_photo_cd, log.recipeSnapshot.title),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(200.dp).testTag("history-photo"),
                )
                detail.referencedPhotoMissing || detail.availablePhotoPath != null -> Text(
                    stringResource(R.string.final_photo_missing),
                    modifier = Modifier.testTag("history-photo-missing"),
                )
                else -> Text(stringResource(R.string.final_photo_empty), modifier = Modifier.testTag("history-photo-empty"))
            }
        }
        item {
            DetailSection(
                stringResource(R.string.evaluation),
                listOf(
                    stringResource(R.string.rating_value, feedback.rating),
                    stringResource(if (feedback.recommendAgain) R.string.recommended_again else R.string.not_recommended_again),
                    feedback.notes.ifBlank { stringResource(R.string.no_notes) },
                ),
            )
        }
        item {
            DetailSection(stringResource(R.string.recipe_snapshot), buildList {
                log.recipeSnapshot.steps.values.forEachIndexed { index, step ->
                    add(stringResource(R.string.numbered_step_format, index + 1, step))
                }
            })
        }
        item {
            DetailSection(stringResource(R.string.actual_use), log.actualUses.values.mapIndexed { index, use ->
                stringResource(
                    R.string.history_amount_line,
                    ingredientLabel(use.displayName, index, use.pantryItemId),
                    amount(use.actualMilliUnits),
                    unit(use.unit),
                )
            }.ifEmpty { listOf(stringResource(R.string.no_ingredients_used)) })
        }
        item {
            DetailSection(stringResource(R.string.remaining_quantity), log.remainingPantry.values.mapIndexed { index, remaining ->
                stringResource(
                    R.string.history_amount_line,
                    ingredientLabel(displayNamesByPantryId[remaining.pantryItemId], index, remaining.pantryItemId),
                    amount(remaining.quantityMilliUnits),
                    unit(remaining.unit),
                )
            }.ifEmpty { listOf(stringResource(R.string.no_remaining_record)) })
        }
        item {
            DetailSection(stringResource(R.string.next_cooking_adjustment), feedback.measurementAdjustments.map { adjustment ->
                val note = adjustment.note.takeIf(String::isNotBlank)
                    ?.let { stringResource(R.string.history_adjustment_note, it) }.orEmpty()
                stringResource(
                    R.string.history_adjustment_line,
                    adjustment.ingredientName,
                    amount(adjustment.preferredAmountMilliUnits),
                    unit(adjustment.unit),
                    note,
                )
            }.ifEmpty { listOf(stringResource(R.string.no_adjustment_record)) })
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun DetailSection(title: String, lines: List<String>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        lines.forEach { Text(it) }
    }
}

@Composable
private fun completedAt(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern(stringResource(R.string.completed_at_pattern)))

@Composable
private fun ingredientLabel(displayName: String?, index: Int, id: PantryItemId) =
    displayName ?: stringResource(R.string.ingredient_fallback, index + 1, id.value.takeLast(4))

private fun amount(milliUnits: Long): String = BigDecimal.valueOf(milliUnits)
    .movePointLeft(3).stripTrailingZeros().toPlainString()

@Composable
private fun unit(unit: PantryUnit): String =
    if (unit == PantryUnit.COUNT) stringResource(R.string.unit_count) else unit.value

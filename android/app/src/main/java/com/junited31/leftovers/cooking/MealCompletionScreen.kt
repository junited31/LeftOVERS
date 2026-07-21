package com.junited31.leftovers.cooking

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.junited31.leftovers.R
import com.junited31.leftovers.pantryDisplayName
import com.junited31.leftovers.data.ActualPantryUse
import com.junited31.leftovers.data.ActualPantryUses
import com.junited31.leftovers.data.CompleteCookSessionCommand
import com.junited31.leftovers.data.CompletionResult
import com.junited31.leftovers.data.MealFeedback
import com.junited31.leftovers.data.MeasurementAdjustment
import com.junited31.leftovers.data.PantryItemEntity
import com.junited31.leftovers.data.QuantityParser
import com.junited31.leftovers.photo.InvalidPhotoException
import com.junited31.leftovers.photo.PhotoContracts
import com.junited31.leftovers.photo.PhotoLifecycle
import com.junited31.leftovers.photo.PhotoTooLargeException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.util.UUID

@Composable
internal fun MealCompletionForm(
    active: ActiveCookingSession,
    pantry: List<PantryItemEntity>,
    store: MealCompletionStore,
    photos: PhotoLifecycle,
    pickerFixture: () -> Uri?,
    onCancel: () -> Unit,
    onSuccess: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bindings = active.recipe.pantryBindings.values
    val pantryById = pantry.associateBy { it.id }
    val scope = rememberCoroutineScope()
    var actualAmounts by rememberSaveable(active.session.id) {
        mutableStateOf(bindings.map { formatAmount(it.proposedMilliUnits) })
    }
    var adjustmentAmounts by rememberSaveable(active.session.id) { mutableStateOf(List(bindings.size) { "" }) }
    var adjustmentNotes by rememberSaveable(active.session.id) { mutableStateOf(List(bindings.size) { "" }) }
    var rating by rememberSaveable(active.session.id) { mutableStateOf(3) }
    var notes by rememberSaveable(active.session.id) { mutableStateOf("") }
    var recommendAgain by rememberSaveable(active.session.id) { mutableStateOf(true) }
    var finalPhoto by remember(active.session.id) { mutableStateOf<PhotoLifecycle.ManagedPhoto?>(null) }
    var photoPreparing by remember(active.session.id) { mutableStateOf(false) }
    var submitting by remember(active.session.id) { mutableStateOf(false) }
    var error by remember(active.session.id) { mutableStateOf<Int?>(null) }

    fun attach(uri: Uri) {
        photoPreparing = true
        error = null
        scope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) { photos.compress(uri) }
                finalPhoto?.let(photos::discard)
                finalPhoto = prepared
            } catch (_: InvalidPhotoException) {
                error = R.string.error_photo_read
            } catch (_: PhotoTooLargeException) {
                error = R.string.error_photo_large
            } finally {
                photoPreparing = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(PhotoContracts.pick) { uri -> uri?.let(::attach) }
    DisposableEffect(Unit) {
        onDispose { finalPhoto?.let(photos::discard) }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
            .testTag("completion-form"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.completion_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() }.testTag("completion-form-title"),
        )
        Text(stringResource(R.string.completion_subtitle))
        bindings.forEachIndexed { index, binding ->
            val item = pantryById[binding.pantryItemId]
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item?.let { pantryDisplayName(it.name) } ?: stringResource(R.string.ingredient_number, index + 1), style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = actualAmounts[index],
                    onValueChange = { value -> actualAmounts = actualAmounts.updated(index, value) },
                    label = { Text(stringResource(R.string.actual_amount_label, displayUnit(binding.unit.value))) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("actual-use-$index"),
                )
                OutlinedTextField(
                    value = adjustmentAmounts[index],
                    onValueChange = { value -> adjustmentAmounts = adjustmentAmounts.updated(index, value) },
                    label = { Text(stringResource(R.string.next_amount_label, displayUnit(binding.unit.value))) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("adjustment-amount-$index"),
                )
                OutlinedTextField(
                    value = adjustmentNotes[index],
                    onValueChange = { value -> adjustmentNotes = adjustmentNotes.updated(index, value.take(200)) },
                    label = { Text(stringResource(R.string.measurement_note)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("adjustment-note-$index"),
                )
            }
        }
        Text(stringResource(R.string.rating), style = MaterialTheme.typography.titleMedium)
        Row(
            Modifier.fillMaxWidth().selectableGroup().testTag("rating-group"),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            (1..5).forEach { choice ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.selectable(
                        selected = rating == choice,
                        role = Role.RadioButton,
                        onClick = { rating = choice },
                    ).testTag("rating-$choice"),
                ) {
                    RadioButton(selected = rating == choice, onClick = null)
                    Text(choice.toString())
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().toggleable(
                value = recommendAgain,
                role = Role.Checkbox,
                onValueChange = { recommendAgain = it },
            ).testTag("recommend-again"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = recommendAgain, onCheckedChange = null)
            Text(stringResource(R.string.recommend_again))
        }
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it.take(1_000) },
            label = { Text(stringResource(R.string.meal_notes)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth().testTag("meal-notes"),
        )
        OutlinedButton(
            onClick = {
                pickerFixture()?.let(::attach) ?: picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            enabled = !photoPreparing && !submitting,
            modifier = Modifier.fillMaxWidth().testTag("attach-final-photo"),
        ) {
            Text(stringResource(if (photoPreparing) R.string.final_photo_preparing else R.string.choose_final_photo))
        }
        if (finalPhoto != null) Text(stringResource(R.string.final_photo_ready), modifier = Modifier.testTag("final-photo-ready"))
        error?.let {
            Text(
                stringResource(it),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                    .testTag("completion-error"),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel, enabled = !submitting, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.back))
            }
            Button(
                onClick = {
                    val actual = actualAmounts.map(QuantityParser::parseMilliUnits)
                    val preferred = adjustmentAmounts.map { value ->
                        value.takeIf(String::isNotBlank)?.let(QuantityParser::parseMilliUnits)
                    }
                    if (
                        actual.any { it == null || it < 0 } ||
                        preferred.withIndex().any { (index, amount) ->
                            adjustmentAmounts[index].isNotBlank() && (amount == null || amount <= 0)
                        } ||
                        adjustmentNotes.withIndex().any { (index, note) ->
                            note.isNotBlank() && adjustmentAmounts[index].isBlank()
                        }
                    ) {
                        error = R.string.completion_error_input
                        return@Button
                    }
                    val completedAt = System.currentTimeMillis()
                    val adjustments = bindings.mapIndexedNotNull { index, binding ->
                        preferred[index]?.let { amount ->
                            MeasurementAdjustment(
                                ingredientName = pantryById[binding.pantryItemId]?.name.orEmpty(),
                                preferredAmountMilliUnits = amount,
                                unit = binding.unit,
                                note = adjustmentNotes[index].trim(),
                                completedAtEpochMillis = completedAt,
                            )
                        }
                    }
                    val command = CompleteCookSessionCommand(
                        cookSessionId = active.session.id,
                        mealLogId = UUID.randomUUID().toString(),
                        completedAtEpochMillis = completedAt,
                        actualUses = ActualPantryUses(
                            bindings.mapIndexed { index, binding ->
                                ActualPantryUse(
                                    pantryItemId = binding.pantryItemId,
                                    sourceVersion = binding.sourceVersion,
                                    unit = binding.unit,
                                    actualMilliUnits = checkNotNull(actual[index]),
                                    displayName = pantryById[binding.pantryItemId]?.name,
                                )
                            },
                        ),
                        feedback = MealFeedback(
                            rating = rating,
                            notes = notes.trim(),
                            recommendAgain = recommendAgain,
                            measurementAdjustments = adjustments,
                            finalPhotoPath = null,
                        ),
                    )
                    submitting = true
                    error = null
                    scope.launch {
                        try {
                            val result = withContext(Dispatchers.IO) { store.complete(command, finalPhoto) }
                            finalPhoto = null
                            if (result is CompletionResult.Success) {
                                onSuccess(result.mealLogId)
                            } else {
                                error = result.messageRes()
                            }
                        } catch (_: Exception) {
                            finalPhoto = null
                            error = R.string.completion_error_save
                        } finally {
                            submitting = false
                        }
                    }
                },
                enabled = !submitting && !photoPreparing,
                modifier = Modifier.weight(1f).testTag("complete-meal"),
            ) { Text(stringResource(if (submitting) R.string.completion_saving else R.string.save_completion)) }
        }
    }
}

private fun CompletionResult.messageRes(): Int = when (this) {
    is CompletionResult.StaleInventory -> R.string.completion_error_stale
    is CompletionResult.UnitMismatch -> R.string.completion_error_unit
    is CompletionResult.InvalidActualUse -> R.string.completion_error_overuse
    else -> R.string.completion_error_unknown
}

private fun List<String>.updated(index: Int, value: String): List<String> =
    toMutableList().apply { this[index] = value }

private fun formatAmount(milliUnits: Long): String = BigDecimal.valueOf(milliUnits)
    .movePointLeft(3).stripTrailingZeros().toPlainString()

@Composable
private fun displayUnit(value: String): String = if (value == "count") stringResource(R.string.unit_count) else value

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
import com.junited31.leftovers.recipes.RecipeNormalizer
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
    var actualAmounts by remember(active.session.id) {
        mutableStateOf(bindings.map { formatAmount(it.proposedMilliUnits) })
    }
    var adjustmentAmounts by remember(active.session.id) { mutableStateOf(List(bindings.size) { "" }) }
    var adjustmentNotes by remember(active.session.id) { mutableStateOf(List(bindings.size) { "" }) }
    var rating by remember(active.session.id) { mutableStateOf(3) }
    var notes by remember(active.session.id) { mutableStateOf("") }
    var recommendAgain by remember(active.session.id) { mutableStateOf(true) }
    var finalPhoto by remember(active.session.id) { mutableStateOf<PhotoLifecycle.ManagedPhoto?>(null) }
    var photoPreparing by remember(active.session.id) { mutableStateOf(false) }
    var submitting by remember(active.session.id) { mutableStateOf(false) }
    var error by remember(active.session.id) { mutableStateOf<String?>(null) }

    fun attach(uri: Uri) {
        photoPreparing = true
        error = null
        scope.launch {
            try {
                val prepared = withContext(Dispatchers.IO) { photos.compress(uri) }
                finalPhoto?.let(photos::discard)
                finalPhoto = prepared
            } catch (_: InvalidPhotoException) {
                error = "사진을 읽을 수 없어요."
            } catch (_: PhotoTooLargeException) {
                error = "사진이 너무 커요."
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
            "요리 완료",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() }.testTag("completion-form-title"),
        )
        Text("실제로 사용한 양과 다음 추천에 반영할 내용을 확인해 주세요.")
        bindings.forEachIndexed { index, binding ->
            val item = pantryById[binding.pantryItemId]
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item?.name ?: "재료 ${index + 1}", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = actualAmounts[index],
                    onValueChange = { value -> actualAmounts = actualAmounts.updated(index, value) },
                    label = { Text("실제 사용량 (${displayUnit(binding.unit.value)})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("actual-use-$index"),
                )
                OutlinedTextField(
                    value = adjustmentAmounts[index],
                    onValueChange = { value -> adjustmentAmounts = adjustmentAmounts.updated(index, value) },
                    label = { Text("다음 추천량 (${displayUnit(binding.unit.value)}, 선택)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("adjustment-amount-$index"),
                )
                OutlinedTextField(
                    value = adjustmentNotes[index],
                    onValueChange = { value -> adjustmentNotes = adjustmentNotes.updated(index, value.take(200)) },
                    label = { Text("계량 메모 (선택)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("adjustment-note-$index"),
                )
            }
        }
        Text("평점", style = MaterialTheme.typography.titleMedium)
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
            Text("다시 추천해도 좋아요")
        }
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it.take(1_000) },
            label = { Text("식사 메모 (기기에만 저장)") },
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
        ) { Text(if (photoPreparing) "사진 준비 중…" else "완성 사진 선택 (선택)") }
        if (finalPhoto != null) Text("완성 사진이 준비됐어요.", modifier = Modifier.testTag("final-photo-ready"))
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                    .testTag("completion-error"),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel, enabled = !submitting, modifier = Modifier.weight(1f)) {
                Text("돌아가기")
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
                        error = "사용량과 다음 추천량을 올바르게 입력해 주세요."
                        return@Button
                    }
                    val completedAt = System.currentTimeMillis()
                    val adjustments = bindings.mapIndexedNotNull { index, binding ->
                        preferred[index]?.let { amount ->
                            MeasurementAdjustment(
                                ingredientName = RecipeNormalizer.normalize(
                                    pantryById[binding.pantryItemId]?.name.orEmpty(),
                                ),
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
                                error = result.message()
                            }
                        } catch (_: Exception) {
                            finalPhoto = null
                            error = "완료 내용을 저장하지 못했어요. 다시 시도해 주세요."
                        } finally {
                            submitting = false
                        }
                    }
                },
                enabled = !submitting && !photoPreparing,
                modifier = Modifier.weight(1f).testTag("complete-meal"),
            ) { Text(if (submitting) "저장 중…" else "완료 저장") }
        }
    }
}

private fun CompletionResult.message(): String = when (this) {
    is CompletionResult.StaleInventory -> "재료 수량이 변경됐어요. 다시 확인해 주세요."
    is CompletionResult.UnitMismatch -> "재료 단위가 변경됐어요. 다시 확인해 주세요."
    is CompletionResult.InvalidActualUse -> "사용량이 현재 재료보다 많아요."
    else -> "완료 내용을 저장하지 못했어요. 다시 확인해 주세요."
}

private fun List<String>.updated(index: Int, value: String): List<String> =
    toMutableList().apply { this[index] = value }

private fun formatAmount(milliUnits: Long): String = BigDecimal.valueOf(milliUnits)
    .movePointLeft(3).stripTrailingZeros().toPlainString()

private fun displayUnit(value: String): String = if (value == "count") "개" else value

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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
                "완료한 요리",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp).semantics { heading() },
            )
        }
        when {
            logs == null -> item { Text("요리 기록을 불러오는 중…") }
            logs.isEmpty() -> item {
                Text("아직 완료한 요리가 없어요.", modifier = Modifier.testTag("history-empty"))
            }
            else -> itemsIndexed(logs, key = { _, log -> log.id }) { _, log ->
                Card(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onOpen(log.id) }
                        .semantics { contentDescription = "${log.recipeSnapshot.title} 기록 열기" }
                        .testTag("history-row-${log.id}"),
                ) {
                    Column(
                        Modifier.fillMaxWidth().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(log.recipeSnapshot.title, style = MaterialTheme.typography.titleMedium)
                        Text(completedAt(log.completedAtEpochMillis))
                        Text("평점 ${log.recipeSnapshot.feedback.rating} / 5")
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
            TextButton(onClick = onBack, modifier = Modifier.padding(top = 4.dp)) { Text("기록 목록") }
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
                    contentDescription = "${log.recipeSnapshot.title} 완성 사진",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().height(200.dp).testTag("history-photo"),
                )
                detail.referencedPhotoMissing || detail.availablePhotoPath != null -> Text(
                    "완성 사진 파일을 찾을 수 없어요.",
                    modifier = Modifier.testTag("history-photo-missing"),
                )
                else -> Text("완성 사진을 남기지 않았어요.", modifier = Modifier.testTag("history-photo-empty"))
            }
        }
        item {
            DetailSection(
                "평가",
                listOf(
                    "평점 ${feedback.rating} / 5",
                    if (feedback.recommendAgain) "다시 추천함" else "다시 추천하지 않음",
                    feedback.notes.ifBlank { "메모 없음" },
                ),
            )
        }
        item {
            DetailSection("레시피 스냅샷", buildList {
                log.recipeSnapshot.steps.values.forEachIndexed { index, step -> add("${index + 1}. $step") }
            })
        }
        item {
            DetailSection("실제 사용량", log.actualUses.values.mapIndexed { index, use ->
                "${ingredientLabel(use.displayName, index, use.pantryItemId)} · " +
                    "${amount(use.actualMilliUnits)} ${unit(use.unit)}"
            }.ifEmpty { listOf("사용한 재료 없음") })
        }
        item {
            DetailSection("남은 수량", log.remainingPantry.values.mapIndexed { index, remaining ->
                "${ingredientLabel(displayNamesByPantryId[remaining.pantryItemId], index, remaining.pantryItemId)} · " +
                    "${amount(remaining.quantityMilliUnits)} ${unit(remaining.unit)}"
            }.ifEmpty { listOf("남은 수량 기록 없음") })
        }
        item {
            DetailSection("다음 조리 조정", feedback.measurementAdjustments.map { adjustment ->
                val note = adjustment.note.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
                "${adjustment.ingredientName} · ${amount(adjustment.preferredAmountMilliUnits)} " +
                    "${unit(adjustment.unit)}$note"
            }.ifEmpty { listOf("조정 기록 없음") })
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

private val completedAtFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm")

private fun completedAt(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis)
    .atZone(ZoneId.systemDefault())
    .format(completedAtFormatter)

private fun ingredientLabel(displayName: String?, index: Int, id: PantryItemId) =
    displayName ?: "재료 ${index + 1} (${id.value.takeLast(4)})"

private fun amount(milliUnits: Long): String = BigDecimal.valueOf(milliUnits)
    .movePointLeft(3).stripTrailingZeros().toPlainString()

private fun unit(unit: PantryUnit): String = if (unit == PantryUnit.COUNT) "개" else unit.value

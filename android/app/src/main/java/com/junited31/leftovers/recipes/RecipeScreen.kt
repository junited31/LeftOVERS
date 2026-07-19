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
import java.util.UUID

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
            "냉장고 재료로 만드는 세 가지 요리",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text("선택한 조리도구로 만들 수 있는 완성된 조합만 보여드려요.")
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
            Text(if (state == RecipeUiState.Loading) "생성 중…" else "레시피 생성")
        }
        when (val current = state) {
            RecipeUiState.Idle -> Text("재료와 조리도구를 준비한 뒤 레시피를 생성해 보세요.")
            RecipeUiState.Loading -> Text("다양성과 재료 활용도를 확인하는 중…")
            is RecipeUiState.Error -> Text(
                current.message,
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
        savedTitle?.let { title -> Text("$title 레시피를 저장했어요.") }
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
            is RecommendationResult.Invalid -> RecipeUiState.Error(
                "서로 다른 세 가지 유효한 레시피를 만들지 못했어요.",
            )
        }
        RecipeDecodeResult.Invalid -> RecipeUiState.Error("서로 다른 세 가지 유효한 레시피를 만들지 못했어요.")
    }
    ApiResult.InvalidRequest -> RecipeUiState.Error("서로 다른 세 가지 유효한 레시피를 만들지 못했어요.")
    ApiResult.AuthUnavailable, ApiResult.Unauthorized -> RecipeUiState.Error("인증 정보를 확인할 수 없어요.")
    is ApiResult.QuotaLimited -> RecipeUiState.Error("오늘의 레시피 생성 횟수를 모두 사용했어요.")
    ApiResult.PayloadTooLarge -> RecipeUiState.Error("레시피 요청이 너무 커요.")
    ApiResult.UpstreamUnavailable, ApiResult.NetworkFailure -> RecipeUiState.Error("레시피 서비스에 연결할 수 없어요.")
    ApiResult.Cancelled -> RecipeUiState.Error("레시피 요청이 취소됐어요.")
    ApiResult.AlreadyExecuted, ApiResult.InvalidPhoto, is ApiResult.UnexpectedHttp ->
        RecipeUiState.Error("레시피 요청에 실패했어요.")
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
            Text("${candidate.cuisine} · ${candidate.primaryTechnique}")
            Text("사용 재료:\n${uses(candidate, pantryById)}")
            Text("조리도구: ${candidate.requiredEquipment.joinToString { displayEquipment(it) }.ifEmpty { "없음" }}")
            Text("추천 이유", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(metric("재료 활용", ranked.coverage), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(metric("유통기한", ranked.expiry), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(metric("취향 일치", ranked.preference), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(metric("새로움", ranked.novelty), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            Text("부족한 재료: ${missing(candidate)}")
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth().testTag("save-recipe-$index"),
            ) { Text("저장하고 요리 시작") }
        }
    }
}

private fun uses(
    candidate: RecommendationCandidate,
    pantryById: Map<PantryItemId, PantryItemEntity>,
): String = candidate.trackedUses.joinToString { use ->
    "${pantryById.getValue(use.pantryItemId).name} ${amount(use.proposedMilliUnits)} ${displayUnit(use.unit.value)}"
}.replace(", ", "\n").ifEmpty { "사용한 식재료 없음" }

private fun missing(candidate: RecommendationCandidate): String =
    candidate.missingIngredients.joinToString {
        "${it.name} ${amount(it.amountMilliUnits)} ${displayUnit(it.unit.value)}"
    }.ifEmpty { "없음" }

private fun displayEquipment(value: String): String = when (RecipeNormalizer.normalize(value.replace('_', ' '))) {
    "induction" -> "인덕션"
    "gas burner" -> "가스레인지"
    "microwave" -> "전자레인지"
    "oven" -> "오븐"
    "air fryer" -> "에어프라이어"
    "blender" -> "블렌더"
    "rice cooker" -> "전기밥솥"
    "toaster" -> "토스터"
    "basic cookware" -> "기본 조리도구"
    else -> value
}

private fun displayUnit(value: String): String = if (value == "count") "개" else value

private fun amount(milliUnits: Long): String = BigDecimal.valueOf(milliUnits)
    .movePointLeft(3)
    .stripTrailingZeros()
    .toPlainString()

private fun metric(label: String, value: Double): String =
    String.format(Locale.ROOT, "%s %.0f%%", label, value * 100)

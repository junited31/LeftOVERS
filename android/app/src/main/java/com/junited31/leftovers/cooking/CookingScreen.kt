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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.junited31.leftovers.network.ApiResult
import com.junited31.leftovers.network.LeftoversApi
import com.junited31.leftovers.network.PhotoAdviceCall
import com.junited31.leftovers.photo.InvalidPhotoException
import com.junited31.leftovers.photo.PhotoContracts
import com.junited31.leftovers.photo.PhotoLifecycle
import com.junited31.leftovers.photo.PhotoTooLargeException
import com.junited31.leftovers.data.PantryItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface AdviceUiState {
    data object Idle : AdviceUiState
    data object PreparingPhoto : AdviceUiState
    data object PhotoReady : AdviceUiState
    data object Loading : AdviceUiState
    data class Ready(val advice: CookingAdvice) : AdviceUiState
    data class Error(val message: String, val retryable: Boolean) : AdviceUiState
}

private const val NETWORK_RETRY_MESSAGE = "네트워크 연결을 확인하고\n다시 시도해 주세요."

internal class PhotoPreparationEpoch {
    private var current = 0

    fun begin(): Int = ++current

    fun invalidate() {
        current++
    }

    fun owns(epoch: Int): Boolean = epoch == current
}

@Composable
fun CookingScreen(
    store: CookingSessionStore,
    apiProvider: () -> LeftoversApi,
    photos: PhotoLifecycle,
    pantry: List<PantryItemEntity>,
    completionStore: MealCompletionStore,
    pickerFixture: () -> Uri?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val photoEpoch = remember { PhotoPreparationEpoch() }
    var refresh by remember { mutableIntStateOf(0) }
    var active by remember { mutableStateOf<ActiveCookingSession?>(null) }
    var state by remember { mutableStateOf<AdviceUiState>(AdviceUiState.Idle) }
    var attached by remember { mutableStateOf<PhotoLifecycle.ManagedPhoto?>(null) }
    var cameraInput by remember { mutableStateOf<PhotoLifecycle.ManagedPhoto?>(null) }
    var adviceCall by remember { mutableStateOf<PhotoAdviceCall?>(null) }
    var showCompletion by remember { mutableStateOf(false) }
    var completedMealId by remember { mutableStateOf<String?>(null) }

    fun changeStep(load: suspend () -> ActiveCookingSession?) {
        photoEpoch.invalidate()
        adviceCall?.cancel()
        adviceCall = null
        attached?.let(photos::discard)
        attached = null
        state = AdviceUiState.Idle
        scope.launch {
            active = load()
            scrollState.scrollTo(0)
        }
    }

    LaunchedEffect(refresh) { active = store.resume() }
    LaunchedEffect(state) {
        if (state is AdviceUiState.Error) scrollState.scrollTo(scrollState.maxValue)
    }

    fun attach(uri: Uri) {
        val epoch = photoEpoch.begin()
        state = AdviceUiState.PreparingPhoto
        scope.launch {
            try {
                attached?.let(photos::discard)
                attached = null
                val prepared = withContext(Dispatchers.IO) { photos.compress(uri) }
                if (photoEpoch.owns(epoch)) {
                    attached = prepared
                    state = AdviceUiState.PhotoReady
                } else {
                    photos.discard(prepared)
                }
            } catch (_: InvalidPhotoException) {
                if (photoEpoch.owns(epoch)) {
                    state = AdviceUiState.Error("사진을 읽을 수 없어요.", false)
                }
            } catch (_: PhotoTooLargeException) {
                if (photoEpoch.owns(epoch)) {
                    state = AdviceUiState.Error("사진이 너무 커요.", false)
                }
            }
        }
    }

    val picker = rememberLauncherForActivityResult(PhotoContracts.pick) { uri -> uri?.let(::attach) }
    val camera = rememberLauncherForActivityResult(PhotoContracts.takePicture) { captured ->
        val input = cameraInput
        cameraInput = null
        if (captured && input != null) {
            val epoch = photoEpoch.begin()
            state = AdviceUiState.PreparingPhoto
            scope.launch {
                try {
                    attached?.let(photos::discard)
                    attached = null
                    val prepared = withContext(Dispatchers.IO) { photos.compressCamera(input) }
                    if (photoEpoch.owns(epoch)) {
                        attached = prepared
                        state = AdviceUiState.PhotoReady
                    } else {
                        photos.discard(prepared)
                    }
                } catch (_: InvalidPhotoException) {
                    if (photoEpoch.owns(epoch)) {
                        state = AdviceUiState.Error("사진을 읽을 수 없어요.", false)
                    }
                } catch (_: PhotoTooLargeException) {
                    if (photoEpoch.owns(epoch)) {
                        state = AdviceUiState.Error("사진이 너무 커요.", false)
                    }
                }
            }
        } else {
            input?.let(photos::discard)
        }
    }

    fun pickPhoto() {
        pickerFixture()?.let(::attach) ?: picker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            adviceCall?.cancel()
            attached?.let(photos::discard)
            cameraInput?.let(photos::discard)
        }
    }

    val current = active
    completedMealId?.let {
        Column(
            modifier.fillMaxSize().padding(20.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .testTag("completion-success"),
        ) {
            Text(
                "요리를 완료했어요.",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() }.testTag("completion-success-title"),
            )
            Text("남은 재료와 다음 추천 취향을 기기에 저장했어요.")
        }
        return
    }
    if (current == null) {
        Column(modifier.fillMaxSize().padding(20.dp)) {
            Text("진행 중인 요리가 없어요.", style = MaterialTheme.typography.titleMedium)
            Text("레시피에서 요리 시작을 눌러 주세요.")
        }
        return
    }
    if (showCompletion) {
        MealCompletionForm(
            active = current,
            pantry = pantry,
            store = completionStore,
            photos = photos,
            pickerFixture = pickerFixture,
            onCancel = { showCompletion = false },
            onSuccess = { completedMealId = it },
            modifier = modifier,
        )
        return
    }
    val index = current.session.currentStepIndex
    val step = CookingStep.from(current.recipe.steps.values[index])

    Column(
        modifier.fillMaxSize().verticalScroll(scrollState).padding(20.dp)
            .testTag("cooking-screen"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(current.recipe.title, style = MaterialTheme.typography.titleLarge)
        Text("${index + 1} / ${current.recipe.steps.values.size} 단계", style = MaterialTheme.typography.labelLarge)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(step.text, style = MaterialTheme.typography.titleMedium)
                step.durationLabel?.let { Text("예상 $it · 안내용") }
                Text("자동 타이머는 실행되지 않아요.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { changeStep { store.previous(current.session.id) } },
                enabled = index > 0,
                modifier = Modifier.weight(1f).testTag("previous-step"),
            ) { Text("이전 단계") }
            Button(
                onClick = { changeStep { store.next(current.session.id) } },
                enabled = index < current.recipe.steps.values.lastIndex,
                modifier = Modifier.weight(1f).testTag("next-step"),
            ) { Text("다음 단계") }
        }
        if (index == current.recipe.steps.values.lastIndex) {
            Button(
                onClick = {
                    adviceCall?.cancel()
                    adviceCall = null
                    attached?.let(photos::discard)
                    attached = null
                    state = AdviceUiState.Idle
                    showCompletion = true
                },
                modifier = Modifier.fillMaxWidth().testTag("start-completion"),
            ) { Text("요리 완료 입력") }
        }
        Text("사진 조언", style = MaterialTheme.typography.titleMedium)
        Text("사진은 현재 상태를 참고하는 용도이며 익음이나 안전을 판정하지 않아요.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = ::pickPhoto, modifier = Modifier.weight(1f).testTag("attach-gallery")) {
                Text("갤러리")
            }
            OutlinedButton(
                onClick = {
                    val input = photos.createManagedPhoto()
                    cameraInput = input
                    camera.launch(photos.fileProviderUri(input))
                },
                modifier = Modifier.weight(1f).testTag("attach-camera"),
            ) { Text("카메라") }
        }
        if (state == AdviceUiState.PhotoReady) Text("사진이 준비됐어요.")
        Button(
            onClick = {
                val photo = attached ?: return@Button
                attached = null
                state = AdviceUiState.Loading
                val api = try {
                    apiProvider()
                } catch (_: IllegalStateException) {
                    photos.discard(photo)
                    state = AdviceUiState.Error(NETWORK_RETRY_MESSAGE, true)
                    return@Button
                }
                val call = api.newPhotoAdviceCall(CookingAdviceJson.request(step.text), photo)
                adviceCall = call
                scope.launch {
                    val result = withContext(Dispatchers.IO) { call.execute() }
                    if (adviceCall === call) {
                        adviceCall = null
                        state = adviceState(result)
                    }
                }
            },
            enabled = attached != null && state != AdviceUiState.Loading,
            modifier = Modifier.fillMaxWidth().testTag("request-advice"),
        ) { Text(if (state == AdviceUiState.Loading) "확인 중…" else "이 사진으로 조언 받기") }
        when (val adviceState = state) {
            AdviceUiState.Idle, AdviceUiState.PhotoReady -> Unit
            AdviceUiState.PreparingPhoto -> Text("사진을 준비하는 중…")
            AdviceUiState.Loading -> Text("사진을 한 번 전송해 확인하는 중…")
            is AdviceUiState.Error -> {
                Text(adviceState.message, color = MaterialTheme.colorScheme.error)
                if (adviceState.retryable) {
                    Button(onClick = ::pickPhoto, modifier = Modifier.testTag("retry-advice")) {
                        Text("사진 다시 선택해 재시도")
                    }
                }
            }
            is AdviceUiState.Ready -> AdviceCard(adviceState.advice)
        }
    }
}

private fun adviceState(result: ApiResult): AdviceUiState = when (result) {
    is ApiResult.Success -> when (val decoded = CookingAdviceJson.response(result.body)) {
        is CookingAdviceDecodeResult.Success -> AdviceUiState.Ready(decoded.advice)
        CookingAdviceDecodeResult.Invalid -> AdviceUiState.Error("조언 형식을 확인할 수 없어요.", true)
    }
    ApiResult.NetworkFailure, ApiResult.UpstreamUnavailable -> AdviceUiState.Error(NETWORK_RETRY_MESSAGE, true)
    ApiResult.Cancelled -> AdviceUiState.Error("요청이 취소됐어요.", true)
    ApiResult.Unauthorized, ApiResult.AuthUnavailable -> AdviceUiState.Error("인증 정보를 확인할 수 없어요.", true)
    is ApiResult.QuotaLimited -> AdviceUiState.Error("오늘의 사진 조언 횟수를 모두 사용했어요.", false)
    ApiResult.PayloadTooLarge, ApiResult.InvalidPhoto -> AdviceUiState.Error("사진을 처리할 수 없어요.", false)
    ApiResult.InvalidRequest -> AdviceUiState.Error("조언 형식을 확인할 수 없어요.", true)
    ApiResult.AlreadyExecuted, is ApiResult.UnexpectedHttp -> AdviceUiState.Error("사진 조언에 실패했어요.", true)
}

@Composable
private fun AdviceCard(advice: CookingAdvice) {
    Card(Modifier.fillMaxWidth().testTag("advice-card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "관찰 결과",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            advice.observations.forEach { Text("• $it") }
            Text(
                "다음 행동",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            advice.nextActions.forEach { Text("• $it") }
            Text("신뢰도 ${advice.confidencePercent}%")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "안전 안내",
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(advice.safetyNote.replace(Regex("(?<=[.!?。！？])\\s+"), "\n"))
                }
            }
        }
    }
}

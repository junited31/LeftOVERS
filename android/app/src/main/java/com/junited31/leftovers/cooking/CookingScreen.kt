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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.junited31.leftovers.R
import com.junited31.leftovers.recipeKindLabel
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
    data class Error(val messageRes: Int, val retryable: Boolean) : AdviceUiState
}

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
    var showCompletion by rememberSaveable { mutableStateOf(false) }
    var completedMealId by rememberSaveable { mutableStateOf<String?>(null) }

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
                    state = AdviceUiState.Error(R.string.error_photo_read, false)
                }
            } catch (_: PhotoTooLargeException) {
                if (photoEpoch.owns(epoch)) {
                    state = AdviceUiState.Error(R.string.error_photo_large, false)
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
                        state = AdviceUiState.Error(R.string.error_photo_read, false)
                    }
                } catch (_: PhotoTooLargeException) {
                    if (photoEpoch.owns(epoch)) {
                        state = AdviceUiState.Error(R.string.error_photo_large, false)
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
                stringResource(R.string.cooking_complete_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() }.testTag("completion-success-title"),
            )
            Text(stringResource(R.string.cooking_complete_body))
        }
        return
    }
    if (current == null) {
        Column(modifier.fillMaxSize().padding(20.dp)) {
            Text(stringResource(R.string.cooking_none_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.cooking_none_body))
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
        Text(recipeKindLabel(current.recipe.steps.recipeKind), modifier = Modifier.testTag("selected-recipe-kind"))
        Text(stringResource(R.string.step_progress, index + 1, current.recipe.steps.values.size), style = MaterialTheme.typography.labelLarge)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(step.text, style = MaterialTheme.typography.titleMedium)
                step.durationMinutes?.let {
                    Text(stringResource(R.string.estimated_duration, stringResource(R.string.duration_approx_minutes, it)))
                }
                Text(stringResource(R.string.no_automatic_timer), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { changeStep { store.previous(current.session.id) } },
                enabled = index > 0,
                modifier = Modifier.weight(1f).testTag("previous-step"),
            ) { Text(stringResource(R.string.previous_step)) }
            Button(
                onClick = { changeStep { store.next(current.session.id) } },
                enabled = index < current.recipe.steps.values.lastIndex,
                modifier = Modifier.weight(1f).testTag("next-step"),
            ) { Text(stringResource(R.string.next_step)) }
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
            ) { Text(stringResource(R.string.enter_completion)) }
        }
        Text(stringResource(R.string.photo_advice), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.photo_advice_disclaimer))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = ::pickPhoto, modifier = Modifier.weight(1f).testTag("attach-gallery")) {
                Text(stringResource(R.string.gallery))
            }
            OutlinedButton(
                onClick = {
                    val input = photos.createManagedPhoto()
                    cameraInput = input
                    camera.launch(photos.fileProviderUri(input))
                },
                modifier = Modifier.weight(1f).testTag("attach-camera"),
            ) { Text(stringResource(R.string.camera)) }
        }
        if (state == AdviceUiState.PhotoReady) Text(stringResource(R.string.photo_ready))
        Button(
            onClick = {
                val photo = attached ?: return@Button
                attached = null
                state = AdviceUiState.Loading
                val api = try {
                    apiProvider()
                } catch (_: IllegalStateException) {
                    photos.discard(photo)
                    state = AdviceUiState.Error(R.string.error_network_retry, true)
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
        ) {
            Text(stringResource(if (state == AdviceUiState.Loading) R.string.photo_checking else R.string.request_photo_advice))
        }
        when (val adviceState = state) {
            AdviceUiState.Idle, AdviceUiState.PhotoReady -> Unit
            AdviceUiState.PreparingPhoto -> Text(stringResource(R.string.photo_preparing))
            AdviceUiState.Loading -> Text(stringResource(R.string.photo_sending))
            is AdviceUiState.Error -> {
                Text(
                    stringResource(adviceState.messageRes),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                        .testTag("advice-error"),
                )
                if (adviceState.retryable) {
                    Button(onClick = ::pickPhoto, modifier = Modifier.testTag("retry-advice")) {
                        Text(stringResource(R.string.photo_retry))
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
        CookingAdviceDecodeResult.Invalid -> AdviceUiState.Error(R.string.advice_error_format, true)
    }
    ApiResult.NetworkFailure, ApiResult.UpstreamUnavailable -> AdviceUiState.Error(R.string.error_network_retry, true)
    ApiResult.Cancelled -> AdviceUiState.Error(R.string.advice_error_cancelled, true)
    ApiResult.Unauthorized, ApiResult.AuthUnavailable -> AdviceUiState.Error(R.string.error_auth, true)
    is ApiResult.QuotaLimited -> AdviceUiState.Error(R.string.advice_error_quota, false)
    ApiResult.PayloadTooLarge, ApiResult.InvalidPhoto -> AdviceUiState.Error(R.string.advice_error_photo, false)
    ApiResult.InvalidRequest -> AdviceUiState.Error(R.string.advice_error_format, true)
    ApiResult.AlreadyExecuted, is ApiResult.UnexpectedHttp -> AdviceUiState.Error(R.string.advice_error_unknown, true)
}

@Composable
private fun AdviceCard(advice: CookingAdvice) {
    Card(Modifier.fillMaxWidth().testTag("advice-card")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.observations),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            advice.observations.forEach { Text(stringResource(R.string.bullet_item, stringResource(it))) }
            Text(
                stringResource(R.string.next_actions),
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
            advice.nextActions.forEach { Text(stringResource(R.string.bullet_item, stringResource(it))) }
            Text(stringResource(R.string.confidence, advice.confidencePercent))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        stringResource(R.string.safety_guidance_title),
                        modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(stringResource(advice.safetyNote).replace(Regex("(?<=[.!?。！？])\\s+"), "\n"))
                }
            }
        }
    }
}

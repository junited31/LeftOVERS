package com.junited31.leftovers

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material.icons.outlined.SoupKitchen
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.junited31.leftovers.data.LeftoversDatabase
import com.junited31.leftovers.data.LeftoversPreferenceKeys
import com.junited31.leftovers.data.PantryDao
import com.junited31.leftovers.data.PantryItemEntity
import com.junited31.leftovers.data.PantryItemId
import com.junited31.leftovers.data.PantryUnit
import com.junited31.leftovers.data.PantryEditVersion
import com.junited31.leftovers.data.QuantityParser
import com.junited31.leftovers.data.leftoversDataStore
import com.junited31.leftovers.cooking.CookingScreen
import com.junited31.leftovers.cooking.CookingSessionStore
import com.junited31.leftovers.cooking.MealCompletionStore
import com.junited31.leftovers.history.HistoryRepository
import com.junited31.leftovers.history.HistoryScreen
import com.junited31.leftovers.recipes.RecipeScreen
import com.junited31.leftovers.photo.PhotoLifecycle
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = LeftoversDatabase.get(applicationContext)
        val recipeApiProvider = { (application as LeftoversApplication).createRecipeApi() }
        setContent {
            LeftoversApp(
                applicationContext,
                database.pantryDao(),
                database.recipeSnapshotDao(),
                database.cookSessionDao(),
                database.mealLogDao(),
                database.inventoryCompletionDao(),
                applicationContext.leftoversDataStore,
                recipeApiProvider,
                (application as LeftoversApplication)::consumePhotoPickerFixture,
            )
        }
    }
}

private enum class AppScreen { PANTRY, RECIPES, COOKING, HISTORY, EQUIPMENT }

private data class EquipmentChoice(val id: String, val label: String)

private val equipmentChoices = listOf(
    EquipmentChoice("induction", "인덕션"),
    EquipmentChoice("gas_burner", "가스레인지"),
    EquipmentChoice("microwave", "전자레인지"),
    EquipmentChoice("oven", "오븐"),
    EquipmentChoice("air_fryer", "에어프라이어"),
    EquipmentChoice("blender", "블렌더"),
    EquipmentChoice("rice_cooker", "전기밥솥"),
    EquipmentChoice("toaster", "토스터"),
    EquipmentChoice("basic_cookware", "기본 조리도구"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeftoversApp(
    context: android.content.Context,
    pantryDao: PantryDao,
    snapshotDao: com.junited31.leftovers.data.RecipeSnapshotDao,
    cookSessionDao: com.junited31.leftovers.data.CookSessionDao,
    mealLogDao: com.junited31.leftovers.data.MealLogDao,
    completionDao: com.junited31.leftovers.data.InventoryCompletionDao,
    dataStore: DataStore<Preferences>,
    recipeApiProvider: () -> com.junited31.leftovers.network.LeftoversApi,
    pickerFixture: () -> android.net.Uri?,
) {
    val pantryItems by pantryDao.observeAll().collectAsState(initial = emptyList())
    val preferences by dataStore.data.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf(AppScreen.PANTRY) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var showForm by rememberSaveable { mutableStateOf(false) }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (showForm) "재료" else screen.title) },
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = screen == AppScreen.PANTRY,
                        onClick = {
                            screen = AppScreen.PANTRY
                            showForm = false
                        },
                        icon = { Icon(Icons.Outlined.Inventory2, contentDescription = "식재료") },
                        label = { Text("식재료") },
                        modifier = Modifier.testTag("nav-pantry").semantics {
                            contentDescription = "식재료"
                        },
                    )
                    NavigationBarItem(
                        selected = screen == AppScreen.RECIPES,
                        onClick = {
                            screen = AppScreen.RECIPES
                            showForm = false
                        },
                        icon = { Icon(Icons.Outlined.RestaurantMenu, contentDescription = "레시피") },
                        label = { Text("레시피") },
                        modifier = Modifier.testTag("nav-recipes").semantics {
                            contentDescription = "레시피"
                        },
                    )
                    NavigationBarItem(
                        selected = screen == AppScreen.COOKING,
                        onClick = {
                            screen = AppScreen.COOKING
                            showForm = false
                        },
                        icon = { Icon(Icons.Outlined.SoupKitchen, contentDescription = "요리") },
                        label = { Text("요리") },
                        modifier = Modifier.testTag("nav-cooking").semantics {
                            contentDescription = "요리"
                        },
                    )
                    NavigationBarItem(
                        selected = screen == AppScreen.HISTORY,
                        onClick = {
                            screen = AppScreen.HISTORY
                            showForm = false
                        },
                        icon = { Icon(Icons.Outlined.History, contentDescription = "기록") },
                        label = { Text("기록") },
                        modifier = Modifier.testTag("nav-history").semantics {
                            contentDescription = "기록"
                        },
                    )
                    NavigationBarItem(
                        selected = screen == AppScreen.EQUIPMENT,
                        onClick = {
                            screen = AppScreen.EQUIPMENT
                            showForm = false
                        },
                        icon = { Icon(Icons.Outlined.Kitchen, contentDescription = "조리도구") },
                        label = { Text("조리도구") },
                        modifier = Modifier.testTag("nav-equipment").semantics {
                            contentDescription = "조리도구"
                        },
                    )
                }
            },
        ) { padding ->
            when {
                showForm -> PantryForm(
                    existingItem = pantryItems.firstOrNull { it.id.value == editId },
                    modifier = Modifier.padding(padding),
                    onCancel = { showForm = false },
                    onSave = { saved ->
                        scope.launch {
                            if (editId == null) pantryDao.insertAll(listOf(saved)) else pantryDao.update(saved)
                            showForm = false
                        }
                    },
                )
                screen == AppScreen.PANTRY -> PantryList(
                    pantryItems = pantryItems,
                    modifier = Modifier.padding(padding),
                    onAdd = {
                        editId = null
                        showForm = true
                    },
                    onEdit = {
                        editId = it.id.value
                        showForm = true
                    },
                    onDelete = { scope.launch { pantryDao.delete(it.id) } },
                )
                screen == AppScreen.RECIPES -> preferences?.let { loadedPreferences ->
                    val recipeApi = remember { recipeApiProvider() }
                    RecipeScreen(
                        pantry = pantryItems,
                        equipment = loadedPreferences[LeftoversPreferenceKeys.EQUIPMENT_IDS].orEmpty(),
                        api = recipeApi,
                        snapshotDao = snapshotDao,
                        cookSessionDao = cookSessionDao,
                        mealLogDao = mealLogDao,
                        onCookingStarted = { screen = AppScreen.COOKING },
                        modifier = Modifier.padding(padding),
                    )
                } ?: Text("레시피를 불러오는 중…", modifier = Modifier.padding(padding).padding(20.dp))
                screen == AppScreen.COOKING -> {
                    val photos = remember(context) { PhotoLifecycle(context) }
                    CookingScreen(
                        store = remember { CookingSessionStore(snapshotDao, cookSessionDao) },
                        apiProvider = recipeApiProvider,
                        photos = photos,
                        pantry = pantryItems,
                        completionStore = remember(photos) { MealCompletionStore(completionDao, photos) },
                        pickerFixture = pickerFixture,
                        modifier = Modifier.padding(padding),
                    )
                }
                screen == AppScreen.HISTORY -> HistoryScreen(
                    repository = remember(mealLogDao) { HistoryRepository(mealLogDao) },
                    modifier = Modifier.padding(padding),
                )
                else -> preferences?.let { loadedPreferences ->
                    EquipmentChecklist(
                        selected = loadedPreferences[LeftoversPreferenceKeys.EQUIPMENT_IDS].orEmpty(),
                        modifier = Modifier.padding(padding),
                        onToggle = { id ->
                            scope.launch {
                                dataStore.edit { values ->
                                    val current = values[LeftoversPreferenceKeys.EQUIPMENT_IDS].orEmpty()
                                    values[LeftoversPreferenceKeys.EQUIPMENT_IDS] =
                                        if (id in current) current - id else current + id
                                    values[LeftoversPreferenceKeys.ONBOARDING_COMPLETE] = true
                                }
                            }
                        },
                    )
                } ?: Text("조리도구를 불러오는 중…", modifier = Modifier.padding(padding).padding(20.dp))
            }
        }
    }
}

private val AppScreen.title: String
    get() = when (this) {
        AppScreen.PANTRY -> "식재료"
        AppScreen.RECIPES -> "레시피"
        AppScreen.COOKING -> "요리"
        AppScreen.HISTORY -> "기록"
        AppScreen.EQUIPMENT -> "조리도구"
    }

@Composable
private fun PantryList(
    pantryItems: List<PantryItemEntity>,
    modifier: Modifier,
    onAdd: () -> Unit,
    onEdit: (PantryItemEntity) -> Unit,
    onDelete: (PantryItemEntity) -> Unit,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Text(
            text = "지금 사용할 수 있는 재료",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp).semantics { heading() },
        )
        Button(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("add-pantry"),
        ) {
            Text("재료 추가")
        }
        if (pantryItems.isEmpty()) {
            Text("아직 등록한 재료가 없어요. 재료를 추가해 주세요.")
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(pantryItems, key = { it.id.value }) { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium)
                            Text("${formatQuantity(item.quantityMilliUnits)} ${displayUnit(item.unit)}")
                            item.expiryEpochDay?.let { Text("유통기한: ${LocalDate.ofEpochDay(it)}") }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(
                                    onClick = { onEdit(item) },
                                    modifier = Modifier.semantics {
                                        contentDescription = "${item.name} 수정"
                                    },
                                ) { Text("수정") }
                                TextButton(
                                    onClick = { onDelete(item) },
                                    modifier = Modifier.semantics {
                                        contentDescription = "${item.name} 삭제"
                                    },
                                ) { Text("삭제") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PantryForm(
    existingItem: PantryItemEntity?,
    modifier: Modifier,
    onCancel: () -> Unit,
    onSave: (PantryItemEntity) -> Unit,
) {
    var name by rememberSaveable(existingItem?.id?.value) { mutableStateOf(existingItem?.name.orEmpty()) }
    var quantity by rememberSaveable(existingItem?.id?.value) {
        mutableStateOf(existingItem?.let { formatQuantity(it.quantityMilliUnits) }.orEmpty())
    }
    var unit by rememberSaveable(existingItem?.id?.value) { mutableStateOf(existingItem?.unit ?: PantryUnit.GRAM) }
    var expiry by rememberSaveable(existingItem?.id?.value) {
        mutableStateOf(existingItem?.expiryEpochDay?.let { LocalDate.ofEpochDay(it).toString() }.orEmpty())
    }
    var nameError by rememberSaveable { mutableStateOf(false) }
    var quantityError by rememberSaveable { mutableStateOf(false) }
    var expiryError by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("재료 이름") },
                isError = nameError,
                supportingText = if (nameError) ({ Text("재료 이름을 입력해 주세요.") }) else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("name-input"),
            )
        }
        item {
            OutlinedTextField(
                value = quantity,
                onValueChange = { quantity = it },
                label = { Text("수량") },
                isError = quantityError,
                supportingText = if (quantityError) ({
                    Text("수량은 0보다 큰 값으로 소수점 셋째 자리까지 입력해 주세요.")
                }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("quantity-input"),
            )
        }
        item {
            Text("단위", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PantryUnit.entries.forEach { choice ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.selectable(
                            selected = unit == choice,
                            role = Role.RadioButton,
                            onClick = { unit = choice },
                        ).padding(vertical = 8.dp),
                    ) {
                        RadioButton(selected = unit == choice, onClick = null)
                        Text(displayUnit(choice))
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = expiry,
                onValueChange = { expiry = it },
                label = { Text("유통기한 (YYYY-MM-DD, 선택)") },
                isError = expiryError,
                supportingText = if (expiryError) ({ Text("YYYY-MM-DD 형식으로 입력해 주세요.") }) else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("취소") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val parsedQuantity = QuantityParser.parseMilliUnits(quantity)
                        val parsedExpiry = expiry.trim().takeIf { it.isNotEmpty() }?.let {
                            runCatching { LocalDate.parse(it) }.getOrNull()
                        }
                        nameError = name.isBlank()
                        quantityError = parsedQuantity == null || parsedQuantity <= 0
                        expiryError = expiry.isNotBlank() && parsedExpiry == null
                        if (!nameError && !quantityError && !expiryError) {
                            onSave(
                                PantryItemEntity(
                                    id = existingItem?.id ?: requireNotNull(
                                        PantryItemId.parse(UUID.randomUUID().toString()),
                                    ),
                                    name = name.trim(),
                                    quantityMilliUnits = checkNotNull(parsedQuantity),
                                    unit = unit,
                                    expiryEpochDay = parsedExpiry?.toEpochDay(),
                                    version = PantryEditVersion.next(
                                        existingItem,
                                        checkNotNull(parsedQuantity),
                                        unit,
                                    ),
                                ),
                            )
                        }
                    },
                    modifier = Modifier.testTag("save-pantry"),
                ) { Text("재료 저장") }
            }
        }
    }
}

@Composable
private fun EquipmentChecklist(
    selected: Set<String>,
    modifier: Modifier,
    onToggle: (String) -> Unit,
) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Text(
                text = "주방에 있는 조리도구를 선택해 주세요",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 16.dp).semantics { heading() },
            )
        }
        items(equipmentChoices, key = { it.id }) { equipment ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics(mergeDescendants = true) {}
                    .toggleable(
                        value = equipment.id in selected,
                        role = Role.Checkbox,
                        onValueChange = { onToggle(equipment.id) },
                    )
                    .heightIn(min = 48.dp)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = equipment.id in selected, onCheckedChange = null)
                Spacer(Modifier.width(12.dp))
                Text(equipment.label, style = MaterialTheme.typography.bodyLarge)
            }
            HorizontalDivider()
        }
    }
}

private fun formatQuantity(milliUnits: Long): String =
    BigDecimal.valueOf(milliUnits).movePointLeft(3).stripTrailingZeros().toPlainString()

private fun displayUnit(unit: PantryUnit): String = when (unit) {
    PantryUnit.COUNT -> "개"
    else -> unit.value
}

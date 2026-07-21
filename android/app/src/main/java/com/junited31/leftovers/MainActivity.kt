package com.junited31.leftovers

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.RestaurantMenu
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SoupKitchen
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
import androidx.core.os.LocaleListCompat
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

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
        }
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

private enum class AppScreen { PANTRY, RECIPES, COOKING, HISTORY, SETTINGS }

private data class EquipmentChoice(val id: String, val labelRes: Int)

private val equipmentChoices = listOf(
    EquipmentChoice("induction", R.string.equipment_induction),
    EquipmentChoice("gas_burner", R.string.equipment_gas_burner),
    EquipmentChoice("microwave", R.string.equipment_microwave),
    EquipmentChoice("oven", R.string.equipment_oven),
    EquipmentChoice("air_fryer", R.string.equipment_air_fryer),
    EquipmentChoice("blender", R.string.equipment_blender),
    EquipmentChoice("rice_cooker", R.string.equipment_rice_cooker),
    EquipmentChoice("toaster", R.string.equipment_toaster),
    EquipmentChoice("basic_cookware", R.string.equipment_basic_cookware),
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
    var formSession by rememberSaveable { mutableStateOf(0) }
    val pantryLabel = stringResource(R.string.nav_pantry)
    val recipesLabel = stringResource(R.string.nav_recipes)
    val cookingLabel = stringResource(R.string.nav_cooking)
    val historyLabel = stringResource(R.string.nav_history)
    val settingsLabel = stringResource(R.string.nav_settings)

    MaterialTheme {
        val loadedPreferences = preferences ?: return@MaterialTheme
        if (loadedPreferences[LeftoversPreferenceKeys.ONBOARDING_COMPLETE] != true) {
            OnboardingFlow(
                pantryItems = pantryItems,
                selectedEquipment = loadedPreferences[LeftoversPreferenceKeys.EQUIPMENT_IDS].orEmpty(),
                onToggleEquipment = { id ->
                    scope.launch {
                        dataStore.edit { values ->
                            val current = values[LeftoversPreferenceKeys.EQUIPMENT_IDS].orEmpty()
                            values[LeftoversPreferenceKeys.EQUIPMENT_IDS] =
                                if (id in current) current - id else current + id
                        }
                    }
                },
                onSavePantry = { saved, editing ->
                    scope.launch {
                        if (editing) pantryDao.update(saved) else pantryDao.insertAll(listOf(saved))
                    }
                },
                onDeletePantry = { scope.launch { pantryDao.delete(it.id) } },
                onFinish = {
                    scope.launch {
                        dataStore.edit { it[LeftoversPreferenceKeys.ONBOARDING_COMPLETE] = true }
                    }
                },
            )
            return@MaterialTheme
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(if (showForm) R.string.title_ingredient else screen.titleRes)) },
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
                        icon = { Icon(Icons.Outlined.Inventory2, contentDescription = pantryLabel) },
                        label = { Text(pantryLabel) },
                        modifier = Modifier.testTag("nav-pantry").semantics {
                            contentDescription = pantryLabel
                        },
                    )
                    NavigationBarItem(
                        selected = screen == AppScreen.RECIPES,
                        onClick = {
                            screen = AppScreen.RECIPES
                            showForm = false
                        },
                        icon = { Icon(Icons.Outlined.RestaurantMenu, contentDescription = recipesLabel) },
                        label = { Text(recipesLabel) },
                        modifier = Modifier.testTag("nav-recipes").semantics {
                            contentDescription = recipesLabel
                        },
                    )
                    NavigationBarItem(
                        selected = screen == AppScreen.COOKING,
                        onClick = {
                            screen = AppScreen.COOKING
                            showForm = false
                        },
                        icon = { Icon(Icons.Outlined.SoupKitchen, contentDescription = cookingLabel) },
                        label = { Text(cookingLabel) },
                        modifier = Modifier.testTag("nav-cooking").semantics {
                            contentDescription = cookingLabel
                        },
                    )
                    NavigationBarItem(
                        selected = screen == AppScreen.HISTORY,
                        onClick = {
                            screen = AppScreen.HISTORY
                            showForm = false
                        },
                        icon = { Icon(Icons.Outlined.History, contentDescription = historyLabel) },
                        label = { Text(historyLabel) },
                        modifier = Modifier.testTag("nav-history").semantics {
                            contentDescription = historyLabel
                        },
                    )
                    NavigationBarItem(
                        selected = screen == AppScreen.SETTINGS,
                        onClick = {
                            screen = AppScreen.SETTINGS
                            showForm = false
                        },
                        icon = { Icon(Icons.Outlined.Settings, contentDescription = settingsLabel) },
                        label = { Text(settingsLabel) },
                        modifier = Modifier.testTag("nav-settings").semantics {
                            contentDescription = settingsLabel
                        },
                    )
                }
            },
        ) { padding ->
            when {
                showForm -> key(formSession) {
                    PantryForm(
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
                }
                screen == AppScreen.PANTRY -> PantryList(
                    pantryItems = pantryItems,
                    modifier = Modifier.padding(padding),
                    onAdd = {
                        editId = null
                        formSession++
                        showForm = true
                    },
                    onEdit = {
                        editId = it.id.value
                        formSession++
                        showForm = true
                    },
                    onDelete = { scope.launch { pantryDao.delete(it.id) } },
                )
                screen == AppScreen.RECIPES -> {
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
                }
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
                else -> {
                    EquipmentChecklist(
                        selected = loadedPreferences[LeftoversPreferenceKeys.EQUIPMENT_IDS].orEmpty(),
                        modifier = Modifier.padding(padding).testTag("settings-kitchen"),
                        onToggle = { id ->
                            scope.launch {
                                dataStore.edit { values ->
                                    val current = values[LeftoversPreferenceKeys.EQUIPMENT_IDS].orEmpty()
                                    values[LeftoversPreferenceKeys.EQUIPMENT_IDS] =
                                        if (id in current) current - id else current + id
                                }
                            }
                        },
                        showLanguage = true,
                    )
                }
            }
        }
    }
}

private val AppScreen.titleRes: Int
    get() = when (this) {
        AppScreen.PANTRY -> R.string.nav_pantry
        AppScreen.RECIPES -> R.string.nav_recipes
        AppScreen.COOKING -> R.string.nav_cooking
        AppScreen.HISTORY -> R.string.nav_history
        AppScreen.SETTINGS -> R.string.nav_settings
    }

@Composable
private fun OnboardingFlow(
    pantryItems: List<PantryItemEntity>,
    selectedEquipment: Set<String>,
    onToggleEquipment: (String) -> Unit,
    onSavePantry: (PantryItemEntity, Boolean) -> Unit,
    onDeletePantry: (PantryItemEntity) -> Unit,
    onFinish: () -> Unit,
) {
    var pantryStep by rememberSaveable { mutableStateOf(false) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var showForm by rememberSaveable { mutableStateOf(false) }
    var formSession by rememberSaveable { mutableStateOf(0) }

    when {
        !pantryStep -> Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("onboarding-equipment"),
        ) {
            EquipmentChecklist(
                selected = selectedEquipment,
                modifier = Modifier.weight(1f),
                onToggle = onToggleEquipment,
            )
            Button(
                onClick = { pantryStep = true },
                enabled = selectedEquipment.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(20.dp).testTag("onboarding-continue"),
            ) { Text(stringResource(R.string.continue_setup)) }
        }
        showForm -> key(formSession) {
            PantryForm(
                existingItem = pantryItems.firstOrNull { it.id.value == editId },
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).testTag("onboarding-pantry-form"),
                onCancel = { showForm = false },
                onSave = { saved ->
                    onSavePantry(saved, editId != null)
                    showForm = false
                },
            )
        }
        else -> Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("onboarding-pantry"),
        ) {
            PantryList(
                pantryItems = pantryItems,
                modifier = Modifier.weight(1f),
                onAdd = {
                    editId = null
                    formSession++
                    showForm = true
                },
                onEdit = {
                    editId = it.id.value
                    formSession++
                    showForm = true
                },
                onDelete = onDeletePantry,
            )
            Button(
                onClick = onFinish,
                enabled = pantryItems.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(20.dp).testTag("onboarding-finish"),
            ) { Text(stringResource(R.string.finish_setup)) }
        }
    }
}

@Composable
private fun PantryList(
    pantryItems: List<PantryItemEntity>,
    modifier: Modifier,
    onAdd: () -> Unit,
    onEdit: (PantryItemEntity) -> Unit,
    onDelete: (PantryItemEntity) -> Unit,
) {
    Column(modifier.fillMaxSize().padding(horizontal = 20.dp).testTag("pantry-list")) {
        Text(
            text = stringResource(R.string.pantry_heading),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp).semantics { heading() },
        )
        Button(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("add-pantry"),
        ) {
            Text(stringResource(R.string.add_ingredient))
        }
        if (pantryItems.isEmpty()) {
            Text(stringResource(R.string.pantry_empty))
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).testTag("pantry-items"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(pantryItems, key = { it.id.value }) { item ->
                    val displayName = pantryDisplayName(item.name)
                    val editDescription = stringResource(R.string.edit_item_cd, displayName)
                    val deleteDescription = stringResource(R.string.delete_item_cd, displayName)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(displayName, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.quantity_unit_format, formatQuantity(item.quantityMilliUnits), displayUnit(item.unit)))
                            item.expiryEpochDay?.let {
                                Text(stringResource(R.string.expiry_date_format, LocalDate.ofEpochDay(it)))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(
                                    onClick = { onEdit(item) },
                                    modifier = Modifier.semantics {
                                        contentDescription = editDescription
                                    },
                                ) { Text(stringResource(R.string.edit)) }
                                TextButton(
                                    onClick = { onDelete(item) },
                                    modifier = Modifier.semantics {
                                        contentDescription = deleteDescription
                                    },
                                ) { Text(stringResource(R.string.delete)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal data class PantrySuggestion(val canonicalId: String, val unit: PantryUnit, val labelRes: Int)

internal val pantrySuggestions = listOf(
    PantrySuggestion("eggs", PantryUnit.COUNT, R.string.pantry_suggestion_eggs),
    PantrySuggestion("milk", PantryUnit.MILLILITER, R.string.pantry_suggestion_milk),
    PantrySuggestion("butter", PantryUnit.GRAM, R.string.pantry_suggestion_butter),
    PantrySuggestion("cheese", PantryUnit.GRAM, R.string.pantry_suggestion_cheese),
    PantrySuggestion("onion", PantryUnit.COUNT, R.string.pantry_suggestion_onion),
    PantrySuggestion("garlic", PantryUnit.COUNT, R.string.pantry_suggestion_garlic),
    PantrySuggestion("rice", PantryUnit.GRAM, R.string.pantry_suggestion_rice),
    PantrySuggestion("bread", PantryUnit.COUNT, R.string.pantry_suggestion_bread),
    PantrySuggestion("flour", PantryUnit.GRAM, R.string.pantry_suggestion_flour),
    PantrySuggestion("sugar", PantryUnit.GRAM, R.string.pantry_suggestion_sugar),
    PantrySuggestion("salt", PantryUnit.GRAM, R.string.pantry_suggestion_salt),
    PantrySuggestion("black pepper", PantryUnit.GRAM, R.string.pantry_suggestion_black_pepper),
    PantrySuggestion("cooking oil", PantryUnit.MILLILITER, R.string.pantry_suggestion_cooking_oil),
    PantrySuggestion("tomato", PantryUnit.COUNT, R.string.pantry_suggestion_tomato),
    PantrySuggestion("potato", PantryUnit.COUNT, R.string.pantry_suggestion_potato),
    PantrySuggestion("carrot", PantryUnit.COUNT, R.string.pantry_suggestion_carrot),
    PantrySuggestion("spinach", PantryUnit.GRAM, R.string.pantry_suggestion_spinach),
)

@Composable
internal fun pantryDisplayName(name: String): String =
    pantrySuggestions.firstOrNull { it.canonicalId == name }?.let { stringResource(it.labelRes) } ?: name

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
            Text(stringResource(R.string.suggested_ingredients), style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                pantrySuggestions.forEach { suggestion ->
                    SuggestionChip(
                        onClick = {
                            name = suggestion.canonicalId
                            unit = suggestion.unit
                            quantity = ""
                        },
                        label = { Text(stringResource(suggestion.labelRes)) },
                        modifier = Modifier.testTag("suggestion-${suggestion.canonicalId}"),
                    )
                }
            }
        }
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.ingredient_name)) },
                isError = nameError,
                supportingText = if (nameError) ({ Text(stringResource(R.string.ingredient_name_error)) }) else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("name-input"),
            )
        }
        item {
            OutlinedTextField(
                value = quantity,
                onValueChange = { quantity = it },
                label = { Text(stringResource(R.string.quantity)) },
                isError = quantityError,
                supportingText = if (quantityError) ({
                    Text(stringResource(R.string.quantity_error))
                }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("quantity-input"),
            )
        }
        if (unit == PantryUnit.COUNT) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("0.25" to "1/4", "0.5" to "1/2", "1" to "1", "2" to "2").forEach { (value, label) ->
                        SuggestionChip(
                            onClick = { quantity = value },
                            label = { Text(label) },
                            modifier = Modifier.testTag("fraction-$value"),
                        )
                    }
                }
            }
        }
        item {
            Text(stringResource(R.string.unit), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PantryUnit.entries.forEach { choice ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.selectable(
                            selected = unit == choice,
                            role = Role.RadioButton,
                            onClick = { unit = choice },
                        ).padding(vertical = 8.dp).testTag("unit-${choice.value}"),
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
                label = { Text(stringResource(R.string.expiry_optional)) },
                isError = expiryError,
                supportingText = if (expiryError) ({ Text(stringResource(R.string.expiry_error)) }) else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
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
                                    name = name,
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
                ) { Text(stringResource(R.string.save_ingredient)) }
            }
        }
    }
}

@Composable
private fun EquipmentChecklist(
    selected: Set<String>,
    modifier: Modifier,
    onToggle: (String) -> Unit,
    showLanguage: Boolean = false,
) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        item {
            Text(
                text = stringResource(R.string.equipment_heading),
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
                    .padding(vertical = 4.dp)
                    .testTag("equipment-${equipment.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = equipment.id in selected, onCheckedChange = null)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(equipment.labelRes), style = MaterialTheme.typography.bodyLarge)
            }
            HorizontalDivider()
        }
        if (showLanguage) {
            item {
                Text(
                    text = stringResource(R.string.language),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 24.dp, bottom = 8.dp).semantics { heading() },
                )
                LanguageChoice("en", R.string.language_english)
                LanguageChoice("ko", R.string.language_korean)
                Spacer(Modifier.padding(bottom = 16.dp))
            }
        }
    }
}

@Composable
private fun LanguageChoice(languageTag: String, labelRes: Int) {
    val selected = AppCompatDelegate.getApplicationLocales().get(0)?.language == languageTag
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = {
                    if (languageTag == "en") {
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
                    } else {
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ko"))
                    }
                },
            )
            .heightIn(min = 48.dp)
            .testTag("settings-language-$languageTag"),
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(stringResource(labelRes))
    }
}

private fun formatQuantity(milliUnits: Long): String =
    BigDecimal.valueOf(milliUnits).movePointLeft(3).stripTrailingZeros().toPlainString()

@Composable
private fun displayUnit(unit: PantryUnit): String = when (unit) {
    PantryUnit.COUNT -> stringResource(R.string.unit_count)
    else -> unit.value
}

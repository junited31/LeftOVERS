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
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Kitchen
import androidx.compose.material.icons.outlined.RestaurantMenu
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import com.junited31.leftovers.data.QuantityParser
import com.junited31.leftovers.data.leftoversDataStore
import com.junited31.leftovers.recipes.RecipeScreen
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = LeftoversDatabase.get(applicationContext)
        val recipeApi = (application as LeftoversApplication).createRecipeApi()
        setContent {
            LeftoversApp(
                database.pantryDao(),
                database.recipeSnapshotDao(),
                applicationContext.leftoversDataStore,
                recipeApi,
            )
        }
    }
}

private enum class AppScreen { PANTRY, RECIPES, EQUIPMENT }

private data class EquipmentChoice(val id: String, val label: String)

private val equipmentChoices = listOf(
    EquipmentChoice("induction", "Induction"),
    EquipmentChoice("gas_burner", "Gas burner"),
    EquipmentChoice("microwave", "Microwave"),
    EquipmentChoice("oven", "Oven"),
    EquipmentChoice("air_fryer", "Air fryer"),
    EquipmentChoice("blender", "Blender"),
    EquipmentChoice("rice_cooker", "Rice cooker"),
    EquipmentChoice("toaster", "Toaster"),
    EquipmentChoice("basic_cookware", "Basic cookware"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeftoversApp(
    pantryDao: PantryDao,
    snapshotDao: com.junited31.leftovers.data.RecipeSnapshotDao,
    dataStore: DataStore<Preferences>,
    recipeApi: com.junited31.leftovers.network.LeftoversApi,
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
                    title = { Text(if (showForm) "Ingredient" else screen.title) },
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
                        icon = { Icon(Icons.Outlined.Inventory2, contentDescription = "Pantry") },
                        label = { Text("Pantry") },
                        modifier = Modifier.testTag("nav-pantry").semantics {
                            contentDescription = "Pantry"
                        },
                    )
                    NavigationBarItem(
                        selected = screen == AppScreen.RECIPES,
                        onClick = {
                            screen = AppScreen.RECIPES
                            showForm = false
                        },
                        icon = { Icon(Icons.Outlined.RestaurantMenu, contentDescription = "Recipes") },
                        label = { Text("Recipes") },
                        modifier = Modifier.testTag("nav-recipes").semantics {
                            contentDescription = "Recipes"
                        },
                    )
                    NavigationBarItem(
                        selected = screen == AppScreen.EQUIPMENT,
                        onClick = {
                            screen = AppScreen.EQUIPMENT
                            showForm = false
                        },
                        icon = { Icon(Icons.Outlined.Kitchen, contentDescription = "Equipment") },
                        label = { Text("Equipment") },
                        modifier = Modifier.testTag("nav-equipment").semantics {
                            contentDescription = "Equipment"
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
                    RecipeScreen(
                        pantry = pantryItems,
                        equipment = loadedPreferences[LeftoversPreferenceKeys.EQUIPMENT_IDS].orEmpty(),
                        api = recipeApi,
                        snapshotDao = snapshotDao,
                        modifier = Modifier.padding(padding),
                    )
                } ?: Text("Loading recipes…", modifier = Modifier.padding(padding).padding(20.dp))
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
                } ?: Text("Loading equipment…", modifier = Modifier.padding(padding).padding(20.dp))
            }
        }
    }
}

private val AppScreen.title: String
    get() = when (this) {
        AppScreen.PANTRY -> "Pantry"
        AppScreen.RECIPES -> "Recipes"
        AppScreen.EQUIPMENT -> "Equipment"
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
            text = "What is available right now",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Button(
            onClick = onAdd,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp).testTag("add-pantry"),
        ) {
            Text("Add ingredient")
        }
        if (pantryItems.isEmpty()) {
            Text("Your pantry is empty. Add an ingredient to get started.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(pantryItems, key = { it.id.value }) { item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(item.name, style = MaterialTheme.typography.titleMedium)
                            Text("${formatQuantity(item.quantityMilliUnits)} ${item.unit.value}")
                            item.expiryEpochDay?.let { Text("Expires ${LocalDate.ofEpochDay(it)}") }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                            ) {
                                TextButton(
                                    onClick = { onEdit(item) },
                                    modifier = Modifier.semantics {
                                        contentDescription = "Edit ${item.name}"
                                    },
                                ) { Text("Edit") }
                                TextButton(
                                    onClick = { onDelete(item) },
                                    modifier = Modifier.semantics {
                                        contentDescription = "Delete ${item.name}"
                                    },
                                ) { Text("Delete") }
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
    var name by remember(existingItem?.id) { mutableStateOf(existingItem?.name.orEmpty()) }
    var quantity by remember(existingItem?.id) {
        mutableStateOf(existingItem?.let { formatQuantity(it.quantityMilliUnits) }.orEmpty())
    }
    var unit by remember(existingItem?.id) { mutableStateOf(existingItem?.unit ?: PantryUnit.GRAM) }
    var expiry by remember(existingItem?.id) {
        mutableStateOf(existingItem?.expiryEpochDay?.let { LocalDate.ofEpochDay(it).toString() }.orEmpty())
    }
    var nameError by remember { mutableStateOf(false) }
    var quantityError by remember { mutableStateOf(false) }
    var expiryError by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Ingredient name") },
                isError = nameError,
                supportingText = if (nameError) ({ Text("Name is required") }) else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("name-input"),
            )
        }
        item {
            OutlinedTextField(
                value = quantity,
                onValueChange = { quantity = it },
                label = { Text("Quantity") },
                isError = quantityError,
                supportingText = if (quantityError) ({
                    Text("Enter a positive quantity with up to 3 decimals")
                }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("quantity-input"),
            )
        }
        item {
            Text("Unit", style = MaterialTheme.typography.labelLarge)
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
                        Text(choice.value)
                    }
                }
            }
        }
        item {
            OutlinedTextField(
                value = expiry,
                onValueChange = { expiry = it },
                label = { Text("Expiry (YYYY-MM-DD, optional)") },
                isError = expiryError,
                supportingText = if (expiryError) ({ Text("Use YYYY-MM-DD") }) else null,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onCancel) { Text("Cancel") }
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
                                    version = (existingItem?.version ?: 0) + 1,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.testTag("save-pantry"),
                ) { Text("Save ingredient") }
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
                text = "Choose what is available in your kitchen",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 16.dp),
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

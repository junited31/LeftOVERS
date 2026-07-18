package com.junited31.leftovers.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

object LeftoversPreferenceKeys {
    const val DATASTORE_NAME = "leftovers_preferences"
    val EQUIPMENT_IDS = stringSetPreferencesKey("equipment_ids")
    val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
}

val Context.leftoversDataStore: DataStore<Preferences> by preferencesDataStore(
    name = LeftoversPreferenceKeys.DATASTORE_NAME,
)

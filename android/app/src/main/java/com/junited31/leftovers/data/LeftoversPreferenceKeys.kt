package com.junited31.leftovers.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

object LeftoversPreferenceKeys {
    const val DATASTORE_NAME = "leftovers_preferences"
    val EQUIPMENT_IDS = stringSetPreferencesKey("equipment_ids")
    val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
}

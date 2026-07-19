package com.junited31.leftovers.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LeftoversDatabase::class.java,
    )

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(NAME)
    }

    @Test
    fun versionOneSchemaOpensAndValidatesWithoutDestructiveFallback() {
        helper.createDatabase(NAME, LeftoversDatabase.VERSION).close()
        helper.runMigrationsAndValidate(NAME, LeftoversDatabase.VERSION, true).close()
    }

    private companion object {
        const val NAME = "leftovers-migration-test.db"
    }
}

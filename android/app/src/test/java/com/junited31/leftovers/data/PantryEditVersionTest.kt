package com.junited31.leftovers.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PantryEditVersionTest {
    private val id = requireNotNull(PantryItemId.parse("00000000-0000-4000-8000-000000000008"))
    private val existing = PantryItemEntity(id, "Rice", 500_000, PantryUnit.GRAM, 10, 7)

    @Test
    fun nameAndExpiryOnlyEditsPreserveCompletionVersion() {
        assertEquals(7, PantryEditVersion.next(existing, 500_000, PantryUnit.GRAM))
    }

    @Test
    fun quantityOrUnitEditsIncrementCompletionVersion() {
        assertEquals(8, PantryEditVersion.next(existing, 499_000, PantryUnit.GRAM))
        assertEquals(8, PantryEditVersion.next(existing, 500_000, PantryUnit.MILLILITER))
    }
}

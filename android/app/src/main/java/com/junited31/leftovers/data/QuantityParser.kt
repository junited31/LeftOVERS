package com.junited31.leftovers.data

import java.math.BigDecimal

object QuantityParser {
    private val decimalPattern = Regex("-?\\d+(?:\\.\\d{1,3})?")

    fun parseMilliUnits(input: String): Long? {
        if (!decimalPattern.matches(input)) return null
        return try {
            BigDecimal(input).movePointRight(3).longValueExact()
        } catch (_: ArithmeticException) {
            null
        }
    }
}

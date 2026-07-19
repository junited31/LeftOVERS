package com.junited31.leftovers.cooking

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.roundToInt

enum class CookingAdviceStatus { CONTINUE, ADJUST, STOP }

data class CookingAdvice(
    val status: CookingAdviceStatus,
    val observations: List<String>,
    val nextActions: List<String>,
    val confidence: Double,
    val safetyNote: String,
) {
    val confidencePercent: Int = (confidence * 100).roundToInt()
}

sealed interface CookingAdviceDecodeResult {
    data class Success(val advice: CookingAdvice) : CookingAdviceDecodeResult
    data object Invalid : CookingAdviceDecodeResult
}

data class CookingStep(val text: String, val durationLabel: String?) {
    companion object {
        private val duration = Regex("(?:약\\s*)?(\\d{1,3})\\s*분")

        fun from(text: String): CookingStep {
            val match = duration.find(text)
            return CookingStep(text, match?.groupValues?.get(1)?.let { "약 ${it}분" })
        }
    }
}

object CookingAdviceJson {
    fun request(step: String, notes: String? = null): String = JSONObject()
        .put("step", step)
        .apply { notes?.takeIf(String::isNotBlank)?.let { put("notes", it) } }
        .toString()

    fun response(body: String): CookingAdviceDecodeResult = try {
        val json = JSONObject(body)
        val status = when (json.getString("status")) {
            "continue" -> CookingAdviceStatus.CONTINUE
            "adjust" -> CookingAdviceStatus.ADJUST
            "stop" -> CookingAdviceStatus.STOP
            else -> return CookingAdviceDecodeResult.Invalid
        }
        val observations = nonBlankStrings(json.getJSONArray("observations"))
        val actions = nonBlankStrings(json.getJSONArray("nextActions"))
        val confidence = json.getDouble("confidence")
        val safety = json.getString("safetyNote").trim()
        if (observations.isEmpty() || actions.isEmpty() || confidence !in 0.0..1.0 || safety.isEmpty()) {
            CookingAdviceDecodeResult.Invalid
        } else {
            CookingAdviceDecodeResult.Success(
                CookingAdvice(status, observations, actions, confidence, safety),
            )
        }
    } catch (_: JSONException) {
        CookingAdviceDecodeResult.Invalid
    }

    private fun nonBlankStrings(array: JSONArray): List<String> = List(array.length()) { index ->
        array.getString(index).trim()
    }.takeIf { values -> values.all(String::isNotEmpty) }.orEmpty()
}

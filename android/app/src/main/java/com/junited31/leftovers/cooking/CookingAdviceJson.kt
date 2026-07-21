package com.junited31.leftovers.cooking

import com.junited31.leftovers.R
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.roundToInt

enum class CookingAdviceStatus { CONTINUE, ADJUST, STOP }

data class CookingAdvice(
    val status: CookingAdviceStatus,
    val observations: List<Int>,
    val nextActions: List<Int>,
    val confidence: Double,
    val safetyNote: Int,
) {
    val confidencePercent: Int = (confidence * 100).roundToInt()
}

sealed interface CookingAdviceDecodeResult {
    data class Success(val advice: CookingAdvice) : CookingAdviceDecodeResult
    data object Invalid : CookingAdviceDecodeResult
}

data class CookingStep(val text: String, val durationMinutes: Int?) {
    companion object {
        private val duration = Regex("(?:약\\s*)?(\\d{1,3})\\s*분")

        fun from(text: String): CookingStep {
            val match = duration.find(text)
            return CookingStep(text, match?.groupValues?.get(1)?.toInt())
        }
    }
}

object CookingAdviceJson {
    private val observationCopy = mapOf(
        "surface_browned" to R.string.observation_surface_browned,
        "surface_pale" to R.string.observation_surface_pale,
        "visible_moisture" to R.string.observation_visible_moisture,
        "visible_smoke" to R.string.observation_visible_smoke,
        "uneven_browning" to R.string.observation_uneven_browning,
    )
    private val actionCopy = mapOf(
        "check_center_temperature" to R.string.action_check_center_temperature,
        "turn_and_check_center_temperature" to R.string.action_turn_check_temperature,
        "turn_or_stir" to R.string.action_turn_or_stir,
        "lower_heat" to R.string.action_lower_heat,
        "continue_cooking" to R.string.action_continue_cooking,
        "turn_off_heat" to R.string.action_turn_off_heat,
    )

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
        val observations = approvedCopy(json.getJSONArray("observations"), observationCopy)
        val actions = approvedCopy(json.getJSONArray("nextActions"), actionCopy)
        val confidence = json.getDouble("confidence")
        val safety = json.getString("safetyNote").trim()
        if (
            observations.isEmpty() || actions.isEmpty() || confidence !in 0.0..1.0 || safety.isEmpty()
        ) {
            CookingAdviceDecodeResult.Invalid
        } else {
            CookingAdviceDecodeResult.Success(
                CookingAdvice(status, observations, actions, confidence, R.string.safety_guidance),
            )
        }
    } catch (_: JSONException) {
        CookingAdviceDecodeResult.Invalid
    }

    private fun approvedCopy(array: JSONArray, copy: Map<String, Int>): List<Int> {
        val codes = List(array.length()) { index -> array.getString(index).trim() }
        return codes.mapNotNull(copy::get).takeIf { it.size == codes.size }.orEmpty()
    }
}

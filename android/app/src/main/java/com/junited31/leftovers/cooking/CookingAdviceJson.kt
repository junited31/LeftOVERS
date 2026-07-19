package com.junited31.leftovers.cooking

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import kotlin.math.roundToInt

enum class CookingAdviceStatus { CONTINUE, ADJUST, STOP }

internal const val COOKING_SAFETY_GUIDANCE =
    "사진만으로 익음과 안전을 확인할 수 없어요. 시간과 온도를 확인하세요."

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
    private val observationCopy = mapOf(
        "surface_browned" to "표면이 노릇해졌어요",
        "surface_pale" to "표면 색이 아직 옅어 보여요",
        "visible_moisture" to "표면에 수분이 보여요",
        "visible_smoke" to "연기가 보여요",
        "uneven_browning" to "표면 색이 고르지 않아 보여요",
    )
    private val actionCopy = mapOf(
        "check_center_temperature" to "중심 온도를 확인하세요",
        "turn_and_check_center_temperature" to "뒤집고 중심 온도를 확인하세요",
        "turn_or_stir" to "뒤집거나 저어 주세요",
        "lower_heat" to "불을 낮춰 주세요",
        "continue_cooking" to "시간을 확인하며 더 조리하세요",
        "turn_off_heat" to "불을 끄고 연기 원인을 확인하세요",
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
                CookingAdvice(status, observations, actions, confidence, COOKING_SAFETY_GUIDANCE),
            )
        }
    } catch (_: JSONException) {
        CookingAdviceDecodeResult.Invalid
    }

    private fun approvedCopy(array: JSONArray, copy: Map<String, String>): List<String> {
        val codes = List(array.length()) { index -> array.getString(index).trim() }
        return codes.mapNotNull(copy::get).takeIf { it.size == codes.size }.orEmpty()
    }
}

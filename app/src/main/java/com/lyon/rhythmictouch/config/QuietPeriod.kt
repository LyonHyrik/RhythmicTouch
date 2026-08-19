package com.lyon.rhythmictouch.config

import org.json.JSONArray
import org.json.JSONObject

data class QuietPeriod(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val startHour: Int = 22,
    val startMinute: Int = 0,
    val endHour: Int = 7,
    val endMinute: Int = 0,
    val enabled: Boolean = true,
    val repeatDaily: Boolean = true,
    val lastTriggeredDate: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("startHour", startHour)
        put("startMinute", startMinute)
        put("endHour", endHour)
        put("endMinute", endMinute)
        put("enabled", enabled)
        put("repeatDaily", repeatDaily)
        put("lastTriggeredDate", lastTriggeredDate)
    }

    fun timeString(): String {
        val s = "%02d:%02d".format(startHour, startMinute)
        val e = "%02d:%02d".format(endHour, endMinute)
        return "$s - $e"
    }

    fun isActiveNow(now: java.util.Calendar = java.util.Calendar.getInstance()): Boolean {
        if (!enabled) return false
        if (!repeatDaily) {
            val today = "%04d-%02d-%02d".format(
                now.get(java.util.Calendar.YEAR),
                now.get(java.util.Calendar.MONTH) + 1,
                now.get(java.util.Calendar.DAY_OF_MONTH),
            )
            if (lastTriggeredDate == today) return false
        }

        val nowMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
        val startMinutes = startHour * 60 + startMinute
        val endMinutes = endHour * 60 + endMinute

        return if (startMinutes <= endMinutes) {
            nowMinutes in startMinutes until endMinutes
        } else {
            nowMinutes >= startMinutes || nowMinutes < endMinutes
        }
    }

    fun markTriggered(now: java.util.Calendar = java.util.Calendar.getInstance()): QuietPeriod {
        val today = "%04d-%02d-%02d".format(
            now.get(java.util.Calendar.YEAR),
            now.get(java.util.Calendar.MONTH) + 1,
            now.get(java.util.Calendar.DAY_OF_MONTH),
        )
        return copy(lastTriggeredDate = today)
    }

    companion object {
        fun fromJson(obj: JSONObject): QuietPeriod = QuietPeriod(
            id = obj.optString("id", java.util.UUID.randomUUID().toString()),
            name = obj.optString("name", ""),
            startHour = obj.optInt("startHour", 22),
            startMinute = obj.optInt("startMinute", 0),
            endHour = obj.optInt("endHour", 7),
            endMinute = obj.optInt("endMinute", 0),
            enabled = obj.optBoolean("enabled", true),
            repeatDaily = obj.optBoolean("repeatDaily", true),
            lastTriggeredDate = obj.optString("lastTriggeredDate", ""),
        )

        fun toJsonList(list: List<QuietPeriod>): String =
            JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()

        fun fromJsonList(json: String?): List<QuietPeriod> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val arr = JSONArray(json)
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let { QuietPeriod.fromJson(it) } }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}

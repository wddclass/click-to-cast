package com.wddclass.clicktocast.prefs

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class CastRecord(
    val at: Long,
    val title: String,
    val source: String,
    val quality: String,
    val outcome: String,
    val session: Long,
)

/** Local cast list. Titles and source labels only — media URLs are not stored. */
class CastHistory(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<CastRecord> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        CastRecord(
                            at = item.optLong("at"),
                            title = item.optString("title"),
                            source = item.optString("source"),
                            quality = item.optString("quality"),
                            outcome = item.optString("outcome"),
                            session = item.optLong("session"),
                        ),
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(record: CastRecord) {
        val next = (listOf(record) + list()).take(40)
        write(next)
    }

    fun updateSession(session: Long, outcome: String, quality: String) {
        if (session == 0L) return
        val next = list().map { record ->
            if (record.session == session) {
                record.copy(
                    outcome = outcome,
                    quality = quality.ifBlank { record.quality },
                )
            } else {
                record
            }
        }
        write(next)
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun write(records: List<CastRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("at", record.at)
                    .put("title", record.title)
                    .put("source", record.source)
                    .put("quality", record.quality)
                    .put("outcome", record.outcome)
                    .put("session", record.session),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        private const val PREFS = "clicktocast_history"
        private const val KEY = "items"
    }
}

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
    val uri: String = "",
    val userAgent: String = "",
    val headers: Map<String, String> = emptyMap(),
) {
    /** True when this entry still has the address that was playing. */
    val canReplay: Boolean
        get() = uri.startsWith("http://") || uri.startsWith("https://")
}

/**
 * Local cast list. New rows keep the http(s) URL and the headers used to fetch it
 * so OK can replay them. Rows saved by older builds have no URL and cannot be replayed.
 */
class CastHistory(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<CastRecord> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val headers = linkedMapOf<String, String>()
                    val headerJson = item.optJSONObject("headers")
                    if (headerJson != null) {
                        val keys = headerJson.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            headers[key] = headerJson.optString(key)
                        }
                    }
                    add(
                        CastRecord(
                            at = item.optLong("at"),
                            title = item.optString("title"),
                            source = item.optString("source"),
                            quality = item.optString("quality"),
                            outcome = item.optString("outcome"),
                            session = item.optLong("session"),
                            uri = item.optString("uri"),
                            userAgent = item.optString("ua"),
                            headers = headers,
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
            val headerJson = JSONObject()
            record.headers.entries.take(12).forEach { (key, value) ->
                if (key.isNotBlank() && value.isNotBlank()) {
                    headerJson.put(key.take(80), value.take(300))
                }
            }
            array.put(
                JSONObject()
                    .put("at", record.at)
                    .put("title", record.title)
                    .put("source", record.source)
                    .put("quality", record.quality)
                    .put("outcome", record.outcome)
                    .put("session", record.session)
                    .put("uri", record.uri.take(4000))
                    .put("ua", record.userAgent.take(300))
                    .put("headers", headerJson),
            )
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    companion object {
        private const val PREFS = "clicktocast_history"
        private const val KEY = "items"
    }
}

package dev.smantics.scribe.history

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Opt-in transcript history, ported from the desktop `history.py` including its contract.
 *
 * Off by default, and when off **nothing is read or written at all** — not an empty file,
 * not a zero-length record. The file only comes into existence if the user asks for it.
 *
 * Every disk operation is best-effort and never throws. A corrupt or unreadable history is
 * an empty list plus a log line, never a crash: losing the log of what you dictated is a
 * nuisance, losing the ability to dictate is not.
 */
class HistoryStore(context: Context) {

    private val file = File(context.filesDir, "history.json")

    data class Entry(
        val text: String,
        val raw: String,
        val backend: String,
        val seconds: Double,
        val at: Long,
    )

    fun load(): List<Entry> = runCatching {
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText())
        buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                add(
                    Entry(
                        text = o.optString("text"),
                        raw = o.optString("raw"),
                        backend = o.optString("backend"),
                        seconds = o.optDouble("seconds", 0.0),
                        at = o.optLong("at", 0L),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized
    fun prepend(text: String, raw: String, backend: String, seconds: Double) {
        runCatching {
            val entries = load().toMutableList()
            entries.add(
                0,
                Entry(text, raw, backend, seconds, System.currentTimeMillis()),
            )
            save(entries.take(CAP))
        }
    }

    @Synchronized
    fun clear() {
        runCatching { file.delete() }
    }

    private fun save(entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { e ->
            array.put(
                JSONObject().apply {
                    put("text", e.text)
                    put("raw", e.raw)
                    put("backend", e.backend)
                    put("seconds", e.seconds)
                    put("at", e.at)
                },
            )
        }
        file.writeText(array.toString())
    }

    private companion object {
        /** The desktop's cap, unchanged. Oldest entries fall off silently. */
        const val CAP = 500
    }
}

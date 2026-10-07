package dev.kapil.healthcal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** An event you removed in the app. Its tag is never written to a calendar again. */
data class RemovedEvent(
    val tag: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val removedAt: Long,
)

/**
 * The list of events you chose not to sync, kept in a small JSON file.
 * Every sync checks it: a removed event is skipped, and deleted if it is
 * still in the calendar. Restoring an event takes it off this list.
 */
object RemovedEvents {
    private val lock = Any()

    private fun file(context: Context) = File(context.applicationContext.filesDir, "removed_events.json")

    fun all(context: Context): List<RemovedEvent> = synchronized(lock) { read(context) }

    fun tags(context: Context): Set<String> = all(context).mapTo(HashSet()) { it.tag }

    fun add(context: Context, event: RemovedEvent) {
        synchronized(lock) {
            write(context, read(context).filter { it.tag != event.tag } + event)
        }
    }

    fun restore(context: Context, tag: String) {
        synchronized(lock) {
            write(context, read(context).filter { it.tag != tag })
        }
    }

    private fun read(context: Context): List<RemovedEvent> = runCatching {
        val f = file(context)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            RemovedEvent(
                tag = o.getString("tag"),
                title = o.optString("title"),
                startMs = o.getLong("start"),
                endMs = o.getLong("end"),
                removedAt = o.optLong("removedAt"),
            )
        }
    }.getOrDefault(emptyList())

    private fun write(context: Context, events: List<RemovedEvent>) {
        val arr = JSONArray()
        events.forEach { e ->
            arr.put(
                JSONObject()
                    .put("tag", e.tag)
                    .put("title", e.title)
                    .put("start", e.startMs)
                    .put("end", e.endMs)
                    .put("removedAt", e.removedAt)
            )
        }
        val target = file(context)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(target)) {
            target.writeText(arr.toString())
            tmp.delete()
        }
    }
}

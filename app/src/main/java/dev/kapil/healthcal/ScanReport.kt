package dev.kapil.healthcal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What happened to one Health Connect record during a scan. */
enum class ScanResult(val label: String) {
    ADDED("Added"),
    FIXED("Updated"),
    SAME("In calendar"),
    FAILED("Couldn't write"),
    IGNORED("Source ignored"),
    REMOVED("Removed by you"),
}

/** One sleep session or workout found in Health Connect, and the event made from it. */
data class ScanItem(
    val tag: String,
    val kind: Kind,
    val startMs: Long,
    val endMs: Long,
    val title: String,     // exact calendar event title
    val source: String,    // app that wrote the record
    val calendar: String,  // calendar the event goes to
    val result: ScanResult,
)

data class ScanReport(
    val ranAt: Long,
    val fromMs: Long,
    val toMs: Long,
    val items: List<ScanItem>,
)

/** Keeps the latest scan in a small JSON file so the Found tab survives restarts. */
object ScanReportStore {
    private fun file(context: Context) = File(context.applicationContext.filesDir, "last_scan.json")

    fun save(context: Context, report: ScanReport) {
        val items = JSONArray()
        report.items.forEach { i ->
            items.put(
                JSONObject()
                    .put("tag", i.tag)
                    .put("kind", i.kind.name)
                    .put("start", i.startMs)
                    .put("end", i.endMs)
                    .put("title", i.title)
                    .put("source", i.source)
                    .put("calendar", i.calendar)
                    .put("result", i.result.name)
            )
        }
        val root = JSONObject()
            .put("ranAt", report.ranAt)
            .put("from", report.fromMs)
            .put("to", report.toMs)
            .put("items", items)

        val target = file(context)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(target)) {
            target.writeText(root.toString())
            tmp.delete()
        }
    }

    fun load(context: Context): ScanReport? = runCatching {
        val f = file(context)
        if (!f.exists()) return null
        val root = JSONObject(f.readText())
        val arr = root.getJSONArray("items")
        val items = (0 until arr.length()).mapNotNull { idx ->
            val o = arr.getJSONObject(idx)
            val kind = runCatching { Kind.valueOf(o.getString("kind")) }.getOrNull() ?: return@mapNotNull null
            val result = runCatching { ScanResult.valueOf(o.getString("result")) }.getOrNull() ?: return@mapNotNull null
            ScanItem(
                tag = o.getString("tag"),
                kind = kind,
                startMs = o.getLong("start"),
                endMs = o.getLong("end"),
                title = o.getString("title"),
                source = o.optString("source"),
                calendar = o.optString("calendar"),
                result = result,
            )
        }
        ScanReport(root.getLong("ranAt"), root.getLong("from"), root.getLong("to"), items)
    }.getOrNull()
}

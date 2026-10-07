package dev.kapil.healthcal

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

enum class SignalType { ARRIVE, LEAVE, CAR_ON, CAR_OFF, SHUTTLE }

/** One raw observation: "geofence said I left home at 8:05", "car connected at 8:07". */
data class Signal(
    val id: Long,
    val ts: Long,
    val type: SignalType,
    val place: String?,  // Place.key for ARRIVE / LEAVE, otherwise null
    val source: String,  // "location", "wifi" or "bluetooth"
    val detail: String,  // Wi-Fi name, device name, ...
)

/**
 * Tiny local log of signals. Receivers only append here (cheap); the sync job
 * later turns the log into calendar events.
 */
class SignalLog private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "signals.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE signals (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "ts INTEGER NOT NULL, " +
                "type TEXT NOT NULL, " +
                "place TEXT, " +
                "source TEXT NOT NULL, " +
                "detail TEXT NOT NULL)"
        )
        db.execSQL("CREATE INDEX signals_ts ON signals(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun add(ts: Long, type: SignalType, place: String?, source: String, detail: String) {
        val values = ContentValues().apply {
            put("ts", ts)
            put("type", type.name)
            put("place", place)
            put("source", source)
            put("detail", detail)
        }
        writableDatabase.insert("signals", null, values)
    }

    fun between(fromMs: Long, toMs: Long): List<Signal> =
        query("ts >= ? AND ts <= ?", arrayOf(fromMs.toString(), toMs.toString()), "ts ASC, id ASC", null)

    fun recent(limit: Int): List<Signal> = query(null, null, "ts DESC, id DESC", limit.toString())

    fun hasRecent(type: SignalType, place: String?, sinceMs: Long): Boolean {
        val selection = if (place == null) "type = ? AND place IS NULL AND ts >= ?" else "type = ? AND place = ? AND ts >= ?"
        val args = if (place == null) arrayOf(type.name, sinceMs.toString()) else arrayOf(type.name, place, sinceMs.toString())
        return readableDatabase.query("signals", arrayOf("id"), selection, args, null, null, null, "1")
            .use { c -> c.moveToFirst() }
    }

    /** The most recent arrive / leave signal for a place, from any source. */
    fun lastForPlace(place: String): Signal? =
        query("place = ?", arrayOf(place), "ts DESC, id DESC", "1").firstOrNull()

    fun pruneOlderThan(ms: Long) {
        writableDatabase.delete("signals", "ts < ?", arrayOf(ms.toString()))
    }

    private fun query(selection: String?, args: Array<String>?, order: String, limit: String?): List<Signal> {
        val out = mutableListOf<Signal>()
        readableDatabase.query(
            "signals",
            arrayOf("id", "ts", "type", "place", "source", "detail"),
            selection, args, null, null, order, limit,
        ).use { c ->
            while (c.moveToNext()) {
                val type = runCatching { SignalType.valueOf(c.getString(2)) }.getOrNull() ?: continue
                out += Signal(
                    id = c.getLong(0),
                    ts = c.getLong(1),
                    type = type,
                    place = if (c.isNull(3)) null else c.getString(3),
                    source = c.getString(4),
                    detail = c.getString(5),
                )
            }
        }
        return out
    }

    companion object {
        @Volatile private var instance: SignalLog? = null

        fun get(context: Context): SignalLog =
            instance ?: synchronized(this) { instance ?: SignalLog(context).also { instance = it } }
    }
}

/** Entry point for receivers: log a signal and schedule a quick calendar update. */
object Signals {
    private const val WIFI_DEDUPE_MS = 20 * 60_000L

    fun record(context: Context, ts: Long, type: SignalType, place: String?, source: String, detail: String) {
        val log = SignalLog.get(context)
        // Wi-Fi can report the same network repeatedly; one entry per 20 minutes is enough.
        if (source == "wifi") {
            val repeat = if (place != null) {
                // Only a repeat if nothing happened since: an exit in between makes this a real return.
                val last = log.lastForPlace(place)
                last != null && last.type == type && ts - last.ts < WIFI_DEDUPE_MS
            } else {
                log.hasRecent(type, null, ts - WIFI_DEDUPE_MS)
            }
            if (repeat) return
        }
        log.add(ts, type, place, source, detail)
        Prefs(context).signalsChangedAt = System.currentTimeMillis()
        SyncWorker.schedulePlaces(context, afterLeave = type == SignalType.LEAVE)
    }
}

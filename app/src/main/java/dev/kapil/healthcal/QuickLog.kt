package dev.kapil.healthcal

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/** A button on the Quick log widget and tab. */
data class Tile(val id: String, val label: String, val emoji: String, val on: Boolean = true, val custom: Boolean = false)

/** One thing you logged: "Coffee at 3:39 AM for 15 min, oat latte". */
data class LogEntry(
    val id: String,
    val tileId: String,
    val label: String,
    val emoji: String,
    val startMs: Long,
    val minutes: Int,
    val note: String = "",
) {
    val endMs: Long get() = startMs + minutes * 60_000L
}

/**
 * Quick log: one tap writes a short event, "Coffee | At: 3:54 am", to your
 * calendar. Entries are kept on the phone and mirrored to the calendar, so
 * editing or deleting one here changes or removes its event.
 */
object QuickLog {
    const val TAG_ROOT = "ql:"
    const val DEFAULT_MINUTES = 15

    /** After a widget tap, that tile ignores further taps for this long, so a double tap logs once. */
    const val COOLDOWN_MS = 2 * 60_000L

    /** How long the "Coffee logged" line with Edit and Undo stays on the widget. */
    const val CONFIRM_MS = 15_000L

    private val widgetTaps = Mutex()
    private const val KEEP_DAYS = 30L
    private const val PREFS = "quick_log"

    val DEFAULT_TILES = listOf(
        Tile("coffee", "Coffee", "☕"),
        Tile("meal", "Meal", "🍽️"),
        Tile("snack", "Snack", "🍪"),
        Tile("water", "Water", "💧"),
        Tile("alcohol", "Alcohol", "🍷"),
        Tile("meds", "Meds", "💊"),
        Tile("intimate", "Intimate", "❤️"),
        Tile("nap", "Nap", "😴"),
        Tile("mood", "Mood", "🙂"),
    )

    // ------------------------------------------------------------ pure

    /** Calendar events for [entries]: "Coffee | At: 3:54 am", with the note in the description. */
    fun events(entries: List<LogEntry>, calendarId: Long): List<DesiredEvent> = entries.map { e ->
        val tag = TAG_ROOT + e.id
        val note = if (e.note.isBlank()) "" else e.note.trim() + "\n"
        DesiredEvent(
            tag = tag,
            calendarId = calendarId,
            startMs = e.startMs,
            endMs = e.endMs,
            title = EventFormatter.markerTitle(e.label, Instant.ofEpochMilli(e.startMs)),
            description = "${note}Logged with Quick log\n\n${EventFormatter.TAG_PREFIX}$tag]",
        )
    }

    /** How many of each tile were logged on [day]. */
    fun countsOn(entries: List<LogEntry>, day: LocalDate, zone: ZoneId): Map<String, Int> =
        entries.filter { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() == day }
            .groupingBy { it.tileId }.eachCount()

    /** Ids are the tap time in base 36 plus two random characters. */
    /** The entry that keeps [tileId] locked at [now], if it was tapped less than [COOLDOWN_MS] ago. */
    fun coolingDown(entries: List<LogEntry>, tileId: String, now: Long): LogEntry? =
        entries.filter { it.tileId == tileId && now - tappedAt(it) in 0 until COOLDOWN_MS }.maxByOrNull { tappedAt(it) }

    /** The entry to confirm on the widget at [now]: the newest one tapped less than [CONFIRM_MS] ago. */
    fun toConfirm(entries: List<LogEntry>, now: Long): LogEntry? =
        entries.maxByOrNull { tappedAt(it) }?.takeIf { now - tappedAt(it) in 0 until CONFIRM_MS }

    fun newId(now: Long): String = now.toString(36) + Random.nextInt(36 * 36).toString(36).padStart(2, '0')

    /** When the entry was logged (not when it happened). */
    fun tappedAt(e: LogEntry): Long = e.id.dropLast(2).toLongOrNull(36) ?: 0L

    // ------------------------------------------------------------ storage

    private fun sp(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun tiles(context: Context): List<Tile> {
        val raw = sp(context).getString("tiles", null) ?: return DEFAULT_TILES
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Tile(o.getString("id"), o.getString("label"), o.getString("emoji"), o.getBoolean("on"), o.optBoolean("custom"))
            }
        }.getOrDefault(DEFAULT_TILES)
    }

    fun saveTiles(context: Context, tiles: List<Tile>) {
        val a = JSONArray()
        tiles.forEach {
            a.put(JSONObject().put("id", it.id).put("label", it.label).put("emoji", it.emoji).put("on", it.on).put("custom", it.custom))
        }
        sp(context).edit().putString("tiles", a.toString()).apply()
    }

    /** All entries from the last 30 days, newest first. */
    fun entries(context: Context): List<LogEntry> {
        val raw = sp(context).getString("entries", null) ?: return emptyList()
        val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                LogEntry(
                    id = o.getString("id"),
                    tileId = o.getString("tile"),
                    label = o.getString("label"),
                    emoji = o.optString("emoji"),
                    startMs = o.getLong("start"),
                    minutes = o.optInt("minutes", DEFAULT_MINUTES),
                    note = o.optString("note"),
                )
            }
        }.getOrDefault(emptyList()).filter { it.startMs >= cutoff }.sortedByDescending { it.startMs }
    }

    private fun saveEntries(context: Context, entries: List<LogEntry>) {
        val a = JSONArray()
        entries.forEach {
            a.put(
                JSONObject().put("id", it.id).put("tile", it.tileId).put("label", it.label).put("emoji", it.emoji)
                    .put("start", it.startMs).put("minutes", it.minutes).put("note", it.note),
            )
        }
        sp(context).edit().putString("entries", a.toString()).apply()
    }

    @Synchronized
    fun put(context: Context, entry: LogEntry) {
        saveEntries(context, entries(context).filter { it.id != entry.id } + entry)
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        saveEntries(context, entries(context).filter { it.id != id })
    }

    /** The calendar chosen for Quick log, or else the places calendar, or else the sleep calendar. */
    fun calendarId(context: Context): Long {
        val own = sp(context).getLong("calendar", -1)
        if (own >= 0) return own
        val places = PlacesConfig(context).calendarId
        if (places >= 0) return places
        return Prefs(context).sleepCalendarId
    }

    fun setCalendarId(context: Context, id: Long) = sp(context).edit().putLong("calendar", id).apply()

    // ------------------------------------------------------------ actions

    /** Log [tile] now (or at [startMs]) and write it to the calendar. Returns the entry. */
    suspend fun log(context: Context, tile: Tile, startMs: Long = System.currentTimeMillis(), minutes: Int = DEFAULT_MINUTES, note: String = ""): LogEntry {
        val e = LogEntry(newId(System.currentTimeMillis()), tile.id, tile.label, tile.emoji, startMs, minutes, note)
        put(context, e)
        changed(context)
        return e
    }

    /**
     * A widget tap. Taps on a tile that was logged in the last two minutes are
     * ignored, and taps are handled one at a time, so a double tap logs once.
     */
    suspend fun logFromWidget(context: Context, tileId: String): LogEntry? {
        val entry = widgetTaps.withLock {
            val tile = tiles(context).firstOrNull { it.id == tileId } ?: return null
            val now = System.currentTimeMillis()
            if (coolingDown(entries(context), tileId, now) != null) return null
            LogEntry(newId(now), tile.id, tile.label, tile.emoji, now, DEFAULT_MINUTES).also { put(context, it) }
        }
        changed(context)
        QuickLogRefresh.scheduleFor(context, tappedAt(entry))
        return entry
    }

    suspend fun update(context: Context, entry: LogEntry) {
        put(context, entry)
        changed(context)
    }

    suspend fun delete(context: Context, id: String) {
        remove(context, id)
        changed(context)
    }

    private suspend fun changed(context: Context) {
        // Redraw first so the tap shows at once; writing the calendar can take a moment.
        runCatching { QuickLogWidget.refresh(context) }
        runCatching { sync(context) }
    }

    /** Make the calendar match the entries. Safe to call any time; does nothing without calendar access. */
    suspend fun sync(context: Context): String = SyncLock.mutex.withLock {
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            if (!hasCalendarPermission(app)) return@withContext "Quick log: calendar access needed."
            val calendarId = calendarId(app)
            if (calendarId < 0) return@withContext "Quick log: choose a calendar."
            val now = System.currentTimeMillis()
            val from = now - KEEP_DAYS * 86_400_000L
            val c = CalendarReconciler.apply(
                context = app,
                calendarId = calendarId,
                desired = events(entries(app), calendarId),
                windowStartMs = from - 86_400_000L,
                windowEndMs = now + 2 * 86_400_000L,
                staleTagPrefix = TAG_ROOT,
                staleFromMs = from,
            )
            "Quick log: added ${c.added}, fixed ${c.fixed}, removed ${c.removed + c.duplicates}."
        }
    }
}

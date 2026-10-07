package dev.kapil.healthcal

import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import java.util.TimeZone

data class CalendarInfo(val id: Long, val name: String, val account: String)

/** Everything that decides whether events in a calendar reach Google. */
data class CalendarStatus(
    val id: Long,
    val name: String,
    val account: String,
    val accountType: String,
    val visible: Boolean,
    val syncEvents: Boolean,
    val accountSyncOn: Boolean?, // Calendar switch under the account in Settings; null if unknown
    val masterSyncOn: Boolean?,  // phone-wide auto-sync; null if unknown
) {
    val isLocal: Boolean
        get() = accountType.isBlank() || accountType == CalendarContract.ACCOUNT_TYPE_LOCAL
}

enum class Upload { ON_SERVER, WAITING, UNKNOWN }

/** One of our events as it sits in the phone's calendar database. */
data class OurEvent(
    val eventId: Long,
    val calendarId: Long,
    val tag: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val upload: Upload,
)

/** An event already in the calendar that carries one of our "[hc:...]" tags. */
data class TaggedEvent(
    val eventId: Long,
    val tag: String,
    val startMs: Long,
    val endMs: Long,
    val title: String,
    val description: String,
)

/**
 * Talks to Android's Calendar Provider. Events written here sync to Google
 * Calendar through the normal account sync, so no cloud API is involved.
 */
object CalendarRepo {
    private val TAG_REGEX = Regex("""\[hc:([^\]]+)]""")

    fun listWritableCalendars(context: Context): List<CalendarInfo> {
        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
        )
        val selection = "${Calendars.CALENDAR_ACCESS_LEVEL} >= ?"
        val args = arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        val sort = "${Calendars.ACCOUNT_NAME} ASC, ${Calendars.CALENDAR_DISPLAY_NAME} ASC"

        val out = mutableListOf<CalendarInfo>()
        context.contentResolver.query(Calendars.CONTENT_URI, projection, selection, args, sort)?.use { c ->
            while (c.moveToNext()) {
                out += CalendarInfo(
                    id = c.getLong(0),
                    name = c.getString(1) ?: "Untitled calendar",
                    account = c.getString(2) ?: "",
                )
            }
        }
        return out
    }

    /** All of our tagged events in [calendarId] whose start falls in the window. */
    fun queryTaggedEvents(context: Context, calendarId: Long, fromMs: Long, toMs: Long): List<TaggedEvent> {
        val projection = arrayOf(
            Events._ID,
            Events.DTSTART,
            Events.DTEND,
            Events.TITLE,
            Events.DESCRIPTION,
        )
        val selection = "${Events.CALENDAR_ID} = ? AND ${Events.DTSTART} >= ? AND ${Events.DTSTART} <= ? " +
            "AND ${Events.DELETED} = 0 AND ${Events.DESCRIPTION} LIKE ?"
        val args = arrayOf(
            calendarId.toString(),
            fromMs.toString(),
            toMs.toString(),
            "%${EventFormatter.TAG_PREFIX}%",
        )

        val out = mutableListOf<TaggedEvent>()
        context.contentResolver.query(Events.CONTENT_URI, projection, selection, args, null)?.use { c ->
            while (c.moveToNext()) {
                val description = c.getString(4) ?: continue
                val tag = TAG_REGEX.find(description)?.groupValues?.get(1) ?: continue
                out += TaggedEvent(
                    eventId = c.getLong(0),
                    tag = tag,
                    startMs = c.getLong(1),
                    endMs = c.getLong(2),
                    title = c.getString(3) ?: "",
                    description = description,
                )
            }
        }
        return out
    }

    fun calendarStatus(context: Context, calendarId: Long): CalendarStatus? {
        val projection = arrayOf(
            Calendars._ID,
            Calendars.CALENDAR_DISPLAY_NAME,
            Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE,
            Calendars.VISIBLE,
            Calendars.SYNC_EVENTS,
        )
        val uri = ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId)
        val row = context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (!c.moveToFirst()) null else Triple(
                c.getString(1) ?: "Untitled calendar",
                (c.getString(2) ?: "") to (c.getString(3) ?: ""),
                (c.getInt(4) == 1) to (c.getInt(5) == 1),
            )
        } ?: return null

        val (name, account, flags) = row
        val (accountName, accountType) = account
        val isLocal = accountType.isBlank() || accountType == CalendarContract.ACCOUNT_TYPE_LOCAL
        val accountSync = if (isLocal || accountName.isBlank()) null else runCatching {
            ContentResolver.getSyncAutomatically(Account(accountName, accountType), CalendarContract.AUTHORITY)
        }.getOrNull()
        val masterSync = runCatching { ContentResolver.getMasterSyncAutomatically() }.getOrNull()

        return CalendarStatus(
            id = calendarId,
            name = name,
            account = accountName,
            accountType = accountType,
            visible = flags.first,
            syncEvents = flags.second,
            accountSyncOn = accountSync,
            masterSyncOn = masterSync,
        )
    }

    /** Our tagged events in these calendars, newest first, with upload state when Android shares it. */
    fun listOurEvents(context: Context, calendarIds: Collection<Long>, fromMs: Long, toMs: Long): List<OurEvent> {
        if (calendarIds.isEmpty()) return emptyList()
        val placeholders = calendarIds.joinToString(",") { "?" }
        val selection = "${Events.CALENDAR_ID} IN ($placeholders) AND ${Events.DTSTART} >= ? AND " +
            "${Events.DTSTART} <= ? AND ${Events.DELETED} = 0 AND ${Events.DESCRIPTION} LIKE ?"
        val args = calendarIds.map { it.toString() }.toTypedArray() +
            arrayOf(fromMs.toString(), toMs.toString(), "%${EventFormatter.TAG_PREFIX}%")
        val base = arrayOf(
            Events._ID,
            Events.CALENDAR_ID,
            Events.DTSTART,
            Events.DTEND,
            Events.TITLE,
            Events.DESCRIPTION,
        )
        val sort = "${Events.DTSTART} DESC"

        fun read(withSync: Boolean): List<OurEvent> {
            val projection = if (withSync) base + arrayOf(Events._SYNC_ID, Events.DIRTY) else base
            val out = mutableListOf<OurEvent>()
            context.contentResolver.query(Events.CONTENT_URI, projection, selection, args, sort)?.use { c ->
                while (c.moveToNext()) {
                    val description = c.getString(5) ?: continue
                    val tag = TAG_REGEX.find(description)?.groupValues?.get(1) ?: continue
                    val upload = when {
                        !withSync -> Upload.UNKNOWN
                        c.isNull(6) || c.getInt(7) == 1 -> Upload.WAITING
                        else -> Upload.ON_SERVER
                    }
                    out += OurEvent(
                        eventId = c.getLong(0),
                        calendarId = c.getLong(1),
                        tag = tag,
                        title = c.getString(4) ?: "",
                        startMs = c.getLong(2),
                        endMs = if (c.isNull(3)) c.getLong(2) else c.getLong(3),
                        upload = upload,
                    )
                }
            }
            return out
        }

        // Some phones don't let apps read the sync columns; fall back to plain events.
        return runCatching { read(withSync = true) }.getOrElse { read(withSync = false) }
    }

    /** Turns sync and visibility back on for a calendar that was switched off on this phone. */
    fun enableSync(context: Context, calendarId: Long): Boolean = runCatching {
        val values = ContentValues().apply {
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.VISIBLE, 1)
        }
        val uri = ContentUris.withAppendedId(Calendars.CONTENT_URI, calendarId)
        context.contentResolver.update(uri, values, null, null) > 0
    }.getOrDefault(false)

    /**
     * Asks Android to sync this account's calendars right now. A manual request
     * runs even when auto-sync is off.
     */
    fun requestSync(accountName: String, accountType: String): Boolean = runCatching {
        val extras = Bundle().apply {
            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
        }
        ContentResolver.requestSync(Account(accountName, accountType), CalendarContract.AUTHORITY, extras)
        true
    }.getOrDefault(false)

    fun insert(context: Context, e: DesiredEvent): Boolean {
        val values = e.toValues().apply { put(Events.CALENDAR_ID, e.calendarId) }
        return context.contentResolver.insert(Events.CONTENT_URI, values) != null
    }

    fun update(context: Context, eventId: Long, e: DesiredEvent): Boolean {
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        return context.contentResolver.update(uri, e.toValues(), null, null) > 0
    }

    fun delete(context: Context, eventId: Long): Boolean {
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        return context.contentResolver.delete(uri, null, null) > 0
    }

    private fun DesiredEvent.toValues() = ContentValues().apply {
        put(Events.DTSTART, startMs)
        put(Events.DTEND, endMs)
        put(Events.TITLE, title)
        put(Events.DESCRIPTION, description)
        put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        // Sleep and workouts shouldn't block your availability.
        put(Events.AVAILABILITY, Events.AVAILABILITY_FREE)
    }
}

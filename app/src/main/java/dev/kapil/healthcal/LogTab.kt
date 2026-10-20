package dev.kapil.healthcal

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the sheet is editing: a new entry for [tile], or an existing [entry]. */
private data class Draft(val tile: Tile, val entry: LogEntry?)

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
private fun time(ms: Long): String = TIME.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogTab(resumeTick: Int, editEntryId: String?, onEditHandled: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var rev by remember { mutableIntStateOf(0) }
    var tiles by remember { mutableStateOf(QuickLog.tiles(ctx)) }
    var entries by remember { mutableStateOf(emptyList<LogEntry>()) }
    var calendars by remember { mutableStateOf(emptyList<CalendarInfo>()) }
    var calendarId by remember { mutableStateOf(QuickLog.calendarId(ctx)) }
    var draft by remember { mutableStateOf<Draft?>(null) }
    var adding by remember { mutableStateOf(false) }
    val calGranted = hasCalendarPermission(ctx)

    LaunchedEffect(resumeTick, rev) {
        tiles = QuickLog.tiles(ctx)
        entries = withContext(Dispatchers.IO) { QuickLog.entries(ctx) }
        if (calGranted && calendars.isEmpty()) {
            calendars = withContext(Dispatchers.IO) { CalendarRepo.listWritableCalendars(ctx) }
        }
    }

    // Opened from the widget's Edit button.
    LaunchedEffect(editEntryId, entries) {
        if (editEntryId == null) return@LaunchedEffect
        val e = QuickLog.entries(ctx).firstOrNull { it.id == editEntryId }
        if (e != null) {
            val tile = tiles.firstOrNull { it.id == e.tileId } ?: Tile(e.tileId, e.label, e.emoji)
            draft = Draft(tile, e)
        }
        onEditHandled()
    }

    fun saveTiles(next: List<Tile>) {
        tiles = next
        QuickLog.saveTiles(ctx, next)
        scope.launch { runCatching { QuickLogWidget.refresh(ctx) } }
    }

    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val (todays, earlier) = entries.partition { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() == today }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!calGranted) {
            item(key = "perm") { Notice("Grant calendar access in the Setup tab so entries reach your calendar.", Tone.WAIT) }
        }
        item(key = "grid") {
            Section("Log something") {
                Text(
                    "Tap to log it now as a 15-minute event. You can change the time, length and add a note.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                tiles.filter { it.on }.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { t ->
                            Card(
                                Modifier
                                    .weight(1f)
                                    .clickable { draft = Draft(t, null) },
                            ) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(t.emoji, fontSize = 24.sp)
                                    Text(t.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                                }
                            }
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        item(key = "today") {
            Section("Today") {
                if (todays.isEmpty()) {
                    Text("Nothing logged yet today.", style = MaterialTheme.typography.bodySmall)
                }
                todays.forEach { e -> EntryRow(e, showDay = false) { draft = Draft(tileFor(tiles, e), e) } }
            }
        }

        if (earlier.isNotEmpty()) {
            item(key = "earlier") {
                Section("Earlier") {
                    earlier.take(40).forEach { e -> EntryRow(e, showDay = true) { draft = Draft(tileFor(tiles, e), e) } }
                }
            }
        }

        item(key = "tiles") {
            Section("Tiles") {
                Text(
                    "Switched-on tiles show here and on the Quick log widget (up to 9).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                tiles.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.emoji, fontSize = 20.sp, modifier = Modifier.width(36.dp))
                        Text(t.label, modifier = Modifier.weight(1f))
                        if (t.custom) {
                            TextButton(onClick = { saveTiles(tiles - t) }) { Text("Remove") }
                        }
                        Switch(checked = t.on, onCheckedChange = { on ->
                            saveTiles(tiles.map { if (it.id == t.id) it.copy(on = on) else it })
                        })
                    }
                }
                OutlinedButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Add a tile") }
            }
        }

        item(key = "calendar") {
            Section("Calendar for Quick log") {
                if (calGranted) {
                    CalendarPicker(calendars, calendarId) {
                        calendarId = it
                        QuickLog.setCalendarId(ctx, it)
                        scope.launch { runCatching { QuickLog.sync(ctx) } }
                    }
                } else {
                    Text("Needs calendar access.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    draft?.let { d ->
        ModalBottomSheet(
            onDismissRequest = { draft = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            EntrySheet(
                draft = d,
                onSave = { start, minutes, note ->
                    draft = null
                    scope.launch {
                        if (d.entry == null) {
                            QuickLog.log(ctx, d.tile, start, minutes, note)
                        } else {
                            QuickLog.update(ctx, d.entry.copy(startMs = start, minutes = minutes, note = note))
                        }
                        rev++
                    }
                },
                onDelete = d.entry?.let { e ->
                    fun() {
                        draft = null
                        scope.launch {
                            QuickLog.delete(ctx, e.id)
                            rev++
                        }
                    }
                },
            )
        }
    }

    if (adding) AddTileDialog(
        onDismiss = { adding = false },
        onAdd = { label, emoji ->
            adding = false
            val id = "custom-" + QuickLog.newId(System.currentTimeMillis())
            saveTiles(tiles + Tile(id, label.trim(), emoji.trim().ifEmpty { "⭐" }, on = true, custom = true))
        },
    )
}

private fun tileFor(tiles: List<Tile>, e: LogEntry) = tiles.firstOrNull { it.id == e.tileId } ?: Tile(e.tileId, e.label, e.emoji)

@Composable
private fun EntryRow(e: LogEntry, showDay: Boolean, onClick: () -> Unit) {
    StripeRow(Kind.LOG) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(e.emoji, fontSize = 20.sp, modifier = Modifier.width(36.dp))
            Column(Modifier.weight(1f)) {
                Text(e.label, style = MaterialTheme.typography.titleSmall)
                if (e.note.isNotBlank()) {
                    Text(e.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                (if (showDay) whenText(e.startMs) else time(e.startMs)) + " · ${e.minutes} min",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
    }
}

@Composable
private fun EntrySheet(draft: Draft, onSave: (Long, Int, String) -> Unit, onDelete: (() -> Unit)?) {
    val ctx = LocalContext.current
    val opened = remember { System.currentTimeMillis() }
    var start by remember { mutableStateOf(draft.entry?.startMs ?: opened) }
    var minutes by remember { mutableIntStateOf(draft.entry?.minutes ?: QuickLog.DEFAULT_MINUTES) }
    var note by remember { mutableStateOf(draft.entry?.note ?: "") }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(draft.tile.emoji, fontSize = 26.sp)
            Spacer(Modifier.width(10.dp))
            Text(draft.tile.label, style = MaterialTheme.typography.titleLarge)
        }

        Text("WHEN", style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Now", 15 to "15 min ago", 30 to "30 min ago", 60 to "1 h ago").forEach { (ago, label) ->
                val value = opened - ago * 60_000L
                FilterChip(selected = start == value, onClick = { start = value }, label = { Text(label) })
            }
            FilterChip(selected = false, onClick = {
                val t = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault())
                TimePickerDialog(ctx, { _, h, m ->
                    var picked = LocalDate.now().atTime(h, m).atZone(ZoneId.systemDefault())
                    if (picked.toInstant().toEpochMilli() > System.currentTimeMillis()) picked = picked.minusDays(1)
                    start = picked.toInstant().toEpochMilli()
                }, t.hour, t.minute, DateFormat.is24HourFormat(ctx)).show()
            }, label = { Text("Pick…") })
        }
        Text("Started ${whenText(start)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Text("HOW LONG", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(5, 15, 30, 60).forEach { m ->
                FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(if (m < 60) "$m min" else "1 h") })
            }
        }

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Note (optional)") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
        )

        Text(
            "In your calendar: ${draft.tile.label} | At: ${time(start).lowercase(Locale.getDefault())}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onDelete != null) OutlinedButton(onClick = onDelete) { Text("Delete") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { onSave(start, minutes, note) }) {
                Text(if (draft.entry == null) "Log it" else "Save")
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun AddTileDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var label by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a tile") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Name, e.g. Shower") }, singleLine = true)
                OutlinedTextField(value = emoji, onValueChange = { emoji = it.take(4) }, label = { Text("Emoji (optional)") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(enabled = label.isNotBlank(), onClick = { onAdd(label, emoji) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

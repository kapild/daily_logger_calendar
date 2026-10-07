package dev.kapil.healthcal

import android.Manifest
import android.app.DatePickerDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

class MainActivity : ComponentActivity() {
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The header is dark blue in both themes, so keep status bar icons light.
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        setContent { AppTheme { AppRoot(resumeTick) } }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++ // re-check permissions after returning from Settings / Health Connect
    }
}

private val TABS = listOf("Setup", "Found", "Events")

@Composable
fun AppRoot(resumeTick: Int) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // Keep the periodic job registered if auto-sync is on.
    LaunchedEffect(Unit) {
        if (Prefs(ctx).autoSync) SyncWorker.setPeriodic(ctx, true)
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Header(selected = tab, onSelect = { tab = it })
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
        ) {
            when (tab) {
                0 -> SetupTab(resumeTick)
                1 -> FoundTab(resumeTick)
                else -> EventsTab(resumeTick)
            }
        }
    }
}

@Composable
private fun Header(selected: Int, onSelect: (Int) -> Unit) {
    val colors = headerColors()
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.container)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Text(
            "HealthCal",
            style = MaterialTheme.typography.headlineSmall,
            color = colors.content,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp),
        )
        Text(
            "Sleep, workouts and places, written to your calendar",
            style = MaterialTheme.typography.bodySmall,
            color = colors.muted,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
                .selectableGroup(),
        ) {
            TABS.forEachIndexed { index, label ->
                val isSelected = index == selected
                Column(
                    Modifier
                        .weight(1f)
                        .selectable(selected = isSelected, onClick = { onSelect(index) }, role = Role.Tab)
                        .padding(top = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isSelected) colors.content else colors.muted,
                    )
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth(0.55f)
                            .height(3.dp)
                            .background(
                                if (isSelected) colors.accent else Color.Transparent,
                                RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
                            ),
                    )
                }
            }
        }
    }
}

@Composable
fun SetupTab(resumeTick: Int) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }

    var hcStatus by remember { mutableIntStateOf(HealthReader.sdkStatus(ctx)) }
    var granted by remember { mutableStateOf(emptySet<String>()) }
    var calGranted by remember { mutableStateOf(hasCalendarPermission(ctx)) }
    var calendars by remember { mutableStateOf(emptyList<CalendarInfo>()) }

    var sleepCal by remember { mutableLongStateOf(prefs.sleepCalendarId) }
    var exerciseCal by remember { mutableLongStateOf(prefs.exerciseCalendarId) }
    var exportSleep by remember { mutableStateOf(prefs.exportSleep) }
    var exportExercise by remember { mutableStateOf(prefs.exportExercise) }
    var autoSync by remember { mutableStateOf(prefs.autoSync) }
    var startDate by remember { mutableStateOf(prefs.backfillStart) }
    var excluded by remember { mutableStateOf(prefs.excludedSources) }
    var seen by remember { mutableStateOf(prefs.seenSources) }
    var status by remember { mutableStateOf(prefs.lastStatus) }
    var runningSince by remember { mutableLongStateOf(prefs.runningSince) }
    var lastRunAt by remember { mutableLongStateOf(prefs.lastRunAt) }

    // Live status updates from the background worker.
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            status = prefs.lastStatus
            runningSince = prefs.runningSince
            lastRunAt = prefs.lastRunAt
            seen = prefs.seenSources
        }
        prefs.sp.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    LaunchedEffect(resumeTick) {
        hcStatus = HealthReader.sdkStatus(ctx)
        if (hcStatus == HealthConnectClient.SDK_AVAILABLE) {
            granted = runCatching { HealthReader.grantedPermissions(ctx) }.getOrDefault(emptySet())
        }
        calGranted = hasCalendarPermission(ctx)
    }

    LaunchedEffect(calGranted, resumeTick) {
        if (calGranted) {
            calendars = withContext(Dispatchers.IO) { CalendarRepo.listWritableCalendars(ctx) }
        }
    }

    val hcLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { result -> granted = result }

    val calLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result -> calGranted = result.values.all { it } }

    val isRunning = runningSince > 0 && System.currentTimeMillis() - runningSince < 20 * 60_000L
    val dateLabel = startDate.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Section("1. Health Connect access") {
            if (hcStatus != HealthConnectClient.SDK_AVAILABLE) {
                Text("Health Connect isn't available on this phone (status $hcStatus). Update it from the Play Store, then reopen HealthCal.")
            } else {
                PermissionLine("Sleep", HealthReader.SLEEP in granted)
                PermissionLine("Exercise", HealthReader.EXERCISE in granted)
                PermissionLine("Sync in the background", HealthReader.BACKGROUND in granted)
                PermissionLine("Data older than 30 days", HealthReader.HISTORY in granted)
                if (!granted.containsAll(HealthReader.ALL_PERMISSIONS)) {
                    Button(onClick = { hcLauncher.launch(HealthReader.ALL_PERMISSIONS) }) {
                        Text("Grant Health Connect access")
                    }
                }
            }
        }

        Section("2. Calendars") {
            if (!calGranted) {
                Button(onClick = {
                    calLauncher.launch(
                        arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
                    )
                }) { Text("Grant calendar access") }
            } else {
                LabeledCheckbox("Export sleep", exportSleep) {
                    exportSleep = it
                    prefs.exportSleep = it
                }
                if (exportSleep) {
                    CalendarPicker(calendars, sleepCal) {
                        sleepCal = it
                        prefs.sleepCalendarId = it
                    }
                }
                LabeledCheckbox("Export workouts", exportExercise) {
                    exportExercise = it
                    prefs.exportExercise = it
                }
                if (exportExercise) {
                    CalendarPicker(calendars, exerciseCal) {
                        exerciseCal = it
                        prefs.exerciseCalendarId = it
                    }
                }
                if (exportSleep && exportExercise && sleepCal >= 0 && exerciseCal >= 0 && sleepCal != exerciseCal) {
                    Notice(
                        "Workouts go to a different calendar than sleep. If you can't find them, " +
                            "use the sleep calendar for both and run Backfill.",
                        Tone.WAIT,
                    )
                    TextButton(onClick = {
                        exerciseCal = sleepCal
                        prefs.exerciseCalendarId = sleepCal
                    }) { Text("Use the sleep calendar for workouts") }
                }
            }
        }

        Section("3. Data sources") {
            if (seen.isEmpty()) {
                Text(
                    "Apps that write sleep or workouts appear here after your first sync. " +
                        "Uncheck one to ignore it, for example to avoid duplicate events.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                seen.sorted().forEach { pkg ->
                    val name = remember(pkg) { appLabel(ctx, pkg) }
                    LabeledCheckbox(name, pkg !in excluded) { include ->
                        excluded = if (include) excluded - pkg else excluded + pkg
                        prefs.excludedSources = excluded
                    }
                }
            }
        }

        Section("4. Places and drives") {
            PlacesSection(resumeTick, calGranted, calendars)
        }

        Section("5. Sync") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Backfill from $dateLabel", Modifier.weight(1f))
                TextButton(onClick = {
                    DatePickerDialog(
                        ctx,
                        { _, y, m, d ->
                            val picked = LocalDate.of(y, m + 1, d)
                            startDate = picked
                            prefs.backfillStart = picked
                        },
                        startDate.year,
                        startDate.monthValue - 1,
                        startDate.dayOfMonth,
                    ).apply { datePicker.maxDate = System.currentTimeMillis() }.show()
                }) { Text("Change date") }
            }
            Button(
                onClick = { SyncWorker.runNow(ctx, SyncWorker.MODE_BACKFILL) },
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Backfill and repair from $dateLabel") }
            OutlinedButton(
                onClick = { SyncWorker.runNow(ctx, SyncWorker.MODE_RECENT) },
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sync last ${SyncWorker.RECENT_DAYS} days") }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Sync automatically")
                    Text(
                        "Every 6 hours, re-checks the last ${SyncWorker.RECENT_DAYS} days and fills any gaps.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = autoSync, onCheckedChange = {
                    autoSync = it
                    prefs.autoSync = it
                    SyncWorker.setPeriodic(ctx, it)
                })
            }

            HorizontalDivider()
            if (isRunning) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(status, style = MaterialTheme.typography.bodyMedium)
            if (lastRunAt > 0) {
                val finished = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(lastRunAt))
                Text("Last finished $finished", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun PermissionLine(name: String, ok: Boolean) {
    Text(
        text = (if (ok) "✓  " else "✗  ") + name,
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
}

@Composable
internal fun LabeledCheckbox(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(text)
    }
}

@Composable
internal fun CalendarPicker(calendars: List<CalendarInfo>, selectedId: Long, onSelect: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val selected = calendars.firstOrNull { it.id == selectedId }
    Box(Modifier.padding(start = 48.dp)) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected?.let { "${it.name} (${it.account})" } ?: "Choose a calendar")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (calendars.isEmpty()) {
                DropdownMenuItem(text = { Text("No writable calendars found") }, onClick = { open = false })
            }
            calendars.forEach { cal ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(cal.name)
                            Text(cal.account, style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    onClick = {
                        onSelect(cal.id)
                        open = false
                    },
                )
            }
        }
    }
}

internal fun hasCalendarPermission(ctx: Context): Boolean =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

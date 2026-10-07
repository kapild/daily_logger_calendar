package dev.kapil.healthcal

import android.Manifest
import android.content.SharedPreferences
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun PlacesSection(resumeTick: Int, calGranted: Boolean, calendars: List<CalendarInfo>) {
    val ctx = LocalContext.current
    val prefs = remember { Prefs(ctx) }
    val config = remember { PlacesConfig(ctx) }

    var enabled by remember { mutableStateOf(config.enabled) }
    var calendarId by remember { mutableLongStateOf(config.calendarId) }
    var fine by remember { mutableStateOf(PlaceMonitor.hasFineLocation(ctx)) }
    var background by remember { mutableStateOf(PlaceMonitor.hasBackgroundLocation(ctx)) }
    var bluetooth by remember { mutableStateOf(PlaceMonitor.hasBluetooth(ctx)) }
    var carDevices by remember { mutableStateOf(config.carDevices) }
    var shuttle by remember { mutableStateOf(config.shuttleSsids) }
    var bonded by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var recent by remember { mutableStateOf(emptyList<Signal>()) }
    var placesStatus by remember { mutableStateOf(prefs.placesStatus) }
    var signalsTick by remember { mutableLongStateOf(prefs.signalsChangedAt) }

    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            placesStatus = prefs.placesStatus
            signalsTick = prefs.signalsChangedAt
        }
        prefs.sp.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.sp.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    LaunchedEffect(resumeTick) {
        fine = PlaceMonitor.hasFineLocation(ctx)
        background = PlaceMonitor.hasBackgroundLocation(ctx)
        bluetooth = PlaceMonitor.hasBluetooth(ctx)
        if (bluetooth) bonded = PlaceMonitor.bondedDevices(ctx)
        if (config.enabled) PlaceMonitor.registerAll(ctx)
    }

    LaunchedEffect(signalsTick, resumeTick) {
        recent = withContext(Dispatchers.IO) { SignalLog.get(ctx).recent(12) }
    }

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        fine = PlaceMonitor.hasFineLocation(ctx)
        PlaceMonitor.registerAll(ctx)
    }
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        background = PlaceMonitor.hasBackgroundLocation(ctx)
        PlaceMonitor.registerAll(ctx)
    }
    val bluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        bluetooth = PlaceMonitor.hasBluetooth(ctx)
        if (bluetooth) bonded = PlaceMonitor.bondedDevices(ctx)
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Log places and drives")
            Text(
                "Leaving and arriving at home, the office and the gym, plus commutes and drives. " +
                    "Uses geofences, Wi-Fi and car Bluetooth, so nothing polls in the background.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = enabled, onCheckedChange = {
            enabled = it
            config.enabled = it
            PlaceMonitor.registerAll(ctx)
        })
    }

    if (enabled) {
        PermissionLine("Precise location", fine)
        PermissionLine("Location all the time", background)
        PermissionLine("Nearby devices, for car Bluetooth", bluetooth)
        if (!fine) {
            Button(onClick = {
                locationLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
            }) { Text("Grant location access") }
        } else if (!background && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Button(onClick = { backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) {
                Text("Allow location all the time")
            }
        }
        if (!bluetooth && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Button(onClick = { bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                Text("Grant nearby devices access")
            }
        }

        Text("Calendar for places and drives", style = MaterialTheme.typography.labelLarge)
        if (calGranted) {
            CalendarPicker(calendars, calendarId) {
                calendarId = it
                config.calendarId = it
            }
        } else {
            Text("Grant calendar access in step 2 first.", style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()
        Place.entries.forEach { place ->
            PlaceEditor(place, config, canLocate = fine)
            HorizontalDivider()
        }

        Text("Car", style = MaterialTheme.typography.titleSmall)
        Text(
            "Check your car's Bluetooth. Connecting and disconnecting mark the start and end of a drive.",
            style = MaterialTheme.typography.bodySmall,
        )
        when {
            !bluetooth -> Text("Grant nearby devices access to list paired devices.", style = MaterialTheme.typography.bodySmall)
            bonded.isEmpty() -> Text("No paired Bluetooth devices found.", style = MaterialTheme.typography.bodySmall)
            else -> bonded.forEach { (address, name) ->
                LabeledCheckbox(name, address in carDevices) { on ->
                    carDevices = if (on) carDevices + address else carDevices - address
                    config.carDevices = carDevices
                }
            }
        }

        HorizontalDivider()
        Text("Shuttle", style = MaterialTheme.typography.titleSmall)
        Text(
            "When your phone joins the shuttle Wi-Fi, that commute is labeled as a shuttle ride. " +
                "Join the network once so your phone reconnects to it on its own. " +
                "A name saved here always counts as the shuttle, even if it's also saved under a place.",
            style = MaterialTheme.typography.bodySmall,
        )
        SsidEditor(shuttle) {
            shuttle = it
            config.shuttleSsids = it
        }

        HorizontalDivider()
        Text("Recent signals", style = MaterialTheme.typography.titleSmall)
        if (recent.isEmpty()) {
            Text("Nothing logged yet. Signals appear here as you come and go.", style = MaterialTheme.typography.bodySmall)
        } else {
            recent.forEach { Text(describe(it), style = MaterialTheme.typography.bodySmall) }
        }
        if (placesStatus.isNotBlank()) Text(placesStatus, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PlaceEditor(place: Place, config: PlacesConfig, canLocate: Boolean) {
    val ctx = LocalContext.current
    var loc by remember { mutableStateOf(config.location(place)) }
    var ssids by remember { mutableStateOf(config.ssids(place)) }
    var radius by remember { mutableFloatStateOf((config.location(place)?.radiusM ?: place.defaultRadiusM).toFloat()) }
    var locating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(place.label, style = MaterialTheme.typography.titleSmall)

        val current = loc
        Text(
            if (current == null) "No location set" else
                String.format(Locale.US, "Location %.5f, %.5f, %d m radius", current.lat, current.lng, current.radiusM),
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = canLocate && !locating,
                onClick = {
                    locating = true
                    error = null
                    PlaceMonitor.currentLocation(ctx) { fix ->
                        locating = false
                        if (fix == null) {
                            error = "Couldn't get a location fix. Try again near a window or outside."
                        } else {
                            val updated = PlaceLocation(fix.latitude, fix.longitude, radius.toInt())
                            config.setLocation(place, updated)
                            loc = updated
                            PlaceMonitor.registerAll(ctx)
                        }
                    }
                },
            ) { Text(if (locating) "Locating…" else "Set to where I am now") }
            if (current != null) {
                TextButton(onClick = {
                    config.setLocation(place, null)
                    loc = null
                    PlaceMonitor.registerAll(ctx)
                }) { Text("Clear") }
            }
        }
        if (current != null) {
            Slider(
                value = radius,
                onValueChange = { radius = it },
                valueRange = 100f..500f,
                steps = 7,
                onValueChangeFinished = {
                    val updated = current.copy(radiusM = radius.toInt())
                    config.setLocation(place, updated)
                    loc = updated
                    PlaceMonitor.registerAll(ctx)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        SsidEditor(ssids) {
            ssids = it
            config.setSsids(place, it)
        }
    }
}

@Composable
private fun SsidEditor(ssids: Set<String>, onChange: (Set<String>) -> Unit) {
    val ctx = LocalContext.current
    var input by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ssids.sorted().forEach { name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Wi-Fi: $name", Modifier.weight(1f))
                TextButton(onClick = { onChange(ssids - name) }) { Text("Remove") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("Wi-Fi name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                enabled = input.isNotBlank(),
                onClick = {
                    onChange(ssids + input.trim())
                    input = ""
                },
            ) { Text("Add") }
        }
        TextButton(onClick = {
            val now = PlaceMonitor.currentSsid(ctx)
            if (now == null) {
                hint = "Not on Wi-Fi right now, or location access is missing."
            } else {
                onChange(ssids + now)
                hint = null
            }
        }) { Text("Add the Wi-Fi I'm on now") }
        hint?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

private val SIGNAL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.getDefault())

private fun describe(s: Signal): String {
    val time = SIGNAL_TIME.format(Instant.ofEpochMilli(s.ts).atZone(ZoneId.systemDefault()))
    return "$time   ${signalTitle(s)} (${signalVia(s)})"
}

/** "Arrived home", "Car connected", ... */
internal fun signalTitle(s: Signal): String {
    val placeName = Place.fromKey(s.place)?.label?.lowercase() ?: (s.place ?: "")
    return when (s.type) {
        SignalType.ARRIVE -> "Arrived $placeName"
        SignalType.LEAVE -> "Left $placeName"
        SignalType.CAR_ON -> "Car connected"
        SignalType.CAR_OFF -> "Car disconnected"
        SignalType.SHUTTLE -> "On shuttle Wi-Fi"
    }
}

/** How the signal was detected: "Wi-Fi aroundthecorner", "Tesla Model Y", "location". */
internal fun signalVia(s: Signal): String = when (s.source) {
    "wifi" -> "Wi-Fi ${s.detail}"
    "bluetooth" -> s.detail
    else -> "location"
}

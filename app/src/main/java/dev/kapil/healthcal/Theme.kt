package dev.kapil.healthcal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Cool palette: deep fjord blue, glacier teal and dusk indigo on a frost background.

private val LightColors = lightColorScheme(
    primary = Color(0xFF1D5C7E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE6F2),
    onPrimaryContainer = Color(0xFF08324A),
    secondary = Color(0xFF237F7E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFEDEA),
    onSecondaryContainer = Color(0xFF0A3C3B),
    tertiary = Color(0xFF4B53A8),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0E2FA),
    onTertiaryContainer = Color(0xFF1C2366),
    background = Color(0xFFEAF1F5),
    onBackground = Color(0xFF15242D),
    surface = Color(0xFFEAF1F5),
    onSurface = Color(0xFF15242D),
    surfaceVariant = Color(0xFFD9E5EC),
    onSurfaceVariant = Color(0xFF465864),
    outline = Color(0xFF7F939E),
    outlineVariant = Color(0xFFC3D3DC),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF3F8FA),
    surfaceContainer = Color(0xFFEEF4F7),
    surfaceContainerHigh = Color(0xFFF7FAFC),
    surfaceContainerHighest = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8DCDEA),
    onPrimary = Color(0xFF003548),
    primaryContainer = Color(0xFF0E4C68),
    onPrimaryContainer = Color(0xFFCDE6F2),
    secondary = Color(0xFF7FD3CF),
    onSecondary = Color(0xFF003735),
    secondaryContainer = Color(0xFF135452),
    onSecondaryContainer = Color(0xFFCFEDEA),
    tertiary = Color(0xFFBEC2FF),
    onTertiary = Color(0xFF1C2366),
    tertiaryContainer = Color(0xFF343B8C),
    onTertiaryContainer = Color(0xFFE0E2FA),
    background = Color(0xFF0D161B),
    onBackground = Color(0xFFDCE6EB),
    surface = Color(0xFF0D161B),
    onSurface = Color(0xFFDCE6EB),
    surfaceVariant = Color(0xFF2A3A43),
    onSurfaceVariant = Color(0xFFA9BAC4),
    outline = Color(0xFF73868F),
    outlineVariant = Color(0xFF34454E),
    surfaceContainerLowest = Color(0xFF091115),
    surfaceContainerLow = Color(0xFF121D23),
    surfaceContainer = Color(0xFF16232A),
    surfaceContainerHigh = Color(0xFF1A2830),
    surfaceContainerHighest = Color(0xFF1E2E37),
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

/** The dark blue band at the top. Dark in both themes so status bar icons stay light. */
data class HeaderColors(val container: Color, val content: Color, val muted: Color, val accent: Color)

@Composable
fun headerColors(): HeaderColors =
    if (isSystemInDarkTheme()) {
        HeaderColors(Color(0xFF113D55), Color(0xFFE6F3F9), Color(0xFF9CC3D6), Color(0xFF7FD3CF))
    } else {
        HeaderColors(Color(0xFF1D5C7E), Color(0xFFF2FAFD), Color(0xFFB3D6E6), Color(0xFF8FE3DC))
    }

/** What a row is about. Each kind has its own color throughout the app. */
enum class Kind(val label: String) {
    SLEEP("Sleep"),
    WORKOUT("Workouts"),
    PLACES("Places");

    companion object {
        fun ofTag(tag: String): Kind = when {
            tag.startsWith("sleep:") -> SLEEP
            tag.startsWith("ex:") -> WORKOUT
            else -> PLACES
        }
    }
}

@Composable
fun Kind.accent(): Color {
    val dark = isSystemInDarkTheme()
    return when (this) {
        Kind.SLEEP -> if (dark) Color(0xFFB9BEFF) else Color(0xFF4B53A8)   // dusk indigo
        Kind.WORKOUT -> if (dark) Color(0xFF76D8C4) else Color(0xFF12806E) // kelp teal
        Kind.PLACES -> if (dark) Color(0xFF9CC6F2) else Color(0xFF2468A8)  // harbor blue
    }
}

enum class Tone { GOOD, WAIT, BAD, NEUTRAL }

@Composable
private fun toneColors(tone: Tone): Pair<Color, Color> {
    val dark = isSystemInDarkTheme()
    return when (tone) {
        Tone.GOOD -> if (dark) Color(0xFF0F4A40) to Color(0xFFA6EBDD) else Color(0xFFD3F0EA) to Color(0xFF0B5A4E)
        Tone.WAIT -> if (dark) Color(0xFF4A3708) to Color(0xFFF5D28C) else Color(0xFFFBE7C2) to Color(0xFF6B4500)
        Tone.BAD -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        Tone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** Small rounded status label. */
@Composable
fun Pill(text: String, tone: Tone) {
    val (bg, fg) = toneColors(tone)
    Text(
        text = text,
        color = fg,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .background(bg, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** A status sentence on a tinted background, for problems and confirmations. */
@Composable
fun Notice(text: String, tone: Tone) {
    val (bg, fg) = toneColors(tone)
    Text(
        text = text,
        color = fg,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

/** A list row with a colored stripe on the left showing its kind. */
@Composable
fun StripeRow(kind: Kind, content: @Composable ColumnScope.() -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            Modifier
                .width(5.dp)
                .fillMaxHeight()
                .background(kind.accent()),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            content = content,
        )
    }
}

/** "All / Sleep / Workouts / Places" chips with counts. `null` means all. */
@Composable
fun KindFilter(selected: Kind?, counts: Map<Kind, Int>, onSelect: (Kind?) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("All ${counts.values.sum()}") },
        )
        Kind.entries.forEach { kind ->
            FilterChip(
                selected = selected == kind,
                onClick = { onSelect(kind) },
                label = { Text("${kind.label} ${counts[kind] ?: 0}") },
                leadingIcon = {
                    Box(
                        Modifier
                            .size(10.dp)
                            .background(kind.accent(), CircleShape),
                    )
                },
            )
        }
    }
}

private val DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a", Locale.getDefault())
private val TIME_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
private val WEEKDAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.getDefault())

/** "Tue Sep 30, 11:02 PM – Wed 6:10 AM", or just the start for instant events. */
fun whenText(startMs: Long, endMs: Long? = null): String {
    val zone = ZoneId.systemDefault()
    val start = Instant.ofEpochMilli(startMs).atZone(zone)
    if (endMs == null || endMs - startMs < 2 * 60_000L) return DAY_TIME.format(start)
    val end = Instant.ofEpochMilli(endMs).atZone(zone)
    val endText = if (end.toLocalDate() == start.toLocalDate()) TIME_ONLY.format(end) else WEEKDAY_TIME.format(end)
    return "${DAY_TIME.format(start)} – $endText"
}

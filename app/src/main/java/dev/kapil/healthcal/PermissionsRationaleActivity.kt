package dev.kapil.healthcal

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Shown by Health Connect when you tap "privacy policy" for this app. */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .safeDrawingPadding()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("How HealthCal uses your data", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "HealthCal reads sleep sessions (including stages) and exercise sessions " +
                                "from Health Connect on this phone, and writes them as events into the " +
                                "calendars you pick. Nothing leaves your phone except through your own " +
                                "calendar sync. There are no servers and no analytics."
                        )
                        Text(
                            "Background access lets it sync on a schedule. History access lets it " +
                                "backfill data older than 30 days."
                        )
                        Button(onClick = { finish() }) { Text("Done") }
                    }
                }
            }
        }
    }
}

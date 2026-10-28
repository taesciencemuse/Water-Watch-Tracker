package com.colehydro.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.launch

/**
 * Phone companion UI — deliberately minimal. Its real job is the
 * background WatchMessageService. This screen exists to:
 *  1. Request Health Connect READ/WRITE_HYDRATION permission.
 *  2. Show today's Health Connect total, so you can prove a watch
 *     tap actually landed in Health Connect.
 *  3. (v2) Change default tap size + daily goal, synced to the watch.
 */
class MainActivity : ComponentActivity() {

    private var status by mutableStateOf("Not checked yet")

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        status = if (granted.containsAll(HealthConnectWriter.PERMISSIONS)) {
            "Health Connect permission granted"
        } else {
            "Health Connect permission NOT fully granted — taps will queue on the watch"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Water — phone companion")
                    Text(status)
                    Button(onClick = {
                        permissionLauncher.launch(HealthConnectWriter.PERMISSIONS)
                    }) { Text("Grant Health Connect access") }
                    Button(onClick = {
                        scope.launch {
                            val ml = HealthConnectWriter.todayTotalMl(this@MainActivity)
                            status = "Health Connect today: ${(ml / 29.5735).toInt()} oz"
                        }
                    }) { Text("Refresh today's total") }
                    Text(
                        "Defaults for v1: tap = 8 oz, goal = 64 oz. " +
                        "Change them in code (WaterRepository) until the v2 settings screen lands."
                    )
                }
            }
        }
    }
}

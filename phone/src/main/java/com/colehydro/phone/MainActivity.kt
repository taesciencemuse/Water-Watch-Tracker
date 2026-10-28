package com.colehydro.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.launch

/**
 * Phone companion UI, restyled in Material 3 Expressive style.
 *
 * Its real job is still the background WatchMessageService. This screen
 * exists to:
 *  1. Request Health Connect READ/WRITE_HYDRATION permission.
 *  2. Show today's Health Connect total, so you can prove a watch
 *     tap actually landed in Health Connect.
 *  3. (v2) Change default tap size + daily goal, synced to the watch.
 *
 * Expressive-style notes: plain MaterialTheme with expressive values —
 * MaterialExpressiveTheme itself is internal in material3 1.3.0, so this
 * uses large rounded shapes, bold large type and big pill buttons to get
 * the same look; a light
 * water-blue color scheme; the status lives in a big rounded card
 * instead of bare text.
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
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF0B6FA4),
                    onPrimary = Color(0xFFFFFFFF),
                    primaryContainer = Color(0xFFC6E7FF),
                    onPrimaryContainer = Color(0xFF00344D),
                    secondary = Color(0xFF0E7C6B),
                    secondaryContainer = Color(0xFFBFEDE3),
                    onSecondaryContainer = Color(0xFF00382F),
                    background = Color(0xFFF2F8FB),
                    onBackground = Color(0xFF0B1D29),
                    surface = Color(0xFFFFFFFF),
                    onSurface = Color(0xFF0B1D29),
                    surfaceVariant = Color(0xFFDCEBF3),
                    onSurfaceVariant = Color(0xFF3E5A6B)
                ),
                shapes = Shapes(
                    extraSmall = RoundedCornerShape(12.dp),
                    small = RoundedCornerShape(16.dp),
                    medium = RoundedCornerShape(24.dp),
                    large = RoundedCornerShape(32.dp),
                    extraLarge = RoundedCornerShape(40.dp)
                )
            ) {
                val scope = rememberCoroutineScope()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp)
                ) {
                    Text(
                        "Water",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        "Phone companion",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(24.dp)) {
                            Text(
                                "Health Connect",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                status,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            permissionLauncher.launch(HealthConnectWriter.PERMISSIONS)
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Text(
                            "Grant Health Connect access",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = {
                            status = when (HealthConnectClient.getSdkStatus(this@MainActivity)) {
                                HealthConnectClient.SDK_AVAILABLE ->
                                    "Health Connect SDK: available — permission request should show a screen"
                                HealthConnectClient.SDK_UNAVAILABLE ->
                                    "Health Connect SDK: unavailable on this device / Android version"
                                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                                    "Health Connect SDK: provider update required — update Health Connect in the Play Store, then try Grant again"
                                else -> "Health Connect SDK: unknown status"
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = MaterialTheme.shapes.extraLarge
                    ) {
                        Text(
                            "Check Health Connect status",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                status = try {
                                    val granted = HealthConnectClient.getOrCreate(this@MainActivity)
                                        .permissionController.getGrantedPermissions()
                                    if (granted.isEmpty()) "Granted permissions: none"
                                    else "Granted permissions: ${granted.joinToString()}"
                                } catch (e: Exception) {
                                    "Granted permissions check failed: ${e.message}"
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = MaterialTheme.shapes.extraLarge
                    ) {
                        Text(
                            "Show granted permissions",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                val ml = HealthConnectWriter.todayTotalMl(this@MainActivity)
                                status = "Health Connect today: ${(ml / 29.5735).toInt()} oz"
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = MaterialTheme.shapes.extraLarge
                    ) {
                        Text(
                            "Refresh today's total",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        "Defaults for v1: tap = 8 oz, goal = 64 oz. " +
                        "Change them in code (WaterRepository) until the v2 settings screen lands.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

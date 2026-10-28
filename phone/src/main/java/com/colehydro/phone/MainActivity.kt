package com.colehydro.phone

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.launch

/**
 * Phone companion, v4 layout.
 *
 * Main screen: today's Health Connect total, progress toward the 64 oz
 * goal, and quick-add buttons mirroring the watch quick-add screen
 * (big +8 oz, then +12 / +16 / Undo). Phone adds write straight to
 * Health Connect; Undo deletes the most recent phone add.
 *
 * Settings screen: everything that used to be the whole app — Health
 * Connect permission (Grant / status / granted list) and the
 * today's-total refresh, plus defaults and version info.
 */
class MainActivity : ComponentActivity() {

    private enum class Screen { Main, Settings }

    private var screen by mutableStateOf(Screen.Main)
    private var status by mutableStateOf("Not checked yet")
    private var totalMl by mutableStateOf<Int?>(null)
    private var lastAction by mutableStateOf("")

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        status = if (granted.containsAll(HealthConnectWriter.PERMISSIONS)) {
            "Health Connect permission granted"
        } else {
            "Health Connect permission NOT fully granted — taps will queue on the watch"
        }
    }

    private fun oz(ml: Int) = (ml / 29.5735).toInt()


    /**
     * Expressive press motion: a button that springs down slightly while
     * pressed and bounces back on release, using expressive spring physics.
     * The interaction source is shared with the button so the press state
     * is real, not a timer.
     */
    @Composable
    private fun PressButton(
        onClick: () -> Unit,
        modifier: Modifier,
        tonal: Boolean,
        content: @Composable RowScope.() -> Unit
    ) {
        val interaction = androidx.compose.runtime.remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(
            targetValue = if (pressed) 0.93f else 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessLow
            ),
            label = "pressScale"
        )
        val scaled = modifier.graphicsLayer { scaleX = scale; scaleY = scale }
        if (tonal) {
            FilledTonalButton(
                onClick = onClick,
                modifier = scaled,
                interactionSource = interaction,
                shape = MaterialTheme.shapes.extraLarge,
                content = content
            )
        } else {
            Button(
                onClick = onClick,
                modifier = scaled,
                interactionSource = interaction,
                shape = MaterialTheme.shapes.extraLarge,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                content = content
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Material 3 Expressive on stable material3 1.3.0: the
            // MaterialExpressiveTheme / MotionScheme / expressive scheme
            // APIs are internal in this stable release (they only exist
            // in alpha builds), so the robust version is: system dynamic
            // color + expressive shapes + expressive spring motion done
            // explicitly below (PressButton, animated progress), on the
            // stable theme. No alpha dependency.
            val darkTheme = isSystemInDarkTheme()
            val colorScheme = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && darkTheme ->
                    dynamicDarkColorScheme(LocalContext.current)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    dynamicLightColorScheme(LocalContext.current)
                darkTheme -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(
                colorScheme = colorScheme,
                shapes = Shapes(
                    extraSmall = RoundedCornerShape(12.dp),
                    small = RoundedCornerShape(16.dp),
                    medium = RoundedCornerShape(24.dp),
                    large = RoundedCornerShape(32.dp),
                    extraLarge = RoundedCornerShape(40.dp)
                )
            ) {
                val scope = rememberCoroutineScope()

                // System back gesture / back button on the Settings screen
                // must return to Main, not exit the app: the two screens
                // are internal Compose state, not Android back-stack entries.
                if (screen == Screen.Settings) {
                    BackHandler { screen = Screen.Main }
                }

                fun refreshTotal() {
                    scope.launch {
                        totalMl = try {
                            val client = HealthConnectClient.getOrCreate(this@MainActivity)
                            val granted = client.permissionController.getGrantedPermissions()
                            if (granted.containsAll(HealthConnectWriter.PERMISSIONS)) {
                                HealthConnectWriter.todayTotalMl(this@MainActivity)
                            } else {
                                null
                            }
                        } catch (_: Exception) {
                            null
                        }
                    }
                }

                LaunchedEffect(screen) { refreshTotal() }

                // Expressive motion: total/progress spring to the new
                // value instead of jumping, using expressive spring physics.
                val animatedProgress by animateFloatAsState(
                    targetValue = ((totalMl?.toFloat() ?: 0f) / 1893f).coerceIn(0f, 1f),
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "todayProgress"
                )

                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp)
                            ) {
                                NavigationDrawerItem(
                                    label = { Text("Today") },
                                    selected = screen == Screen.Main,
                                    onClick = {
                                        scope.launch {
                                            drawerState.close()
                                            screen = Screen.Main
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                NavigationDrawerItem(
                                    label = { Text("Settings") },
                                    selected = screen == Screen.Settings,
                                    onClick = {
                                        scope.launch {
                                            drawerState.close()
                                            screen = Screen.Settings
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Water (phone) 0.1.0 (v8)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                ) {
                    Scaffold(
                        floatingActionButton = {
                            // Circular Material 3 floating menu button,
                            // bottom-left, opening the left drawer.
                            FloatingActionButton(
                                onClick = { scope.launch { drawerState.open() } }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Menu,
                                    contentDescription = "Open menu"
                                )
                            }
                        },
                        floatingActionButtonPosition = FabPosition.Start
                    ) { innerPadding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                                .verticalScroll(rememberScrollState())
                                .padding(innerPadding)
                                .padding(24.dp)
                        ) {
                            // Modern fading screen transition, used both
                            // for drawer navigation and for the back
                            // gesture (which just changes `screen`).
                            AnimatedContent(
                                targetState = screen,
                                transitionSpec = {
                                    fadeIn(
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessLow
                                        )
                                    ) togetherWith fadeOut(
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioMediumBouncy,
                                            stiffness = Spring.StiffnessLow
                                        )
                                    )
                                },
                                label = "screenFade"
                            ) { targetScreen ->
                                if (targetScreen == Screen.Main) {
                        // ---- Main screen ----
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
                                    "Today",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                if (totalMl == null) {
                                    Text(
                                        "Permission needed — open Settings and grant Health Connect access.",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                } else {
                                    Text(
                                        "${oz(totalMl!!)} / 64 oz",
                                        style = MaterialTheme.typography.displayMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    LinearProgressIndicator(
                                        progress = { animatedProgress },
                                        modifier = Modifier.fillMaxWidth().height(12.dp),
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        PressButton(
                            onClick = {
                                scope.launch {
                                    val err = HealthConnectWriter.writeManualMlResult(this@MainActivity, 237)
                                    lastAction = if (err == null) "+8 oz logged" else "Couldn't log: $err"
                                    refreshTotal()
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(64.dp),
                            tonal = false
                        ) {
                            Text(
                                "+8 oz",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            PressButton(
                                onClick = {
                                    scope.launch {
                                        val err = HealthConnectWriter.writeManualMlResult(this@MainActivity, 355)
                                        lastAction = if (err == null) "+12 oz logged" else "Couldn't log: $err"
                                        refreshTotal()
                                    }
                                },
                                modifier = Modifier.weight(1f).height(56.dp),
                                tonal = true
                            ) { Text("+12", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                            PressButton(
                                onClick = {
                                    scope.launch {
                                        val err = HealthConnectWriter.writeManualMlResult(this@MainActivity, 473)
                                        lastAction = if (err == null) "+16 oz logged" else "Couldn't log: $err"
                                        refreshTotal()
                                    }
                                },
                                modifier = Modifier.weight(1f).height(56.dp),
                                tonal = true
                            ) { Text("+16", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                            FilledTonalButton(
                                onClick = {
                                    scope.launch {
                                        val undone = HealthConnectWriter.undoLastPhoneAdd(this@MainActivity)
                                        lastAction = if (undone) "Last phone add undone" else "Nothing to undo (Undo only covers phone adds, not watch taps)"
                                        refreshTotal()
                                    }
                                },
                                modifier = Modifier.weight(1f).height(56.dp),
                                shape = MaterialTheme.shapes.extraLarge
                            ) { Text("Undo", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                        }
                        if (lastAction.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                lastAction,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.height(88.dp))
                    } else {
                        // ---- Settings screen ----
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
                                    status = "Health Connect today: ${oz(ml)} oz"
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
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Water (phone) 0.1.0 (v8) • Health Connect client 1.1.0",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                                }
                        }
                    }
                }
            }
        }
    }
}

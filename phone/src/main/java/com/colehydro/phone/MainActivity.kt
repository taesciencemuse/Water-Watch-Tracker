package com.colehydro.phone

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Switch
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
import androidx.lifecycle.lifecycleScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
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

    private enum class Screen { Main, Settings, History, Watch, Reminders }

    private var screen by mutableStateOf(Screen.Main)
    private var showClearConfirm by mutableStateOf(false)
    // 0 = no back gesture in progress, 1 = gesture fully committed.
    // Driven by PredictiveBackHandler while on Settings.
    private var backProgress by mutableStateOf(0f)
    private var status by mutableStateOf("Not checked yet")
    private var totalMl by mutableStateOf<Int?>(null)
    private var goalMl by mutableStateOf(1893)
    private var goalText by mutableStateOf("64")
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

    private val notificationPermissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ -> }

    /** Phone -> watch goal sync: sends the saved goal over the Data Layer
     *  (path /hydro/goal, plain ml integer) to every connected watch node.
     *  The watch listener saves it to its DataStore, so Tile, complication
     *  and watch quick-add all use the same goal as the phone. If no watch
     *  is connected, nothing is queued — the next goal save re-sends it. */
    private fun sendGoalToWatch(ml: Int) {
        lifecycleScope.launch {
            try {
                val nodes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.google.android.gms.tasks.Tasks.await(
                        com.google.android.gms.wearable.Wearable.getNodeClient(this@MainActivity).connectedNodes
                    )
                }
                nodes.forEach { node ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.google.android.gms.tasks.Tasks.await(
                            com.google.android.gms.wearable.Wearable.getMessageClient(this@MainActivity)
                                .sendMessage(node.id, "/hydro/goal", ml.toString().toByteArray(Charsets.UTF_8))
                        )
                    }
                }
            } catch (_: Exception) {
                // No connected watch, or send failed: goal stays saved on
                // the phone and will re-sync on the next goal save.
            }
        }
    }

    // Round, don't truncate: 64 oz -> 1892.7 ml -> 1892 ml -> 63 oz
    // was the v9 bug. Rounding both directions keeps 64 oz = 64 oz.
    /** Phone -> watch clear: sends /hydro/reset to every connected watch.
     *  The watch clears its today total and unsynced queue. If no watch
     *  is connected, the phone clear still happens; the watch will
     *  keep its old total until midnight, and any unsynced taps that
     *  later flush are blocked by phone dedupe (hydro_seen). */
    private fun sendResetToWatch() {
        lifecycleScope.launch {
            try {
                val nodes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.google.android.gms.tasks.Tasks.await(
                        com.google.android.gms.wearable.Wearable.getNodeClient(this@MainActivity).connectedNodes
                    )
                }
                nodes.forEach { node ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        com.google.android.gms.tasks.Tasks.await(
                            com.google.android.gms.wearable.Wearable.getMessageClient(this@MainActivity)
                                .sendMessage(node.id, "/hydro/reset", ByteArray(0))
                        )
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun oz(ml: Int) = (ml / 29.5735).roundToInt()


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
        shape: androidx.compose.ui.graphics.Shape = MaterialTheme.shapes.extraLarge,
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
                shape = shape,
                content = content
            )
        } else {
            Button(
                onClick = onClick,
                modifier = scaled,
                interactionSource = interaction,
                shape = shape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                content = content
            )
        }
    }

    // ---------- Drawer pages: History / Watch / Reminders ----------

    @Composable
    private fun BarChart(labels: List<String>, valuesOz: List<Int>) {
        // Expressive bounce: bars spring in from 0 whenever the data
        // changes (tab switch, new tap). A single progress value for
        // all bars keeps the animation safe when bar count changes.
        val chartProgress = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
        androidx.compose.runtime.LaunchedEffect(valuesOz) {
            chartProgress.snapTo(0f)
            chartProgress.animateTo(
                1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessLow
                )
            )
        }
        val maxVal = (valuesOz.maxOrNull() ?: 0).coerceAtLeast(1)
        val primary = MaterialTheme.colorScheme.primary
        val track = MaterialTheme.colorScheme.surfaceVariant
        Column(modifier = Modifier.fillMaxWidth()) {
            Canvas(modifier = Modifier.fillMaxWidth().height(160.dp)) {
                val barSlot = size.width / labels.size.toFloat()
                val barWidth = barSlot * 0.6f
                valuesOz.forEachIndexed { i, v ->
                    val x = barSlot * i + (barSlot - barWidth) / 2f
                    // Track
                    drawRoundRect(
                        color = track,
                        topLeft = androidx.compose.ui.geometry.Offset(x, 0f),
                        size = androidx.compose.ui.geometry.Size(barWidth, size.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
                    )
                    val h = size.height * (v.toFloat() / maxVal.toFloat()) * chartProgress.value
                    if (h > 0f) {
                        drawRoundRect(
                            color = primary,
                            topLeft = androidx.compose.ui.geometry.Offset(x, size.height - h),
                            size = androidx.compose.ui.geometry.Size(barWidth, h),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                labels.forEach { label ->
                    Text(
                        label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }
    }

    @Composable
    private fun HistoryContent() {
        var tab by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
        var daily by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<Pair<java.time.LocalDate, Int>>>(emptyList()) }
        var monthly by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<Pair<java.time.YearMonth, Int>>>(emptyList()) }
        var entries by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<HealthConnectWriter.HydrationEntry>>(emptyList()) }
        val context = LocalContext.current
        val zone = java.time.ZoneId.systemDefault()
        val today = java.time.LocalDate.now(zone)
        androidx.compose.runtime.LaunchedEffect(tab) {
            when (tab) {
                0 -> {
                    val start = today.atStartOfDay(zone).toInstant()
                    entries = HealthConnectWriter.readHydrationEntries(context, start, java.time.Instant.now())
                    daily = listOf(today to entries.sumOf { it.ml })
                }
                1 -> {
                    daily = HealthConnectWriter.dailyTotals(context, today.minusDays(6), today)
                    entries = emptyList()
                }
                2 -> {
                    daily = HealthConnectWriter.dailyTotals(context, today.withDayOfMonth(1), today)
                    entries = emptyList()
                }
                else -> {
                    val months = (0..11).map { java.time.YearMonth.of(today.year, today.month).minusMonths((11 - it).toLong()) }
                    monthly = HealthConnectWriter.monthlyTotals(context, months)
                    daily = emptyList()
                }
            }
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            // Google Health style: big total, bar chart, then entries / days below
            val totalOz: Int
            val subtitle: String
            when (tab) {
                0 -> { totalOz = oz(entries.sumOf { it.ml }); subtitle = "Today" }
                1 -> { totalOz = oz(daily.sumOf { it.second }); subtitle = "This week • avg ${if (daily.isNotEmpty()) oz(daily.sumOf { it.second } / daily.size) else 0} oz/day" }
                2 -> { totalOz = oz(daily.sumOf { it.second }); subtitle = "This month • avg ${if (daily.isNotEmpty()) oz(daily.sumOf { it.second } / daily.size) else 0} oz/day" }
                else -> { totalOz = oz(monthly.sumOf { it.second }); subtitle = "This year • avg ${if (monthly.isNotEmpty()) oz(monthly.sumOf { it.second } / monthly.size) else 0} oz/month" }
            }
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text(subtitle, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("$totalOz oz", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            TabRow(selectedTabIndex = tab) {
                listOf("Today", "Week", "Month", "Year").forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            androidx.compose.animation.AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    (androidx.compose.animation.fadeIn(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    ) + androidx.compose.animation.scaleIn(
                        initialScale = 0.96f,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    )) togetherWith androidx.compose.animation.fadeOut(
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioMediumBouncy,
                            stiffness = Spring.StiffnessLow
                        )
                    )
                },
                label = "historyTab"
            ) { tabState ->
            Column(modifier = Modifier.fillMaxWidth()) {
            when (tabState) {
                0 -> {
                    // Hourly bars: 24 hours, label every 3 hours
                    val hourly = IntArray(24)
                    entries.forEach { e ->
                        val h = java.time.Instant.ofEpochMilli(e.timeMillis).atZone(zone).hour
                        hourly[h] += e.ml
                    }
                    val labels = (0..23).map { h -> if (h % 3 == 0) "${if (h == 0) 12 else if (h > 12) h - 12 else h}${if (h < 12) "a" else "p"}" else "" }
                    BarChart(labels, hourly.map { oz(it) })
                    Spacer(modifier = Modifier.height(24.dp))
                    Text("Entries", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (entries.isEmpty()) {
                        Text("No entries today yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        entries.sortedByDescending { it.timeMillis }.forEach { e ->
                            val time = java.time.Instant.ofEpochMilli(e.timeMillis).atZone(zone).toLocalTime()
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(time.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a")), style = MaterialTheme.typography.bodyMedium)
                                Text("${oz(e.ml)} oz", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                1 -> {
                    val labels = daily.map { it.first.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) }
                    BarChart(labels, daily.map { oz(it.second) })
                    Spacer(modifier = Modifier.height(16.dp))
                    daily.reversed().forEach { (d, ml) ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(d.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d")), style = MaterialTheme.typography.bodyMedium)
                            Text("${oz(ml)} oz", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                2 -> {
                    val labels = daily.map { (d, _) -> if (d.dayOfMonth % 5 == 1) d.dayOfMonth.toString() else "" }
                    BarChart(labels, daily.map { oz(it.second) })
                    Spacer(modifier = Modifier.height(16.dp))
                    daily.reversed().forEach { (d, ml) ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(d.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d")), style = MaterialTheme.typography.bodyMedium)
                            Text("${oz(ml)} oz", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                else -> {
                    val labels = monthly.map { it.first.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) }
                    BarChart(labels, monthly.map { oz(it.second) })
                    Spacer(modifier = Modifier.height(16.dp))
                    monthly.reversed().forEach { (m, ml) ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(m.format(java.time.format.DateTimeFormatter.ofPattern("MMM yyyy")), style = MaterialTheme.typography.bodyMedium)
                            Text("${oz(ml)} oz", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
            }
            }
            Spacer(modifier = Modifier.height(88.dp))
        }
    }

    @Composable
    private fun WatchContent() {
        val context = LocalContext.current
        var lastTap by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Pair<Long, Int>?>(null) }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            lastTap = HealthConnectWriter.getLastWatchTap(context)
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Watch", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (lastTap == null) "No watch taps received yet on this phone."
                        else {
                            val dt = java.time.Instant.ofEpochMilli(lastTap!!.first).atZone(java.time.ZoneId.systemDefault())
                            "Last watch tap: ${oz(lastTap!!.second)} oz at ${dt.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, h:mm a"))}"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "The phone can't verify from here whether the Tile or complication is actually added on the watch — that status lives on the watch itself.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("How it works", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Watch Tile / quick-add → phone companion → Health Connect. Taps queue on the watch when the phone is away and flush on reconnect. Watch goal syncs from phone Settings: saving a goal on the phone sends it to the watch, so Tile, complication and phone all use the same goal.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Setup", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("1. Install the watch APK via wireless adb (Pixel Watch has no USB port).\n2. Swipe to the Tile carousel, tap +, add Water.\n3. Long-press the watch face, pick a complication slot, add Water today.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(modifier = Modifier.height(88.dp))
        }
    }

    @Composable
    private fun RemindersContent() {
        val context = LocalContext.current
        var enabled by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        var interval by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(60) }
        var startHour by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(8) }
        var endHour by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(20) }
        var status by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            enabled = HealthConnectWriter.getRemindersEnabled(context)
            interval = HealthConnectWriter.getReminderIntervalMinutes(context)
            startHour = HealthConnectWriter.getReminderStartHour(context)
            endHour = HealthConnectWriter.getReminderEndHour(context)
        }
        fun save() {
            HealthConnectWriter.setRemindersEnabled(context, enabled)
            HealthConnectWriter.setReminderIntervalMinutes(context, interval)
            HealthConnectWriter.setReminderWindow(context, startHour, endHour)
            val wm = androidx.work.WorkManager.getInstance(context)
            if (enabled) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                val request = androidx.work.PeriodicWorkRequestBuilder<ReminderWorker>(interval.toLong(), java.util.concurrent.TimeUnit.MINUTES).build()
                wm.enqueueUniquePeriodicWork("water_reminders", androidx.work.ExistingPeriodicWorkPolicy.UPDATE, request)
                status = "Reminders on: every $interval min, $startHour:00–$endHour:00 (approximate — WorkManager may defer for battery)."
            } else {
                wm.cancelUniqueWork("water_reminders")
                status = "Reminders off."
            }
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Reminders", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Every", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(30, 60, 120).forEach { mins ->
                            FilledTonalButton(
                                onClick = { interval = mins },
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.extraLarge,
                                colors = if (interval == mins) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) else ButtonDefaults.filledTonalButtonColors()
                            ) { Text("${mins}m") }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Window: $startHour:00 – $endHour:00", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { if (startHour > 0) startHour-- }, modifier = Modifier.weight(1f)) { Text("Start −") }
                        FilledTonalButton(onClick = { if (startHour < endHour - 1) startHour++ }, modifier = Modifier.weight(1f)) { Text("Start +") }
                        FilledTonalButton(onClick = { if (endHour > startHour + 1) endHour-- }, modifier = Modifier.weight(1f)) { Text("End −") }
                        FilledTonalButton(onClick = { if (endHour < 23) endHour++ }, modifier = Modifier.weight(1f)) { Text("End +") }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { save() }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = MaterialTheme.shapes.extraLarge) {
                        Text("Save reminders", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    if (status.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Reminders use WorkManager, so times are approximate, not exact alarms. Outside the window, no reminder is posted.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(modifier = Modifier.height(88.dp))
        }
    }


    // Today screen, extracted so the unfolded two-pane layout can reuse
    // it as the left pane. Parameters are the setContent-local scope,
    // the spring-animated progress, and refreshTotal from setContent.
    @Composable
    private fun TodayMainContent(
        scope: kotlinx.coroutines.CoroutineScope,
        animatedProgress: Float,
        refreshTotal: () -> Unit
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
                        // ---- Main screen ----
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.extraLarge,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            // Subtle wave: a faint fill rising with progress,
                            // drawn behind the card content. Height follows
                            // the spring-animated progress; amplitude is small
                            // so it reads as texture, not decoration.
                            // Idle wave: a slow, continuous phase drift (one full
                            // cycle every 6 seconds, linear, restarting) on top of
                            // the progress-tied phase, so the wave gently rolls
                            // even when no taps are happening. Uses an Animatable
                            // loop instead of InfiniteTransition, whose
                            // animateFloat extension needs an extra import and
                            // failed to resolve in the first attempt. Compose
                            // cancels the loop when the app is backgrounded.
                            val idlePhaseAnim = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(0f) }
                            androidx.compose.runtime.LaunchedEffect(Unit) {
                                while (true) {
                                    idlePhaseAnim.animateTo(
                                        (2f * Math.PI.toFloat()),
                                        animationSpec = androidx.compose.animation.core.tween<Float>(
                                            durationMillis = 6000,
                                            easing = androidx.compose.animation.core.LinearEasing
                                        )
                                    )
                                    idlePhaseAnim.snapTo(0f)
                                }
                            }
                            val idleWavePhase = idlePhaseAnim.value
                            androidx.compose.foundation.layout.Box {
                                val waveColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                Canvas(modifier = Modifier.matchParentSize()) {
                                    val progress = animatedProgress
                                    val waveTop = size.height * (1f - progress)
                                    val amplitude = size.height * 0.03f
                                    val path = Path().apply {
                                        moveTo(0f, waveTop)
                                        var x = 0f
                                        while (x <= size.width) {
                                            val y = waveTop + kotlin.math.sin(x / size.width * 2f * Math.PI.toFloat() * 2f + progress * 2f + idleWavePhase) * amplitude
                                            lineTo(x, y)
                                            x += 8f
                                        }
                                        lineTo(size.width, size.height)
                                        lineTo(0f, size.height)
                                        close()
                                    }
                                    drawPath(path, color = waveColor)
                                }
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
                                        "${oz(totalMl!!)} / ${oz(goalMl)} oz",
                                        style = MaterialTheme.typography.displayMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        if (totalMl!! >= goalMl) "Goal reached!"
                                        else "${oz(goalMl - totalMl!!)} oz to go",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    LinearProgressIndicator(
                                        progress = { animatedProgress },
                                        modifier = Modifier.fillMaxWidth().height(12.dp),
                                    )
                                }
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
                            horizontalArrangement = Arrangement.spacedBy(1.dp)
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
                                tonal = true,
                                shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp)
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
                                tonal = true,
                                shape = RoundedCornerShape(0.dp)
                            ) { Text("+16", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                            PressButton(
                                onClick = {
                                    scope.launch {
                                        val undone = HealthConnectWriter.undoLastPhoneAdd(this@MainActivity)
                                        lastAction = if (undone) "Last phone add undone" else "Nothing to undo (Undo only covers phone adds, not watch taps)"
                                        refreshTotal()
                                    }
                                },
                                modifier = Modifier.weight(1f).height(56.dp),
                                tonal = true,
                                shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)
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
        }
    }

    // Right pane when unfolded: this week's daily totals as a bar chart.
    // Reads the same Health Connect records as History, so watch taps
    // that have synced show up here too.
    @Composable
    private fun WeekPreviewContent() {
        var daily by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<Pair<java.time.LocalDate, Int>>>(emptyList()) }
        val context = LocalContext.current
        val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
        androidx.compose.runtime.LaunchedEffect(Unit) {
            daily = HealthConnectWriter.dailyTotals(context, today.minusDays(6), today)
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("This week", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("${oz(daily.sumOf { it.second })} oz total", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))
                    if (daily.isNotEmpty()) {
                        val labels = daily.map { it.first.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault()) }
                        BarChart(labels, daily.map { oz(it.second) })
                    }
                }
            }
            Spacer(modifier = Modifier.height(88.dp))
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

                // Predictive back: on Settings / History / Watch / Reminders, the back gesture drives
                // a live fade/scale preview (backProgress 0->1) instead of
                // an instant switch. On commit, go to Main; on cancel,
                // backProgress returns to 0 and the animated value below
                // springs the Settings screen back to full size.
                // On Main there is no handler, so the system predictive
                // back-to-home animation runs instead.
                if (screen != Screen.Main) {
                    PredictiveBackHandler { progress ->
                        try {
                            progress.collect { event ->
                                backProgress = event.progress
                            }
                            backProgress = 0f
                            screen = Screen.Main
                        } catch (_: kotlinx.coroutines.CancellationException) {
                            backProgress = 0f
                        }
                    }
                }
                // Animated (spring) version of the gesture progress, so a
                // cancelled gesture springs back instead of jumping.
                val animatedBackProgress by animateFloatAsState(
                    targetValue = backProgress,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessLow
                    ),
                    label = "backProgress"
                )

                fun refreshTotal() {
                    goalMl = HealthConnectWriter.getGoalMl(this@MainActivity)
                    goalText = oz(goalMl).toString()
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
                    targetValue = ((totalMl?.toFloat() ?: 0f) / goalMl.toFloat()).coerceIn(0f, 1f),
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
                                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                                    selected = screen == Screen.Main,
                                    onClick = {
                                        scope.launch {
                                            drawerState.close()
                                            screen = Screen.Main
                                        }
                                    }
                                )
                                NavigationDrawerItem(
                                    label = { Text("History") },
                                    icon = { Icon(Icons.Filled.History, contentDescription = null) },
                                    selected = screen == Screen.History,
                                    onClick = {
                                        scope.launch {
                                            drawerState.close()
                                            screen = Screen.History
                                        }
                                    }
                                )
                                NavigationDrawerItem(
                                    label = { Text("Watch") },
                                    icon = { Icon(Icons.Filled.Watch, contentDescription = null) },
                                    selected = screen == Screen.Watch,
                                    onClick = {
                                        scope.launch {
                                            drawerState.close()
                                            screen = Screen.Watch
                                        }
                                    }
                                )
                                NavigationDrawerItem(
                                    label = { Text("Reminders") },
                                    icon = { Icon(Icons.Filled.Notifications, contentDescription = null) },
                                    selected = screen == Screen.Reminders,
                                    onClick = {
                                        scope.launch {
                                            drawerState.close()
                                            screen = Screen.Reminders
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                HorizontalDivider()
                                Spacer(modifier = Modifier.height(8.dp))
                                NavigationDrawerItem(
                                    label = { Text("Settings") },
                                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
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
                                    "Water Logged 0.1.0 (v11)",
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
                                    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                                        // Foldable: at >=600dp (Pixel 9 Pro Fold unfolded)
                                        // show two panes with a central gap, so no
                                        // card or button sits across the hinge.
                                        // Below 600dp (folded) it's the single column.
                                        if (maxWidth >= 600.dp) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                                            ) {
                                                androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                                                    TodayMainContent(scope, animatedProgress) { refreshTotal() }
                                                }
                                                androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                                                    WeekPreviewContent()
                                                }
                                            }
                                        } else {
                                            TodayMainContent(scope, animatedProgress) { refreshTotal() }
                                        }
                                    }
                    } else if (targetScreen == Screen.Settings) {
                                    Column(modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = 1f - animatedBackProgress; scaleX = 1f - 0.08f * animatedBackProgress; scaleY = 1f - 0.08f * animatedBackProgress }) {
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
                                    "Daily goal",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedTextField(
                                        value = goalText,
                                        onValueChange = { goalText = it.filter { ch -> ch.isDigit() } },
                                        label = { Text("Goal (oz)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = {
                                            val ozVal = goalText.toIntOrNull()
                                            if (ozVal != null && ozVal in 16..200) {
                                                val ml = (ozVal * 29.5735).roundToInt()
                                                HealthConnectWriter.setGoalMl(this@MainActivity, ml)
                                                goalMl = ml
                                                sendGoalToWatch(ml)
                                                status = "Goal saved: $ozVal oz"
                                            } else {
                                                status = "Enter a goal between 16 and 200 oz"
                                            }
                                        },
                                        shape = MaterialTheme.shapes.extraLarge
                                    ) { Text("Save") }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    listOf(48, 64, 80, 100).forEach { preset ->
                                        FilledTonalButton(
                                            onClick = {
                                                val ml = (preset * 29.5735).roundToInt()
                                                HealthConnectWriter.setGoalMl(this@MainActivity, ml)
                                                goalMl = ml
                                                goalText = preset.toString()
                                                status = "Goal saved: $preset oz"
                                            },
                                            modifier = Modifier.weight(1f),
                                            shape = MaterialTheme.shapes.extraLarge
                                        ) { Text("$preset", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    "Health Connect doesn't store a hydration goal, so this goal lives in this app only. Saving it also sends it to the watch, so phone, Tile and complication use the same goal.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
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
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.extraLarge,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(24.dp)) {
                                Text(
                                    "About",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "Tap buttons: +8 / +12 / +16 oz. Daily goal is set above.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Water Logged 0.1.0 (v11) • Health Connect client 1.1.0",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                            }
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.extraLarge,
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(24.dp)) {
                                Text(
                                    "Clear today's log",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                if (!showClearConfirm) {
                                    Text(
                                        "Deletes all of today's water entries from Health Connect and resets the watch's today total and unsynced queue.",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Button(
                                        onClick = { showClearConfirm = true },
                                        modifier = Modifier.fillMaxWidth().height(56.dp),
                                        shape = MaterialTheme.shapes.extraLarge,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                            contentColor = MaterialTheme.colorScheme.onError
                                        )
                                    ) {
                                        Text("Clear today's log", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    }
                                } else {
                                    Text(
                                        "Are you sure? This deletes today's log and can't be undone.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Button(
                                            onClick = {
                                                showClearConfirm = false
                                                scope.launch {
                                                    val count = HealthConnectWriter.deleteTodayRecords(this@MainActivity)
                                                    sendResetToWatch()
                                                    status = if (count >= 0) "Cleared today's log ($count entries)" else "Couldn't clear today's log"
                                                    refreshTotal()
                                                }
                                            },
                                            modifier = Modifier.weight(1f).height(56.dp),
                                            shape = MaterialTheme.shapes.extraLarge,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.error,
                                                contentColor = MaterialTheme.colorScheme.onError
                                            )
                                        ) {
                                            Text("Yes, clear it", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        }
                                        FilledTonalButton(
                                            onClick = { showClearConfirm = false },
                                            modifier = Modifier.weight(1f).height(56.dp),
                                            shape = MaterialTheme.shapes.extraLarge
                                        ) {
                                            Text("Cancel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                                    }
                    } else if (targetScreen == Screen.History) {
                        HistoryContent()
                    } else if (targetScreen == Screen.Watch) {
                        WatchContent()
                    } else if (targetScreen == Screen.Reminders) {
                        RemindersContent()
                    }
                                }
                        }
                    }
                }
            }
        }
    }
}

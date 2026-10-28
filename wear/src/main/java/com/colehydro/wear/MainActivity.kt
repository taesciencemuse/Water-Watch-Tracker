package com.colehydro.wear

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Quick-add screen, restyled in Material 3 Expressive style.
 *
 * This is what the Tile button and the complication tap open. If launched
 * with EXTRA_AMOUNT_ML, it logs that amount immediately — so from the Tile
 * it is still one tap, just via this tiny screen for confirmation.
 *
 * Expressive-style notes: plain MaterialTheme with expressive values —
 * MaterialExpressiveTheme itself is internal in material3 1.3.0, so this
 * uses large rounded shapes, bold large type and big pill buttons to get
 * the same look; a dark water-blue
 * color scheme; the day's total sits inside a progress ring so the
 * goal is visible at a glance; the main +oz button is big and pill-shaped.
 * The Tile itself is unchanged — Tiles are ProtoLayout, not Compose, so
 * they can't use Material 3 Expressive.
 */
class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_AMOUNT_ML = "amount_ml"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = WaterRepository(this)
        val launchMl = intent.getStringExtra(EXTRA_AMOUNT_ML)?.toIntOrNull()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF7DD3FC),
                    onPrimary = Color(0xFF082A3D),
                    primaryContainer = Color(0xFF0E4A6B),
                    onPrimaryContainer = Color(0xFFBEE6FF),
                    secondary = Color(0xFF5EEAD4),
                    onSecondary = Color(0xFF06332B),
                    secondaryContainer = Color(0xFF0F4A3E),
                    background = Color(0xFF08131D),
                    onBackground = Color(0xFFF2F8FC),
                    surface = Color(0xFF0F2436),
                    onSurface = Color(0xFFF2F8FC),
                    surfaceVariant = Color(0xFF1A3448),
                    onSurfaceVariant = Color(0xFFB9D2E2)
                ),
                shapes = Shapes(
                    extraSmall = RoundedCornerShape(12.dp),
                    small = RoundedCornerShape(16.dp),
                    medium = RoundedCornerShape(24.dp),
                    large = RoundedCornerShape(32.dp),
                    extraLarge = RoundedCornerShape(40.dp)
                )
            ) {
                WaterScreen(
                    context = this,
                    repo = repo,
                    launchMl = launchMl,
                    onLogged = { refreshSurfaces() }
                )
            }
        }
    }

    private fun refreshSurfaces() {
        // Tile
        WaterTileService.requestTileUpdate(this)
        // Complication: ask the system to re-request data.
        try {
            ComplicationDataSourceUpdateRequester.create(
                this,
                ComponentName(this, HydrationComplicationService::class.java)
            ).requestUpdateAll()
        } catch (_: Exception) {
            // Watch faces that are not currently using this complication
            // simply ignore the request; never crash the quick-add flow.
        }
    }
}

@Composable
fun WaterScreen(
    context: android.content.Context,
    repo: WaterRepository,
    launchMl: Int?,
    onLogged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var todayMl by remember { mutableStateOf(0) }
    var goalMl by remember { mutableStateOf(WaterRepository.DEFAULT_GOAL_ML) }
    var defaultMl by remember { mutableStateOf(WaterRepository.DEFAULT_TAP_ML) }
    var pending by remember { mutableStateOf(0) }
    var justAdded by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            val (t, g, d) = repo.snapshot()
            todayMl = t; goalMl = g; defaultMl = d
            pending = repo.pending().size
        }
    }

    LaunchedEffect(Unit) {
        if (launchMl != null) {
            if (launchMl == -1) {
                repo.undoLastTap()
                justAdded = "Undid last drink"
            } else {
                val tap = WaterTap(
                    id = UUID.randomUUID().toString(),
                    ml = launchMl,
                    timestampMillis = System.currentTimeMillis()
                )
                repo.addTap(tap)
                scope.launch { SyncSender.sendTap(context, tap) }
                justAdded = "+${mlToOz(launchMl)} oz logged"
            }
            onLogged()
        }
        refresh()
    }

    fun log(ml: Int) {
        scope.launch {
            val tap = WaterTap(
                id = UUID.randomUUID().toString(),
                ml = ml,
                timestampMillis = System.currentTimeMillis()
            )
            repo.addTap(tap)
            SyncSender.sendTap(context, tap)
            justAdded = "+${mlToOz(ml)} oz logged"
            refresh()
            onLogged()
        }
    }

    val fraction = if (goalMl > 0) (todayMl.toFloat() / goalMl.toFloat()).coerceIn(0f, 1f) else 0f
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "Today",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Progress ring with the total inside it.
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(128.dp)) {
                    val stroke = Stroke(width = 28f, cap = StrokeCap.Round)
                    drawArc(
                        color = track,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(14f, 14f),
                        size = Size(size.width - 28f, size.height - 28f),
                        style = stroke
                    )
                    drawArc(
                        color = primary,
                        startAngle = -90f,
                        sweepAngle = 360f * fraction,
                        useCenter = false,
                        topLeft = Offset(14f, 14f),
                        size = Size(size.width - 28f, size.height - 28f),
                        style = stroke
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${mlToOz(todayMl)}",
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        "/ ${mlToOz(goalMl)} oz",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            if (justAdded != null) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        justAdded!!,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            } else if (pending > 0) {
                Text(
                    "$pending pending — phone away",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            // Big expressive main button.
            Button(
                onClick = { log(defaultMl) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = MaterialTheme.shapes.extraLarge,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    "+${mlToOz(defaultMl)} oz",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { log(ozToMl(12)) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = MaterialTheme.shapes.large
                ) { Text("+12", style = MaterialTheme.typography.titleMedium) }
                FilledTonalButton(
                    onClick = { log(ozToMl(16)) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = MaterialTheme.shapes.large
                ) { Text("+16", style = MaterialTheme.typography.titleMedium) }
                FilledTonalButton(
                    onClick = {
                        scope.launch { repo.undoLastTap(); refresh(); onLogged() }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = MaterialTheme.shapes.large
                ) { Text("Undo", style = MaterialTheme.typography.titleMedium) }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Default and goal are changed on the phone for v1.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

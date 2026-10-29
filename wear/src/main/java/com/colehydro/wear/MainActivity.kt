package com.colehydro.wear

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import androidx.compose.ui.graphics.graphicsLayer
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
        // Tile — wrapped: a Tile update failure must never crash
        // the quick-add screen (this was a suspect in the Oct 28
        // "tap closes the app" crash, since it ran uncaught).
        try {
            WaterTileService.requestTileUpdate(this)
        } catch (_: Exception) { }
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

/**
 * Big Material Expressive-style button for the watch quick-add
 * screen (v13). Tiles (ProtoLayout) cannot do Compose animations,
 * so this is where the expressive animation lives: a press scales
 * the button to 0.93 with a medium-bouncy spring, and releases
 * back with the same spring — plus a haptic tick on tap, via
 * vibrateClick in the onClick wrapper below.
 */
@Composable
fun ExpressiveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tonal: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "expressivePress"
    )
    // Same corner-radius shape morph as the phone: pill (28dp)
    // at rest, squarer (7dp) on press, with the expressive spring.
    val morphProgress by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.4f,
            stiffness = Spring.StiffnessMedium
        ),
        label = "morphProgress"
    )
    val morphedShape = androidx.compose.foundation.shape.RoundedCornerShape(
        28.dp * (1f - 0.75f * morphProgress)
    )
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = modifier
            .graphicsLayer(scaleX = scale, scaleY = scale),
        interactionSource = interactionSource,
        shape = morphedShape,
        colors = if (tonal) {
            ButtonDefaults.filledTonalButtonColors()
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) {
        content()
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
            val (t, g, d) = repo.displayedSnapshot()
            todayMl = t; goalMl = g; defaultMl = d
            pending = repo.pending().size
        }
    }

    LaunchedEffect(Unit) {
        if (launchMl != null) {
            if (launchMl == -1) {
                justAdded = when (repo.undoLastTapResult()) {
                    WaterRepository.UndoResult.PendingRemoved -> "Undid last drink"
                    WaterRepository.UndoResult.AlreadySynced -> "Already synced — undo from phone"
                    WaterRepository.UndoResult.NothingToUndo -> "Nothing to undo"
                }
            } else {
                val tap = WaterTap(
                    id = UUID.randomUUID().toString(),
                    ml = launchMl,
                    timestampMillis = System.currentTimeMillis()
                )
                repo.addTap(tap)
                scope.launch { if (SyncSender.sendTap(context, tap)) repo.markSynced(setOf(tap.id)) }
                justAdded = "+${mlToOz(launchMl)} oz logged"
            }
            onLogged()
        }
        refresh()
    }

    // Refresh on every resume, not just first composition: phone
    // adds / clears that arrive while this screen is already open
    // otherwise only show after several opens/closes (Oct 28 test 4).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun log(ml: Int) {
        scope.launch {
            try {
                val tap = WaterTap(
                    id = UUID.randomUUID().toString(),
                    ml = ml,
                    timestampMillis = System.currentTimeMillis()
                )
                repo.addTap(tap)
                if (SyncSender.sendTap(context, tap)) repo.markSynced(setOf(tap.id))
                justAdded = "+${mlToOz(ml)} oz logged"
            } catch (_: Exception) {
                // Never crash the app on a tap: show the failure
                // instead, so the next report says "couldn't log"
                // rather than "app just closes".
                justAdded = "Couldn't log — try again"
            }
            try { refresh() } catch (_: Exception) { }
            try { onLogged() } catch (_: Exception) { }
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Text(
                "Today",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            // Progress ring with the total inside it.
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(96.dp)) {
                    val stroke = Stroke(width = 22f, cap = StrokeCap.Round)
                    drawArc(
                        color = track,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(11f, 11f),
                        size = Size(size.width - 22f, size.height - 22f),
                        style = stroke
                    )
                    drawArc(
                        color = primary,
                        startAngle = -90f,
                        sweepAngle = 360f * fraction,
                        useCenter = false,
                        topLeft = Offset(11f, 11f),
                        size = Size(size.width - 22f, size.height - 22f),
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
            Spacer(modifier = Modifier.height(8.dp))
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
            // Big expressive main button: press scale + haptic.
            ExpressiveButton(
                onClick = { vibrateClick(context); log(defaultMl) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    "+${mlToOz(defaultMl)} oz",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ExpressiveButton(
                    onClick = { vibrateClick(context); log(ozToMl(12)) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    tonal = true
                ) { Text("+12", style = MaterialTheme.typography.titleMedium) }
                ExpressiveButton(
                    onClick = { vibrateClick(context); log(ozToMl(16)) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    tonal = true
                ) { Text("+16", style = MaterialTheme.typography.titleMedium) }
                ExpressiveButton(
                    onClick = {
                        vibrateClick(context)
                        scope.launch {
                            when (repo.undoLastTapResult()) {
                                WaterRepository.UndoResult.PendingRemoved -> justAdded = "Undid last drink"
                                WaterRepository.UndoResult.AlreadySynced -> justAdded = "Already synced — undo from phone"
                                WaterRepository.UndoResult.NothingToUndo -> justAdded = "Nothing to undo"
                            }
                            refresh(); onLogged()
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    tonal = true
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

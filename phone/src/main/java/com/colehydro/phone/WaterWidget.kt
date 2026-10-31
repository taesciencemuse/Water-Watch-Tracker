package com.colehydro.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.BitmapImageProvider
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.components.Scaffold
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Home screen widgets (Oct 30, 2026 redesign): four Glance variants
 * sharing one data model, one theme, and one log callback.
 *
 * Variants (picker names in the manifest):
 *  1. Ring — progress ring, tap the ring to log +8 oz.
 *  2. Wave — rectangle with a wave fill, tap to log at small sizes,
 *     pill at larger sizes.
 *  3. Ring + Quick Add — ring plus two stacked quick-add pills.
 *  4. Quick Add (Tile-style) — big today/goal on one side, two
 *     stacked quick-add pills on the other, like the watch Tile.
 *
 * Sizing: SizeMode.Responsive buckets (tiny / wide / square / large).
 * All four variants resize from 1x1 to 2x3. At the tiny bucket the
 * quick-add variants collapse: the pills are dropped, the whole
 * tile logs the default +8 oz, and the pills return at larger
 * sizes. A pill is never shrunk below the 48dp minimum tap target
 * to fit a smaller size.
 *
 * Colour: everything uses Material You dynamic colour roles via
 * color resources (values-v31 overrides the static fallback with
 * the wallpaper-derived system palette), EXCEPT the wave fill,
 * which is the one hard-coded colour: bright blue #2196F3, kept
 * consistent with the wave in the phone app (Cole, Oct 30, 2026).
 *
 * Animation: none continuous. Glance widgets are RemoteViews
 * snapshots, so the wave is static at rest; no loop, no
 * WorkManager-driven frames.
 *
 * Updates: on widget taps (here), on phone adds (MainActivity
 * refreshTotal), on watch taps (WatchMessageService), and on
 * notification +8 — all via WaterWidgetHelper.updateAll, which
 * updates every variant, not just the one tapped. No periodic
 * refresh is scheduled: the widget only changes when a real log
 * happens, plus Glance's own update on add / resize.
 */
object WidgetTheme {
    val background = ColorProvider(R.color.widget_bg)
    val onSurface = ColorProvider(R.color.widget_text)
    val onSurfaceVariant = ColorProvider(R.color.widget_on_surface_variant)
    val primary = ColorProvider(R.color.widget_primary)
    val onPrimary = ColorProvider(R.color.widget_on_primary)
    val primaryContainer = ColorProvider(R.color.widget_primary_container)
    val onPrimaryContainer = ColorProvider(R.color.widget_on_primary_container)
    val track = ColorProvider(R.color.widget_track)

    // The ONE hard-coded widget colour (see header): the wave fill,
    // matching the phone app's bright-blue wave.
    const val WAVE_ARGB: Int = 0xFF2196F3.toInt()
}

val WidgetAmountKey = ActionParameters.Key<Int>("amount_ml")
private const val DEFAULT_ML = 237 // +8 oz
private const val SECOND_ML = 473 // +16 oz

data class WidgetData(
    val totalMl: Int,
    val goalMl: Int,
    val totalOz: Int,
    val goalOz: Int,
    val progress: Float,
    val pct: Int,
    val ozToGo: Int
)

private suspend fun loadWidgetData(context: Context): WidgetData {
    // Oct 28 widget bug: the aggregate read can return 0 in widget
    // context even though the app total moved. Take the max of
    // three sources, so a failing read can never drag a known
    // total back to 0: aggregate, entries-sum, cached today total.
    val aggMl = try { HealthConnectWriter.todayTotalMl(context) } catch (_: Exception) { 0 }
    val entriesMl = try { HealthConnectWriter.todayTotalViaEntries(context) } catch (_: Exception) { 0 }
    val cachedMl = try { HealthConnectWriter.getCachedTotalMl(context) } catch (_: Exception) { 0 }
    val totalMl = maxOf(aggMl, entriesMl, cachedMl)
    val goalMl = HealthConnectWriter.getGoalMl(context)
    val totalOz = (totalMl / 29.5735).roundToInt()
    val goalOz = (goalMl / 29.5735).roundToInt()
    val progress = if (goalMl > 0) (totalMl.toFloat() / goalMl.toFloat()).coerceIn(0f, 1f) else 0f
    val ozToGo = if (goalMl > 0) maxOf(0, goalOz - totalOz) else 0
    return WidgetData(totalMl, goalMl, totalOz, goalOz, progress, (progress * 100).roundToInt(), ozToGo)
}

private fun drawRingBitmap(context: Context, progress: Float): Bitmap {
    val size = 480
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val stroke = 52f
    val rect = RectF(stroke / 2 + 6f, stroke / 2 + 6f, size - stroke / 2 - 6f, size - stroke / 2 - 6f)
    val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = WidgetTheme.track.getColor(context).toArgb()
        style = Paint.Style.STROKE
        strokeWidth = stroke
        strokeCap = Paint.Cap.ROUND
    }
    canvas.drawArc(rect, -90f, 360f, false, trackPaint)
    if (progress > 0f) {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WidgetTheme.primary.getColor(context).toArgb()
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        canvas.drawArc(rect, -90f, (progress * 360f).coerceAtLeast(4f), false, fillPaint)
    }
    return bitmap
}

private fun drawWaveBitmap(context: Context, progress: Float): Bitmap {
    val width = 600
    val height = 240
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    // Rounded-rect clip so the wave bar has soft corners at any size.
    val clip = Path().apply {
        addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 36f, 36f, Path.Direction.CW)
    }
    canvas.clipPath(clip)
    canvas.drawColor(WidgetTheme.track.getColor(context).toArgb())
    val fillHeight = (height * progress).toInt()
    if (fillHeight > 0) {
        val topY = (height - fillHeight).toFloat()
        val wavePath = Path().apply {
            moveTo(0f, topY)
            var x = 0
            while (x <= width) {
                val y = topY + (sin(x.toFloat() / width * Math.PI * 4).toFloat() * 10f)
                lineTo(x.toFloat(), y)
                x += 20
            }
            lineTo(width.toFloat(), height.toFloat())
            lineTo(0f, height.toFloat())
            close()
        }
        val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = WidgetTheme.WAVE_ARGB
            style = Paint.Style.FILL
        }
        canvas.drawPath(wavePath, wavePaint)
    }
    return bitmap
}

object WaterWidgetHelper {
    private val widgetClasses = listOf(
        WaterWidget::class.java,
        WaveWidget::class.java,
        RingQuickAddWidget::class.java,
        TileQuickAddWidget::class.java
    )

    fun updateAll(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val manager = GlanceAppWidgetManager(context)
                widgetClasses.forEach { clazz ->
                    manager.getGlanceIds(clazz).forEach { id ->
                        try {
                            @Suppress("UNCHECKED_CAST")
                            val widget = clazz.getDeclaredConstructor().newInstance() as GlanceAppWidget
                            widget.update(context, id)
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("WaterWidget", "updateAll failed", e)
            }
        }
    }
}

class AddWaterAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val ml = parameters[WidgetAmountKey] ?: DEFAULT_ML
        val wrote = try {
            HealthConnectWriter.writeManualMl(context, ml)
        } catch (_: Exception) {
            false
        }
        if (wrote) {
            vibrateClick(context)
            HealthConnectWriter.addToCachedTotalMl(context, ml)
        }
        WaterWidgetHelper.updateAll(context)
        // Live refresh (Oct 30): an open phone app re-reads now.
        if (wrote) TotalRefresh.send(context)
    }
}

enum class WidgetVariant { RING, WAVE, RING_QUICK_ADD, TILE_QUICK_ADD }

abstract class BaseWaterWidget(private val variant: WidgetVariant) : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(
            androidx.compose.ui.unit.DpSize(110.dp, 110.dp),
            androidx.compose.ui.unit.DpSize(250.dp, 110.dp),
            androidx.compose.ui.unit.DpSize(250.dp, 250.dp),
            androidx.compose.ui.unit.DpSize(250.dp, 350.dp)
        )
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadWidgetData(context)
        // Bitmaps are drawn here, before provideContent, so the
        // colours are resolved at draw time (never cached across
        // a wallpaper / light-dark change).
        val ringBitmap = drawRingBitmap(context, data.progress)
        val waveBitmap = drawWaveBitmap(context, data.progress)
        provideContent {
            Scaffold(
                backgroundColor = WidgetTheme.background,
                modifier = GlanceModifier.fillMaxSize().appWidgetBackground()
            ) {
                WidgetContent(variant, data, ringBitmap, waveBitmap)
            }
        }
    }
}

/** Ring (legacy WaterWidget class, so an already-placed widget migrates to the ring). */
class WaterWidget : BaseWaterWidget(WidgetVariant.RING)
class WaveWidget : BaseWaterWidget(WidgetVariant.WAVE)
class RingQuickAddWidget : BaseWaterWidget(WidgetVariant.RING_QUICK_ADD)
class TileQuickAddWidget : BaseWaterWidget(WidgetVariant.TILE_QUICK_ADD)

class WaterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WaterWidget()
}

class WaveWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WaveWidget()
}

class RingQuickAddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RingQuickAddWidget()
}

class TileQuickAddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TileQuickAddWidget()
}

@Composable
private fun QuickAddPill(oz: Int, ml: Int, primary: Boolean) {
    Box(
        modifier = GlanceModifier.fillMaxWidth().height(48.dp)
            .background(if (primary) WidgetTheme.primary else WidgetTheme.primaryContainer)
            .cornerRadius(24.dp)
            .clickable(actionRunCallback<AddWaterAction>(actionParametersOf(WidgetAmountKey to ml))),
        contentAlignment = Alignment.Center
    ) {
        Text(
            "+$oz oz",
            style = TextStyle(
                fontWeight = FontWeight.Bold,
                color = if (primary) WidgetTheme.onPrimary else WidgetTheme.onPrimaryContainer
            )
        )
    }
}

@Composable
private fun RingDisplay(data: WidgetData, bitmap: Bitmap, size: Dp) {
    Box(
        modifier = GlanceModifier.size(size)
            .clickable(actionRunCallback<AddWaterAction>(actionParametersOf(WidgetAmountKey to DEFAULT_ML))),
        contentAlignment = Alignment.Center
    ) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = "Today's water progress",
            modifier = GlanceModifier.fillMaxSize()
        )
        Text(
            "${data.pct}%",
            style = TextStyle(fontWeight = FontWeight.Bold, color = WidgetTheme.onSurface)
        )
    }
}

@Composable
private fun NumbersColumn(data: WidgetData, showToGo: Boolean) {
    Column {
        if (data.goalOz > 0) {
            Text(
                "${data.totalOz} / ${data.goalOz} oz",
                style = TextStyle(fontWeight = FontWeight.Bold, color = WidgetTheme.onSurface)
            )
            if (showToGo) {
                Spacer(modifier = GlanceModifier.height(4.dp))
                Text(
                    if (data.ozToGo > 0) "${data.ozToGo} oz to go" else "Goal reached!",
                    style = TextStyle(color = WidgetTheme.onSurfaceVariant)
                )
            }
        } else {
            Text(
                "${data.totalOz} oz",
                style = TextStyle(fontWeight = FontWeight.Bold, color = WidgetTheme.onSurface)
            )
            if (showToGo) {
                Spacer(modifier = GlanceModifier.height(4.dp))
                Text("Set a goal in the app", style = TextStyle(color = WidgetTheme.onSurfaceVariant))
            }
        }
    }
}

@Composable
private fun WidgetContent(
    variant: WidgetVariant,
    data: WidgetData,
    ringBitmap: Bitmap,
    waveBitmap: Bitmap
) {
    val size = LocalSize.current
    val tiny = size.width < 180.dp
    val wide = !tiny && size.height < 180.dp
    val large = !tiny && !wide && size.height >= 300.dp
    // Everything except a log tap opens the app; log zones below
    // carry their own clickable, which takes precedence there.
    Box(
        modifier = GlanceModifier.fillMaxSize().padding(12.dp)
            .clickable(actionStartActivity<MainActivity>()),
        contentAlignment = Alignment.Center
    ) {
        when {
            tiny -> TinyContent(variant, data, ringBitmap, waveBitmap)
            wide -> WideContent(variant, data, ringBitmap, waveBitmap)
            large -> {
                Column(
                    modifier = GlanceModifier.fillMaxSize(),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Text("Water Logged", style = TextStyle(fontWeight = FontWeight.Medium, color = WidgetTheme.onSurfaceVariant))
                    Spacer(modifier = GlanceModifier.height(8.dp))
                    SquareContent(variant, data, ringBitmap, waveBitmap)
                    Spacer(modifier = GlanceModifier.height(12.dp))
                    Text(
                        if (data.goalOz > 0 && data.ozToGo > 0) "${data.ozToGo} oz to go today"
                        else if (data.goalOz > 0) "Daily goal reached!"
                        else "Set a daily goal in the app",
                        style = TextStyle(color = WidgetTheme.onSurfaceVariant)
                    )
                }
            }
            else -> SquareContent(variant, data, ringBitmap, waveBitmap)
        }
    }
}

@Composable
private fun TinyContent(
    variant: WidgetVariant,
    data: WidgetData,
    ringBitmap: Bitmap,
    waveBitmap: Bitmap
) {
    // One thing only, and the whole tile logs the default amount.
    // Quick-add pills are omitted here and return at larger sizes.
    when (variant) {
        WidgetVariant.WAVE -> {
            Box(
                modifier = GlanceModifier.fillMaxSize()
                    .background(ImageProvider(waveBitmap), ContentScale.FillBounds)
                    .cornerRadius(12.dp)
                    .clickable(actionRunCallback<AddWaterAction>(actionParametersOf(WidgetAmountKey to DEFAULT_ML))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${data.pct}%",
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        color = if (data.pct >= 50) ColorProvider(android.graphics.Color.WHITE) else WidgetTheme.onSurface
                    )
                )
            }
        }
        WidgetVariant.TILE_QUICK_ADD -> {
            Box(
                modifier = GlanceModifier.fillMaxSize()
                    .clickable(actionRunCallback<AddWaterAction>(actionParametersOf(WidgetAmountKey to DEFAULT_ML))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "${data.totalOz} oz",
                    style = TextStyle(fontWeight = FontWeight.Bold, color = WidgetTheme.onSurface)
                )
            }
        }
        else -> RingDisplay(data, ringBitmap, 84.dp)
    }
}

@Composable
private fun WideContent(
    variant: WidgetVariant,
    data: WidgetData,
    ringBitmap: Bitmap,
    waveBitmap: Bitmap
) {
    when (variant) {
        WidgetVariant.WAVE -> {
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                NumbersColumn(data, showToGo = false)
                Spacer(modifier = GlanceModifier.height(8.dp))
                Box(
                    modifier = GlanceModifier.fillMaxWidth().height(48.dp)
                        .background(ImageProvider(waveBitmap), ContentScale.FillBounds)
                        .cornerRadius(12.dp)
                        .clickable(actionRunCallback<AddWaterAction>(actionParametersOf(WidgetAmountKey to DEFAULT_ML)))
                ) {}
            }
        }
        WidgetVariant.RING_QUICK_ADD, WidgetVariant.TILE_QUICK_ADD -> {
            Row(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                if (variant == WidgetVariant.RING_QUICK_ADD) {
                    RingDisplay(data, ringBitmap, 72.dp)
                    Spacer(modifier = GlanceModifier.width(12.dp))
                } else {
                    Box(modifier = GlanceModifier.fillMaxHeight().defaultWeight(), contentAlignment = Alignment.CenterStart) {
                        NumbersColumn(data, showToGo = false)
                    }
                    Spacer(modifier = GlanceModifier.width(12.dp))
                }
                // One pill only at wide height: two stacked 48dp
                // pills cannot fit 110dp with padding, so +16
                // returns at square and larger.
                Box(modifier = GlanceModifier.fillMaxHeight().defaultWeight(), contentAlignment = Alignment.Center) {
                    QuickAddPill(8, DEFAULT_ML, primary = true)
                }
            }
        }
        else -> {
            Row(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                RingDisplay(data, ringBitmap, 72.dp)
                Spacer(modifier = GlanceModifier.width(12.dp))
                NumbersColumn(data, showToGo = true)
            }
        }
    }
}

@Composable
private fun SquareContent(
    variant: WidgetVariant,
    data: WidgetData,
    ringBitmap: Bitmap,
    waveBitmap: Bitmap
) {
    when (variant) {
        WidgetVariant.WAVE -> {
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                NumbersColumn(data, showToGo = true)
                Spacer(modifier = GlanceModifier.height(12.dp))
                Box(
                    modifier = GlanceModifier.fillMaxWidth().height(100.dp)
                        .background(ImageProvider(waveBitmap), ContentScale.FillBounds)
                        .cornerRadius(12.dp)
                        .clickable(actionRunCallback<AddWaterAction>(actionParametersOf(WidgetAmountKey to DEFAULT_ML)))
                ) {}
                Spacer(modifier = GlanceModifier.height(12.dp))
                QuickAddPill(8, DEFAULT_ML, primary = true)
            }
        }
        WidgetVariant.RING_QUICK_ADD -> {
            Row(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                RingDisplay(data, ringBitmap, 120.dp)
                Spacer(modifier = GlanceModifier.width(12.dp))
                Column(modifier = GlanceModifier.fillMaxWidth()) {
                    QuickAddPill(8, DEFAULT_ML, primary = true)
                    Spacer(modifier = GlanceModifier.height(8.dp))
                    QuickAddPill(16, SECOND_ML, primary = false)
                }
            }
        }
        WidgetVariant.TILE_QUICK_ADD -> {
            Row(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Box(modifier = GlanceModifier.fillMaxHeight().defaultWeight(), contentAlignment = Alignment.CenterStart) {
                    NumbersColumn(data, showToGo = true)
                }
                Spacer(modifier = GlanceModifier.width(12.dp))
                Column(modifier = GlanceModifier.fillMaxHeight().defaultWeight(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                    QuickAddPill(8, DEFAULT_ML, primary = true)
                    Spacer(modifier = GlanceModifier.height(8.dp))
                    QuickAddPill(16, SECOND_ML, primary = false)
                }
            }
        }
        else -> {
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
                horizontalAlignment = Alignment.Horizontal.CenterHorizontally
            ) {
                RingDisplay(data, ringBitmap, 140.dp)
                Spacer(modifier = GlanceModifier.height(12.dp))
                NumbersColumn(data, showToGo = true)
            }
        }
    }
}

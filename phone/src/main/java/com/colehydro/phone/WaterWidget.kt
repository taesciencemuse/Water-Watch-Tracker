package com.colehydro.phone

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Home screen widget (v12, item 8): today total / goal, progress bar,
 * a +8 button, and tap-to-open Water Logged. Built with Glance, so
 * it uses the same Compose-style layout as the app.
 *
 * Updates: on widget +8 tap (here), on phone adds (MainActivity
 * refreshTotal), on watch taps (WatchMessageService), and on
 * notification +8 (NotificationActionReceiver) — all via
 * WaterWidgetHelper.updateAll. No periodic refresh is scheduled:
 * the widget only changes when a real log happens, plus Glance's
 * own update on widget add / resize.
 */
object WaterWidgetHelper {
    fun updateAll(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val manager = GlanceAppWidgetManager(context)
                manager.getGlanceIds(WaterWidget::class.java).forEach { id ->
                    WaterWidget().update(context, id)
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
        val wrote = try {
            HealthConnectWriter.writeManualMl(context, 237)
        } catch (_: Exception) {
            false
        }
        if (wrote) {
            vibrateClick(context)
            HealthConnectWriter.addToCachedTotalMl(context, 237)
        }
        try {
            WaterWidget().update(context, glanceId)
        } catch (_: Exception) {
        }
    }
}

class WaterWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Oct 28 widget bug: widget +8 writes (app total goes up)
        // and a fresh re-add both still show 0, while the goal
        // (prefs) reads fine. So the widget's aggregate read is
        // returning 0 in this context even though the same call
        // works in the app. Take the max of three sources, so a
        // failing read can never drag a known total back to 0:
        // aggregate, entries-sum, and the app's cached today total.
        val aggMl = try { HealthConnectWriter.todayTotalMl(context) } catch (_: Exception) { 0 }
        val entriesMl = try { HealthConnectWriter.todayTotalViaEntries(context) } catch (_: Exception) { 0 }
        val cachedMl = try { HealthConnectWriter.getCachedTotalMl(context) } catch (_: Exception) { 0 }
        android.util.Log.d("WaterWidget", "provideGlance agg=$aggMl entries=$entriesMl cached=$cachedMl")
        val totalMl = maxOf(aggMl, entriesMl, cachedMl)
        val goalMl = HealthConnectWriter.getGoalMl(context)
        val totalOz = (totalMl / 29.5735).roundToInt()
        val goalOz = (goalMl / 29.5735).roundToInt()
        val progress = (totalMl.toFloat() / goalMl.toFloat()).coerceIn(0f, 1f)
        provideContent {
            Box(
                modifier = GlanceModifier.fillMaxSize()
                    .background(ColorProvider(androidx.compose.ui.graphics.Color(0xFFE3F2FD)))
                    .clickable(actionStartActivity<MainActivity>())
                    .padding(16.dp)
            ) {
                Column(
                    modifier = GlanceModifier.fillMaxSize(),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Text(
                        "Water Logged",
                        style = TextStyle(fontWeight = FontWeight.Medium)
                    )
                    Spacer(modifier = GlanceModifier.height(4.dp))
                    Text(
                        "$totalOz / $goalOz oz",
                        style = TextStyle(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = GlanceModifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = progress,
                        modifier = GlanceModifier.fillMaxWidth().height(8.dp)
                    )
                    Spacer(modifier = GlanceModifier.height(12.dp))
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.Horizontal.End
                    ) {
                        Text(
                            text = "+8 oz",
                            modifier = GlanceModifier.clickable(actionRunCallback<AddWaterAction>(actionParametersOf())),
                            style = TextStyle(fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}

class WaterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = WaterWidget()
}

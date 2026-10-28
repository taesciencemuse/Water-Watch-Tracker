package com.colehydro.wear

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.GoalProgressComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * The watch-face surface.
 *
 * Shows today's total against the goal. Watch faces render this
 * differently by slot: a ring / arc for RANGED_VALUE and
 * GOAL_PROGRESS slots, plain text ("32 oz") for SHORT_TEXT slots.
 *
 * Direct log (v13, item 6): a complication tap is a PendingIntent
 * to LogWaterReceiver, which logs the default amount (+8 oz) with
 * no activity opening at all. MainActivity is still the watch
 * app-list quick-add screen; the complication no longer opens it.
 *
 * Updates are manual (UPDATE_PERIOD_SECONDS = 0 in the manifest):
 * after every tap, MainActivity / the Tile must request a
 * complication refresh. Complications are not for real-time updates.
 */
class HydrationComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        when (type) {
            ComplicationType.RANGED_VALUE -> ranged(32f, 64f)
            ComplicationType.GOAL_PROGRESS -> goal(32f, 64f)
            ComplicationType.SHORT_TEXT -> short(32)
            else -> null
        }

    override suspend fun onComplicationRequest(
        request: ComplicationRequest
    ): ComplicationData? {
        val repo = WaterRepository(this)
        val (todayMl, goalMl, _) = repo.displayedSnapshot()
        val todayOz = mlToOz(todayMl).toFloat()
        val goalOz = mlToOz(goalMl).toFloat()
        return when (request.complicationType) {
            ComplicationType.RANGED_VALUE -> ranged(todayOz, goalOz)
            ComplicationType.GOAL_PROGRESS -> goal(todayOz, goalOz)
            ComplicationType.SHORT_TEXT -> short(todayOz.toInt())
            else -> null
        }
    }

    private fun tapAction(): PendingIntent {
        val intent = Intent(this, LogWaterReceiver::class.java).apply {
            action = LogWaterReceiver.ACTION_LOG_DEFAULT
        }
        return PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ranged(value: Float, max: Float): ComplicationData {
        val text = PlainComplicationText.Builder("${value.toInt()} oz").build()
        return RangedValueComplicationData.Builder(
            value = value,
            min = 0f,
            max = max,
            contentDescription = text
        )
            .setText(text)
            .setTapAction(tapAction())
            .build()
    }

    private fun goal(value: Float, target: Float): ComplicationData {
        val text = PlainComplicationText.Builder("${value.toInt()} oz").build()
        return GoalProgressComplicationData.Builder(
            value = value,
            targetValue = target,
            contentDescription = text
        )
            .setText(text)
            .setTapAction(tapAction())
            .build()
    }

    private fun short(valueOz: Int): ComplicationData {
        val text = PlainComplicationText.Builder("$valueOz oz").build()
        return ShortTextComplicationData.Builder(
            text = text,
            contentDescription = text
        )
            .setTapAction(tapAction())
            .build()
    }
}

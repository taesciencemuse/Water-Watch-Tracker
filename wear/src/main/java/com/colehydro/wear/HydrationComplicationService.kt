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
 * The watch-face surface — "Water Logged - Add water" in the
 * complication picker (Oct 30, Cole: two picker entries instead
 * of a phone Settings toggle).
 *
 * Shows today's total against the goal. Watch faces render this
 * differently by slot: a ring / arc for RANGED_VALUE and
 * GOAL_PROGRESS slots, plain text ("32 oz") for SHORT_TEXT slots.
 *
 * Direct log (v13, item 6): a complication tap is a PendingIntent
 * to LogWaterReceiver, which logs the default amount (+8 oz) with
 * no activity opening at all. The sibling service
 * HydrationOpenComplicationService shows the same data but opens
 * the watch app on tap; the user chooses the behaviour by which
 * complication they place in a slot, not by a setting.
 *
 * Updates are manual (UPDATE_PERIOD_SECONDS = 0 in the manifest):
 * after every tap, MainActivity / the Tile must request a
 * complication refresh. Complications are not for real-time updates.
 */
open class HydrationComplicationService : SuspendingComplicationDataSourceService() {

    /** Tap behaviour for this provider: "add" here, "open" in
     *  the sibling service. Subclasses override this; it is not
     *  read from a setting or a DataStore mode any more. */
    protected open val tapMode: String = "add"

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
            ComplicationType.RANGED_VALUE -> ranged(todayOz, goalOz, tapMode)
            ComplicationType.GOAL_PROGRESS -> goal(todayOz, goalOz, tapMode)
            ComplicationType.SHORT_TEXT -> short(todayOz.toInt(), tapMode)
            else -> null
        }
    }

    companion object {
        /** Request a refresh of BOTH complication providers.
         *  Call sites must update both, otherwise the provider
         *  the user did not tap would go stale on goal/total
         *  changes (Oct 30, two-provider change). */
        fun requestAllUpdates(context: android.content.Context) {
            for (cls in listOf(
                HydrationComplicationService::class.java,
                HydrationOpenComplicationService::class.java
            )) {
                try {
                    androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester.create(
                        context,
                        android.content.ComponentName(context, cls)
                    ).requestUpdateAll()
                } catch (_: Exception) { }
            }
        }
    }

    private fun tapAction(mode: String = "add"): PendingIntent {
        if (mode == "open") {
            // Open the watch app instead of logging (Oct 29, Cole:
            // user-chosen complication behaviour, set in phone Settings).
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            return PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        val intent = Intent(this, LogWaterReceiver::class.java).apply {
            action = LogWaterReceiver.ACTION_LOG_DEFAULT
        }
        return PendingIntent.getBroadcast(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun ranged(value: Float, max: Float, mode: String = "add"): ComplicationData {
        val text = PlainComplicationText.Builder("${value.toInt()} oz").build()
        return RangedValueComplicationData.Builder(
            value = value,
            min = 0f,
            max = max,
            contentDescription = text
        )
            .setText(text)
            .setTapAction(tapAction(mode))
            .build()
    }

    private fun goal(value: Float, target: Float, mode: String = "add"): ComplicationData {
        val text = PlainComplicationText.Builder("${value.toInt()} oz").build()
        return GoalProgressComplicationData.Builder(
            value = value,
            targetValue = target,
            contentDescription = text
        )
            .setText(text)
            .setTapAction(tapAction(mode))
            .build()
    }

    private fun short(valueOz: Int, mode: String = "add"): ComplicationData {
        val text = PlainComplicationText.Builder("$valueOz oz").build()
        return ShortTextComplicationData.Builder(
            text = text,
            contentDescription = text
        )
            .setTapAction(tapAction(mode))
            .build()
    }
}

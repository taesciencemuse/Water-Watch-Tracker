package com.colehydro.wear

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

/**
 * The primary surface.
 *
 * Layout (top to bottom):
 *   "Water" + today's total, e.g. "32 / 64 oz"
 *   Big +8 oz button (your default — changeable in the app)
 *   Row: +12 oz | +16 oz | Undo
 *   Footer: "Synced" or "Pending — phone away" (pending count)
 *
 * Direct-log interaction (v13, items 5): Tile buttons use
 * LoadAction, not LaunchAction. A tap re-requests this TileService
 * with the clicked element's id in
 * requestParams.currentState.lastClickableId; onTileRequest logs
 * that amount (or undoes) BEFORE snapshotting, so the returned
 * tile already shows the new total — no activity opens at all.
 * CRITICAL: the returned tile must carry a cleared (empty) state,
 * otherwise the next normal tile request would see the same
 * lastClickableId again and log a second drink.
 */
class WaterTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> = scope.future {
        val repo = WaterRepository(this@WaterTileService)
        // Direct log: LoadAction tap arrives here as lastClickableId.
        // Log BEFORE snapshotting, so the returned tile shows the
        // new total. Unknown / empty ids mean a normal refresh.
        val clicked = try { requestParams.currentState?.lastClickableId } catch (_: Exception) { null }
        if (clicked == "add_default" || clicked == "add_12" || clicked == "add_16") {
            vibrateClick(this@WaterTileService)
            val ( _, _, defaultMl0) = repo.snapshot()
            val ml = when (clicked) {
                "add_12" -> ozToMl(12)
                "add_16" -> ozToMl(16)
                else -> defaultMl0
            }
            val tap = WaterTap(
                id = java.util.UUID.randomUUID().toString(),
                ml = ml,
                timestampMillis = System.currentTimeMillis()
            )
            repo.addTap(tap)
            SyncSender.sendTap(this@WaterTileService, tap)
            try {
                androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester.create(
                    this@WaterTileService,
                    android.content.ComponentName(this@WaterTileService, HydrationComplicationService::class.java)
                ).requestUpdateAll()
            } catch (_: Exception) { }
        } else if (clicked == "undo") {
            vibrateClick(this@WaterTileService)
            repo.undoLastTap()
        }
        // Try to flush anything queued while the phone was away.
        scope.launch { SyncSender.flushPending(this@WaterTileService, repo) }
        val (todayMl, goalMl, defaultMl) = repo.snapshot()
        val pending = repo.pending().size
        val todayOz = mlToOz(todayMl)
        val goalOz = mlToOz(goalMl)
        val defaultOz = mlToOz(defaultMl)

        val layout = PrimaryLayout.Builder(
            DeviceParametersBuilders.DeviceParameters.Builder().build()
        )
            .setContent(
                LayoutElementBuilders.Column.Builder()
                    .addContent(
                        Text.Builder(this@WaterTileService, "$todayOz / $goalOz oz")
                            .build()
                    )
                    .addContent(
                        bigButton("add_default", "+$defaultOz oz", defaultMl)
                    )
                    .addContent(
                        LayoutElementBuilders.Row.Builder()
                            .addContent(smallButton("add_12", "+12 oz", ozToMl(12)))
                            .addContent(smallButton("add_16", "+16 oz", ozToMl(16)))
                            .addContent(smallButton("undo", "Undo", -1))
                            .build()
                    )
                    .addContent(
                        Text.Builder(
                            this@WaterTileService,
                            if (pending == 0) "Synced" else "$pending pending — phone away"
                        ).build()
                    )
                    .build()
            )
            .build()

        TileBuilders.Tile.Builder()
            .setState(androidx.wear.protolayout.StateBuilders.State.Builder().build())
            .setResourcesVersion("1")
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(layout)
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .build()
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest
    ): ListenableFuture<ResourceBuilders.Resources> =
        Futures.immediateFuture(
            ResourceBuilders.Resources.Builder().setVersion("1").build()
        )

    private fun bigButton(id: String, label: String, ml: Int) =
        LayoutElementBuilders.Box.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId(id)
                            .setOnClick(launchAdd(ml))
                            .build()
                    )
                    .build()
            )
            .addContent(Text.Builder(this, label).build())
            .build()

    private fun smallButton(id: String, label: String, ml: Int) =
        LayoutElementBuilders.Box.Builder()
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId(id)
                            .setOnClick(launchAdd(ml))
                            .build()
                    )
                    .build()
            )
            .addContent(Text.Builder(this, label).build())
            .build()

    private fun launchAdd(ml: Int): ActionBuilders.LoadAction {
        // Direct log: no activity, no extra. The amount is recovered
        // from the clickable id in onTileRequest (add_default /
        // add_12 / add_16 / undo), so the ml argument is kept only
        // for the button label's call site — the id carries it.
        return ActionBuilders.LoadAction.Builder()
            .setRequestState(androidx.wear.protolayout.StateBuilders.State.Builder().build())
            .build()
    }

    companion object {
        // Call this after any tap so the Tile and complication refresh.
        // TileService.requestUpdate / ComplicationDataSource update
        // calls belong here once the exact ProtoLayout version is pinned
        // in Android Studio — see README "Refresh" section.
        fun requestTileUpdate(context: android.content.Context) {
            TileService.getUpdater(context)
                .requestUpdate(WaterTileService::class.java)
        }
    }
}

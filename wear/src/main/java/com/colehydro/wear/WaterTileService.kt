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
 * v1 interaction note — read this before changing it:
 * Tile buttons here use LaunchAction to MainActivity with an amount
 * extra. That is the documented, version-stable path: tapping the
 * Tile button opens the tiny quick-add screen, which logs immediately
 * and shows the new total. It is still one tap from the Tile, with no
 * app drawer involved.
 *
 * The stretch goal is LoadAction (delegate to this TileService so the
 * tap logs without opening any activity at all). LoadAction click
 * dispatch differs between ProtoLayout versions, so prototype it
 * after the LaunchAction version works on the Pixel Watch 5, rather
 * than building v1 on it.
 */
class WaterTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest
    ): ListenableFuture<TileBuilders.Tile> = scope.future {
        val repo = WaterRepository(this@WaterTileService)
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

    private fun launchAdd(ml: Int): ActionBuilders.LaunchAction {
        // ml == -1 means Undo. MainActivity interprets the extra.
        return ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(MainActivity::class.java.name)
                    .addKeyToExtraMapping(
                        MainActivity.EXTRA_AMOUNT_ML,
                        ActionBuilders.stringExtra(ml.toString())
                    )
                    .build()
            )
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

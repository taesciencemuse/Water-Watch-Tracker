package com.colehydro.wear

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DeviceParametersBuilders
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonDefaults
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * The primary surface.
 *
 * Layout (weather-tile style, Oct 28, Cole):
 *   Top: "Today"
 *   Middle Row: left large square (today / goal + wave fill
 *     rising with progress) | right stacked pills (+default, +16)
 *   Bottom footer: "Synced" or "Pending — phone away" (pending count)
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
        var tileError: String? = null
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
            try {
                repo.addTap(tap)
            } catch (e: Exception) {
                // Tile taps previously blinked but did nothing, with
                // no visible feedback (Oct 28). Show the failure in
                // the footer so a failed tap is visible, not silent.
                tileError = "Couldn't log: ${e.javaClass.simpleName}"
            }
            if (tileError == null && SyncSender.sendTap(this@WaterTileService, tap)) repo.markSynced(setOf(tap.id))
            HydrationComplicationService.requestAllUpdates(this@WaterTileService)
        } else if (clicked == "undo") {
            vibrateClick(this@WaterTileService)
            try {
                repo.undoLastTap()
            } catch (e: Exception) {
                tileError = "Couldn't undo: ${e.javaClass.simpleName}"
            }
        }
        // Try to flush anything queued while the phone was away.
        scope.launch { SyncSender.flushPending(this@WaterTileService, repo) }
        val (todayMl, goalMl, defaultMl) = repo.displayedSnapshot()
        val pending = repo.pending().size
        // Footer honesty (Oct 30, Cole: Tile said "Synced" before
        // the phone app had updated). Previously pending == 0
        // meant "sent to the phone", not "phone confirmed it".
        // Now: pending taps -> "N pending — phone away"; no
        // pending taps but the phone's HC total hasn't caught up
        // to the watch total yet -> "Syncing"; otherwise "Synced".
        // The /hydro/total ack from the phone re-requests the
        // Tile (GoalMessageService), so "Syncing" flips to
        // "Synced" when that ack arrives. A 0 total with nothing
        // logged is "Synced", not "Syncing".
        val watchMl = repo.watchTodayMl()
        val hcMl = repo.hcTodayMl()
        val tileFooter = tileError ?: if (pending > 0) "$pending pending — phone away"
            else if (watchMl > 0 && hcMl < watchMl) "Syncing"
            else "Synced"
        val todayOz = mlToOz(todayMl)
        val goalOz = mlToOz(goalMl)
        val defaultOz = mlToOz(defaultMl)

        // Weather-tile layout (Oct 28, Cole): top "Today", middle Row
        // with a large left square (today / goal + wave fill rising
        // with progress) and two stacked pill buttons on the right
        // (default tap, then +16), bottom footer (Synced / pending /
        // error). Tiles are ProtoLayout, not Compose, so the wave is
        // a static wave-edge image + a fill height computed from
        // progress on each refresh, not an animated Canvas wave.
        // +12 and Undo leave the Tile in this layout; both stay in
        // the Wear app. If the default tap is already +16, the
        // second pill falls back to +12 so the two pills differ.
        val progress = if (goalMl > 0) (todayMl.toFloat() / goalMl.toFloat()).coerceIn(0f, 1f) else 0f
        val squareH = 124
        val waveH = 12
        val fillTotal = (progress * squareH).toInt()
        val waveShown = if (fillTotal <= 0) 0 else minOf(waveH, fillTotal)
        val fillBoxH = fillTotal - waveShown
        val secondOz = if (defaultOz == 16) 12 else 16
        val secondId = if (defaultOz == 16) "add_12" else "add_16"

        // ProtoLayout Column has no vertical alignment: it
        // stacks top-to-bottom, so bottom-align the wave/fill with
        // a top spacer of the unfilled height.
        val fillColumn = LayoutElementBuilders.Column.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.dp(104f))
            .setHeight(androidx.wear.protolayout.DimensionBuilders.dp(squareH.toFloat()))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(spacerH(squareH - fillTotal))
        if (waveShown > 0) {
            fillColumn.addContent(
                LayoutElementBuilders.Image.Builder()
                    .setResourceId("wave")
                    .setWidth(androidx.wear.protolayout.DimensionBuilders.dp(104f))
                    .setHeight(androidx.wear.protolayout.DimensionBuilders.dp(waveShown.toFloat()))
                    .setContentScaleMode(LayoutElementBuilders.CONTENT_SCALE_MODE_FILL_BOUNDS)
                    .build()
            )
        }
        if (fillBoxH > 0) {
            fillColumn.addContent(
                LayoutElementBuilders.Box.Builder()
                    .setWidth(androidx.wear.protolayout.DimensionBuilders.dp(104f))
                    .setHeight(androidx.wear.protolayout.DimensionBuilders.dp(fillBoxH.toFloat()))
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setBackground(
                                ModifiersBuilders.Background.Builder()
                                    .setColor(ColorBuilders.argb(0xFF2196F3.toInt()))
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
        }

        val leftSquare = LayoutElementBuilders.Box.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.dp(104f))
            .setHeight(androidx.wear.protolayout.DimensionBuilders.dp(squareH.toFloat()))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId("open_app")
                            .setOnClick(
                                ActionBuilders.LaunchAction.Builder()
                                    .setAndroidActivity(
                                        ActionBuilders.AndroidActivity.Builder()
                                            .setPackageName("com.taescience.waterlogged")
                                            .setClassName("com.colehydro.wear.MainActivity")
                                            .build()
                                    )
                                    .build()
                            )
                            .build()
                    )
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(0xFF0B57D0.toInt()))
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(androidx.wear.protolayout.DimensionBuilders.dp(20f))
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .addContent(fillColumn.build())
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setWidth(androidx.wear.protolayout.DimensionBuilders.dp(104f))
                    .setHeight(androidx.wear.protolayout.DimensionBuilders.dp(squareH.toFloat()))
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(spacerH(37))
                    .addContent(sizedText("$todayOz", 32, 0xFFFFFFFF.toInt(), LayoutElementBuilders.FONT_WEIGHT_BOLD, 34))
                    .addContent(sizedText("/ $goalOz oz", 14, 0xFFFFFFFF.toInt(), LayoutElementBuilders.FONT_WEIGHT_NORMAL, 16))
                    .addContent(spacerH(37))
                    .build()
            )
            .build()

        val rightColumn = LayoutElementBuilders.Column.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.dp(76f))
            .setHeight(androidx.wear.protolayout.DimensionBuilders.dp(squareH.toFloat()))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(spacerH(2))
            .addContent(pillButton("add_default", "+$defaultOz"))
            .addContent(spacerH(8))
            .addContent(pillButton(secondId, "+$secondOz"))
            .build()

        // Vertically centred (Oct 29, Cole): previously this was
        // a top-aligned Column, so the Tile sat high on screen and
        // "Today" collided with the Tile app-icon header. A Box with
        // CENTER alignment centres the inner Column in the Tile area.
        val root = LayoutElementBuilders.Box.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.expand())
            .setHeight(androidx.wear.protolayout.DimensionBuilders.expand())
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setPadding(
                        ModifiersBuilders.Padding.Builder()
                            .setAll(androidx.wear.protolayout.DimensionBuilders.dp(12f))
                            .build()
                    )
                    .build()
            )
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                    .addContent(sizedText("Today", 14, 0xFFFFFFFF.toInt(), LayoutElementBuilders.FONT_WEIGHT_NORMAL, 16))
                    .addContent(spacerH(8))
                    .addContent(
                        LayoutElementBuilders.Row.Builder()
                            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
                            .addContent(leftSquare)
                            .addContent(spacer(8))
                            .addContent(rightColumn)
                            .build()
                    )
                    .addContent(spacerH(8))
                    .addContent(
                        sizedText(
                            tileFooter,
                            11, 0xFFFFFFFF.toInt(), LayoutElementBuilders.FONT_WEIGHT_NORMAL, 13
                        )
                    )
                    .build()
            )
            .build()

        // Midnight expiry (Oct 30, Cole: Tile showed yesterday's
        // 51 oz on Oct 30 morning while app/complication/phone
        // correctly showed 0). A Tile timeline with no validity
        // is cached indefinitely until a tap or requestUpdate, so
        // nothing re-rendered it at midnight. Expiring it at
        // local midnight forces the system to re-request it on
        // the new day, when displayedSnapshot() rolls the total
        // over to 0.
        val endOfDayMillis = LocalDate.now().plusDays(1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        TileBuilders.Tile.Builder()
            .setState(androidx.wear.protolayout.StateBuilders.State.Builder().build())
            .setResourcesVersion("2")
            .setTileTimeline(
                TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setValidity(
                                TimelineBuilders.TimeInterval.Builder()
                                    .setStartMillis(System.currentTimeMillis())
                                    .setEndMillis(endOfDayMillis)
                                    .build()
                            )
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(root)
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
            ResourceBuilders.Resources.Builder().setVersion("2")
                .addIdToImageMapping(
                    "wave",
                    ResourceBuilders.ImageResource.Builder()
                        .setAndroidResourceByResId(
                            ResourceBuilders.AndroidImageResourceByResId.Builder()
                                .setResourceId(R.drawable.wave_tile)
                                .build()
                        )
                        .build()
                )
                .build()
        )

    private fun white() =
        ColorBuilders.ColorProp.Builder().setArgb(0xFFFFFFFF.toInt()).build()

    private fun spacer(widthDp: Int) =
        LayoutElementBuilders.Spacer.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.dp(widthDp.toFloat()))
            .build()

    private fun spacerH(heightDp: Int) =
        LayoutElementBuilders.Spacer.Builder()
            .setHeight(androidx.wear.protolayout.DimensionBuilders.dp(heightDp.toFloat()))
            .build()

    // Sized ProtoLayout text with a tight line height, so the
    // number / goal block fits inside its container instead of
    // pushing apart on line-height leading.
    private fun sizedText(text: String, sizeSp: Int, argb: Int, weight: Int, lineHeightSp: Int) =
        LayoutElementBuilders.Text.Builder()
            .setText(text)
            .setFontStyle(
                LayoutElementBuilders.FontStyle.Builder()
                    .setSize(androidx.wear.protolayout.DimensionBuilders.sp(sizeSp.toFloat()))
                    .setColor(ColorBuilders.argb(argb))
                    .setWeight(weight)
                    .build()
            )
            .setLineHeight(androidx.wear.protolayout.DimensionBuilders.sp(lineHeightSp.toFloat()))
            .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
            .build()

    // Weather-tile pill button: rounded rectangle, bright blue,
    // dark text, LoadAction tap (no activity opens). Replaces the
    // circular Material Buttons from v17 for the Tile only.
    private fun pillButton(id: String, label: String) =
        LayoutElementBuilders.Box.Builder()
            .setWidth(androidx.wear.protolayout.DimensionBuilders.dp(76f))
            .setHeight(androidx.wear.protolayout.DimensionBuilders.dp(56f))
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .setVerticalAlignment(LayoutElementBuilders.VERTICAL_ALIGN_CENTER)
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(
                        ModifiersBuilders.Clickable.Builder()
                            .setId(id)
                            .setOnClick(launchAdd(0))
                            .build()
                    )
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(ColorBuilders.argb(0xFF2196F3.toInt()))
                            .setCorner(
                                ModifiersBuilders.Corner.Builder()
                                    .setRadius(androidx.wear.protolayout.DimensionBuilders.dp(28f))
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .addContent(sizedText(label, 18, 0xFF102A43.toInt(), LayoutElementBuilders.FONT_WEIGHT_BOLD, 20))
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
            // applicationContext: a BroadcastReceiver's restricted
            // context cannot bind, and getUpdater binds (v19 fix).
            TileService.getUpdater(context.applicationContext)
                .requestUpdate(WaterTileService::class.java)
        }
    }
}

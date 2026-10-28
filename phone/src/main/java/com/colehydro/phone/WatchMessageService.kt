package com.colehydro.phone

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class WaterTapMessage(
    val id: String,
    val ml: Int,
    val timestampMillis: Long,
)

/**
 * Receives watch taps. No UI here — just parse, dedupe, write.
 * If Health Connect permission has not been granted yet, the tap is
 * dropped in v1 (and stays in the watch's pending queue until the
 * next flush, because the watch only marks synced on a successful
 * send — the ack path in README v2 closes this loop properly).
 * For v1 testing, grant Health Connect permission in the phone app
 * first, then tap on the watch.
 */
class WatchMessageService : WearableListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != "/hydro/add") return
        val tap = try {
            Json.decodeFromString<WaterTapMessage>(
                event.data.toString(Charsets.UTF_8)
            )
        } catch (_: Exception) {
            return
        }
        scope.launch {
            try {
                HealthConnectWriter.writeTapIfNew(
                    applicationContext, tap.id, tap.ml, tap.timestampMillis
                )
            } catch (_: Exception) {
                // Leave it: the watch queue will retry on next Tile open.
            }
        }
    }
}

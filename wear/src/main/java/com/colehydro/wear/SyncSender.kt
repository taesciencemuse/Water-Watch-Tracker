package com.colehydro.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Watch -> phone transport (Wear Data Layer, MessageClient).
 *
 * The watch never writes to Health Connect directly in v1. It sends each
 * tap to the phone companion, which is the only Health Connect writer.
 * Sends are best-effort: taps stay in WaterRepository's pending queue
 * until the phone acknowledges them (see phone WatchMessageService,
 * which should send an ack back on "/hydro/ack" — v1 marks synced
 * optimistically after a successful send, and re-sends the queue on
 * the next Tile open if the phone was unreachable).
 */
object SyncSender {
    const val PATH_ADD = "/hydro/add"

    suspend fun sendTap(context: Context, tap: WaterTap): Boolean {
        return try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            if (nodes.isEmpty()) return false
            val payload = Json.encodeToString(tap).toByteArray(Charsets.UTF_8)
            var sent = false
            for (node in nodes) {
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, PATH_ADD, payload).await()
                sent = true
            }
            sent
        } catch (_: Exception) {
            false
        }
    }

    /** Re-send everything still pending (called when the Tile is opened). */
    suspend fun flushPending(context: Context, repo: WaterRepository) {
        val pending = repo.pending()
        if (pending.isEmpty()) return
        val sentIds = mutableSetOf<String>()
        for (tap in pending) {
            if (sendTap(context, tap)) sentIds += tap.id
        }
        if (sentIds.isNotEmpty()) repo.markSynced(sentIds)
    }
}

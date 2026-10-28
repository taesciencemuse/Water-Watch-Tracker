package com.colehydro.wear

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * Quick-add screen. This is what the Tile button and the complication
 * tap open. If launched with EXTRA_AMOUNT_ML, it logs that amount
 * immediately — so from the Tile it is still one tap, just via this
 * tiny screen for confirmation.
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
            MaterialTheme {
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
        // Tile
        WaterTileService.requestTileUpdate(this)
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
            val (t, g, d) = repo.snapshot()
            todayMl = t; goalMl = g; defaultMl = d
            pending = repo.pending().size
        }
    }

    LaunchedEffect(Unit) {
        if (launchMl != null) {
            if (launchMl == -1) {
                repo.undoLastTap()
                justAdded = "Undid last drink"
            } else {
                val tap = WaterTap(
                    id = UUID.randomUUID().toString(),
                    ml = launchMl,
                    timestampMillis = System.currentTimeMillis()
                )
                repo.addTap(tap)
                scope.launch { SyncSender.sendTap(context, tap) }
                justAdded = "+${mlToOz(launchMl)} oz logged"
            }
            onLogged()
        }
        refresh()
    }

    fun log(ml: Int) {
        scope.launch {
            val tap = WaterTap(
                id = UUID.randomUUID().toString(),
                ml = ml,
                timestampMillis = System.currentTimeMillis()
            )
            repo.addTap(tap)
            SyncSender.sendTap(context, tap)
            justAdded = "+${mlToOz(ml)} oz logged"
            refresh()
            onLogged()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("${mlToOz(todayMl)} / ${mlToOz(goalMl)} oz")
        if (pending > 0) Text("$pending pending — phone away")
        justAdded?.let { Text(it) }
        Button(onClick = { log(defaultMl) }) {
            Text("+${mlToOz(defaultMl)} oz")
        }
        Row {
            Button(onClick = { log(ozToMl(12)) }) { Text("+12") }
            Button(onClick = { log(ozToMl(16)) }) { Text("+16") }
            Button(onClick = {
                scope.launch { repo.undoLastTap(); refresh(); onLogged() }
            }) { Text("Undo") }
        }
        Text("Default and goal are changed on the phone for v1.")
    }
}

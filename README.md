# Water — Tile + Complication for Pixel Watch 5

One-tap water logging, Tile-first.

## What you get

- **Watch Tile (primary):** swipe from the watch face. Big +8 oz button,
  +12 / +16 row, Undo, today's total vs goal, and synced / pending status.
- **Watch face complication:** today's total as a ring (RANGED_VALUE /
  GOAL_PROGRESS slots) or plain text ("32 oz", SHORT_TEXT slots).
  Tapping it opens the quick-add screen — see the correction below.
- **Phone companion (invisible job):** receives watch taps over the
  Wear Data Layer and writes them to Health Connect as
  `HydrationRecord` entries. No account, no cloud.
- **Offline-safe queue:** taps stay on the watch until the phone
  confirms them, each with a unique id so a retry can't double-count
  in Health Connect.

## Defaults (v1)

- Default tap: **8 oz (237 ml)**
- Daily goal: **64 oz (1,893 ml)**
- Both are constants in `wear/.../WaterRepository.kt` until the v2
  settings screen lands. Change them there, rebuild, done.

## Correction: complication tap does NOT open the Tile

Earlier in the design I said tapping the complication would open the
Tile. That's not how complications work: a complication tap is a
`PendingIntent`, and in v1 that PendingIntent opens MainActivity
(the quick-add screen). From the Tile, a tap is still one tap to log.
From the watch face, it's: tap complication → tap +8 oz on the
quick-add screen. Two taps, still no app drawer.

Stretch to prototype after v1 works: a direct-log complication tap
via a BroadcastReceiver PendingIntent. Don't build v1 on it — tap
handling varies by watch face.

## Also not in v1 (on purpose)

- **LoadAction direct Tile logging** (tap logs without opening any
  activity). Tile buttons in v1 use the documented `LaunchAction`
  path to the quick-add screen. Prototype LoadAction after v1 works
  on the Watch 5; its click dispatch varies by ProtoLayout version.
- **Undo after sync.** Undo only removes an unsynced tap from the
  watch total / queue. If a tap already reached Health Connect, v1
  does not delete that record.
- **Phone → watch ack.** The watch marks taps synced after a
  successful Data Layer send. A proper ack message
  (`/hydro/ack`, phone → watch) is the v2 hardening step, so a tap
  that reaches the phone but fails the Health Connect write isn't
  lost from the queue.

## Build it

1. Open this folder in **Android Studio** (latest stable).
2. Let Gradle sync. If a library version is flagged, use Android
   Studio's suggested matching set for Wear OS 7 / compileSdk 35 —
   this scaffold was written on my VM, which has no Android SDK,
   so it has **not been compiled here**. First compile in Android
   Studio is the real test, and ProtoLayout / Tiles / Complication
   APIs are the likeliest places to need small version fixes.
3. Install the `phone` module on your Pixel phone first, open it,
   and grant Health Connect READ + WRITE_HYDRATION.
4. Install the `wear` module on the Pixel Watch 5 (paired to that
   phone, via Android Studio's Wear pairing assistant).
5. On the watch: long-press the watch face → add the Water
   complication to a slot, then swipe to the Tile carousel → add
   the Water Tile.
6. Test: tap +8 oz on the Tile, then Refresh in the phone app.
   The Health Connect total should move by 8 oz.

## Test checklist

- [ ] Tile shows today's total, updates after a tap
- [ ] Complication shows the same total (ring or text, by slot)
- [ ] Phone away: Tile shows "pending", tap still counts locally
- [ ] Phone back in range: pending flushes, Health Connect total matches
- [ ] Double-send / retry does not double-count in Health Connect
- [ ] Midnight rollover resets the watch total, keeps pending taps
- [ ] Undo removes an unsynced tap, does not go negative

## Files

```
wear/
  MainActivity.kt            Quick-add screen (Tile + complication target)
  WaterTileService.kt        Tile layout + buttons
  HydrationComplicationService.kt  RANGED_VALUE / GOAL_PROGRESS / SHORT_TEXT
  WaterRepository.kt         DataStore: today total, goal, default, queue
  SyncSender.kt              Wear Data Layer MessageClient sender
phone/
  MainActivity.kt            Permission + "today's total" proof screen
  WatchMessageService.kt     WearableListenerService, /hydro/add
  HealthConnectWriter.kt     HydrationRecord writer + dedupe + aggregation
```

## Next decisions for you

1. Keep 8 oz / 64 oz, or change the defaults?
2. After v1 works, prototype first: LoadAction Tile logging, or
   direct-log complication tap?
3. Settings on the phone (v2) — or is changing constants in code
   fine for a personal app?

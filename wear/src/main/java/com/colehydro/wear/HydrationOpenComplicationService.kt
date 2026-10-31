package com.colehydro.wear

/**
 * "Water Logged - Open app" in the complication picker (Oct 30,
 * Cole: two picker entries instead of a phone Settings toggle).
 *
 * Identical data to HydrationComplicationService (today / goal),
 * but a tap opens the watch app instead of logging. The user
 * chooses the behaviour by which complication they place in a
 * slot; there is no mode setting to sync or go stale.
 */
class HydrationOpenComplicationService : HydrationComplicationService() {
    override val tapMode: String = "open"
}

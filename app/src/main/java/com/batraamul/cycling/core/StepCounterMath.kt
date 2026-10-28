package com.batraamul.cycling.core

/**
 * The hardware TYPE_STEP_COUNTER sensor does not report "new steps". It reports the
 * TOTAL steps since the phone last booted (e.g. 18 342). The app must therefore
 * remember the previous reading (the "baseline") and add only the difference.
 *
 * This object contains that bookkeeping as pure functions so it can be unit-tested.
 *
 * Duplicate-prevention rule: the baseline ([CounterBaseline.lastRaw]) is saved in the
 * SAME database transaction as the step total it produced. After a crash the app
 * reloads both together, so a reading is never added twice and never lost.
 */
data class CounterBaseline(
    /** Last raw counter value already accounted for, or null = "no valid baseline". */
    val lastRaw: Long?,
    /** Settings.Global.BOOT_COUNT when [lastRaw] was read; detects reboots. */
    val bootCount: Int?,
)

data class CounterUpdate(val newSteps: Long, val baseline: CounterBaseline, val note: Note) {
    enum class Note { BASELINE_SET, NORMAL, REBOOT_DETECTED, ANOMALY_REBASELINED }
}

object StepCounterMath {
    /** A single jump larger than this is treated as a sensor glitch, not real walking. */
    const val MAX_PLAUSIBLE_DELTA = 40_000L

    fun update(previous: CounterBaseline, raw: Long, bootCount: Int?): CounterUpdate {
        val last = previous.lastRaw
        val next = CounterBaseline(lastRaw = raw, bootCount = bootCount)
        if (last == null) {
            // First reading after walking (re)starts: e.g. right after a cycling ride ends.
            // Steps taken during the ride are deliberately NOT added.
            return CounterUpdate(0, next, CounterUpdate.Note.BASELINE_SET)
        }
        val rebooted = (bootCount != null && previous.bootCount != null && bootCount != previous.bootCount) || raw < last
        val delta = if (rebooted) raw else raw - last
        if (delta < 0 || delta > MAX_PLAUSIBLE_DELTA) {
            return CounterUpdate(0, next, CounterUpdate.Note.ANOMALY_REBASELINED)
        }
        return CounterUpdate(delta, next, if (rebooted) CounterUpdate.Note.REBOOT_DETECTED else CounterUpdate.Note.NORMAL)
    }
}

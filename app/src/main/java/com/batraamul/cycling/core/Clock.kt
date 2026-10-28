package com.batraamul.cycling.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Time source. Production code uses the real clock; tests substitute a fake one so that
 * "midnight", "a week later" etc. can be simulated instantly and deterministically.
 */
interface Clock {
    fun nowMs(): Long
    fun zone(): ZoneId = ZoneId.systemDefault()
    fun today(): LocalDate = dayOf(nowMs())
    fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate()
}

object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

/** Day key stored in the database, e.g. "2026-10-28" (ISO strings sort chronologically). */
fun LocalDate.key(): String = toString()

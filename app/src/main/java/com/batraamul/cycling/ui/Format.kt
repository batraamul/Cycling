package com.batraamul.cycling.ui

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Display formatting in one place, so every screen shows numbers the same way. */
object Fmt {
    private fun int() = NumberFormat.getIntegerInstance(Locale.getDefault())
    fun steps(n: Long): String = int().format(n)
    fun km(m: Double, decimals: Int = 2): String = String.format(Locale.getDefault(), "%.${decimals}f", m / 1000.0)
    fun km3(m: Double): String = String.format(Locale.US, "%.3f", m / 1000.0)
    fun kcal(k: Double): String = int().format(k.toLong())
    fun speedKmh(mps: Double?): String = if (mps == null || mps.isNaN()) "–" else String.format(Locale.getDefault(), "%.1f", mps * 3.6)
    /** Running pace from speed, as minutes:seconds per km ("5:32"). */
    fun pace(mps: Double?): String {
        if (mps == null || mps.isNaN() || mps < 0.3) return "–"
        val secPerKm = (1000.0 / mps).toLong()
        if (secPerKm >= 60 * 60) return "–"
        return String.format(Locale.US, "%d:%02d", secPerKm / 60, secPerKm % 60)
    }
    fun bytes(n: Long): String = when {
        n >= 1_000_000 -> String.format(Locale.US, "%.1f MB", n / 1e6)
        n >= 1_000 -> String.format(Locale.US, "%.0f kB", n / 1e3)
        else -> "$n bytes"
    }
    fun factor(k: Double): String = String.format(Locale.US, "%.4f", k)
    fun pct(k: Double): String = String.format(Locale.US, "%+.1f %%", (k - 1) * 100)
    fun stepCm(m: Double): String = String.format(Locale.getDefault(), "%.1f cm", m * 100)

    fun duration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%02d:%02d", m, sec)
    }

    fun durationShort(ms: Long): String {
        val min = ms / 60_000
        return if (min >= 60) "${min / 60} h ${min % 60} min" else "$min min"
    }

    private val dateTime = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
    private val date = DateTimeFormatter.ofPattern("d MMM yyyy")
    fun dateTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dateTime)
    fun date(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(date)
}

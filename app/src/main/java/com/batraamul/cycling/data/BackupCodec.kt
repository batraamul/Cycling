package com.batraamul.cycling.data

import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import java.io.Reader
import java.io.Writer

/**
 * The backup file format: one human-readable JSON document.
 *
 * ```
 * {
 *   "format": "cycling-app-backup", "formatVersion": 1,
 *   "createdAtMs": …, "appVersion": "1.2.0", "databaseVersion": 3, "device": "…",
 *   "settings": { heightCm, weightKg, step lengths, GPS factor, theme … },
 *   "stepCalibrations": [ … ], "gpsCalibrations": [ … ],
 *   "dailyActivity": [ { "day": "2026-10-28", "steps": 8421, "walkDistanceM": 5936.8 }, … ],
 *   "sessions": [ { "activityType": "CYCLING", "startTimeMs": …, …,
 *                   "route": [ [timeMs, lat, lon, accuracyM, speedMps|null, segment], … ] }, … ]
 * }
 * ```
 *
 * Why streaming (JsonWriter/JsonReader) instead of building one big string: years of GPS routes can
 * reach tens of megabytes. Streaming writes and reads one record at a time, so memory use stays small
 * no matter how large the history grows.
 *
 * Compatibility rules: unknown keys are skipped and missing keys take defaults, so a file written by a
 * newer app version can still be read where possible. A file whose formatVersion is newer than this
 * app understands is refused rather than half-imported.
 */
object BackupCodec {
    const val FORMAT = "cycling-app-backup"
    const val FORMAT_VERSION = 1
    const val MIME = "application/json"

    data class Header(val createdAtMs: Long, val appVersion: String, val databaseVersion: Int, val device: String)

    /** Receives records as they are read, so the caller decides whether to count them or import them. */
    interface Sink {
        suspend fun header(h: Header) {}
        suspend fun settings(s: SettingsEntity) {}
        suspend fun day(d: DailyActivity) {}
        suspend fun session(r: Ride, route: List<RoutePoint>) {}
        suspend fun stepCalibration(c: StepCalibrationRecord) {}
        suspend fun gpsCalibration(c: GpsCalibrationRecord) {}
    }

    class FormatException(message: String) : Exception(message)

    // ======================================================================= writing

    suspend fun write(
        out: Writer,
        header: Header,
        settings: SettingsEntity,
        days: List<DailyActivity>,
        sessions: List<Ride>,
        routeOf: suspend (rideId: Long) -> List<RoutePoint>,
        stepCalibrations: List<StepCalibrationRecord>,
        gpsCalibrations: List<GpsCalibrationRecord>,
    ) {
        val w = JsonWriter(out)
        w.setIndent(" ")
        w.beginObject()
        w.name("format").value(FORMAT)
        w.name("formatVersion").value(FORMAT_VERSION.toLong())
        w.name("createdAtMs").value(header.createdAtMs)
        w.name("appVersion").value(header.appVersion)
        w.name("databaseVersion").value(header.databaseVersion.toLong())
        w.name("device").value(header.device)
        w.name("routeColumns").value("timeMs, lat, lon, accuracyM, speedMps, segment")

        w.name("settings").beginObject()
        with(settings) {
            w.name("heightCm").value(heightCm.toLong())
            w.name("weightKg").value(weightKg)
            w.name("themeId").value(themeId.toLong())
            w.name("dailyStepGoal").value(dailyStepGoal.toLong())
            w.name("walkStepM").value(walkStepM)
            w.name("walkStepSource").value(walkStepSource)
            w.name("walkStepCalibratedAtMs").nullable(walkStepCalibratedAtMs)
            w.name("walkStepConfidence").value(walkStepConfidence)
            w.name("runStepM").nullable(runStepM)
            w.name("runStepCalibratedAtMs").nullable(runStepCalibratedAtMs)
            w.name("gpsFactor").value(gpsFactor)
            w.name("gpsCalibratedAtMs").nullable(gpsCalibratedAtMs)
            w.name("stepSource").value(stepSource)
        }
        w.endObject()

        w.name("stepCalibrations").beginArray()
        stepCalibrations.forEach { c ->
            w.beginObject()
            w.name("timeMs").value(c.timeMs); w.name("kind").value(c.kind); w.name("method").value(c.method)
            w.name("inputs").value(c.inputs); w.name("resultM").value(c.resultM); w.name("confidence").value(c.confidence)
            w.endObject()
        }
        w.endArray()

        w.name("gpsCalibrations").beginArray()
        gpsCalibrations.forEach { c ->
            w.beginObject()
            w.name("timeMs").value(c.timeMs); w.name("rawKm").value(c.rawKm); w.name("referenceKm").value(c.referenceKm)
            w.name("factor").value(c.factor); w.name("meanAccuracyM").nullable(c.meanAccuracyM); w.name("fixCount").value(c.fixCount.toLong())
            w.name("quality").value(c.quality); w.name("reasons").value(c.reasons); w.name("deviceModel").value(c.deviceModel)
            w.name("appVersion").value(c.appVersion); w.name("confirmed").value(c.confirmed); w.name("applied").value(c.applied)
            w.endObject()
        }
        w.endArray()

        w.name("dailyActivity").beginArray()
        days.forEach { d ->
            w.beginObject()
            w.name("day").value(d.day); w.name("steps").value(d.steps); w.name("walkDistanceM").value(d.walkDistanceM)
            w.name("updatedAtMs").value(d.updatedAtMs)
            w.endObject()
        }
        w.endArray()

        w.name("sessions").beginArray()
        for (r in sessions) {
            w.beginObject()
            w.name("activityType").value(r.activityType); w.name("day").value(r.day)
            w.name("startTimeMs").value(r.startTimeMs); w.name("endTimeMs").nullable(r.endTimeMs)
            w.name("isCalibration").value(r.isCalibration); w.name("rawDistanceM").value(r.rawDistanceM); w.name("factor").value(r.factor)
            w.name("elapsedMs").value(r.elapsedMs); w.name("movingMs").value(r.movingMs); w.name("topSpeedMps").value(r.topSpeedMps)
            w.name("meanAccuracyM").nullable(r.meanAccuracyM); w.name("fixCount").value(r.fixCount.toLong())
            w.name("lastFixMs").nullable(r.lastFixMs); w.name("segment").value(r.segment.toLong())
            w.name("kcal").value(r.kcal); w.name("steps").value(r.steps)
            w.name("route").beginArray()
            for (p in routeOf(r.id)) {
                w.beginArray()
                w.value(p.timeMs).value(p.lat).value(p.lon).value(p.accuracyM.toDouble())
                if (p.speedMps == null) w.nullValue() else w.value(p.speedMps.toDouble())
                w.value(p.segment.toLong())
                w.endArray()
            }
            w.endArray()
            w.endObject()
        }
        w.endArray()

        w.endObject()
        w.flush()
    }

    private fun JsonWriter.nullable(v: Long?): JsonWriter = if (v == null) nullValue() else value(v)
    private fun JsonWriter.nullable(v: Double?): JsonWriter = if (v == null || v.isNaN()) nullValue() else value(v)

    // ======================================================================= reading

    /** Reads a backup, passing each record to [sink]. Throws [FormatException] if this is not a valid backup. */
    suspend fun read(input: Reader, sink: Sink) {
        val r = JsonReader(input)
        try {
            if (r.peek() != JsonToken.BEGIN_OBJECT) throw FormatException("This file is not a Cycling backup (not a JSON object).")
            r.beginObject()
            var format: String? = null
            var version = -1
            var createdAt = 0L; var appVersion = "?"; var dbVersion = 0; var device = "?"
            var headerSent = false
            suspend fun ensureHeader() {
                if (headerSent) return
                if (format != FORMAT) throw FormatException("This file is not a Cycling backup.")
                if (version > FORMAT_VERSION) throw FormatException("This backup was made by a newer version of the app (format $version). Update the app first.")
                if (version < 1) throw FormatException("This backup file is incomplete (no format version).")
                sink.header(Header(createdAt, appVersion, dbVersion, device)); headerSent = true
            }
            while (r.hasNext()) {
                when (r.nextName()) {
                    "format" -> format = r.nextString()
                    "formatVersion" -> version = r.nextInt()
                    "createdAtMs" -> createdAt = r.nextLong()
                    "appVersion" -> appVersion = r.nextString()
                    "databaseVersion" -> dbVersion = r.nextInt()
                    "device" -> device = r.nextString()
                    "settings" -> { ensureHeader(); sink.settings(settingsFrom(r.readFlat())) }
                    "stepCalibrations" -> { ensureHeader(); r.beginArray(); while (r.hasNext()) sink.stepCalibration(stepCalFrom(r.readFlat())); r.endArray() }
                    "gpsCalibrations" -> { ensureHeader(); r.beginArray(); while (r.hasNext()) sink.gpsCalibration(gpsCalFrom(r.readFlat())); r.endArray() }
                    "dailyActivity" -> { ensureHeader(); r.beginArray(); while (r.hasNext()) sink.day(dayFrom(r.readFlat())); r.endArray() }
                    "sessions" -> { ensureHeader(); r.beginArray(); while (r.hasNext()) readSession(r, sink); r.endArray() }
                    else -> r.skipValue()
                }
            }
            r.endObject()
            ensureHeader()
        } catch (e: FormatException) {
            throw e
        } catch (e: Exception) {
            // Malformed JSON, wrong types, truncated download …
            throw FormatException("The backup file could not be read (${e.javaClass.simpleName}: ${e.message}). It may be damaged or incomplete.")
        }
    }

    private suspend fun readSession(r: JsonReader, sink: Sink) {
        val m = HashMap<String, Any?>()
        val route = ArrayList<RoutePoint>()
        r.beginObject()
        while (r.hasNext()) {
            val name = r.nextName()
            if (name == "route") {
                r.beginArray()
                while (r.hasNext()) {
                    r.beginArray()
                    val t = r.nextLong(); val lat = r.nextDouble(); val lon = r.nextDouble(); val acc = r.nextDouble()
                    val spd = if (r.peek() == JsonToken.NULL) { r.nextNull(); null } else r.nextDouble()
                    val seg = r.nextInt()
                    while (r.hasNext()) r.skipValue()
                    r.endArray()
                    route += RoutePoint(rideId = 0, timeMs = t, lat = lat, lon = lon, accuracyM = acc.toFloat(), speedMps = spd?.toFloat(), segment = seg)
                }
                r.endArray()
            } else m[name] = r.readScalar()
        }
        r.endObject()
        val ride = Ride(
            day = m.str("day") ?: throw FormatException("A session in the backup has no date."),
            startTimeMs = m.long("startTimeMs") ?: throw FormatException("A session in the backup has no start time."),
            endTimeMs = m.long("endTimeMs"), status = RideStatus.COMPLETED, isCalibration = m.bool("isCalibration") ?: false,
            rawDistanceM = m.dbl("rawDistanceM") ?: 0.0, factor = m.dbl("factor") ?: 1.0, elapsedMs = m.long("elapsedMs") ?: 0,
            activeSinceMs = null, movingMs = m.long("movingMs") ?: 0, topSpeedMps = m.dbl("topSpeedMps") ?: 0.0,
            meanAccuracyM = m.dbl("meanAccuracyM"), fixCount = m.long("fixCount")?.toInt() ?: 0, lastFixMs = m.long("lastFixMs"),
            segment = m.long("segment")?.toInt() ?: 0, kcal = m.dbl("kcal") ?: 0.0,
            activityType = m.str("activityType") ?: "CYCLING", steps = m.long("steps") ?: 0,
        )
        sink.session(ride, route)
    }

    private fun settingsFrom(m: Map<String, Any?>): SettingsEntity {
        val d = SettingsEntity()
        return d.copy(
            heightCm = m.long("heightCm")?.toInt() ?: d.heightCm, weightKg = m.dbl("weightKg") ?: d.weightKg,
            themeId = m.long("themeId")?.toInt() ?: d.themeId, dailyStepGoal = m.long("dailyStepGoal")?.toInt() ?: d.dailyStepGoal,
            walkStepM = m.dbl("walkStepM") ?: d.walkStepM, walkStepSource = m.str("walkStepSource") ?: d.walkStepSource,
            walkStepCalibratedAtMs = m.long("walkStepCalibratedAtMs"), walkStepConfidence = m.str("walkStepConfidence"),
            runStepM = m.dbl("runStepM"), runStepCalibratedAtMs = m.long("runStepCalibratedAtMs"),
            gpsFactor = m.dbl("gpsFactor") ?: 1.0, gpsCalibratedAtMs = m.long("gpsCalibratedAtMs"),
            stepSource = m.str("stepSource") ?: d.stepSource,
        )
    }

    private fun dayFrom(m: Map<String, Any?>) = DailyActivity(
        day = m.str("day") ?: throw FormatException("A day in the backup has no date."),
        steps = m.long("steps") ?: 0, walkDistanceM = m.dbl("walkDistanceM") ?: 0.0, updatedAtMs = m.long("updatedAtMs") ?: 0,
    )

    private fun stepCalFrom(m: Map<String, Any?>) = StepCalibrationRecord(
        timeMs = m.long("timeMs") ?: 0, kind = m.str("kind") ?: "WALK", method = m.str("method") ?: "MANUAL",
        inputs = m.str("inputs") ?: "", resultM = m.dbl("resultM") ?: 0.0, confidence = m.str("confidence") ?: "LOW",
    )

    private fun gpsCalFrom(m: Map<String, Any?>) = GpsCalibrationRecord(
        timeMs = m.long("timeMs") ?: 0, rideId = null, rawKm = m.dbl("rawKm") ?: 0.0, referenceKm = m.dbl("referenceKm") ?: 1.0,
        factor = m.dbl("factor") ?: 1.0, meanAccuracyM = m.dbl("meanAccuracyM"), fixCount = m.long("fixCount")?.toInt() ?: 0,
        quality = m.str("quality") ?: "REJECTED", reasons = m.str("reasons") ?: "", deviceModel = m.str("deviceModel") ?: "",
        appVersion = m.str("appVersion") ?: "", confirmed = m.bool("confirmed") ?: false, applied = m.bool("applied") ?: false,
    )

    /** Reads a flat JSON object (no nested objects needed) into a map. Nested values are skipped. */
    private fun JsonReader.readFlat(): Map<String, Any?> {
        val m = HashMap<String, Any?>()
        beginObject()
        while (hasNext()) { val n = nextName(); m[n] = readScalar() }
        endObject()
        return m
    }

    private fun JsonReader.readScalar(): Any? = when (peek()) {
        JsonToken.NULL -> { nextNull(); null }
        JsonToken.BOOLEAN -> nextBoolean()
        JsonToken.NUMBER -> nextString()      // kept as text; converted to Long/Double on use (no precision loss)
        JsonToken.STRING -> nextString()
        else -> { skipValue(); null }
    }

    private fun Map<String, Any?>.str(k: String) = this[k] as? String
    private fun Map<String, Any?>.long(k: String) = (this[k] as? String)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }
    private fun Map<String, Any?>.dbl(k: String) = (this[k] as? String)?.toDoubleOrNull()
    private fun Map<String, Any?>.bool(k: String) = this[k] as? Boolean
}

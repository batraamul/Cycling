package com.batraamul.cycling.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DistanceAccumulatorTest {
    // 1 degree of latitude ≈ 111.2 km; we move north in small steps near New Delhi.
    private val lat0 = 28.6139
    private val lon0 = 77.2090
    private val mPerDegLat = 111_194.9

    @Test
    fun haversineMatchesKnownValue() {
        // 0.01° of latitude ≈ 1.112 km
        assertEquals(1111.95, Geo.distanceM(lat0, lon0, lat0 + 0.01, lon0), 0.5)
    }

    @Test
    fun straightRideOfOneKilometreIsMeasuredAccurately() {
        val acc = DistanceAccumulator()
        // 5 m/s for 200 s = 1000 m, fixes every second, 4 m accuracy, ±2 m noise.
        val rnd = Random(3)
        for (i in 0..200) {
            val north = i * 5.0 + (rnd.nextDouble() * 4 - 2)
            acc.add(GpsFix(i * 1000L, lat0 + north / mPerDegLat, lon0, 4f, 5f))
        }
        assertEquals(1000.0, acc.rawDistanceM, 15.0)   // within 1.5 %
        assertTrue(acc.movingMs in 190_000..201_000)
    }

    @Test
    fun parkedBicycleAccumulatesNoDistance() {
        val acc = DistanceAccumulator()
        val rnd = Random(9)
        for (i in 0..600) { // 10 minutes of wobbling ±4 m around one point
            val dn = (rnd.nextDouble() * 8 - 4) / mPerDegLat
            val de = (rnd.nextDouble() * 8 - 4) / mPerDegLat
            acc.add(GpsFix(i * 1000L, lat0 + dn, lon0 + de, 6f, 0.2f))
        }
        assertTrue("drift ${acc.rawDistanceM}", acc.rawDistanceM < 10.0)
        assertEquals(0L, acc.movingMs)
    }

    @Test
    fun poorAccuracyAndJumpsAreRejected() {
        val acc = DistanceAccumulator()
        acc.add(GpsFix(0, lat0, lon0, 5f))
        assertEquals(DistanceAccumulator.Result.REJECTED_ACCURACY, acc.add(GpsFix(1000, lat0 + 0.001, lon0, 80f)))
        // 500 m in 1 s = impossible on a bicycle
        assertEquals(DistanceAccumulator.Result.REJECTED_SPEED, acc.add(GpsFix(2000, lat0 + 500 / mPerDegLat, lon0, 5f)))
        assertEquals(0.0, acc.rawDistanceM, 0.0)
    }

    @Test
    fun segmentBreakDoesNotBridgeTheGap() {
        val acc = DistanceAccumulator()
        acc.add(GpsFix(0, lat0, lon0, 4f))
        acc.add(GpsFix(1000, lat0 + 10 / mPerDegLat, lon0, 4f))
        acc.breakSegment()                              // e.g. ride paused, moved 2 km by car
        acc.add(GpsFix(600_000, lat0 + 2000 / mPerDegLat, lon0, 4f))
        acc.add(GpsFix(601_000, lat0 + 2010 / mPerDegLat, lon0, 4f))
        assertEquals(20.0, acc.rawDistanceM, 0.5)
    }

    @Test
    fun restoreContinuesFromSavedTotals() {
        val acc = DistanceAccumulator()
        acc.restore(rawDistanceM = 4200.0, movingMs = 900_000, topSpeedMps = 9.0)
        acc.add(GpsFix(0, lat0, lon0, 4f))
        acc.add(GpsFix(2000, lat0 + 10 / mPerDegLat, lon0, 4f, 5f))
        assertEquals(4210.0, acc.rawDistanceM, 0.5)
    }
}

package br.com.gpsciclismo

import br.com.gpsciclismo.data.CloudCodec
import br.com.gpsciclismo.data.Profile
import br.com.gpsciclismo.data.RideEntity
import br.com.gpsciclismo.data.RidePointEntity
import br.com.gpsciclismo.data.WeightEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudCodecTest {
    /** Simula o caminho de ida e volta pela API: vira texto JSON e é lido de novo. */
    private fun roundTrip(fields: Map<String, JsonObject>): JsonObject {
        val text = CloudCodec.document(fields).toString()
        return CloudCodec.fieldsOf(Json.parseToJsonElement(text).jsonObject)
    }

    @Test fun rideSurvivesRoundTrip() {
        val ride = RideEntity(
            id = 42, uuid = "abc-123", startedAt = 1_763_000_000_000, endedAt = 1_763_002_400_000,
            workoutName = "Sweet spot 3x8", distanceM = 18_234.5, movingSec = 2300, elapsedSec = 2400,
            avgSpeedKmh = 27.4, maxSpeedKmh = 41.2, avgPower = 182, maxPower = 410, avgHr = 141, maxHr = 172,
            avgCadence = 88, kcal = 430, adherencePct = 87, planJson = """{"name":"x"}""", ftpUsed = 210,
            testAvgPower = null, testSec = null,
        )
        val back = CloudCodec.rideFrom(roundTrip(CloudCodec.rideFields(ride, 2400, 1)), syncedAt = 99)!!
        assertEquals(ride.copy(id = 0, syncedAt = 99), back)
    }

    @Test fun pointsSurviveRoundTripWithNulls() {
        val pts = listOf(
            RidePointEntity(rideId = 7, time = 1000, lat = -23.5505, lon = -46.6333, altitude = 760.5,
                speedKmh = 30.25, power = 250, heartRate = 150, cadence = 90, block = 2),
            RidePointEntity(rideId = 7, time = 2000, lat = null, lon = null, altitude = null,
                speedKmh = null, power = null, heartRate = null, cadence = null, block = null),
        )
        val back = CloudCodec.decodePoints(CloudCodec.encodePoints(pts), rideId = 5)
        assertEquals(pts.map { it.copy(rideId = 5) }, back)
    }

    @Test fun profileRoundTripAndMissingDate() {
        val p = Profile(name = "André", weightKg = 72.5, heightCm = 178, ftp = 210)
        assertEquals(p to 123L, CloudCodec.profileFrom(roundTrip(CloudCodec.profileFields(p, 123L))))
        assertNull(CloudCodec.profileFrom(JsonObject(emptyMap())))
    }

    @Test fun weightSurvivesRoundTrip() {
        val w = WeightEntity(id = 3, uuid = "w-1", measuredAt = 1_795_700_000_000, weightKg = 81.7, ftp = 210, photoPath = "/x.jpg")
        val photo = byteArrayOf(1, 2, 3, -1)
        val f = roundTrip(CloudCodec.weightFields(w, photo))
        assertEquals(w.copy(id = 0, photoPath = null, syncedAt = 5), CloudCodec.weightFrom(f, syncedAt = 5))
        with(CloudCodec) {
            assertEquals(true, f.getBool("hasPhoto"))
            assertArrayEquals(photo, f.getBytes("photo"))
        }
        val noPhoto = roundTrip(CloudCodec.weightFields(w, null))
        with(CloudCodec) { assertEquals(false, noPhoto.getBool("hasPhoto")) }
        assertNull(CloudCodec.weightFrom(JsonObject(emptyMap()), 0))
    }
}

package br.edu.uff.rescuenet.data.location

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationPublishingTest {

    @Test
    fun payloadIncluiNodeIdComoNumero() {
        // Dados sintéticos; o ID passa de 2^53 e não pode ser arredondado.
        val payload = LocationPayload.encode(
            teamId = 1,
            nodeId = 9_007_199_254_740_993L,
            latitude = -22.5,
            longitude = -41.9,
            accuracy = 4.5f,
            battery = 80,
            timestampSeconds = 1_793_816_106L
        )

        assertTrue(payload.contains("\"team_id\": 1,"))
        assertTrue(payload.contains("\"node_id\": 9007199254740993,"))
        assertTrue(payload.contains("\"latitude\": -22.5,"))
        assertTrue(payload.contains("\"longitude\": -41.9,"))
        assertTrue(payload.contains("\"accuracy\": 4.5,"))
        assertTrue(payload.contains("\"battery\": 80,"))
        assertTrue(payload.contains("\"timestamp\": 1793816106"))
    }

    @Test
    fun throttleLiberaPrimeiroEnvioEBloqueiaDentroDoIntervalo() {
        val throttle = AutoPublishThrottle(intervalMillis = 10_000L)

        assertTrue(throttle.tryAcquire(1_000L))
        assertFalse(throttle.tryAcquire(6_000L))
        assertFalse(throttle.tryAcquire(10_999L))
        assertTrue(throttle.tryAcquire(11_000L))
        assertFalse(throttle.tryAcquire(16_000L))
    }

    @Test
    fun resetLiberaEnvioImediato() {
        val throttle = AutoPublishThrottle(intervalMillis = 10_000L)

        assertTrue(throttle.tryAcquire(1_000L))
        throttle.reset()
        assertTrue(throttle.tryAcquire(2_000L))
    }
}

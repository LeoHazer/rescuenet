package br.edu.uff.rescuenet.data.election

import org.junit.Assert.*
import org.junit.Test

class AdaptiveBullyPolicyTest {

    private fun candidate(
        nodeId: Long,
        score: Double = 80.0,
        battery: Double = 80.0,
        mqttConnected: Boolean = true,
        available: Boolean = true
    ): ElectionCandidate {
        val metrics = ElectionMetrics(
            connectionStability = score,
            battery = battery,
            availability = score,
            connectivity = score,
            computationalResources = score,
            dataEfficiency = score
        )

        return ElectionCandidate(
            nodeId = nodeId,
            metrics = metrics,
            mqttConnected = mqttConnected,
            available = available
        )
    }

    @Test
    fun calculateScore_appliesConfiguredWeights() {
        val metrics = ElectionMetrics(
            connectionStability = 100.0,
            battery = 80.0,
            availability = 60.0,
            connectivity = 40.0,
            computationalResources = 20.0,
            dataEfficiency = 0.0
        )

        assertEquals(
            69.0,
            AdaptiveBullyPolicy.calculateScore(metrics),
            0.001
        )
    }

    @Test
    fun candidateWithBatteryBelowMinimum_isNotEligible() {
        val node = candidate(nodeId = 1L, battery = 14.0)

        assertFalse(node.isEligible)
    }

    @Test
    fun candidateWithMinimumBattery_isEligible() {
        val node = candidate(nodeId = 1L, battery = 15.0)

        assertTrue(node.isEligible)
    }

    @Test
    fun disconnectedCandidate_isNotEligible() {
        val node = candidate(nodeId = 1L, mqttConnected = false)

        assertFalse(node.isEligible)
    }

    @Test
    fun unavailableCandidate_isNotEligible() {
        val node = candidate(nodeId = 1L, available = false)

        assertFalse(node.isEligible)
    }

    @Test
    fun initialElection_selectsHighestScore() {
        val lower = candidate(nodeId = 10L, score = 70.0)
        val higher = candidate(nodeId = 20L, score = 90.0)

        val winner = AdaptiveBullyPolicy.selectInitialCoordinator(
            listOf(lower, higher)
        )

        assertEquals(20L, winner?.nodeId)
    }

    @Test
    fun initialElection_usesHighestNodeIdAsTieBreaker() {
        val first = candidate(nodeId = 10L, score = 80.0)
        val second = candidate(nodeId = 20L, score = 80.0)

        val winner = AdaptiveBullyPolicy.selectInitialCoordinator(
            listOf(first, second)
        )

        assertEquals(20L, winner?.nodeId)
    }

    @Test
    fun initialElection_returnsNullWhenNoCandidateIsEligible() {
        val node = candidate(nodeId = 10L, mqttConnected = false)

        assertNull(
            AdaptiveBullyPolicy.selectInitialCoordinator(listOf(node))
        )
    }

    @Test
    fun hysteresis_rejectsAdvantageBelowMargin() {
        val current = candidate(nodeId = 1L, score = 80.0)
        val challenger = candidate(nodeId = 2L, score = 89.0, battery = 89.0)

        assertFalse(
            AdaptiveBullyPolicy.shouldReplaceCoordinator(
                current,
                challenger,
                consecutiveAdvantageCount = 2
            )
        )
    }

    @Test
    fun hysteresis_requiresPersistentAdvantage() {
        val current = candidate(nodeId = 1L, score = 80.0)
        val challenger = candidate(nodeId = 2L, score = 90.0, battery = 90.0)

        assertFalse(
            AdaptiveBullyPolicy.shouldReplaceCoordinator(
                current,
                challenger,
                consecutiveAdvantageCount = 1
            )
        )
    }

    @Test
    fun hysteresis_allowsReplacementAfterPersistentAdvantage() {
        val current = candidate(nodeId = 1L, score = 80.0)
        val challenger = candidate(nodeId = 2L, score = 90.0, battery = 90.0)

        assertTrue(
            AdaptiveBullyPolicy.shouldReplaceCoordinator(
                current,
                challenger,
                consecutiveAdvantageCount = 2
            )
        )
    }

    @Test
    fun ineligibleCoordinator_canBeReplacedImmediately() {
        val current = candidate(
            nodeId = 1L,
            battery = 10.0
        )
        val challenger = candidate(nodeId = 2L, score = 70.0)

        assertTrue(
            AdaptiveBullyPolicy.shouldReplaceCoordinator(
                current,
                challenger,
                consecutiveAdvantageCount = 0
            )
        )
    }
}
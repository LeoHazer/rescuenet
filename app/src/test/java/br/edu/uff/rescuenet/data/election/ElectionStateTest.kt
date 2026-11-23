package br.edu.uff.rescuenet.data.election

import org.junit.Assert.*
import org.junit.Test

class ElectionStateTest {

    private fun candidate(
        nodeId: Long,
        score: Double = 80.0,
        battery: Double = score,
        mqttConnected: Boolean = true
    ): ElectionCandidate {
        return ElectionCandidate(
            nodeId = nodeId,
            metrics = ElectionMetrics(
                connectionStability = score,
                battery = battery,
                availability = score,
                connectivity = score,
                computationalResources = score,
                dataEfficiency = score
            ),
            mqttConnected = mqttConnected,
            available = true
        )
    }

    @Test
    fun firstEpoch_startsAtOne() {
        val tracker = ElectionEpochTracker(localNodeId = 10L)

        val epoch = tracker.startNewEpoch()

        assertEquals(1L, epoch.counter)
        assertEquals(10L, epoch.initiatorNodeId)
    }

    @Test
    fun newEpoch_incrementsLocalCounter() {
        val tracker = ElectionEpochTracker(
            localNodeId = 10L,
            initialCounter = 4L
        )

        assertEquals(5L, tracker.startNewEpoch().counter)
        assertEquals(6L, tracker.startNewEpoch().counter)
    }

    @Test
    fun observingHigherEpoch_updatesCurrentEpoch() {
        val tracker = ElectionEpochTracker(localNodeId = 10L)
        val received = ElectionEpoch(5L, 20L)

        assertTrue(tracker.observe(received))
        assertEquals(received, tracker.currentEpoch)
        assertEquals(5L, tracker.highestObservedCounter)
    }

    @Test
    fun nextLocalEpoch_exceedsHighestObservedCounter() {
        val tracker = ElectionEpochTracker(localNodeId = 10L)
        tracker.observe(ElectionEpoch(8L, 20L))

        val next = tracker.startNewEpoch()

        assertEquals(9L, next.counter)
        assertEquals(10L, next.initiatorNodeId)
    }

    @Test
    fun staleEpoch_isRejected() {
        val tracker = ElectionEpochTracker(localNodeId = 10L)
        tracker.observe(ElectionEpoch(5L, 20L))

        assertFalse(tracker.observe(ElectionEpoch(4L, 30L)))
        assertEquals(5L, tracker.currentEpoch?.counter)
    }

    @Test
    fun simultaneousEpochs_useInitiatorNodeIdAsTieBreaker() {
        val tracker = ElectionEpochTracker(localNodeId = 10L)
        tracker.observe(ElectionEpoch(5L, 20L))

        val higher = ElectionEpoch(5L, 30L)

        assertTrue(tracker.observe(higher))
        assertEquals(higher, tracker.currentEpoch)
    }

    @Test
    fun hysteresis_requiresTwoConsecutiveAdvantages() {
        val tracker = HysteresisTracker()
        val current = candidate(1L, score = 80.0)
        val challenger = candidate(2L, score = 90.0)

        assertFalse(tracker.observe(current, challenger))
        assertTrue(tracker.observe(current, challenger))
    }

    @Test
    fun hysteresis_resetsWhenAdvantageDisappears() {
        val tracker = HysteresisTracker()
        val current = candidate(1L, score = 80.0)
        val challenger = candidate(2L, score = 90.0)
        val insufficient = candidate(2L, score = 85.0)

        assertFalse(tracker.observe(current, challenger))
        assertFalse(tracker.observe(current, insufficient))
        assertFalse(tracker.observe(current, challenger))
    }

    @Test
    fun hysteresis_resetsWhenChallengerChanges() {
        val tracker = HysteresisTracker()
        val current = candidate(1L, score = 80.0)
        val first = candidate(2L, score = 90.0)
        val second = candidate(3L, score = 95.0)

        assertFalse(tracker.observe(current, first))
        assertFalse(tracker.observe(current, second))
        assertTrue(tracker.observe(current, second))
    }

    @Test
    fun hysteresis_replacesIneligibleCoordinatorImmediately() {
        val tracker = HysteresisTracker()
        val current = candidate(1L, score = 80.0, battery = 10.0)
        val challenger = candidate(2L, score = 70.0)

        assertTrue(tracker.observe(current, challenger))
    }

    @Test
    fun hysteresis_rejectsIneligibleChallenger() {
        val tracker = HysteresisTracker()
        val current = candidate(1L, score = 80.0)
        val challenger = candidate(
            2L,
            score = 100.0,
            mqttConnected = false
        )

        assertFalse(tracker.observe(current, challenger))
    }
}
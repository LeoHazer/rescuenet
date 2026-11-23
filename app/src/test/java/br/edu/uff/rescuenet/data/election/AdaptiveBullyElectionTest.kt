package br.edu.uff.rescuenet.data.election

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveBullyElectionTest {

    private val teamId = 1
    private val localNodeId = 100L
    private val remoteNodeId = 200L

    private fun metrics(value: Double) = ElectionMetrics(
        connectionStability = value,
        battery = value,
        availability = value,
        connectivity = value,
        computationalResources = value,
        dataEfficiency = value
    )

    private fun hello(
        nodeId: Long,
        score: Double
    ) = HelloMessage(
        teamId = teamId,
        nodeId = nodeId,
        epoch = ElectionEpoch(1L, nodeId),
        timestamp = 1_000L,
        metrics = metrics(score)
    )

    private fun election(
        timeout: Long = 5_000L,
        candidateTimeout: Long = 30_000L,
        heartbeatTimeout: Long = 15_000L
    ) = AdaptiveBullyElection(
        teamId = teamId,
        localNodeId = localNodeId,
        electionTimeoutMillis = timeout,
        candidateTimeoutMillis = candidateTimeout,
        heartbeatTimeoutMillis = heartbeatTimeout
    )

    @Test
    fun beginElectionWithoutHigherCandidateElectsLocalNode() {
        val machine = election()
        machine.updateLocalCandidate(hello(localNodeId, 80.0))

        val messages = machine.beginElection(1_000L)

        assertEquals(ElectionRole.COORDINATOR, machine.role)
        assertEquals(localNodeId, machine.coordinatorNodeId)
        assertEquals(1, messages.size)
        assertTrue(messages.first() is CoordinatorMessage)
    }

    @Test
    fun beginElectionWithHigherCandidateSendsElectionRequest() {
        val machine = election()
        machine.updateLocalCandidate(hello(localNodeId, 60.0))
        machine.observeHello(hello(remoteNodeId, 90.0), 1_000L)

        val messages = machine.beginElection(2_000L)

        assertEquals(ElectionRole.ELECTING, machine.role)
        assertNull(machine.coordinatorNodeId)
        assertEquals(1, messages.size)
        assertTrue(messages.first() is ElectionRequestMessage)
    }

    @Test
    fun electionTimeoutWithoutOkElectsLocalNode() {
        val machine = election(timeout = 5_000L)
        machine.updateLocalCandidate(hello(localNodeId, 80.0))
        machine.observeHello(hello(remoteNodeId, 90.0), 1_000L)

        machine.beginElection(1_000L)

        assertTrue(machine.onElectionTimeout(5_999L).isEmpty())

        val messages = machine.onElectionTimeout(6_000L)

        assertEquals(ElectionRole.COORDINATOR, machine.role)
        assertEquals(1, messages.size)
        assertTrue(messages.first() is CoordinatorMessage)
    }

    @Test
    fun okResponseMakesNodeWaitForCoordinator() {
        val machine = election(timeout = 5_000L)
        machine.updateLocalCandidate(hello(localNodeId, 60.0))
        machine.observeHello(hello(remoteNodeId, 90.0), 1_000L)

        machine.beginElection(2_000L)
        val epoch = machine.currentEpoch!!

        val response = ElectionOkMessage(
            teamId = teamId,
            nodeId = remoteNodeId,
            epoch = epoch,
            timestamp = 3L,
            targetNodeId = localNodeId
        )

        assertTrue(machine.receive(response, 3_000L).isEmpty())
        assertEquals(ElectionRole.ELECTING, machine.role)

        val messages = machine.onElectionTimeout(8_000L)

        assertEquals(ElectionRole.ELECTING, machine.role)
        assertTrue(messages.any { it is ElectionRequestMessage })
    }

    @Test
    fun expiredCoordinatorHeartbeatStartsNewElection() {
        val machine = election(heartbeatTimeout = 15_000L)
        machine.updateLocalCandidate(hello(localNodeId, 60.0))
        machine.observeHello(hello(remoteNodeId, 90.0), 1_000L)

        val coordinator = CoordinatorMessage(
            teamId = teamId,
            nodeId = remoteNodeId,
            epoch = ElectionEpoch(1L, remoteNodeId),
            timestamp = 1L,
            coordinatorNodeId = remoteNodeId
        )

        machine.receive(coordinator, 1_000L)

        assertEquals(ElectionRole.FOLLOWER, machine.role)
        assertEquals(remoteNodeId, machine.coordinatorNodeId)

        assertTrue(machine.onCoordinatorTimeout(16_000L).isEmpty())

        val messages = machine.onCoordinatorTimeout(16_001L)

        assertEquals(ElectionRole.ELECTING, machine.role)
        assertNull(machine.coordinatorNodeId)
        assertTrue(messages.any { it is ElectionRequestMessage })
    }

    @Test
    fun expiredCandidateIsRemovedBeforeElection() {
        val machine = election(candidateTimeout = 30_000L)
        machine.updateLocalCandidate(hello(localNodeId, 60.0))
        machine.observeHello(hello(remoteNodeId, 90.0), 1_000L)

        machine.beginElection(1_000L)
        assertEquals(ElectionRole.ELECTING, machine.role)

        machine.pruneExpiredCandidates(31_001L)
        val messages = machine.beginElection(31_001L)

        assertEquals(ElectionRole.COORDINATOR, machine.role)
        assertTrue(messages.any { it is CoordinatorMessage })
    }

    @Test
    fun messageFromAnotherTeamIsIgnored() {
        val machine = election()
        machine.updateLocalCandidate(hello(localNodeId, 80.0))

        val foreignMessage = HelloMessage(
            teamId = 2,
            nodeId = remoteNodeId,
            epoch = ElectionEpoch(1L, remoteNodeId),
            timestamp = 1L,
            metrics = metrics(90.0)
        )

        assertTrue(machine.receive(foreignMessage, 1_000L).isEmpty())

        val messages = machine.beginElection(2_000L)

        assertEquals(ElectionRole.COORDINATOR, machine.role)
        assertTrue(messages.single() is CoordinatorMessage)
    }

    @Test
    fun higherPriorityNodeAnswersElectionRequest() {
        val machine = election()
        machine.updateLocalCandidate(hello(localNodeId, 95.0))
        machine.observeHello(hello(remoteNodeId, 60.0), 1_000L)

        val request = ElectionRequestMessage(
            teamId = teamId,
            nodeId = remoteNodeId,
            epoch = ElectionEpoch(1L, remoteNodeId),
            timestamp = 2L
        )

        val messages = machine.receive(request, 2_000L)

        assertEquals(ElectionRole.COORDINATOR, machine.role)
        assertEquals(2, messages.size)
        assertTrue(messages[0] is ElectionOkMessage)
        assertTrue(messages[1] is CoordinatorMessage)

        val response = messages[0] as ElectionOkMessage
        assertEquals(remoteNodeId, response.targetNodeId)
    }

    @Test
    fun coordinatorCanCreateHeartbeat() {
        val machine = election()
        machine.updateLocalCandidate(hello(localNodeId, 80.0))

        machine.beginElection(1_000L)

        val heartbeat = machine.createHeartbeat(2_000L)

        assertEquals(ElectionRole.COORDINATOR, machine.role)
        assertTrue(heartbeat != null)
        assertEquals(localNodeId, heartbeat!!.coordinatorNodeId)
        assertEquals(machine.currentEpoch, heartbeat.epoch)
    }

    @Test
    fun nonCoordinatorCannotCreateHeartbeat() {
        val machine = election()
        machine.updateLocalCandidate(hello(localNodeId, 80.0))

        assertNull(machine.createHeartbeat(1_000L))
        assertFalse(machine.role == ElectionRole.COORDINATOR)
    }

    @Test
    fun electionRequestSynchronizesEpochBeforeStartingElection() {
        val machine = election()
        machine.updateLocalCandidate(hello(localNodeId, 95.0))
        machine.observeHello(hello(remoteNodeId, 60.0), 1_000L)

        val requestEpoch = ElectionEpoch(5L, remoteNodeId)
        val request = ElectionRequestMessage(
            teamId = teamId,
            nodeId = remoteNodeId,
            epoch = requestEpoch,
            timestamp = 2L
        )

        val messages = machine.receive(request, 2_000L)

        assertEquals(ElectionRole.COORDINATOR, machine.role)
        assertEquals(ElectionEpoch(6L, localNodeId), machine.currentEpoch)
        assertTrue(messages[0] is ElectionOkMessage)
        assertTrue(messages[1] is CoordinatorMessage)
        assertEquals(machine.currentEpoch, messages[1].let {
            (it as CoordinatorMessage).epoch
        })
    }

    @Test
    fun synchronizedEpochIsNotIncrementedAgainByBeginElection() {
        val machine = election()
        machine.updateLocalCandidate(hello(localNodeId, 95.0))
        machine.observeHello(hello(remoteNodeId, 60.0), 1_000L)

        val request = ElectionRequestMessage(
            teamId = teamId,
            nodeId = remoteNodeId,
            epoch = ElectionEpoch(10L, remoteNodeId),
            timestamp = 2L
        )

        machine.receive(request, 2_000L)

        assertEquals(ElectionEpoch(11L, localNodeId), machine.currentEpoch)
    }

    @Test
    fun coordinatorReplacementRequiresTwoConsecutiveAdvantages() {
        val machine = election()
        machine.updateLocalCandidate(hello(localNodeId, 80.0))
        machine.beginElection(1_000L)
        machine.observeHello(hello(remoteNodeId, 90.0), 2_000L)

        assertTrue(machine.evaluateCoordinatorChallenge(remoteNodeId, 2_000L).isEmpty())
        assertEquals(ElectionRole.COORDINATOR, machine.role)

        val messages = machine.evaluateCoordinatorChallenge(remoteNodeId, 3_000L)

        assertEquals(ElectionRole.ELECTING, machine.role)
        assertNull(machine.coordinatorNodeId)
        assertTrue(messages.any { it is ElectionRequestMessage })
    }

    @Test
    fun coordinatorReplacementResetsWhenAdvantageIsInsufficient() {
        val machine = election()
        machine.updateLocalCandidate(hello(localNodeId, 80.0))
        machine.beginElection(1_000L)

        machine.observeHello(hello(remoteNodeId, 95.0), 2_000L)
        assertTrue(machine.evaluateCoordinatorChallenge(remoteNodeId, 2_000L).isEmpty())

        machine.observeHello(hello(remoteNodeId, 85.0), 3_000L)
        assertTrue(machine.evaluateCoordinatorChallenge(remoteNodeId, 3_000L).isEmpty())

        machine.observeHello(hello(remoteNodeId, 90.0), 4_000L)
        assertTrue(machine.evaluateCoordinatorChallenge(remoteNodeId, 4_000L).isEmpty())

        val messages = machine.evaluateCoordinatorChallenge(remoteNodeId, 5_000L)

        assertEquals(ElectionRole.ELECTING, machine.role)
        assertTrue(messages.any { it is ElectionRequestMessage })
    }

    @Test
    fun nonCoordinatorCannotEvaluateReplacement() {
        val machine = election()
        machine.updateLocalCandidate(hello(localNodeId, 80.0))
        machine.observeHello(hello(remoteNodeId, 95.0), 1_000L)

        val messages = machine.evaluateCoordinatorChallenge(remoteNodeId, 2_000L)

        assertTrue(messages.isEmpty())
        assertEquals(ElectionRole.IDLE, machine.role)
    }

    @Test
    fun idleNodeRecognizesCoordinatorFromHeartbeatAfterHello() {
        val machine = election()
        machine.updateLocalCandidate(hello(localNodeId, 60.0))

        val coordinatorEpoch = ElectionEpoch(4L, remoteNodeId)
        val coordinatorHello = HelloMessage(
            teamId = teamId,
            nodeId = remoteNodeId,
            epoch = coordinatorEpoch,
            timestamp = 10L,
            metrics = metrics(90.0)
        )

        machine.receive(coordinatorHello, 10_000L)

        val heartbeat = HeartbeatMessage(
            teamId = teamId,
            nodeId = remoteNodeId,
            epoch = coordinatorEpoch,
            timestamp = 11L,
            coordinatorNodeId = remoteNodeId
        )

        assertTrue(machine.receive(heartbeat, 11_000L).isEmpty())
        assertEquals(ElectionRole.FOLLOWER, machine.role)
        assertEquals(remoteNodeId, machine.coordinatorNodeId)
        assertEquals(coordinatorEpoch, machine.currentEpoch)
        assertTrue(machine.onElectionTimeout(20_000L).isEmpty())
    }

    @Test
    fun idleNodeRejectsHeartbeatFromExpiredCandidate() {
        val machine = election(candidateTimeout = 30_000L)
        machine.updateLocalCandidate(hello(localNodeId, 60.0))

        val coordinatorEpoch = ElectionEpoch(4L, remoteNodeId)
        val coordinatorHello = HelloMessage(
            teamId = teamId,
            nodeId = remoteNodeId,
            epoch = coordinatorEpoch,
            timestamp = 1L,
            metrics = metrics(90.0)
        )

        machine.receive(coordinatorHello, 1_000L)

        val heartbeat = HeartbeatMessage(
            teamId = teamId,
            nodeId = remoteNodeId,
            epoch = coordinatorEpoch,
            timestamp = 31L,
            coordinatorNodeId = remoteNodeId
        )

        machine.receive(heartbeat, 31_001L)

        assertEquals(ElectionRole.IDLE, machine.role)
        assertNull(machine.coordinatorNodeId)
        assertNull(machine.currentEpoch)
    }}
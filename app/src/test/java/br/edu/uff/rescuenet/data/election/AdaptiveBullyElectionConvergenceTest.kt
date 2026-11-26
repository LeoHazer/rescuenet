package br.edu.uff.rescuenet.data.election

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cenários de convergência: nós que entram ou reconectam, dois
 * coordenadores simultâneos e falha do coordenador.
 */
class AdaptiveBullyElectionConvergenceTest {

    private val teamId = 1
    private val localNodeId = 100L
    private val remoteNodeId = 200L
    private val thirdNodeId = 300L

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
        score: Double,
        epoch: ElectionEpoch = ElectionEpoch(1L, nodeId)
    ) = HelloMessage(
        teamId = teamId,
        nodeId = nodeId,
        epoch = epoch,
        timestamp = 1L,
        metrics = metrics(score)
    )

    private fun coordinator(nodeId: Long, epoch: ElectionEpoch) =
        CoordinatorMessage(
            teamId = teamId,
            nodeId = nodeId,
            epoch = epoch,
            timestamp = 1L,
            coordinatorNodeId = nodeId
        )

    private fun heartbeat(nodeId: Long, epoch: ElectionEpoch) =
        HeartbeatMessage(
            teamId = teamId,
            nodeId = nodeId,
            epoch = epoch,
            timestamp = 1L,
            coordinatorNodeId = nodeId
        )

    private fun election() = AdaptiveBullyElection(
        teamId = teamId,
        localNodeId = localNodeId
    )

    /** Coordenador local na época (3, local). */
    private fun coordinatorAtEpochThree(): AdaptiveBullyElection {
        val machine = election()
        machine.updateLocalCandidate(hello(localNodeId, 80.0))
        machine.receive(hello(remoteNodeId, 60.0, ElectionEpoch(2L, remoteNodeId)), 1_000L)
        machine.beginElection(1_000L)
        assertEquals(ElectionEpoch(3L, localNodeId), machine.currentEpoch)
        assertEquals(ElectionRole.COORDINATOR, machine.role)
        return machine
    }

    @Test
    fun initialElectionDoesNotDisplaceRecognizedCoordinator() {
        val machine = election()
        // O nó que entra tem pontuação maior, mas abaixo da margem de histerese.
        machine.updateLocalCandidate(hello(localNodeId, 85.0))
        machine.receive(hello(remoteNodeId, 80.0, ElectionEpoch(3L, remoteNodeId)), 1_000L)
        machine.receive(heartbeat(remoteNodeId, ElectionEpoch(3L, remoteNodeId)), 1_000L)
        assertEquals(ElectionRole.FOLLOWER, machine.role)

        val messages = machine.startInitialElection(6_000L)

        assertTrue(messages.isEmpty())
        assertEquals(ElectionRole.FOLLOWER, machine.role)
        assertEquals(remoteNodeId, machine.coordinatorNodeId)
    }

    @Test
    fun initialElectionRunsWhenNoCoordinatorWasFound() {
        val machine = election()
        machine.updateLocalCandidate(hello(localNodeId, 85.0))
        machine.receive(hello(remoteNodeId, 80.0), 1_000L)

        val messages = machine.startInitialElection(6_000L)

        assertEquals(ElectionRole.COORDINATOR, machine.role)
        assertTrue(messages.single() is CoordinatorMessage)
    }

    @Test
    fun knownCandidateCountDropsWhenCandidateExpires() {
        val machine = election()
        machine.receive(hello(remoteNodeId, 80.0), 1_000L)
        assertEquals(1, machine.knownCandidateCount)

        machine.pruneExpiredCandidates(31_001L)

        assertEquals(0, machine.knownCandidateCount)
    }

    /** Nó com bom desempenho geral, mas bateria abaixo do mínimo (15%). */
    private fun lowBatteryHello(nodeId: Long, level: Double = 90.0, battery: Double = 10.0) =
        HelloMessage(
            teamId = teamId,
            nodeId = nodeId,
            epoch = ElectionEpoch(1L, nodeId),
            timestamp = 1L,
            metrics = ElectionMetrics(
                connectionStability = level,
                battery = battery,
                availability = level,
                connectivity = level,
                computationalResources = level,
                dataEfficiency = level
            )
        )

    @Test
    fun singleLowBatteryNodeBecomesCoordinator() {
        val machine = election()
        machine.updateLocalCandidate(lowBatteryHello(localNodeId))

        val messages = machine.startInitialElection(6_000L)

        assertEquals(ElectionRole.COORDINATOR, machine.role)
        assertTrue(messages.single() is CoordinatorMessage)
    }

    @Test
    fun eligibleNodeOutranksLowBatteryNodeWithHigherScore() {
        // O nó remoto tem pontuação maior (77,5 contra 50), mas está sem bateria.
        val machine = election()
        machine.updateLocalCandidate(hello(localNodeId, 50.0))
        machine.receive(lowBatteryHello(remoteNodeId), 1_000L)

        val messages = machine.beginElection(2_000L)

        assertEquals(ElectionRole.COORDINATOR, machine.role)
        assertTrue(messages.single() is CoordinatorMessage)
    }

    @Test
    fun lowBatteryCoordinatorHandsOverImmediatelyToEligibleNewcomer() {
        val machine = election()
        machine.updateLocalCandidate(lowBatteryHello(localNodeId))
        machine.startInitialElection(6_000L)
        assertEquals(ElectionRole.COORDINATOR, machine.role)

        // Um nó elegível chega, mesmo com pontuação menor e sem margem de 10.
        machine.receive(hello(remoteNodeId, 40.0), 10_000L)
        val messages = machine.evaluateCoordinatorChallenge(remoteNodeId, 10_000L)

        assertEquals(ElectionRole.ELECTING, machine.role)
        assertTrue(messages.single() is ElectionRequestMessage)
    }

    @Test
    fun eligibleNodeAnswersElectionFromLowBatteryCoordinatorAndTakesOver() {
        val machine = election()
        machine.updateLocalCandidate(hello(localNodeId, 40.0))
        machine.receive(lowBatteryHello(remoteNodeId), 1_000L)

        val request = ElectionRequestMessage(
            teamId = teamId,
            nodeId = remoteNodeId,
            epoch = ElectionEpoch(3L, remoteNodeId),
            timestamp = 2L
        )
        val messages = machine.receive(request, 2_000L)

        assertEquals(ElectionRole.COORDINATOR, machine.role)
        assertTrue(messages[0] is ElectionOkMessage)
        assertTrue(messages[1] is CoordinatorMessage)
    }

    @Test
    fun nodeFollowsLowBatteryCoordinatorWhenItIsTheOnlyOption() {
        val machine = election()
        machine.updateLocalCandidate(lowBatteryHello(localNodeId, level = 50.0))
        machine.receive(lowBatteryHello(remoteNodeId, level = 90.0), 1_000L)

        machine.receive(heartbeat(remoteNodeId, ElectionEpoch(2L, remoteNodeId)), 1_000L)

        assertEquals(ElectionRole.FOLLOWER, machine.role)
        assertEquals(remoteNodeId, machine.coordinatorNodeId)
    }

    @Test
    fun weakerHelloInBetweenDoesNotResetStrongerChallenger() {
        val machine = election()
        machine.updateLocalCandidate(hello(localNodeId, 60.0))
        machine.beginElection(1_000L)
        assertEquals(ElectionRole.COORDINATOR, machine.role)

        // Com três ou mais nós, os HELLOs dos desafiantes se intercalam.
        machine.receive(hello(remoteNodeId, 90.0), 2_000L)
        assertTrue(machine.evaluateCoordinatorChallenge(remoteNodeId, 2_000L).isEmpty())

        machine.receive(hello(thirdNodeId, 30.0), 3_000L)
        assertTrue(machine.evaluateCoordinatorChallenge(thirdNodeId, 3_000L).isEmpty())

        machine.receive(hello(remoteNodeId, 90.0), 7_000L)
        val messages = machine.evaluateCoordinatorChallenge(remoteNodeId, 7_000L)

        assertEquals(ElectionRole.ELECTING, machine.role)
        assertTrue(messages.single() is ElectionRequestMessage)
    }

    @Test
    fun helloEpochIsUsedAsBaseForNextElection() {
        val machine = election()
        machine.updateLocalCandidate(hello(localNodeId, 80.0))
        machine.receive(hello(remoteNodeId, 60.0, ElectionEpoch(7L, remoteNodeId)), 1_000L)

        machine.beginElection(2_000L)

        assertEquals(ElectionEpoch(8L, localNodeId), machine.currentEpoch)
    }

    @Test
    fun coordinatorReannouncesWhenAnotherCoordinatorHasOlderEpoch() {
        val machine = coordinatorAtEpochThree()

        val messages = machine.receive(
            coordinator(remoteNodeId, ElectionEpoch(1L, remoteNodeId)),
            2_000L
        )

        assertEquals(ElectionRole.COORDINATOR, machine.role)
        val reannounce = messages.single() as CoordinatorMessage
        assertEquals(ElectionEpoch(3L, localNodeId), reannounce.epoch)
    }

    @Test
    fun coordinatorReannouncesWhenHeartbeatComesFromOlderCoordinator() {
        val machine = coordinatorAtEpochThree()

        val messages = machine.receive(
            heartbeat(remoteNodeId, ElectionEpoch(1L, remoteNodeId)),
            2_000L
        )

        assertEquals(ElectionRole.COORDINATOR, machine.role)
        assertTrue(messages.single() is CoordinatorMessage)
    }

    @Test
    fun coordinatorStepsDownWhenHeartbeatComesFromNewerCoordinator() {
        val machine = coordinatorAtEpochThree()

        val messages = machine.receive(
            heartbeat(remoteNodeId, ElectionEpoch(5L, remoteNodeId)),
            2_000L
        )

        assertTrue(messages.isEmpty())
        assertEquals(ElectionRole.FOLLOWER, machine.role)
        assertEquals(remoteNodeId, machine.coordinatorNodeId)
        assertEquals(ElectionEpoch(5L, remoteNodeId), machine.currentEpoch)
    }

    @Test
    fun twoCoordinatorsConvergeToTheNewestEpoch() {
        val first = AdaptiveBullyElection(teamId, localNodeId)
        val second = AdaptiveBullyElection(teamId, remoteNodeId)
        val firstHello = hello(localNodeId, 80.0)
        val secondHello = hello(remoteNodeId, 82.0)

        first.updateLocalCandidate(firstHello)
        second.updateLocalCandidate(secondHello)

        // Os dois assumem sem se conhecerem a tempo (split-brain).
        // O primeiro já viu uma época maior em um HELLO de terceiro nó.
        first.receive(hello(thirdNodeId, 10.0, ElectionEpoch(4L, thirdNodeId)), 0L)
        first.beginElection(1_000L)
        second.beginElection(1_000L)
        assertEquals(ElectionRole.COORDINATOR, first.role)
        assertEquals(ElectionRole.COORDINATOR, second.role)

        // Só depois os HELLOs chegam.
        first.receive(secondHello, 1_500L)
        second.receive(firstHello, 1_500L)

        // Troca de heartbeats: o de época menor recebe um novo anúncio.
        val firstHeartbeat = first.createHeartbeat(2_000L)!!
        val secondHeartbeat = second.createHeartbeat(2_000L)!!
        val toSecond = first.receive(secondHeartbeat, 2_000L)
        second.receive(firstHeartbeat, 2_000L)
        toSecond.forEach { second.receive(it, 2_100L) }

        val coordinators = listOf(first, second)
            .count { it.role == ElectionRole.COORDINATOR }
        assertEquals(1, coordinators)
        assertEquals(localNodeId, second.coordinatorNodeId)
    }

    @Test
    fun followerAdoptsNewerCoordinatorAfterMissingAnnouncement() {
        val machine = election()
        machine.updateLocalCandidate(hello(localNodeId, 50.0))
        machine.receive(hello(remoteNodeId, 80.0), 1_000L)
        machine.receive(hello(thirdNodeId, 90.0), 1_000L)
        machine.receive(coordinator(remoteNodeId, ElectionEpoch(2L, remoteNodeId)), 1_000L)
        assertEquals(remoteNodeId, machine.coordinatorNodeId)

        machine.receive(heartbeat(thirdNodeId, ElectionEpoch(3L, thirdNodeId)), 2_000L)

        assertEquals(ElectionRole.FOLLOWER, machine.role)
        assertEquals(thirdNodeId, machine.coordinatorNodeId)
        assertTrue(machine.onCoordinatorTimeout(10_000L).isEmpty())
    }

    @Test
    fun singleElectionRequestIsBroadcastForSeveralHigherCandidates() {
        val machine = election()
        machine.updateLocalCandidate(hello(localNodeId, 50.0))
        machine.receive(hello(remoteNodeId, 80.0), 1_000L)
        machine.receive(hello(thirdNodeId, 90.0), 1_000L)

        val messages = machine.beginElection(2_000L)

        assertEquals(1, messages.size)
        assertTrue(messages.single() is ElectionRequestMessage)
    }

    @Test
    fun failedCoordinatorIsDiscardedAndLocalNodeTakesOver() {
        val machine = election()
        machine.updateLocalCandidate(hello(localNodeId, 50.0))
        machine.receive(hello(remoteNodeId, 90.0), 1_000L)
        machine.receive(coordinator(remoteNodeId, ElectionEpoch(2L, remoteNodeId)), 1_000L)

        val messages = machine.onCoordinatorTimeout(16_001L)

        assertEquals(ElectionRole.COORDINATOR, machine.role)
        assertTrue(messages.single() is CoordinatorMessage)
    }

    @Test
    fun failedCoordinatorStillLetsOtherHigherCandidatesCompete() {
        val machine = election()
        machine.updateLocalCandidate(hello(localNodeId, 50.0))
        machine.receive(hello(remoteNodeId, 90.0), 1_000L)
        machine.receive(hello(thirdNodeId, 70.0), 10_000L)
        machine.receive(coordinator(remoteNodeId, ElectionEpoch(2L, remoteNodeId)), 1_000L)

        val messages = machine.onCoordinatorTimeout(16_001L)

        assertEquals(ElectionRole.ELECTING, machine.role)
        assertTrue(messages.single() is ElectionRequestMessage)
    }
}

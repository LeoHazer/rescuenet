package br.edu.uff.rescuenet.data.election

enum class ElectionRole {
    IDLE,
    ELECTING,
    COORDINATOR,
    FOLLOWER
}

class AdaptiveBullyElection(
    private val teamId: Int,
    private val localNodeId: Long,
    private val electionTimeoutMillis: Long = 5_000L,
    private val candidateTimeoutMillis: Long = 30_000L,
    private val heartbeatTimeoutMillis: Long = 15_000L
) {
    var role: ElectionRole = ElectionRole.IDLE
        private set

    var coordinatorNodeId: Long? = null
        private set

    var currentEpoch: ElectionEpoch? = null
        private set

    private val epochTracker = ElectionEpochTracker(localNodeId)
    private val candidates = mutableMapOf<Long, TimedCandidate>()
    private val pendingResponses = mutableSetOf<Long>()
    private val hysteresisTracker = HysteresisTracker()

    private var localHello: HelloMessage? = null
    private var electionDeadlineMillis: Long? = null
    private var coordinatorLastHeartbeatMillis: Long? = null
    private var awaitingCoordinator = false

    private data class TimedCandidate(
        val message: HelloMessage,
        val receivedAtMillis: Long
    )

    init {
        require(teamId > 0) { "teamId deve ser positivo." }
        require(localNodeId > 0L) { "localNodeId deve ser positivo." }
        require(electionTimeoutMillis > 0L)
        require(candidateTimeoutMillis > 0L)
        require(heartbeatTimeoutMillis > 0L)
    }

    fun updateLocalCandidate(message: HelloMessage) {
        require(message.teamId == teamId)
        require(message.nodeId == localNodeId)
        localHello = message
    }

    fun observeHello(
        message: HelloMessage,
        receivedAtMillis: Long
    ) {
        if (message.teamId != teamId) return
        if (message.nodeId == localNodeId) return

        candidates[message.nodeId] = TimedCandidate(
            message = message,
            receivedAtMillis = receivedAtMillis
        )
    }

    fun evaluateCoordinatorChallenge(
        challengerNodeId: Long,
        nowMillis: Long
    ): List<ElectionMessage> {
        if (role != ElectionRole.COORDINATOR) return emptyList()
        if (challengerNodeId == localNodeId) return emptyList()

        pruneExpiredCandidates(nowMillis)

        val local = localHello ?: return emptyList()
        val challenger = candidates[challengerNodeId]?.message
            ?: return emptyList()

        val currentCandidate = ElectionCandidate(
            nodeId = localNodeId,
            metrics = local.metrics,
            mqttConnected = true,
            available = true
        )

        val challengerCandidate = ElectionCandidate(
            nodeId = challenger.nodeId,
            metrics = challenger.metrics,
            mqttConnected = true,
            available = true
        )

        val replacementConfirmed = hysteresisTracker.observe(
            currentCandidate,
            challengerCandidate
        )

        if (!replacementConfirmed) return emptyList()

        hysteresisTracker.reset()

        if (!currentCandidate.isEligible) {
            role = ElectionRole.IDLE
            coordinatorNodeId = null
            coordinatorLastHeartbeatMillis = null
            electionDeadlineMillis = null
            pendingResponses.clear()
            awaitingCoordinator = false
            return emptyList()
        }

        return beginElection(nowMillis)
    }

    fun pruneExpiredCandidates(nowMillis: Long) {
        candidates.entries.removeAll { (_, candidate) ->
            nowMillis - candidate.receivedAtMillis > candidateTimeoutMillis
        }
    }

    fun beginElection(
        nowMillis: Long,
        synchronizedEpoch: ElectionEpoch? = null
    ): List<ElectionMessage> {
        val local = localHello ?: return emptyList()
        if (!isEligible(local)) return emptyList()

        hysteresisTracker.reset()

        val epoch = synchronizedEpoch ?: epochTracker.startNewEpoch()
        currentEpoch = epoch
        role = ElectionRole.ELECTING
        coordinatorNodeId = null
        awaitingCoordinator = false
        pendingResponses.clear()

        pruneExpiredCandidates(nowMillis)

        val higherCandidates = candidates.values
            .map { it.message }
            .filter { isEligible(it) && hasHigherPriority(it, local) }

        electionDeadlineMillis = nowMillis + electionTimeoutMillis

        if (higherCandidates.isEmpty()) {
            return becomeCoordinator(nowMillis)
        }

        return higherCandidates.map { candidate ->
            ElectionRequestMessage(
                teamId = teamId,
                nodeId = localNodeId,
                epoch = epoch,
                timestamp = nowMillis / 1000L
            ).also {
                pendingResponses.add(candidate.nodeId)
            }
        }
    }

    fun receive(
        message: ElectionMessage,
        nowMillis: Long
    ): List<ElectionMessage> {
        if (message.teamId != teamId) return emptyList()
        if (message.nodeId == localNodeId) return emptyList()

        return when (message) {
            is HelloMessage -> {
                observeHello(message, nowMillis)
                emptyList()
            }

            is ElectionRequestMessage -> handleElectionRequest(
                message,
                nowMillis
            )

            is ElectionOkMessage -> handleOk(message, nowMillis)

            is CoordinatorMessage -> handleCoordinator(
                message,
                nowMillis
            )

            is HeartbeatMessage -> handleHeartbeat(
                message,
                nowMillis
            )
        }
    }

    private fun handleElectionRequest(
        message: ElectionRequestMessage,
        nowMillis: Long
    ): List<ElectionMessage> {
        val local = localHello ?: return emptyList()
        if (!isEligible(local)) return emptyList()

        val sender = candidates[message.nodeId]?.message
            ?: return emptyList()

        if (!isEligible(sender) || !hasHigherPriority(local, sender)) {
            return emptyList()
        }

        val response = ElectionOkMessage(
            teamId = teamId,
            nodeId = localNodeId,
            epoch = message.epoch,
            timestamp = nowMillis / 1000L,
            targetNodeId = message.nodeId
        )

        val synchronizedEpoch =
            epochTracker.synchronizeAndStartNewEpoch(message.epoch)

        currentEpoch = synchronizedEpoch
        role = ElectionRole.ELECTING
        coordinatorNodeId = null
        awaitingCoordinator = false
        pendingResponses.clear()

        val electionMessages = beginElection(nowMillis, synchronizedEpoch)
        return listOf(response) + electionMessages
    }

    private fun handleOk(
        message: ElectionOkMessage,
        nowMillis: Long
    ): List<ElectionMessage> {
        if (role != ElectionRole.ELECTING) return emptyList()
        if (message.targetNodeId != localNodeId) return emptyList()
        if (message.epoch != currentEpoch) return emptyList()

        pendingResponses.remove(message.nodeId)
        awaitingCoordinator = true
        electionDeadlineMillis = nowMillis + electionTimeoutMillis

        return emptyList()
    }

    private fun handleCoordinator(
        message: CoordinatorMessage,
        nowMillis: Long
    ): List<ElectionMessage> {
        val knownCoordinator = candidates[message.coordinatorNodeId]?.message
            ?: return emptyList()

        if (!isEligible(knownCoordinator)) return emptyList()
        if (!epochTracker.observe(message.epoch)) return emptyList()

        currentEpoch = message.epoch
        role = ElectionRole.FOLLOWER
        coordinatorNodeId = message.coordinatorNodeId
        pendingResponses.clear()
        awaitingCoordinator = false
        electionDeadlineMillis = null
        coordinatorLastHeartbeatMillis = nowMillis

        return emptyList()
    }

    private fun handleHeartbeat(
        message: HeartbeatMessage,
        nowMillis: Long
    ): List<ElectionMessage> {
        if (message.nodeId != message.coordinatorNodeId) return emptyList()

        if (role == ElectionRole.IDLE) {
            pruneExpiredCandidates(nowMillis)

            val knownCoordinator =
                candidates[message.coordinatorNodeId]?.message
                    ?: return emptyList()

            if (!isEligible(knownCoordinator)) return emptyList()
            if (!epochTracker.observe(message.epoch)) return emptyList()

            currentEpoch = message.epoch
            role = ElectionRole.FOLLOWER
            coordinatorNodeId = message.coordinatorNodeId
            pendingResponses.clear()
            awaitingCoordinator = false
            electionDeadlineMillis = null
            coordinatorLastHeartbeatMillis = nowMillis

            return emptyList()
        }

        if (role != ElectionRole.FOLLOWER) return emptyList()
        if (message.coordinatorNodeId != coordinatorNodeId) return emptyList()
        if (message.epoch != currentEpoch) return emptyList()

        coordinatorLastHeartbeatMillis = nowMillis
        return emptyList()
    }

    fun onElectionTimeout(nowMillis: Long): List<ElectionMessage> {
        if (role != ElectionRole.ELECTING) return emptyList()

        val deadline = electionDeadlineMillis ?: return emptyList()
        if (nowMillis < deadline) return emptyList()

        return if (awaitingCoordinator) {
            beginElection(nowMillis)
        } else {
            becomeCoordinator(nowMillis)
        }
    }

    fun onCoordinatorTimeout(nowMillis: Long): List<ElectionMessage> {
        if (role != ElectionRole.FOLLOWER) return emptyList()

        val lastHeartbeat = coordinatorLastHeartbeatMillis
            ?: return beginElection(nowMillis)

        if (nowMillis - lastHeartbeat <= heartbeatTimeoutMillis) {
            return emptyList()
        }

        return beginElection(nowMillis)
    }

    fun createHeartbeat(nowMillis: Long): HeartbeatMessage? {
        if (role != ElectionRole.COORDINATOR) return null
        val epoch = currentEpoch ?: return null

        return HeartbeatMessage(
            teamId = teamId,
            nodeId = localNodeId,
            epoch = epoch,
            timestamp = nowMillis / 1000L,
            coordinatorNodeId = localNodeId
        )
    }

    private fun becomeCoordinator(
        nowMillis: Long
    ): List<ElectionMessage> {
        val epoch = currentEpoch ?: return emptyList()

        role = ElectionRole.COORDINATOR
        coordinatorNodeId = localNodeId
        hysteresisTracker.reset()
        pendingResponses.clear()
        awaitingCoordinator = false
        electionDeadlineMillis = null
        coordinatorLastHeartbeatMillis = nowMillis

        return listOf(
            CoordinatorMessage(
                teamId = teamId,
                nodeId = localNodeId,
                epoch = epoch,
                timestamp = nowMillis / 1000L,
                coordinatorNodeId = localNodeId
            )
        )
    }

    private fun isEligible(message: HelloMessage): Boolean {
        return ElectionCandidate(
            nodeId = message.nodeId,
            metrics = message.metrics,
            mqttConnected = true,
            available = true
        ).isEligible
    }

    private fun hasHigherPriority(
        first: HelloMessage,
        second: HelloMessage
    ): Boolean {
        if (first.score != second.score) {
            return first.score > second.score
        }

        return first.nodeId > second.nodeId
    }
}
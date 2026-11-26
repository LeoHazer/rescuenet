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

    /** Quantidade de outros nós conhecidos e ainda não expirados. */
    val knownCandidateCount: Int
        get() = candidates.size

    fun isKnownCandidate(nodeId: Long): Boolean =
        candidates.containsKey(nodeId)

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

        // Um nó que entra (ou reconecta) aprende a época da rede pelo HELLO.
        // Se precisar eleger, usará uma época maior que a do coordenador
        // atual, e o anúncio dele não será descartado como antigo.
        epochTracker.observeCounter(message.epoch.counter)
    }

    /**
     * Eleição disparada após a janela de descoberta. Só acontece se o nó
     * ainda não reconheceu um coordenador; caso contrário, a entrada de um
     * nó derrubaria a liderança atual e ignoraria a histerese.
     */
    fun startInitialElection(nowMillis: Long): List<ElectionMessage> {
        if (role != ElectionRole.IDLE) return emptyList()
        return beginElection(nowMillis)
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

        // Também quando o coordenador atual está sem bateria: em vez de sair
        // em silêncio (os outros só agiriam após o timeout de heartbeat), ele
        // abre a eleição e o nó elegível assume na mesma rodada.
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
        // Um nó sem bateria suficiente também pode se candidatar: a prioridade
        // (hasHigherPriority) coloca qualquer nó elegível à frente dele, então
        // ele só vence se não houver nó elegível conhecido.
        val local = localHello ?: return emptyList()

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
            .filter { hasHigherPriority(it, local) }

        electionDeadlineMillis = nowMillis + electionTimeoutMillis

        if (higherCandidates.isEmpty()) {
            return becomeCoordinator(nowMillis)
        }

        higherCandidates.forEach { pendingResponses.add(it.nodeId) }

        // O tópico é compartilhado: uma única mensagem alcança todos os nós
        // de prioridade maior. Cópias repetidas fariam cada um deles
        // reiniciar a própria eleição várias vezes.
        return listOf(
            ElectionRequestMessage(
                teamId = teamId,
                nodeId = localNodeId,
                epoch = epoch,
                timestamp = nowMillis / 1000L
            )
        )
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

        val sender = candidates[message.nodeId]?.message
            ?: return emptyList()

        // A elegibilidade já entra em hasHigherPriority: um nó elegível sempre
        // responde a um nó sem bateria, e entre dois nós sem bateria vale a
        // pontuação (para haver líder quando nenhum nó é elegível).
        if (!hasHigherPriority(local, sender)) {
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
        if (acceptCoordinator(message.coordinatorNodeId, message.epoch, nowMillis)) {
            return emptyList()
        }

        return reannounceIfOlder(message.epoch, nowMillis)
    }

    private fun handleHeartbeat(
        message: HeartbeatMessage,
        nowMillis: Long
    ): List<ElectionMessage> {
        if (message.nodeId != message.coordinatorNodeId) return emptyList()

        // Caso comum: heartbeat do coordenador já seguido.
        if (role == ElectionRole.FOLLOWER &&
            message.coordinatorNodeId == coordinatorNodeId &&
            message.epoch == currentEpoch
        ) {
            coordinatorLastHeartbeatMillis = nowMillis
            return emptyList()
        }

        // Heartbeat de um coordenador com época mais nova: o nó entrou
        // agora, perdeu o COORDINATOR (não retido) ou é um coordenador
        // em conflito. Em todos os casos, a época mais nova prevalece.
        if (acceptCoordinator(message.coordinatorNodeId, message.epoch, nowMillis)) {
            return emptyList()
        }

        return reannounceIfOlder(message.epoch, nowMillis)
    }

    /**
     * Adota [candidateNodeId] como coordenador se ele for conhecido e
     * anunciar uma época mais nova que a local.
     *
     * Não se exige bateria suficiente: um coordenador sem bateria só existe
     * quando não havia nó elegível, e ele mesmo passa a liderança ao
     * receber o HELLO de um nó elegível (evaluateCoordinatorChallenge).
     */
    private fun acceptCoordinator(
        candidateNodeId: Long,
        epoch: ElectionEpoch,
        nowMillis: Long
    ): Boolean {
        if (candidateNodeId == localNodeId) return false

        pruneExpiredCandidates(nowMillis)

        // Só segue coordenadores já anunciados por HELLO e não expirados.
        if (!candidates.containsKey(candidateNodeId)) return false
        if (!epochTracker.observe(epoch)) return false

        currentEpoch = epoch
        role = ElectionRole.FOLLOWER
        coordinatorNodeId = candidateNodeId
        hysteresisTracker.reset()
        pendingResponses.clear()
        awaitingCoordinator = false
        electionDeadlineMillis = null
        coordinatorLastHeartbeatMillis = nowMillis

        return true
    }

    /**
     * Dois coordenadores ao mesmo tempo (split-brain): o de época mais
     * antiga é avisado novamente de quem lidera, para que ceda. Sem isso,
     * cada um ignoraria os heartbeats do outro indefinidamente.
     */
    private fun reannounceIfOlder(
        otherEpoch: ElectionEpoch,
        nowMillis: Long
    ): List<ElectionMessage> {
        if (role != ElectionRole.COORDINATOR) return emptyList()

        val epoch = currentEpoch ?: return emptyList()
        if (otherEpoch >= epoch) return emptyList()

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

        // O coordenador parou de responder: não faz sentido enviar ELECTION
        // para ele e esperar um OK que não virá. Se ele estiver vivo, o
        // próximo HELLO o recoloca entre os candidatos.
        coordinatorNodeId?.let { candidates.remove(it) }

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

    /**
     * Ordem de prioridade: (1) elegível (bateria >= 15%) antes de não
     * elegível; (2) maior pontuação; (3) maior nodeId.
     */
    private fun hasHigherPriority(
        first: HelloMessage,
        second: HelloMessage
    ): Boolean {
        val firstEligible = isEligible(first)
        val secondEligible = isEligible(second)

        if (firstEligible != secondEligible) {
            return firstEligible
        }

        if (first.score != second.score) {
            return first.score > second.score
        }

        return first.nodeId > second.nodeId
    }
}
package br.edu.uff.rescuenet.simulator

import br.edu.uff.rescuenet.data.election.AdaptiveBullyElection
import br.edu.uff.rescuenet.data.election.ElectionEpoch
import br.edu.uff.rescuenet.data.election.ElectionMessage
import br.edu.uff.rescuenet.data.election.ElectionMetrics
import br.edu.uff.rescuenet.data.election.ElectionRole
import br.edu.uff.rescuenet.data.election.HelloMessage

/**
 * Características de um nó simulado. O nível geral vale para estabilidade,
 * disponibilidade, conectividade, recursos e eficiência de dados; a bateria
 * é separada porque abaixo de 15% o nó fica inelegível.
 */
data class NodeProfile(
    val level: Double,
    val battery: Double
) {
    init {
        require(level in 0.0..100.0) { "O nível deve estar entre 0 e 100." }
        require(battery in 0.0..100.0) { "A bateria deve estar entre 0 e 100." }
    }

    fun toMetrics() = ElectionMetrics(
        connectionStability = level,
        battery = battery,
        availability = level,
        connectivity = level,
        computationalResources = level,
        dataEfficiency = level
    )
}

/**
 * Orquestração da eleição de um nó, equivalente à do aplicativo
 * (RescueNetViewModel): HELLO a cada 5 s, janela de descoberta de 6 s,
 * verificação de timeouts a cada tique, heartbeat a cada 5 s quando
 * coordenador e anúncio imediato para nós recém-chegados.
 *
 * Não é thread-safe: todas as chamadas devem vir da mesma thread.
 * O tempo é recebido de fora (milissegundos monotônicos), o que permite
 * testar com um relógio manual.
 */
class ElectionNodeRuntime(
    val nodeId: Long,
    val teamId: Int,
    initialProfile: NodeProfile,
    private val publish: (ElectionMessage) -> Unit
) {
    companion object {
        const val HELLO_INTERVAL_MILLIS = 5_000L
        const val HEARTBEAT_INTERVAL_MILLIS = 5_000L
        const val DISCOVERY_WINDOW_MILLIS = 6_000L
    }

    var profile: NodeProfile = initialProfile

    private var machine: AdaptiveBullyElection? = null
    private var lastHelloAtMillis = 0L
    private var lastHeartbeatAtMillis = 0L
    private var initialElectionAtMillis = 0L

    val role: ElectionRole? get() = machine?.role
    val coordinatorNodeId: Long? get() = machine?.coordinatorNodeId
    val currentEpoch: ElectionEpoch? get() = machine?.currentEpoch
    val knownCandidateCount: Int get() = machine?.knownCandidateCount ?: 0

    /** Nova sessão de eleição, como o app faz a cada (re)conexão ao broker. */
    fun start(nowMillis: Long) {
        machine = AdaptiveBullyElection(teamId = teamId, localNodeId = nodeId)
        publishHello(nowMillis)
        lastHelloAtMillis = nowMillis
        lastHeartbeatAtMillis = nowMillis
        initialElectionAtMillis = nowMillis + DISCOVERY_WINDOW_MILLIS
    }

    fun stop() {
        machine = null
        initialElectionAtMillis = 0L
    }

    /** Equivale ao agendador de 1 s do ViewModel. */
    fun tick(nowMillis: Long) {
        val machine = machine ?: return

        machine.pruneExpiredCandidates(nowMillis)

        if (nowMillis - lastHelloAtMillis >= HELLO_INTERVAL_MILLIS) {
            publishHello(nowMillis)
            lastHelloAtMillis = nowMillis
        }

        if (initialElectionAtMillis > 0L && nowMillis >= initialElectionAtMillis) {
            initialElectionAtMillis = 0L
            publishAll(machine.startInitialElection(nowMillis))
        }

        publishAll(machine.onElectionTimeout(nowMillis))
        publishAll(machine.onCoordinatorTimeout(nowMillis))

        if (machine.role == ElectionRole.COORDINATOR &&
            nowMillis - lastHeartbeatAtMillis >= HEARTBEAT_INTERVAL_MILLIS
        ) {
            machine.createHeartbeat(nowMillis)?.let { publish(it) }
            lastHeartbeatAtMillis = nowMillis
        }
    }

    /** Equivale a handleElectionPayload do ViewModel. */
    fun onMessage(message: ElectionMessage, nowMillis: Long) {
        val machine = machine ?: return
        if (message.teamId != teamId || message.nodeId == nodeId) return

        val isNewcomer = message is HelloMessage &&
            !machine.isKnownCandidate(message.nodeId)
        val responses = machine.receive(message, nowMillis)

        if (message is HelloMessage && machine.role == ElectionRole.COORDINATOR) {
            if (isNewcomer) {
                publishHello(nowMillis)
                lastHelloAtMillis = nowMillis
                machine.createHeartbeat(nowMillis)?.let {
                    publish(it)
                    lastHeartbeatAtMillis = nowMillis
                }
            }

            publishAll(machine.evaluateCoordinatorChallenge(message.nodeId, nowMillis))
        }

        publishAll(responses)
    }

    private fun publishHello(nowMillis: Long) {
        val machine = machine ?: return

        val hello = HelloMessage(
            teamId = teamId,
            nodeId = nodeId,
            epoch = machine.currentEpoch ?: ElectionEpoch(1L, nodeId),
            timestamp = System.currentTimeMillis() / 1000,
            metrics = profile.toMetrics()
        )

        machine.updateLocalCandidate(hello)
        publish(hello)
    }

    private fun publishAll(messages: List<ElectionMessage>) {
        messages.forEach(publish)
    }
}

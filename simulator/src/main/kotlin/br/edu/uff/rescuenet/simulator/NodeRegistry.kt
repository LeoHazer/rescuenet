package br.edu.uff.rescuenet.simulator

import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicLong

class NodeNotFoundException(nodeId: Long) :
    RuntimeException("Nó simulado $nodeId não encontrado.")

class NodeLimitException(limit: Int) :
    RuntimeException("Limite de $limit nós simulados atingido.")

/**
 * Nós simulados ativos. Os IDs são curtos e sequenciais (1001, 1002, ...)
 * para facilitar a leitura na apresentação; como no app, o ID só desempata
 * pontuações iguais.
 */
class NodeRegistry(
    private val brokerHost: String,
    private val brokerPort: Int,
    private val defaultTeamId: Int,
    private val maxNodes: Int = 12,
    firstNodeId: Long = 1001L
) {
    private val nodes = ConcurrentSkipListMap<Long, SimulatedNode>()
    private val nextNodeId = AtomicLong(firstNodeId)

    @Synchronized
    fun create(profile: NodeProfile, teamId: Int?): NodeSnapshot {
        if (nodes.size >= maxNodes) throw NodeLimitException(maxNodes)

        val node = SimulatedNode(
            nodeId = nextNodeId.getAndIncrement(),
            teamId = teamId ?: defaultTeamId,
            profile = profile,
            brokerHost = brokerHost,
            brokerPort = brokerPort
        )

        nodes[node.nodeId] = node
        node.connect()
        return node.snapshot()
    }

    fun list(): List<NodeSnapshot> = nodes.values.map { it.snapshot() }

    fun get(nodeId: Long): SimulatedNode =
        nodes[nodeId] ?: throw NodeNotFoundException(nodeId)

    fun remove(nodeId: Long) {
        val node = nodes.remove(nodeId) ?: throw NodeNotFoundException(nodeId)
        node.shutdown()
    }

    fun removeAll() {
        nodes.keys.toList().forEach { nodeId ->
            nodes.remove(nodeId)?.shutdown()
        }
    }
}

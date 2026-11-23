package br.edu.uff.rescuenet.data.election

import org.json.JSONObject

sealed class ElectionMessage(
    open val teamId: Int,
    open val nodeId: Long,
    open val epoch: ElectionEpoch,
    open val timestamp: Long
) {
    protected fun validateCommonFields() {
        require(teamId > 0) { "teamId deve ser positivo." }
        require(nodeId > 0L) { "nodeId deve ser positivo." }
        require(epoch.counter >= 0L) { "O contador da época não pode ser negativo." }
        require(epoch.initiatorNodeId > 0L) { "O iniciador da época deve ser positivo." }
        require(timestamp >= 0L) { "timestamp não pode ser negativo." }
    }
}

data class HelloMessage(
    override val teamId: Int,
    override val nodeId: Long,
    override val epoch: ElectionEpoch,
    override val timestamp: Long,
    val metrics: ElectionMetrics
) : ElectionMessage(teamId, nodeId, epoch, timestamp) {
    init {
        validateCommonFields()
    }

    val score: Double
        get() = AdaptiveBullyPolicy.calculateScore(metrics)
}

data class ElectionRequestMessage(
    override val teamId: Int,
    override val nodeId: Long,
    override val epoch: ElectionEpoch,
    override val timestamp: Long
) : ElectionMessage(teamId, nodeId, epoch, timestamp) {
    init {
        validateCommonFields()
    }
}

data class ElectionOkMessage(
    override val teamId: Int,
    override val nodeId: Long,
    override val epoch: ElectionEpoch,
    override val timestamp: Long,
    val targetNodeId: Long
) : ElectionMessage(teamId, nodeId, epoch, timestamp) {
    init {
        validateCommonFields()
        require(targetNodeId > 0L) { "targetNodeId deve ser positivo." }
    }
}

data class CoordinatorMessage(
    override val teamId: Int,
    override val nodeId: Long,
    override val epoch: ElectionEpoch,
    override val timestamp: Long,
    val coordinatorNodeId: Long
) : ElectionMessage(teamId, nodeId, epoch, timestamp) {
    init {
        validateCommonFields()
        require(coordinatorNodeId == nodeId) {
            "O coordenador anunciado deve ser o próprio emissor."
        }
    }
}

data class HeartbeatMessage(
    override val teamId: Int,
    override val nodeId: Long,
    override val epoch: ElectionEpoch,
    override val timestamp: Long,
    val coordinatorNodeId: Long
) : ElectionMessage(teamId, nodeId, epoch, timestamp) {
    init {
        validateCommonFields()
        require(coordinatorNodeId == nodeId) {
            "O heartbeat deve ser publicado pelo coordenador."
        }
    }
}

object ElectionMessageCodec {

    fun encode(message: ElectionMessage): String {
        val json = JSONObject()
            .put("team_id", message.teamId)
            .put("node_id", message.nodeId)
            .put("epoch_counter", message.epoch.counter)
            .put("epoch_initiator_node_id", message.epoch.initiatorNodeId)
            .put("timestamp", message.timestamp)

        when (message) {
            is HelloMessage -> {
                json.put("type", "HELLO")
                    .put("connection_stability", message.metrics.connectionStability)
                    .put("battery", message.metrics.battery)
                    .put("availability", message.metrics.availability)
                    .put("connectivity", message.metrics.connectivity)
                    .put("computational_resources", message.metrics.computationalResources)
                    .put("data_efficiency", message.metrics.dataEfficiency)
                    .put("score", message.score)
            }

            is ElectionRequestMessage -> {
                json.put("type", "ELECTION")
            }

            is ElectionOkMessage -> {
                json.put("type", "OK")
                    .put("target_node_id", message.targetNodeId)
            }

            is CoordinatorMessage -> {
                json.put("type", "COORDINATOR")
                    .put("coordinator_node_id", message.coordinatorNodeId)
            }

            is HeartbeatMessage -> {
                json.put("type", "HEARTBEAT")
                    .put("coordinator_node_id", message.coordinatorNodeId)
            }
        }

        return json.toString()
    }

    fun decode(payload: String): ElectionMessage {
        require(payload.isNotBlank()) { "Payload da eleição vazio." }

        val json = JSONObject(payload)
        val teamId = json.getInt("team_id")
        val nodeId = json.getLong("node_id")
        val epoch = ElectionEpoch(
            counter = json.getLong("epoch_counter"),
            initiatorNodeId = json.getLong("epoch_initiator_node_id")
        )
        val timestamp = json.getLong("timestamp")

        return when (json.getString("type")) {
            "HELLO" -> {
                val metrics = ElectionMetrics(
                    connectionStability = json.getDouble("connection_stability"),
                    battery = json.getDouble("battery"),
                    availability = json.getDouble("availability"),
                    connectivity = json.getDouble("connectivity"),
                    computationalResources = json.getDouble("computational_resources"),
                    dataEfficiency = json.getDouble("data_efficiency")
                )

                val message = HelloMessage(
                    teamId = teamId,
                    nodeId = nodeId,
                    epoch = epoch,
                    timestamp = timestamp,
                    metrics = metrics
                )

                val receivedScore = json.getDouble("score")
                require(kotlin.math.abs(receivedScore - message.score) < 0.01) {
                    "A pontuação recebida não corresponde às métricas informadas."
                }

                message
            }

            "ELECTION" -> ElectionRequestMessage(
                teamId = teamId,
                nodeId = nodeId,
                epoch = epoch,
                timestamp = timestamp
            )

            "OK" -> ElectionOkMessage(
                teamId = teamId,
                nodeId = nodeId,
                epoch = epoch,
                timestamp = timestamp,
                targetNodeId = json.getLong("target_node_id")
            )

            "COORDINATOR" -> CoordinatorMessage(
                teamId = teamId,
                nodeId = nodeId,
                epoch = epoch,
                timestamp = timestamp,
                coordinatorNodeId = json.getLong("coordinator_node_id")
            )

            "HEARTBEAT" -> HeartbeatMessage(
                teamId = teamId,
                nodeId = nodeId,
                epoch = epoch,
                timestamp = timestamp,
                coordinatorNodeId = json.getLong("coordinator_node_id")
            )

            else -> throw IllegalArgumentException(
                "Tipo de mensagem de eleição desconhecido: ${json.getString("type")}"
            )
        }
    }
}
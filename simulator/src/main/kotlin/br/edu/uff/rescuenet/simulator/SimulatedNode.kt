package br.edu.uff.rescuenet.simulator

import br.edu.uff.rescuenet.data.election.AdaptiveBullyPolicy
import br.edu.uff.rescuenet.data.election.ElectionMessage
import br.edu.uff.rescuenet.data.election.ElectionMessageCodec
import br.edu.uff.rescuenet.data.election.ElectionRole
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import com.hivemq.client.mqtt.mqtt5.message.disconnect.Mqtt5DisconnectReasonCode
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

enum class NodeStatus(val label: String) {
    CONNECTING("conectando"),
    ONLINE("online"),
    CRASHED("caido"),
    FAILED("falha")
}

data class NodeSnapshot(
    val nodeId: Long,
    val teamId: Int,
    val profile: NodeProfile,
    val score: Double,
    val eligible: Boolean,
    val status: NodeStatus,
    val role: ElectionRole?,
    val coordinatorNodeId: Long?,
    val epochCounter: Long?,
    val epochInitiatorNodeId: Long?,
    val knownCandidates: Int,
    val error: String?
)

/**
 * Um nó simulado: conexão MQTT própria (com LWT e presença, como o app) e
 * uma thread exclusiva que executa o [ElectionNodeRuntime].
 */
class SimulatedNode(
    val nodeId: Long,
    val teamId: Int,
    profile: NodeProfile,
    private val brokerHost: String,
    private val brokerPort: Int
) {
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "sim-node-$nodeId").apply { isDaemon = true }
    }

    private val electionTopic = "rescuenet/teams/$teamId/election"
    private val presenceTopic = "rescuenet/teams/$teamId/nodes/$nodeId/presence"

    private val runtime = ElectionNodeRuntime(nodeId, teamId, profile) { message ->
        publishElection(message)
    }

    // Estado acessado somente pela thread do nó.
    private var client: Mqtt5AsyncClient? = null
    private var ticker: ScheduledFuture<*>? = null
    private var status = NodeStatus.CONNECTING
    private var lastError: String? = null
    private var session = 0

    private fun now(): Long = System.nanoTime() / 1_000_000

    fun connect() = executor.execute { connectOnThread() }

    /** Queda abrupta: o broker publica o LWT e os outros nós deixam de receber heartbeat. */
    fun crash() = executor.execute {
        disconnectOnThread()
        status = NodeStatus.CRASHED
    }

    fun reconnect() = executor.execute {
        if (status == NodeStatus.CRASHED || status == NodeStatus.FAILED) {
            connectOnThread()
        }
    }

    fun updateProfile(profile: NodeProfile) = executor.execute {
        // O próximo HELLO (até 5 s) leva as métricas novas à rede.
        runtime.profile = profile
    }

    /** Encerra a conexão (LWT "offline") e a thread do nó. */
    fun shutdown() {
        executor.execute { disconnectOnThread() }
        executor.shutdown()
        executor.awaitTermination(3, TimeUnit.SECONDS)
    }

    fun snapshot(): NodeSnapshot = executor.submit<NodeSnapshot> {
        val epoch = runtime.currentEpoch
        val metrics = runtime.profile.toMetrics()
        NodeSnapshot(
            nodeId = nodeId,
            teamId = teamId,
            profile = runtime.profile,
            score = AdaptiveBullyPolicy.calculateScore(metrics),
            eligible = runtime.profile.battery >= AdaptiveBullyPolicy.MINIMUM_BATTERY_PERCENT,
            status = status,
            role = runtime.role,
            coordinatorNodeId = runtime.coordinatorNodeId,
            epochCounter = epoch?.counter,
            epochInitiatorNodeId = epoch?.initiatorNodeId,
            knownCandidates = runtime.knownCandidateCount,
            error = lastError
        )
    }.get(2, TimeUnit.SECONDS)

    private fun connectOnThread() {
        session++
        val mySession = session
        status = NodeStatus.CONNECTING
        lastError = null

        val mqttClient = Mqtt5Client.builder()
            .identifier("rescuenet-sim-$nodeId")
            .serverHost(brokerHost)
            .serverPort(brokerPort)
            .buildAsync()

        client = mqttClient

        mqttClient.publishes(MqttGlobalPublishFilter.SUBSCRIBED) { publish ->
            val payload = String(publish.payloadAsBytes, StandardCharsets.UTF_8)
            executor.execute {
                if (session != mySession || status != NodeStatus.ONLINE) return@execute
                try {
                    runtime.onMessage(ElectionMessageCodec.decode(payload), now())
                } catch (error: Exception) {
                    lastError = "Mensagem de eleição inválida: ${error.message}"
                }
            }
        }

        mqttClient.connectWith()
            .willPublish()
                .topic(presenceTopic)
                .qos(MqttQos.AT_LEAST_ONCE)
                .payload(presencePayload("offline").toByteArray(StandardCharsets.UTF_8))
                .retain(true)
                .applyWillPublish()
            .cleanStart(true)
            .keepAlive(30)
            .send()
            .thenCompose {
                mqttClient.subscribeWith()
                    .topicFilter(electionTopic)
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .send()
            }
            .whenComplete { _, error ->
                executor.execute {
                    if (session != mySession) return@execute

                    if (error != null) {
                        status = NodeStatus.FAILED
                        lastError = "Falha ao conectar em $brokerHost:$brokerPort: ${error.message}"
                        return@execute
                    }

                    status = NodeStatus.ONLINE
                    mqttClient.publishWith()
                        .topic(presenceTopic)
                        .qos(MqttQos.AT_LEAST_ONCE)
                        .retain(true)
                        .payload(presencePayload("online").toByteArray(StandardCharsets.UTF_8))
                        .send()

                    runtime.start(now())
                    ticker = executor.scheduleAtFixedRate(
                        { if (session == mySession) runtime.tick(now()) },
                        1, 1, TimeUnit.SECONDS
                    )
                }
            }
    }

    private fun disconnectOnThread() {
        session++
        ticker?.cancel(false)
        ticker = null
        runtime.stop()

        val mqttClient = client ?: return
        client = null

        try {
            if (mqttClient.state.isConnected) {
                // Mesmo comportamento do app: o broker publica o LWT "offline".
                mqttClient.disconnectWith()
                    .reasonCode(Mqtt5DisconnectReasonCode.DISCONNECT_WITH_WILL_MESSAGE)
                    .send()
            }
        } catch (_: Exception) {
            // Conexão já encerrada.
        }
    }

    private fun publishElection(message: ElectionMessage) {
        val mqttClient = client ?: return
        if (status != NodeStatus.ONLINE) return

        mqttClient.publishWith()
            .topic(electionTopic)
            .qos(MqttQos.AT_LEAST_ONCE)
            .retain(false)
            .payload(ElectionMessageCodec.encode(message).toByteArray(StandardCharsets.UTF_8))
            .send()
    }

    private fun presencePayload(status: String): String =
        """{"team_id":$teamId,"node_id":$nodeId,"status":"$status","timestamp":${System.currentTimeMillis() / 1000}}"""
}

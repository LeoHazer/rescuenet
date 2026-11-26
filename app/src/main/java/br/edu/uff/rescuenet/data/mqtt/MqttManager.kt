
package br.edu.uff.rescuenet.data.mqtt

import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import com.hivemq.client.mqtt.mqtt5.message.disconnect.Mqtt5DisconnectReasonCode

import java.nio.charset.StandardCharsets

class MqttManager {

    private data class RegisteredSubscription(
        val onMessage: (String) -> Unit,
        val onError: (Throwable) -> Unit
    )

    @Volatile
    private var client: Mqtt5AsyncClient? = null

    /**
     * Identifica a conexão corrente. Um cliente de geração antiga (após
     * desconectar ou trocar de broker) deixa de reconectar e não notifica
     * mais a interface.
     */
    private val generation = AtomicInteger(0)

    private val subscriptions =
        ConcurrentHashMap<String, RegisteredSubscription>()

    /**
     * Estabelece uma conexão assíncrona com o broker MQTT,
     * habilitando a reconexão automática em caso de perda.
     */
    fun connect(
        host: String,
        port: Int = 1883,
        clientId: String,
        teamId: Int,
        nodeId: Long,
        onSuccess: () -> Unit,
        onError: (Throwable) -> Unit,
        onConnectionStateChanged: (Boolean, Throwable?) -> Unit = { _, _ -> }
    ) {
        try {
            val myGeneration = generation.incrementAndGet()
            client?.let { previous -> closeQuietly(previous) }

            lateinit var self: Mqtt5AsyncClient

            val mqttClient = Mqtt5Client.builder()
                .identifier(clientId)
                .serverHost(host)
                .serverPort(port)
                .automaticReconnectWithDefaultConfig()
                .addConnectedListener {
                    if (generation.get() != myGeneration) {
                        // Conectou depois de ter sido abandonado: encerra.
                        closeQuietly(self)
                        return@addConnectedListener
                    }

                    onConnectionStateChanged(true, null)

                    subscriptions.forEach { (topic, subscription) ->
                        subscribeOnClient(
                            self,
                            topic,
                            {},
                            subscription.onError
                        )
                    }
                }
                .addDisconnectedListener { context ->
                    if (generation.get() != myGeneration) {
                        context.reconnector.reconnect(false)
                        return@addDisconnectedListener
                    }

                    onConnectionStateChanged(false, context.cause)
                }
                .buildAsync()

            self = mqttClient

            client = mqttClient

            mqttClient.publishes(MqttGlobalPublishFilter.SUBSCRIBED) { publish ->
                val topic = publish.topic.toString()
                val payload = String(
                    publish.payloadAsBytes,
                    StandardCharsets.UTF_8
                )

                subscriptions[topic]?.let { subscription ->
                    try {
                        subscription.onMessage(payload)
                    } catch (exception: Exception) {
                        subscription.onError(exception)
                    }
                }
            }

            val offlinePayload = """{"team_id":$teamId,"node_id":$nodeId,"status":"offline","timestamp":${System.currentTimeMillis() / 1000}}"""

            mqttClient.connectWith()
                .willPublish()
                    .topic(MqttConfig.presenceTopic(teamId, nodeId))
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .payload(offlinePayload.toByteArray(StandardCharsets.UTF_8))
                    .retain(true)
                    .applyWillPublish()
                .cleanStart(true)
                .keepAlive(MqttConfig.KEEP_ALIVE_SECONDS)
                .send()
                .whenComplete { _, throwable ->
                    if (generation.get() != myGeneration) return@whenComplete

                    if (throwable != null) {
                        onError(throwable)
                    } else {
                        onSuccess()
                    }
                }

        } catch (exception: Exception) {
            onError(exception)
        }
    }

    /**
     * Encerra um cliente sem notificar a interface. Se ele estiver em
     * reconexão, o listener de desconexão interrompe as novas tentativas.
     */
    private fun closeQuietly(target: Mqtt5AsyncClient) {
        try {
            if (target.state.isConnected) {
                target.disconnectWith()
                    .reasonCode(Mqtt5DisconnectReasonCode.DISCONNECT_WITH_WILL_MESSAGE)
                    .send()
            }
        } catch (_: Exception) {
            // Cliente já encerrado.
        }
    }

    /**
     * Publica uma mensagem no tópico informado.
     */
    fun publish(
        topic: String,
        payload: String,
        retain: Boolean = false,
        onSuccess: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val mqttClient = client

        if (mqttClient == null) {
            onError(
                IllegalStateException("Cliente MQTT não inicializado.")
            )
            return
        }

        try {
            mqttClient.publishWith()
                .topic(topic)
                .qos(MqttQos.AT_LEAST_ONCE)
                .retain(retain)
                .payload(payload.toByteArray(StandardCharsets.UTF_8))
                .send()
                .whenComplete { _, throwable ->
                    if (throwable != null) {
                        onError(throwable)
                    } else {
                        onSuccess()
                    }
                }

        } catch (exception: Exception) {
            onError(exception)
        }
    }

    /**
     * Encerra a conexão com o broker.
     */

    /**
     * Inscreve o cliente em um tópico MQTT e registra o callback de mensagens.
     */
    fun subscribe(
        topic: String,
        onMessage: (String) -> Unit,
        onSuccess: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        val mqttClient = client

        if (mqttClient == null) {
            onError(
                IllegalStateException("Cliente MQTT não inicializado.")
            )
            return
        }

        if (topic.isBlank()) {
            onError(
                IllegalArgumentException("O tópico MQTT não pode ser vazio.")
            )
            return
        }

        subscriptions[topic] = RegisteredSubscription(
            onMessage = onMessage,
            onError = onError
        )

        subscribeOnClient(mqttClient, topic, onSuccess, onError)
    }

    /**
     * Executa a inscrição no broker MQTT com QoS 1.
     */
    private fun subscribeOnClient(
        mqttClient: Mqtt5AsyncClient,
        topic: String,
        onSuccess: () -> Unit,
        onError: (Throwable) -> Unit
    ) {
        try {
            mqttClient.subscribeWith()
                .topicFilter(topic)
                .qos(MqttQos.AT_LEAST_ONCE)
                .send()
                .whenComplete { _, throwable ->
                    if (throwable != null) {
                        onError(throwable)
                    } else {
                        onSuccess()
                    }
                }

        } catch (exception: Exception) {
            onError(exception)
        }
    }
    fun disconnect(
        onComplete: () -> Unit = {},
        onError: (Throwable) -> Unit = {}
    ) {
        val mqttClient = client

        // Invalida a conexão corrente: se ela estiver em reconexão (por
        // exemplo, com um endereço errado), as tentativas param.
        generation.incrementAndGet()
        client = null

        if (mqttClient == null || !mqttClient.state.isConnected) {
            onComplete()
            return
        }

        try {
            // Um DISCONNECT normal descarta o LWT e deixaria a presença
            // retida como "online". Com este código de motivo, o broker
            // publica a mensagem "offline" mesmo no encerramento voluntário.
            mqttClient.disconnectWith()
                .reasonCode(Mqtt5DisconnectReasonCode.DISCONNECT_WITH_WILL_MESSAGE)
                .send()
                .whenComplete { _, throwable ->
                    if (throwable != null) {
                        onError(throwable)
                    } else {
                        onComplete()
                    }
                }

        } catch (exception: Exception) {
            onError(exception)
        }
    }
}

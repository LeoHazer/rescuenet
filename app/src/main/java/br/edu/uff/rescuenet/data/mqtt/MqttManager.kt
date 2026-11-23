
package br.edu.uff.rescuenet.data.mqtt

import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import java.util.concurrent.ConcurrentHashMap
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client

import java.nio.charset.StandardCharsets

class MqttManager {

    private data class RegisteredSubscription(
        val onMessage: (String) -> Unit,
        val onError: (Throwable) -> Unit
    )

    private var client: Mqtt5AsyncClient? = null

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
            val mqttClient = Mqtt5Client.builder()
                .identifier(clientId)
                .serverHost(host)
                .serverPort(port)
                .automaticReconnectWithDefaultConfig()
                .addConnectedListener {
                    onConnectionStateChanged(true, null)

                    client?.let { activeClient ->
                        subscriptions.forEach { (topic, subscription) ->
                            subscribeOnClient(
                                activeClient,
                                topic,
                                {},
                                subscription.onError
                            )
                        }
                    }
                }
                .addDisconnectedListener { context ->
                    onConnectionStateChanged(false, context.cause)
                }
                .buildAsync()

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

        if (mqttClient == null) {
            onComplete()
            return
        }

        try {
            mqttClient.disconnect()
                .whenComplete { _, throwable ->
                    client = null

                    if (throwable != null) {
                        onError(throwable)
                    } else {
                        onComplete()
                    }
                }

        } catch (exception: Exception) {
            client = null
            onError(exception)
        }
    }
}

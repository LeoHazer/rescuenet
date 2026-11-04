
package br.edu.uff.rescuenet.data.mqtt

import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client


import java.nio.charset.StandardCharsets

class MqttManager {

    private var client: Mqtt5AsyncClient? = null

    /**
     * Estabelece uma conexão assíncrona com o broker MQTT.
     */
    fun connect(
        host: String,
        port: Int = 1883,
        clientId: String,
        onSuccess: () -> Unit,
        onError: (Throwable) -> Unit

    ) {
        try {
            val mqttClient = Mqtt5Client.builder()
                .identifier(clientId)
                .serverHost(host)
                .serverPort(port)
                .buildAsync()

            client = mqttClient

            mqttClient.connectWith()
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

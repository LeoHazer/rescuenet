package br.edu.uff.rescuenet.presentation.viewmodel

import androidx.lifecycle.ViewModel
import br.edu.uff.rescuenet.data.mqtt.MqttConfig
import br.edu.uff.rescuenet.data.mqtt.MqttManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class RescueNetUiState(
    val operationalStatus: String = "Em operação",
    val selectedAction: String = "",
    val emergencyActivated: Boolean = false,
    val mqttConnecting: Boolean = false,
    val mqttConnected: Boolean = false,
    val mqttMessage: String = "Desconectado",
    val mqttError: String? = null,
    val lastPublishedMessage: String? = null
)

class RescueNetViewModel : ViewModel() {

    private val mqttManager = MqttManager()

    private val _uiState = MutableStateFlow(RescueNetUiState())
    val uiState: StateFlow<RescueNetUiState> = _uiState.asStateFlow()

    fun updateOperationalStatus(status: String) {
        _uiState.update { it.copy(operationalStatus = status) }
    }

    fun selectAction(action: String) {
        _uiState.update { it.copy(selectedAction = action) }
    }

    fun toggleEmergency() {
        _uiState.update {
            it.copy(emergencyActivated = !it.emergencyActivated)
        }
    }

    fun connectMqtt() {
        val currentState = _uiState.value

        if (currentState.mqttConnecting || currentState.mqttConnected) {
            return
        }

        _uiState.update {
            it.copy(
                mqttConnecting = true,
                mqttError = null,
                mqttMessage = "Conectando ao broker..."
            )
        }

        mqttManager.connect(
            host = MqttConfig.BROKER_HOST,
            port = MqttConfig.BROKER_PORT,
            clientId = MqttConfig.clientId(MqttConfig.TEAM_ID),
            onSuccess = {
                _uiState.update {
                    it.copy(
                        mqttConnecting = false,
                        mqttConnected = true,
                        mqttMessage = "Conectado ao broker",
                        mqttError = null
                    )
                }
            },
            onError = { error ->
                _uiState.update {
                    it.copy(
                        mqttConnecting = false,
                        mqttConnected = false,
                        mqttMessage = "Falha na conexão",
                        mqttError = error.message ?: "Erro desconhecido"
                    )
                }
            }
        )
    }

    fun publishTestLocation() {
        if (!_uiState.value.mqttConnected) {
            _uiState.update {
                it.copy(mqttError = "Conecte-se ao broker antes de publicar.")
            }
            return
        }

        val timestamp = System.currentTimeMillis() / 1000

        val payload = """
            {
                "team_id": ${MqttConfig.TEAM_ID},
                "latitude": -22.526,
                "longitude": -41.945,
                "accuracy": 8.5,
                "battery": 78,
                "timestamp": $timestamp
            }
        """.trimIndent()

        mqttManager.publish(
            topic = MqttConfig.LOCATION_TOPIC,
            payload = payload,
            onSuccess = {
                _uiState.update {
                    it.copy(
                        mqttMessage = "Localização de teste publicada",
                        mqttError = null,
                        lastPublishedMessage = payload
                    )
                }
            },
            onError = { error ->
                _uiState.update {
                    it.copy(
                        mqttMessage = "Falha ao publicar",
                        mqttError = error.message ?: "Erro desconhecido"
                    )
                }
            }
        )
    }

    override fun onCleared() {
        mqttManager.disconnect()
        super.onCleared()
    }
}
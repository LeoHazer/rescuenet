package br.edu.uff.rescuenet.presentation.viewmodel

import androidx.lifecycle.ViewModel
import android.content.Context
import android.location.Location
import br.edu.uff.rescuenet.data.location.LocationTracker
import br.edu.uff.rescuenet.data.mqtt.MqttConfig
import br.edu.uff.rescuenet.data.mqtt.MqttManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class RescueNetUiState(
    val operationalStatus: String = "Em operação",
    val selectedAction: String = "",

    // Dados da localização GPS
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracy: Float? = null,
    val locationMessage: String = "Aguardando autorização de localização",
    val locationTracking: Boolean = false,

    //Os dados abaixo são mantidos

    val emergencyActivated: Boolean = false,
    val mqttConnecting: Boolean = false,
    val mqttConnected: Boolean = false,
    val mqttMessage: String = "Desconectado",
    val mqttError: String? = null,
    val lastPublishedMessage: String? = null
)

class RescueNetViewModel : ViewModel() {

    private val mqttManager = MqttManager()

    private var locationTracker: LocationTracker? = null
    private val _uiState = MutableStateFlow(RescueNetUiState())
    val uiState: StateFlow<RescueNetUiState> = _uiState.asStateFlow()


    fun startLocationTracking(context: Context) {
        if (locationTracker != null) {
            return
        }

        val tracker = LocationTracker(context.applicationContext)
        locationTracker = tracker

        _uiState.update {
            it.copy(
                locationTracking = true,
                locationMessage = "Buscando localização..."
            )
        }

        tracker.startLocationUpdates(
            onLocationReceived = { location ->
                _uiState.update {
                    it.copy(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        locationAccuracy = location.accuracy,
                        locationTracking = true,
                        locationMessage = "Localização atualizada"
                    )
                }
            },
            onError = { exception ->
                tracker.stopLocationUpdates()
                locationTracker = null

                _uiState.update {
                    it.copy(
                        locationTracking = false,
                        locationMessage = exception.message
                            ?: "Falha ao obter localização"
                    )
                }
            }
        )
    }



    fun stopLocationTracking() {
        locationTracker?.stopLocationUpdates()
        locationTracker = null

        _uiState.update {
            it.copy(
                locationTracking = false,
                locationMessage = "Rastreamento interrompido"
            )
        }
    }


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




fun publishCurrentLocation() {
    val currentState = _uiState.value

    if (!currentState.mqttConnected) {
        _uiState.update {
            it.copy(mqttError = "Conecte-se ao broker antes de publicar.")
        }
        return
    }

    val latitude = currentState.latitude
    val longitude = currentState.longitude
    val accuracy = currentState.locationAccuracy

    if (latitude == null || longitude == null || accuracy == null) {
        _uiState.update {
            it.copy(mqttError = "Localização GPS ainda não disponível.")
        }
        return
    }

    val timestamp = System.currentTimeMillis() / 1000

    val payload = """
        {
            "team_id": ${MqttConfig.TEAM_ID},
            "latitude": $latitude,
            "longitude": $longitude,
            "accuracy": $accuracy,
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
                    mqttMessage = "Localização GPS real publicada",
                    mqttError = null,
                    lastPublishedMessage = payload
                )
            }
        },
        onError = { error ->
            _uiState.update {
                it.copy(
                    mqttMessage = "Falha ao publicar localização GPS",
                    mqttError = error.message ?: "Erro desconhecido"
                )
            }
        }
    )
}


    override fun onCleared() {
    stopLocationTracking()
    mqttManager.disconnect()
    super.onCleared()
}
}
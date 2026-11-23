package br.edu.uff.rescuenet.presentation.viewmodel

import androidx.lifecycle.ViewModel
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import br.edu.uff.rescuenet.data.election.AdaptiveBullyElection
import br.edu.uff.rescuenet.data.election.ElectionMessage
import br.edu.uff.rescuenet.data.election.ElectionRole
import android.content.Context
import android.os.BatteryManager
import android.app.ActivityManager
import android.location.Location
import br.edu.uff.rescuenet.data.location.LocationTracker
import br.edu.uff.rescuenet.data.election.AdaptiveBullyPolicy
import br.edu.uff.rescuenet.data.election.ElectionEpoch
import br.edu.uff.rescuenet.data.election.ElectionMetrics
import br.edu.uff.rescuenet.data.election.HelloMessage
import br.edu.uff.rescuenet.data.election.ElectionMessageCodec
import br.edu.uff.rescuenet.data.mqtt.MqttConfig
import br.edu.uff.rescuenet.data.mqtt.NodeIdentity
import br.edu.uff.rescuenet.data.mqtt.MqttManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

data class RescueNetUiState(
    val operationalStatus: String = "Em operação",
    val selectedAction: String = "",

    // Dados da localização GPS
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracy: Float? = null,
    val locationMessage: String = "Aguardando autorização de localização",
    val locationTracking: Boolean = false,
    val batteryLevel: Int? = null,

    //Os dados abaixo são mantidos

    val emergencyActivated: Boolean = false,
    val mqttConnecting: Boolean = false,
    val mqttConnected: Boolean = false,
    val mqttMessage: String = "Desconectado",
    val mqttError: String? = null,
    val lastPublishedMessage: String? = null,
    val lastElectionMessage: String? = null,
    val discoveredNodeCount: Int = 0,
    val localElectionScore: Double? = null
)

class RescueNetViewModel : ViewModel() {

    private val mqttManager = MqttManager()
    private val electionHandler = Handler(Looper.getMainLooper())
    private var electionMachine: AdaptiveBullyElection? = null
    private var electionTask: Runnable? = null
    private var electionNodeId: Long? = null
    private var electionTeamId: Int? = null
    private var electionContext: Context? = null
    private var lastHelloAtMillis = 0L
    private var lastHeartbeatAtMillis = 0L
    private var initialElectionAtMillis = 0L

    private var electionSubscriptionRegistered = false
    private val discoveredCandidates = ConcurrentHashMap<Long, HelloMessage>()

    private var locationTracker: LocationTracker? = null
    private val _uiState = MutableStateFlow(RescueNetUiState())
    val uiState: StateFlow<RescueNetUiState> = _uiState.asStateFlow()


    private fun readBatteryLevel(context: Context): Int? {
        val batteryManager = context.applicationContext
            .getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return null

        val level = batteryManager.getIntProperty(
            BatteryManager.BATTERY_PROPERTY_CAPACITY
        )

        return level.takeIf { it in 0..100 }
    }

    fun refreshBatteryLevel(context: Context) {
        val level = readBatteryLevel(context)

        _uiState.update {
            it.copy(batteryLevel = level)
        }
    }

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



    private fun collectElectionMetrics(context: Context): ElectionMetrics {
        val battery = readBatteryLevel(context)?.toDouble() ?: 0.0

        val memoryInfo = ActivityManager.MemoryInfo()
        val activityManager = context.applicationContext
            .getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager

        activityManager?.getMemoryInfo(memoryInfo)

        val availableMemory = if (memoryInfo.totalMem > 0L) {
            (memoryInfo.availMem.toDouble() / memoryInfo.totalMem.toDouble()) * 100.0
        } else {
            0.0
        }

        return ElectionMetrics(
            connectionStability = 50.0,
            battery = battery,
            availability = 100.0,
            connectivity = 100.0,
            computationalResources = availableMemory.coerceIn(0.0, 100.0),
            dataEfficiency = 50.0
        )
    }

    private fun publishHello(context: Context, teamId: Int, nodeId: Long) {
        if (!_uiState.value.mqttConnected) return

        try {
            val metrics = collectElectionMetrics(context)
            val hello = HelloMessage(
                teamId = teamId,
                nodeId = nodeId,
                epoch = electionMachine?.currentEpoch ?: ElectionEpoch(1L, nodeId),
                timestamp = System.currentTimeMillis() / 1000,
                metrics = metrics
            )

            electionMachine?.updateLocalCandidate(hello)

            val payload = ElectionMessageCodec.encode(hello)

            mqttManager.publish(
                topic = MqttConfig.ELECTION_TOPIC,
                payload = payload,
                retain = false,
                onSuccess = {
                    _uiState.update {
                        it.copy(
                            localElectionScore = hello.score,
                            lastElectionMessage = payload
                        )
                    }
                },
                onError = { error ->
                    _uiState.update {
                        it.copy(
                            mqttError = "Falha ao publicar HELLO: ${error.message ?: "Erro desconhecido"}"
                        )
                    }
                }
            )
        } catch (error: Exception) {
            _uiState.update {
                it.copy(
                    mqttError = "Falha ao preparar HELLO: ${error.message ?: "Erro desconhecido"}"
                )
            }
        }
    }
    private fun publishElectionMessages(messages: List<ElectionMessage>) {
        if (!_uiState.value.mqttConnected) return

        messages.forEach { message ->
            try {
                val payload = ElectionMessageCodec.encode(message)

                mqttManager.publish(
                    topic = MqttConfig.ELECTION_TOPIC,
                    payload = payload,
                    retain = false,
                    onSuccess = {
                        _uiState.update {
                            it.copy(lastElectionMessage = payload)
                        }
                    },
                    onError = { error ->
                        _uiState.update {
                            it.copy(
                                mqttError = "Falha ao publicar mensagem de eleição: ${error.message ?: "Erro desconhecido"}"
                            )
                        }
                    }
                )
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        mqttError = "Falha ao codificar mensagem de eleição: ${error.message ?: "Erro desconhecido"}"
                    )
                }
            }
        }
    }

    private fun handleElectionPayload(payload: String) {
        electionHandler.post {
            if (!_uiState.value.mqttConnected) return@post

            try {
                val message = ElectionMessageCodec.decode(payload)
                val machine = electionMachine ?: return@post

                if (message.teamId != electionTeamId ||
                    message.nodeId == electionNodeId
                ) {
                    return@post
                }

                val now = SystemClock.elapsedRealtime()
                val responses = machine.receive(message, now)

                if (message is HelloMessage) {
                    discoveredCandidates[message.nodeId] = message

                    _uiState.update {
                        it.copy(
                            lastElectionMessage = payload,
                            discoveredNodeCount = discoveredCandidates.size
                        )
                    }

                    if (machine.role == ElectionRole.COORDINATOR) {
                        publishElectionMessages(
                            machine.evaluateCoordinatorChallenge(message.nodeId, now)
                        )
                    }
                }

                publishElectionMessages(responses)
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        mqttError = "Mensagem de eleição inválida: ${error.message ?: "Erro desconhecido"}"
                    )
                }
            }
        }
    }

    private fun startElectionSession(
        context: Context,
        teamId: Int,
        nodeId: Long
    ) {
        electionHandler.post {
            electionContext = context.applicationContext
            electionTeamId = teamId
            electionNodeId = nodeId
            electionMachine = AdaptiveBullyElection(
                teamId = teamId,
                localNodeId = nodeId
            )

            discoveredCandidates.clear()

            _uiState.update {
                it.copy(discoveredNodeCount = 0)
            }

            publishHello(context.applicationContext, teamId, nodeId)

            val now = SystemClock.elapsedRealtime()
            initialElectionAtMillis = now + 6000L

            lastHelloAtMillis = now
            lastHeartbeatAtMillis = now

            startElectionScheduler()
        }
    }

    private fun startElectionScheduler() {
        electionTask?.let { electionHandler.removeCallbacks(it) }

        val task = object : Runnable {
            override fun run() {
                val machine = electionMachine
                val context = electionContext
                val nodeId = electionNodeId
                val teamId = electionTeamId

                if (machine == null || context == null ||
                    nodeId == null || teamId == null ||
                    !_uiState.value.mqttConnected
                ) {
                    return
                }

                val now = SystemClock.elapsedRealtime()

                machine.pruneExpiredCandidates(now)

                if (now - lastHelloAtMillis >= 5000L) {
                    publishHello(context, teamId, nodeId)
                    lastHelloAtMillis = now
                }

                if (initialElectionAtMillis > 0L && now >= initialElectionAtMillis) {
                    initialElectionAtMillis = 0L
                    publishElectionMessages(machine.beginElection(now))
                }

                publishElectionMessages(machine.onElectionTimeout(now))
                publishElectionMessages(machine.onCoordinatorTimeout(now))

                if (machine.role == ElectionRole.COORDINATOR &&
                    now - lastHeartbeatAtMillis >= 5000L
                ) {
                    machine.createHeartbeat(now)?.let {
                        publishElectionMessages(listOf(it))
                    }
                    lastHeartbeatAtMillis = now
                }

                electionHandler.postDelayed(this, 1000L)
            }
        }

        electionTask = task
        electionHandler.postDelayed(task, 1000L)
    }

    private fun stopElectionScheduler() {
        electionTask?.let { electionHandler.removeCallbacks(it) }
        electionTask = null
    }
    fun connectMqtt(context: Context) {
        val currentState = _uiState.value

        if (currentState.mqttConnecting || currentState.mqttConnected) {
            return
        }

        val nodeId = NodeIdentity.getNodeId(context)
        val teamId = MqttConfig.TEAM_ID

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
            clientId = MqttConfig.nodeClientId(nodeId),
            teamId = teamId,
            nodeId = nodeId,
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
            },
            onConnectionStateChanged = { connected, error ->
                if (connected) {
                    _uiState.update {
                        it.copy(
                            mqttConnecting = false,
                            mqttConnected = true,
                            mqttMessage = "Conectado ao broker",
                            mqttError = null
                        )
                    }

                    if (!electionSubscriptionRegistered) {
                        electionSubscriptionRegistered = true

                        mqttManager.subscribe(
                            topic = MqttConfig.ELECTION_TOPIC,
                            onMessage = { message ->
                                handleElectionPayload(message)
                            },
                            onSuccess = {
                                startElectionSession(context.applicationContext, teamId, nodeId)
                            },
                            onError = { subscriptionError ->
                                electionSubscriptionRegistered = false

                                _uiState.update {
                                    it.copy(
                                        mqttError = "Falha ao inscrever no tópico de eleição: ${subscriptionError.message ?: "Erro desconhecido"}"
                                    )
                                }
                            }
                        )
                    } else {
                        startElectionSession(context.applicationContext, teamId, nodeId)
                    }

                    val presencePayload = """{"team_id":$teamId,"node_id":$nodeId,"status":"online","timestamp":${System.currentTimeMillis() / 1000}}"""

                    mqttManager.publish(
                        topic = MqttConfig.presenceTopic(teamId, nodeId),
                        payload = presencePayload,
                        retain = true,
                        onSuccess = {},
                        onError = { presenceError ->
                            _uiState.update {
                                it.copy(
                                    mqttError = "Falha ao publicar presença: ${presenceError.message ?: "Erro desconhecido"}"
                                )
                            }
                        }
                    )
                } else {
                    electionHandler.post {
                        stopElectionScheduler()
                        electionMachine = null
                    }

                    _uiState.update {
                        it.copy(
                            mqttConnecting = true,
                            mqttConnected = false,
                            mqttMessage = "Conexão perdida. Tentando reconectar...",
                            mqttError = error?.message
                                ?: "Aguardando reconexão com o broker."
                        )
                    }
                }
            }
        )
    }

    fun publishTestLocation(context: Context) {
        if (!_uiState.value.mqttConnected) {
            _uiState.update {
                it.copy(mqttError = "Conecte-se ao broker antes de publicar.")
            }
            return
        }

        val batteryLevel = readBatteryLevel(context)

        if (batteryLevel == null) {
            _uiState.update {
                it.copy(
                    batteryLevel = null,
                    mqttError = "Não foi possível obter o nível da bateria."
                )
            }
            return
        }

        _uiState.update {
            it.copy(batteryLevel = batteryLevel)
        }

        val timestamp = System.currentTimeMillis() / 1000

        val payload = """
            {
                "team_id": ${MqttConfig.TEAM_ID},
                "latitude": -22.526,
                "longitude": -41.945,
                "accuracy": 8.5,
                "battery": $batteryLevel,
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

    fun publishCurrentLocation(context: Context) {
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

        val batteryLevel = readBatteryLevel(context)

        if (batteryLevel == null) {
            _uiState.update {
                it.copy(
                    batteryLevel = null,
                    mqttError = "Não foi possível obter o nível da bateria."
                )
            }
            return
        }

        _uiState.update {
            it.copy(batteryLevel = batteryLevel)
        }

        val timestamp = System.currentTimeMillis() / 1000

        val payload = """
            {
                "team_id": ${MqttConfig.TEAM_ID},
                "latitude": $latitude,
                "longitude": $longitude,
                "accuracy": $accuracy,
                "battery": $batteryLevel,
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
        stopElectionScheduler()
        electionHandler.removeCallbacksAndMessages(null)
        stopLocationTracking()
        mqttManager.disconnect()
        super.onCleared()
    }
}
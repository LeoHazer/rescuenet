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
import br.edu.uff.rescuenet.data.location.AutoPublishThrottle
import br.edu.uff.rescuenet.data.location.LocationForegroundService
import br.edu.uff.rescuenet.data.location.LocationPayload
import br.edu.uff.rescuenet.data.location.LocationTracker
import br.edu.uff.rescuenet.data.election.AdaptiveBullyPolicy
import br.edu.uff.rescuenet.data.election.ElectionEpoch
import br.edu.uff.rescuenet.data.election.ElectionMetrics
import br.edu.uff.rescuenet.data.election.HelloMessage
import br.edu.uff.rescuenet.data.election.ElectionMessageCodec
import br.edu.uff.rescuenet.data.mqtt.BrokerAddress
import br.edu.uff.rescuenet.data.mqtt.BrokerSettings
import br.edu.uff.rescuenet.data.mqtt.MqttConfig
import br.edu.uff.rescuenet.data.mqtt.NodeIdentity
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
    val batteryLevel: Int? = null,
    // Horário (relógio do aparelho, em ms) do último envio automático aceito pelo broker.
    val lastAutoLocationPublishedAt: Long? = null,

    //Os dados abaixo são mantidos

    val emergencyActivated: Boolean = false,
    val brokerAddress: String = "",
    val mqttConnecting: Boolean = false,
    val mqttConnected: Boolean = false,
    val mqttMessage: String = "Desconectado",
    val mqttError: String? = null,
    val lastPublishedMessage: String? = null,
    val lastElectionMessage: String? = null,
    val discoveredNodeCount: Int = 0,
    val localElectionScore: Double? = null,

    // Estado da eleição exibido na tela
    val localNodeId: Long? = null,
    val electionRole: ElectionRole? = null,
    val coordinatorNodeId: Long? = null,
    val electionEpoch: ElectionEpoch? = null
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

    private var locationTracker: LocationTracker? = null

    // Contexto da aplicação usado para parar o serviço em primeiro plano;
    // nulo quando o serviço não está ativo.
    private var foregroundServiceContext: Context? = null

    // Envio automático: no máximo uma posição a cada 10 s, a partir do
    // callback do GPS (que entrega a cada ~5 s). Acessado só na main thread.
    private val autoPublishThrottle = AutoPublishThrottle()

    // O rastreamento liga sozinho uma única vez por ViewModel, para não
    // religar após o usuário interromper (por exemplo, ao girar a tela).
    private var locationAutoStartAttempted = false

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

        val appContext = context.applicationContext
        val tracker = LocationTracker(appContext)
        locationTracker = tracker

        // Mantém o GPS entregando posições com a tela bloqueada.
        val serviceError = LocationForegroundService.start(appContext)
        foregroundServiceContext = appContext.takeIf { serviceError == null }

        _uiState.update {
            it.copy(
                locationTracking = true,
                locationMessage = if (serviceError == null) {
                    "Buscando localização..."
                } else {
                    "Buscando localização (sem serviço em segundo plano: $serviceError)"
                }
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

                maybeAutoPublishLocation(appContext)
            },
            onError = { exception ->
                tracker.stopLocationUpdates()
                locationTracker = null
                stopForegroundLocationService()

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



    /**
     * Liga o rastreamento ao abrir a tela, se a permissão já foi concedida
     * (a verificação fica na Activity). Só tenta uma vez por ViewModel.
     */
    fun autoStartLocationTracking(context: Context) {
        if (locationAutoStartAttempted) return

        locationAutoStartAttempted = true
        startLocationTracking(context)
    }

    /**
     * Chamado a cada posição do GPS (main thread). Publica se houver conexão
     * e o intervalo do envio automático já tiver passado.
     */
    private fun maybeAutoPublishLocation(context: Context) {
        val state = _uiState.value

        if (!state.mqttConnected || state.latitude == null) return
        if (!autoPublishThrottle.tryAcquire(SystemClock.elapsedRealtime())) return

        publishLocation(context, automatic = true)
    }

    private fun stopForegroundLocationService() {
        foregroundServiceContext?.let { LocationForegroundService.stop(it) }
        foregroundServiceContext = null
    }

    fun stopLocationTracking() {
        locationTracker?.stopLocationUpdates()
        locationTracker = null
        stopForegroundLocationService()

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
                val isNewcomer = message is HelloMessage &&
                    !machine.isKnownCandidate(message.nodeId)
                val responses = machine.receive(message, now)

                if (message is HelloMessage) {
                    _uiState.update {
                        it.copy(lastElectionMessage = payload)
                    }

                    if (machine.role == ElectionRole.COORDINATOR) {
                        if (isNewcomer) {
                            announceToNewcomer(machine, now)
                        }

                        publishElectionMessages(
                            machine.evaluateCoordinatorChallenge(message.nodeId, now)
                        )
                    }
                }

                publishElectionMessages(responses)
                syncElectionState()
            } catch (error: Exception) {
                _uiState.update {
                    it.copy(
                        mqttError = "Mensagem de eleição inválida: ${error.message ?: "Erro desconhecido"}"
                    )
                }
            }
        }
    }

    /**
     * Um nó acabou de aparecer. O coordenador reenvia HELLO e HEARTBEAT na
     * hora, para que o recém-chegado o reconheça dentro da janela de
     * descoberta em vez de iniciar uma eleição própria.
     */
    private fun announceToNewcomer(machine: AdaptiveBullyElection, now: Long) {
        val context = electionContext ?: return
        val teamId = electionTeamId ?: return
        val nodeId = electionNodeId ?: return

        publishHello(context, teamId, nodeId)
        lastHelloAtMillis = now

        machine.createHeartbeat(now)?.let {
            publishElectionMessages(listOf(it))
            lastHeartbeatAtMillis = now
        }
    }

    private fun syncElectionState() {
        val machine = electionMachine

        _uiState.update {
            it.copy(
                electionRole = machine?.role,
                coordinatorNodeId = machine?.coordinatorNodeId,
                electionEpoch = machine?.currentEpoch,
                discoveredNodeCount = machine?.knownCandidateCount ?: 0
            )
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

            syncElectionState()

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
                    // Só elege se nenhum coordenador foi reconhecido na janela.
                    publishElectionMessages(machine.startInitialElection(now))
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

                syncElectionState()
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
    /** Carrega o último endereço de broker salvo no aparelho. */
    fun loadBrokerAddress(context: Context) {
        if (_uiState.value.brokerAddress.isNotEmpty()) return

        val saved = BrokerSettings.load(context)
        _uiState.update { it.copy(brokerAddress = saved) }
    }

    fun updateBrokerAddress(address: String) {
        _uiState.update { it.copy(brokerAddress = address) }
    }

    /**
     * Encerra a conexão por iniciativa do usuário (também cancela uma
     * reconexão em andamento, como a de um endereço digitado errado).
     * O broker publica a presença "offline" (LWT).
     */
    fun disconnectMqtt() {
        electionHandler.post {
            stopElectionScheduler()
            electionMachine = null
            syncElectionState()
        }

        mqttManager.disconnect()

        _uiState.update {
            it.copy(
                mqttConnecting = false,
                mqttConnected = false,
                mqttMessage = "Desconectado",
                mqttError = null
            )
        }
    }

    fun connectMqtt(context: Context) {
        val currentState = _uiState.value

        if (currentState.mqttConnecting || currentState.mqttConnected) {
            return
        }

        val broker = BrokerAddress.parse(currentState.brokerAddress)

        if (broker == null) {
            _uiState.update {
                it.copy(
                    mqttError = "Endereço do broker inválido. Use, por exemplo, 192.168.0.10 ou 192.168.0.10:1883."
                )
            }
            return
        }

        BrokerSettings.save(context, broker)

        val nodeId = NodeIdentity.getNodeId(context)
        val teamId = MqttConfig.TEAM_ID

        _uiState.update {
            it.copy(
                localNodeId = nodeId,
                brokerAddress = broker.toString(),
                mqttConnecting = true,
                mqttError = null,
                mqttMessage = "Conectando a $broker..."
            )
        }

        mqttManager.connect(
            host = broker.host,
            port = broker.port,
            clientId = MqttConfig.nodeClientId(nodeId),
            teamId = teamId,
            nodeId = nodeId,
            onSuccess = {
                _uiState.update {
                    it.copy(
                        mqttConnecting = false,
                        mqttConnected = true,
                        mqttMessage = "Conectado ao broker $broker",
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
                            mqttMessage = "Conectado ao broker $broker",
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

                    // Ao (re)conectar, a posição já conhecida sai sem esperar o intervalo.
                    electionHandler.post {
                        autoPublishThrottle.reset()
                        maybeAutoPublishLocation(context.applicationContext)
                    }
                } else {
                    electionHandler.post {
                        stopElectionScheduler()
                        electionMachine = null
                        syncElectionState()
                    }

                    _uiState.update {
                        it.copy(
                            mqttConnecting = true,
                            mqttConnected = false,
                            mqttMessage = "Sem conexão com $broker. Tentando reconectar...",
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

        val payload = LocationPayload.encode(
            teamId = MqttConfig.TEAM_ID,
            nodeId = NodeIdentity.getNodeId(context),
            latitude = -22.526,
            longitude = -41.945,
            accuracy = 8.5f,
            battery = batteryLevel,
            timestampSeconds = System.currentTimeMillis() / 1000
        )

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

    /** Envio imediato, pelo botão "Enviar localização GPS real". */
    fun publishCurrentLocation(context: Context) {
        publishLocation(context, automatic = false)
    }

    /**
     * Publica a posição GPS atual. No envio automático, falhas de pré-condição
     * são silenciosas (o próximo callback do GPS tenta de novo) e o sucesso
     * não sobrescreve as mensagens de estado da conexão.
     */
    private fun publishLocation(context: Context, automatic: Boolean) {
        val currentState = _uiState.value

        if (!currentState.mqttConnected) {
            if (!automatic) {
                _uiState.update {
                    it.copy(mqttError = "Conecte-se ao broker antes de publicar.")
                }
            }
            return
        }

        val latitude = currentState.latitude
        val longitude = currentState.longitude
        val accuracy = currentState.locationAccuracy

        if (latitude == null || longitude == null || accuracy == null) {
            if (!automatic) {
                _uiState.update {
                    it.copy(mqttError = "Localização GPS ainda não disponível.")
                }
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

        val payload = LocationPayload.encode(
            teamId = MqttConfig.TEAM_ID,
            nodeId = NodeIdentity.getNodeId(context),
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            battery = batteryLevel,
            timestampSeconds = System.currentTimeMillis() / 1000
        )

        mqttManager.publish(
            topic = MqttConfig.LOCATION_TOPIC,
            payload = payload,
            onSuccess = {
                _uiState.update {
                    if (automatic) {
                        it.copy(
                            lastAutoLocationPublishedAt = System.currentTimeMillis(),
                            lastPublishedMessage = payload
                        )
                    } else {
                        it.copy(
                            mqttMessage = "Localização GPS real publicada",
                            mqttError = null,
                            lastPublishedMessage = payload
                        )
                    }
                }
            },
            onError = { error ->
                _uiState.update {
                    if (automatic) {
                        it.copy(
                            mqttError = "Falha no envio automático da localização: ${error.message ?: "Erro desconhecido"}"
                        )
                    } else {
                        it.copy(
                            mqttMessage = "Falha ao publicar localização GPS",
                            mqttError = error.message ?: "Erro desconhecido"
                        )
                    }
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
package br.edu.uff.rescuenet


import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

import android.os.Build
import android.os.Bundle
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import br.edu.uff.rescuenet.data.election.ElectionRole
import br.edu.uff.rescuenet.presentation.viewmodel.RescueNetViewModel
import br.edu.uff.rescuenet.ui.theme.RescueNetTheme

//Estes imports permitem que os componentes fiquem visíveis em telas menores
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember


// Identidade visual do RescueNet
private val RescueBlue = Color(0xFF2563EB)
private val EmergencyRed = Color(0xFFE5484D)
private val OnlineGreen = Color(0xFF16A34A)
private val AttentionAmber = Color(0xFFF59E0B)
private val BackgroundColor = Color(0xFFFFFFFF)
private val SurfaceColor = Color(0xFFF9FAFB)
private val MainTextColor = Color(0xFF111827)
private val SecondaryTextColor = Color(0xFF4B5563)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val viewModel = ViewModelProvider(this)[
            RescueNetViewModel::class.java
        ]

        setContent {
            RescueNetTheme {
                RescueNetScreen(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun RescueNetScreen(viewModel: RescueNetViewModel) {
    val uiState by viewModel.uiState.collectAsState()

         val context = LocalContext.current

    LaunchedEffect(context, viewModel) {
        viewModel.refreshBatteryLevel(context)
        viewModel.loadBrokerAddress(context)

        // Com a permissão já concedida, o GPS liga sozinho ao abrir a tela.
        // Sem ela, o usuário segue pelo botão "Ativar localização".
        if (hasLocationPermission(context)) {
            viewModel.autoStartLocationTracking(context)
        }
    }

    // A partir do Android 17 (API 37), acessar a rede local (o IP do
    // computador com o broker) exige a permissão ACCESS_LOCAL_NETWORK.
    // Mesmo se negada, a tentativa segue e o erro aparece na tela.
    val localNetworkPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { _ ->
            viewModel.connectMqtt(context)
        }

    fun connectToBroker() {
        val needsLocalNetworkPermission =
            Build.VERSION.SDK_INT >= 37 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_LOCAL_NETWORK
                ) != PackageManager.PERMISSION_GRANTED

        if (needsLocalNetworkPermission) {
            localNetworkPermissionLauncher.launch(
                Manifest.permission.ACCESS_LOCAL_NETWORK
            )
        } else {
            viewModel.connectMqtt(context)
        }
    }

    val locationPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->

            val fineGranted =
                permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true

            val coarseGranted =
                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

            if (fineGranted || coarseGranted) {
                viewModel.startLocationTracking(context)
            }
        }

    fun requestLocationPermission() {
        if (hasLocationPermission(context)) {
            viewModel.startLocationTracking(context)
        } else {
            // Android 13+: a notificação do serviço de localização (que mantém
            // o envio com a tela bloqueada) só aparece com POST_NOTIFICATIONS.
            val permissions = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            locationPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    Scaffold(
        containerColor = BackgroundColor
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BackgroundColor)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {

            // Cabeçalho
            Column {
                Text(
                    text = "RescueNet",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = RescueBlue
                )

                Text(
                    text = "Monitoramento de equipes de resgate",
                    fontSize = 14.sp,
                    color = SecondaryTextColor
                )
            }

            // Identificação e situação da equipe
            InfoCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Equipe",
                            fontSize = 13.sp,
                            color = SecondaryTextColor
                        )

                        Text(
                            text = "#001",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MainTextColor
                        )
                    }

                    StatusBadge(status = uiState.operationalStatus)
                }
            }



            // Informações de localização
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SectionTitle("Localização")

                InfoCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "GPS",
                                fontWeight = FontWeight.SemiBold,
                                color = MainTextColor
                            )

                            Text(
                                text = uiState.locationMessage,
                                fontSize = 12.sp,
                                color = SecondaryTextColor
                            )
                        }

                        StatusBadge(
                            status = when {
                            uiState.locationTracking && uiState.latitude != null -> "Ativo"
                            uiState.locationTracking -> "Buscando"
                            else -> "Inativo"
                        }
                      )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = uiState.latitude?.let {
                            "Latitude: $it"
                        } ?: "Latitude: aguardando localização",
                        fontSize = 14.sp,
                        color = MainTextColor
                    )

                    Text(
                        text = uiState.longitude?.let {
                            "Longitude: $it"
                        } ?: "Longitude: aguardando localização",
                        fontSize = 14.sp,
                        color = MainTextColor
                    )

                    Text(
                        text = uiState.locationAccuracy?.let {
                            "Precisão: %.1f metros".format(it)
                        } ?: "Precisão: indisponível",
                        fontSize = 14.sp,
                        color = MainTextColor
                    )

                    Text(
                        text = autoPublishLabel(
                            lastPublishedAt = uiState.lastAutoLocationPublishedAt,
                            connected = uiState.mqttConnected,
                            hasPosition = uiState.latitude != null
                        ),
                        fontSize = 11.sp,
                        color = SecondaryTextColor
                    )

                    Button(
                        onClick = { requestLocationPermission() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RescueBlue
                        )
                    ) {
                        Text("Ativar localização")
                    }

                    Button(
                        onClick = { viewModel.stopLocationTracking() },
                        enabled = uiState.locationTracking,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SecondaryTextColor
                        )
                    ) {
                        Text("Interromper localização")
                    }
                }
            }

            // Bateria
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SectionTitle("Bateria")

                InfoCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Nível da bateria",
                            color = MainTextColor
                        )

                        Text(
                            text = uiState.batteryLevel?.let { "$it%" } ?: "Indisponível",
                            fontWeight = FontWeight.Bold,
                            color = OnlineGreen
                        )
                    }
                }
            }

            // Conexão MQTT
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SectionTitle("Comunicação MQTT")

                InfoCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Estado da conexão",
                            color = MainTextColor
                        )

                        StatusBadge(
                            status = when {
                                uiState.mqttConnected -> "Conectado"
                                uiState.mqttConnecting -> "Conectando"
                                else -> "Desconectado"
                            }
                        )
                    }

                    Text(
                        text = uiState.mqttMessage,
                        fontSize = 13.sp,
                        color = SecondaryTextColor
                    )

                    if (uiState.mqttError != null) {
                        Text(
                            text = uiState.mqttError!!,
                            fontSize = 12.sp,
                            color = EmergencyRed
                        )
                    }

                    val brokerBusy = uiState.mqttConnecting || uiState.mqttConnected

                    OutlinedTextField(
                        value = uiState.brokerAddress,
                        onValueChange = { viewModel.updateBrokerAddress(it) },
                        label = { Text("Endereço do broker") },
                        placeholder = { Text("192.168.0.10 ou 192.168.0.10:1883") },
                        singleLine = true,
                        enabled = !brokerBusy,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction = ImeAction.Done
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "Wi-Fi: IP do computador com o broker. Cabo USB ou emulador: 127.0.0.1.",
                        fontSize = 11.sp,
                        color = SecondaryTextColor
                    )

                    if (brokerBusy) {
                        // Também cancela uma reconexão para um endereço errado.
                        Button(
                            onClick = { viewModel.disconnectMqtt() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SecondaryTextColor
                            )
                        ) {
                            Text(
                                text = if (uiState.mqttConnected) {
                                    "Desconectar"
                                } else {
                                    "Cancelar conexão"
                                }
                            )
                        }
                    } else {
                        Button(
                            onClick = { connectToBroker() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = RescueBlue
                            )
                        ) {
                            Text("Conectar ao broker")
                        }
                    }

                    Button(
                        onClick = { viewModel.publishTestLocation(context) },
                        enabled = uiState.mqttConnected,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = OnlineGreen
                        )
                    ) {
                        Text("Enviar localização de teste")
                    }


                    Button(
                        onClick = { viewModel.publishCurrentLocation(context) },
                        enabled = uiState.mqttConnected &&
                                uiState.latitude != null &&
                                uiState.longitude != null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = RescueBlue
                        )
                    ) {
                        Text("Enviar localização GPS real")
                    }


                    if (uiState.lastPublishedMessage != null) {
                        Text(
                            text = "Última mensagem publicada:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MainTextColor
                        )

                        Text(
                            text = uiState.lastPublishedMessage!!,
                            fontSize = 11.sp,
                            color = SecondaryTextColor
                        )
                    }
                }
            }

            // Coordenação Inteligente
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SectionTitle("Coordenação Inteligente")

                InfoCard {
                    ElectionInfoRow(
                        label = "Este dispositivo",
                        value = uiState.localNodeId?.let { shortNodeId(it) } ?: "—"
                    )

                    ElectionInfoRow(
                        label = "Papel na eleição",
                        value = electionRoleLabel(uiState.electionRole),
                        valueColor = if (uiState.electionRole == ElectionRole.COORDINATOR) {
                            OnlineGreen
                        } else {
                            RescueBlue
                        }
                    )

                    ElectionInfoRow(
                        label = "Coordenador atual",
                        value = when (val coordinator = uiState.coordinatorNodeId) {
                            null -> "Nenhum"
                            uiState.localNodeId -> "Este dispositivo"
                            else -> shortNodeId(coordinator)
                        }
                    )

                    ElectionInfoRow(
                        label = "Época",
                        value = uiState.electionEpoch?.let {
                            "${it.counter} (iniciada por ${shortNodeId(it.initiatorNodeId)})"
                        } ?: "—"
                    )

                    ElectionInfoRow(
                        label = "Dispositivos descobertos",
                        value = "${uiState.discoveredNodeCount}"
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Pontuação local",
                            color = MainTextColor
                        )

                        Text(
                            text = uiState.localElectionScore?.let {
                                "%.2f / 100".format(it)
                            } ?: "Aguardando",
                            fontWeight = FontWeight.Bold,
                            color = OnlineGreen
                        )
                    }

                    Text(
                        text = "Alguns indicadores da pontuação ainda utilizam valores provisórios.",
                        fontSize = 11.sp,
                        color = SecondaryTextColor
                    )
                }
            }
            // Emergência
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SectionTitle("Emergência")

                Button(
                    onClick = { viewModel.toggleEmergency() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(58.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (uiState.emergencyActivated) {
                            AttentionAmber
                        } else {
                            EmergencyRed
                        }
                    )
                ) {
                    Text(
                        text = if (uiState.emergencyActivated) {
                            "EMERGÊNCIA ATIVADA"
                        } else {
                            "ACIONAR EMERGÊNCIA"
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Ações operacionais
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SectionTitle("Ações operacionais")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ActionButton(
                        text = "Assistência médica",
                        selected = uiState.selectedAction == "Assistência médica",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            viewModel.selectAction("Assistência médica")
                        }
                    )

                    ActionButton(
                        text = "Evacuação",
                        selected = uiState.selectedAction == "Evacuação",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            viewModel.selectAction("Evacuação")
                        }
                    )
                }

                if (uiState.selectedAction.isNotEmpty()) {
                    Text(
                        text = "Ação selecionada: ${uiState.selectedAction}",
                        fontSize = 12.sp,
                        color = RescueBlue
                    )
                }
            }

            // Estado operacional
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SectionTitle("Alterar estado operacional")

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ActionButton(
                        text = "Em operação",
                        selected = uiState.operationalStatus == "Em operação",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            viewModel.updateOperationalStatus("Em operação")
                        }
                    )

                    ActionButton(
                        text = "Em pausa",
                        selected = uiState.operationalStatus == "Em pausa",
                        modifier = Modifier.weight(1f),
                        onClick = {
                            viewModel.updateOperationalStatus("Em pausa")
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Rodapé
            Text(
                text = "RescueNet • Sistema de monitoramento de resgate",
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 11.sp,
                color = SecondaryTextColor
            )
        }
    }
}

@Composable
fun SectionTitle(title: String) {
    Text(
        text = title,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        color = MainTextColor
    )
}

@Composable
fun ElectionInfoRow(
    label: String,
    value: String,
    valueColor: Color = RescueBlue
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = MainTextColor
        )

        Text(
            text = value,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

fun hasLocationPermission(context: Context): Boolean {
    val fineGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    val coarseGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    return fineGranted || coarseGranted
}

private val autoPublishTimeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss")

/** Linha discreta com o estado do envio automático da localização. */
fun autoPublishLabel(
    lastPublishedAt: Long?,
    connected: Boolean,
    hasPosition: Boolean
): String {
    val last = lastPublishedAt?.let {
        Instant.ofEpochMilli(it)
            .atZone(ZoneId.systemDefault())
            .format(autoPublishTimeFormat)
    }

    return when {
        !connected && last != null -> "Envio automático pausado (sem conexão). Último às $last"
        !connected -> "Envio automático: aguardando conexão com o broker"
        !hasPosition -> "Envio automático: aguardando posição do GPS"
        last != null -> "Envio automático a cada 10 s. Último às $last"
        else -> "Envio automático a cada 10 s"
    }
}

/** Mesmo formato curto usado no dashboard: os 6 últimos dígitos. */
fun shortNodeId(nodeId: Long): String {
    val text = nodeId.toString()
    return if (text.length <= 6) text else "…" + text.takeLast(6)
}

fun electionRoleLabel(role: ElectionRole?): String = when (role) {
    ElectionRole.COORDINATOR -> "Coordenador"
    ElectionRole.FOLLOWER -> "Seguidor"
    ElectionRole.ELECTING -> "Em eleição"
    ElectionRole.IDLE -> "Descobrindo nós"
    null -> "Desconectado"
}

@Composable
fun InfoCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = SurfaceColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            content()
        }
    }
}

@Composable
fun StatusBadge(status: String) {
    val badgeColor = when (status) {
        "Em operação", "Conectado" -> OnlineGreen
        "Em pausa", "Conectando" -> AttentionAmber
        else -> SecondaryTextColor
    }

    Surface(
        color = badgeColor.copy(alpha = 0.12f),
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(
            text = status,
            modifier = Modifier.padding(
                horizontal = 12.dp,
                vertical = 7.dp
            ),
            color = badgeColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun ActionButton(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) RescueBlue else SurfaceColor,
            contentColor = if (selected) Color.White else MainTextColor
        )
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Preview(showBackground = true)
@Composable
fun RescueNetPreview() {
    RescueNetTheme {
        RescueNetScreen(
            viewModel = RescueNetViewModel()
        )
    }
}
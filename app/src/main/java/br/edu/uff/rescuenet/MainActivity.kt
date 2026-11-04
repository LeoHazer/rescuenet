package br.edu.uff.rescuenet




import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.edu.uff.rescuenet.ui.theme.RescueNetTheme

private val RescueBlue = Color(0xFF2563EB)
private val RescueRed = Color(0xFFE5484D)
private val RescueGreen = Color(0xFF16A34A)
private val RescueAmber = Color(0xFFF59E0B)
private val BackgroundColor = Color(0xFFF9FAFB)
private val MainText = Color(0xFF111827)
private val SecondaryText = Color(0xFF4B5563)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            RescueNetTheme {
                RescueNetScreen()
            }
        }
    }
}

@Composable
fun RescueNetScreen() {
    var operationalStatus by remember { mutableStateOf("Em operação") }
    var selectedAction by remember { mutableStateOf("") }
    var emergencyActivated by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = BackgroundColor
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 24.dp)
        ) {

            // Cabeçalho
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "RescueNet",
                        fontSize = 27.sp,
                        fontWeight = FontWeight.Bold,
                        color = RescueBlue
                    )

                    Text(
                        text = "MONITORAMENTO DE EQUIPES",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SecondaryText,
                        letterSpacing = 1.2.sp
                    )
                }

                StatusBadge(
                    text = "SISTEMA DEMO",
                    color = RescueAmber
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // Identificação da equipe
            Text(
                text = "Minha equipe",
                fontSize = 14.sp,
                color = SecondaryText
            )

            Spacer(modifier = Modifier.height(5.dp))

            Text(
                text = "Equipe #001",
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                color = MainText
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusBadge(
                    text = operationalStatus.uppercase(),
                    color = if (operationalStatus == "Em operação") {
                        RescueGreen
                    } else {
                        RescueAmber
                    }
                )

                Text(
                    text = "ID: 001",
                    fontSize = 12.sp,
                    color = SecondaryText
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Localização
            SectionTitle("Localização")

            Spacer(modifier = Modifier.height(10.dp))

            InfoCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "GPS",
                            fontSize = 13.sp,
                            color = SecondaryText
                        )

                        Text(
                            text = "Localização simulada",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MainText
                        )
                    }

                    StatusBadge(
                        text = "SIMULADO",
                        color = RescueAmber
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Latitude",
                    fontSize = 12.sp,
                    color = SecondaryText
                )

                Text(
                    text = "-22.526000",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MainText
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Longitude",
                    fontSize = 12.sp,
                    color = SecondaryText
                )

                Text(
                    text = "-41.945000",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MainText
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Bateria
            SectionTitle("Dispositivo")

            Spacer(modifier = Modifier.height(10.dp))

            InfoCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Bateria",
                            fontSize = 13.sp,
                            color = SecondaryText
                        )

                        Text(
                            text = "78%",
                            fontSize = 25.sp,
                            fontWeight = FontWeight.Bold,
                            color = MainText
                        )
                    }

                    StatusBadge(
                        text = "NÍVEL BOM",
                        color = RescueGreen
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Emergência
            SectionTitle("Central de emergência")

            Spacer(modifier = Modifier.height(10.dp))

            Button(
                onClick = {
                    emergencyActivated = !emergencyActivated
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RescueRed
                )
            ) {
                Text(
                    text = if (emergencyActivated) {
                        "EMERGÊNCIA SIMULADA ATIVADA"
                    } else {
                        "ACIONAR EMERGÊNCIA"
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            if (emergencyActivated) {
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Simulação: alerta de emergência acionado na interface.",
                    fontSize = 12.sp,
                    color = RescueRed
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Ações operacionais
            SectionTitle("Ações operacionais")

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ActionButton(
                    text = "Assistência médica",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectedAction = "Assistência médica"
                    }
                )

                ActionButton(
                    text = "Evacuação",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        selectedAction = "Evacuação"
                    }
                )
            }

            if (selectedAction.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Ação selecionada: $selectedAction (simulação)",
                    fontSize = 12.sp,
                    color = RescueBlue
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Estado operacional
            SectionTitle("Estado operacional")

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Em operação", "Em pausa").forEach { status ->
                    OutlinedButton(
                        onClick = {
                            operationalStatus = status
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = status,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = "RescueNet • Protótipo",
                modifier = Modifier.fillMaxWidth(),
                fontSize = 11.sp,
                color = SecondaryText
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold,
        color = MainText
    )
}

@Composable
private fun InfoCard(
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 1.dp
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp)
        ) {
            content()
        }
    }
}

@Composable
private fun StatusBadge(
    text: String,
    color: Color
) {
    Surface(
        color = color.copy(alpha = 0.12f),
        shape = RoundedCornerShape(50)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(
                horizontal = 10.dp,
                vertical = 6.dp
            ),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun ActionButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(58.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = RescueBlue
        )
    }
}

@Preview(showBackground = true)
@Composable
fun RescueNetPreview() {
    RescueNetTheme {
        RescueNetScreen()
    }
}

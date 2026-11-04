
package br.edu.uff.rescuenet.data.mqtt

object MqttConfig {

    // Endereço especial utilizado pelo emulador Android
    // para acessar o computador que o está executando.
    const val BROKER_HOST = "10.0.2.2"

    const val BROKER_PORT = 1883

    const val KEEP_ALIVE_SECONDS = 30

    // Identificação da equipe utilizada nos testes.
    const val TEAM_ID = 1

    // Tópico de publicação da localização.
    const val LOCATION_TOPIC =
        "rescuenet/teams/$TEAM_ID/location"

    fun clientId(teamId: Int): String {
        return "rescuenet-android-team-$teamId"
    }
}

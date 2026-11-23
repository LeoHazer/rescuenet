
package br.edu.uff.rescuenet.data.mqtt

object MqttConfig {

    // Endereço utilizado pelo emulador Android
    // para acessar o computador por meio do ADB Reverse.
    const val BROKER_HOST = "127.0.0.1"

    const val BROKER_PORT = 1883

    const val KEEP_ALIVE_SECONDS = 30

    // Identificação da equipe utilizada nos testes.
    const val TEAM_ID = 1

    // Tópico de publicação da localização.
    const val LOCATION_TOPIC =
        "rescuenet/teams/$TEAM_ID/location"
    // Tópico compartilhado para mensagens do protocolo de eleição.
    const val ELECTION_TOPIC =
        "rescuenet/teams/$TEAM_ID/election"

    fun clientId(teamId: Int): String {
        return "rescuenet-android-team-$teamId"
    }

    fun nodeClientId(nodeId: Long): String {
        return "rescuenet-node-$nodeId"
    }

    fun presenceTopic(teamId: Int, nodeId: Long): String {
        return "rescuenet/teams/$teamId/nodes/$nodeId/presence"
    }
}

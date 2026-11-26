package br.edu.uff.rescuenet.data.mqtt

import android.content.Context

/** Endereço do broker MQTT informado na tela do aplicativo. */
data class BrokerAddress(
    val host: String,
    val port: Int
) {
    override fun toString(): String =
        if (port == MqttConfig.DEFAULT_BROKER_PORT) host else "$host:$port"

    companion object {

        private val HOST_PATTERN = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$")
        private val SCHEMES = listOf("tcp://", "mqtt://")

        /**
         * Aceita "host" ou "host:porta" (por exemplo, "192.168.0.10" ou
         * "192.168.0.10:1883"), com ou sem o prefixo tcp:// ou mqtt://.
         * Retorna null quando o texto não é um endereço válido.
         */
        fun parse(input: String): BrokerAddress? {
            var text = input.trim()

            SCHEMES.firstOrNull { text.startsWith(it, ignoreCase = true) }
                ?.let { text = text.substring(it.length) }

            text = text.trimEnd('/')
            if (text.isEmpty()) return null

            val separator = text.lastIndexOf(':')
            val host: String
            val port: Int

            if (separator >= 0) {
                host = text.substring(0, separator)
                port = text.substring(separator + 1).toIntOrNull() ?: return null
            } else {
                host = text
                port = MqttConfig.DEFAULT_BROKER_PORT
            }

            if (!HOST_PATTERN.matches(host)) return null
            if (port !in 1..65535) return null

            return BrokerAddress(host, port)
        }
    }
}

/** Guarda no aparelho o último endereço de broker usado com sucesso na tela. */
object BrokerSettings {

    private const val PREFS_NAME = "rescuenet_broker"
    private const val KEY_ADDRESS = "address"

    fun load(context: Context): String {
        return context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ADDRESS, null)
            ?: BrokerAddress(
                MqttConfig.DEFAULT_BROKER_HOST,
                MqttConfig.DEFAULT_BROKER_PORT
            ).toString()
    }

    fun save(context: Context, address: BrokerAddress) {
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ADDRESS, address.toString())
            .apply()
    }
}

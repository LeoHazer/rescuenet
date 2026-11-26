package br.edu.uff.rescuenet.simulator

/** Endereço do broker no formato "host" ou "host:porta". */
private data class BrokerAddressArgument(val host: String, val port: Int) {
    companion object {
        fun parse(text: String): BrokerAddressArgument {
            val separator = text.lastIndexOf(':')
            if (separator < 0) return BrokerAddressArgument(text, 1883)

            val port = text.substring(separator + 1).toIntOrNull()
            require(port != null && port in 1..65535) { "Porta do broker inválida: $text" }
            return BrokerAddressArgument(text.substring(0, separator), port)
        }
    }
}

/**
 * Simulador de nós do RescueNet.
 *
 * Uso (a partir da raiz do repositório):
 *   .\gradlew.bat -p simulator run
 *   .\gradlew.bat -p simulator run --args="--broker 192.168.0.10:1883 --porta 8090 --equipe 1"
 */
fun main(args: Array<String>) {
    val options = parseOptions(args)

    val registry = NodeRegistry(
        brokerHost = options.brokerHost,
        brokerPort = options.brokerPort,
        defaultTeamId = options.teamId
    )

    val brokerLabel = "${options.brokerHost}:${options.brokerPort}"
    val server = try {
        SimulatorApi(registry, brokerLabel).start("127.0.0.1", options.httpPort)
    } catch (error: java.net.BindException) {
        System.err.println(
            "A porta ${options.httpPort} já está em uso: provavelmente outro simulador " +
                "ainda está aberto. Feche o terminal dele (ou encerre o processo Java no " +
                "Gerenciador de Tarefas) e rode de novo."
        )
        kotlin.system.exitProcess(1)
    }

    Runtime.getRuntime().addShutdownHook(Thread {
        // Encerramento limpo: cada nó publica o LWT "offline".
        registry.removeAll()
        server.stop(0)
    })

    println("========================================")
    println(" RescueNet - Simulador de nós")
    println("========================================")
    println("Broker MQTT: $brokerLabel")
    println("Equipe padrão: ${options.teamId}")
    println("API: http://127.0.0.1:${options.httpPort}/sim/nodes")
    println("Pressione Ctrl+C para encerrar (os nós publicam LWT offline).")

    Thread.currentThread().join()
}

private data class Options(
    val brokerHost: String,
    val brokerPort: Int,
    val httpPort: Int,
    val teamId: Int
)

private fun parseOptions(args: Array<String>): Options {
    var broker = BrokerAddressArgument("127.0.0.1", 1883)
    var httpPort = 8090
    var teamId = 1

    var index = 0
    while (index < args.size) {
        val value = args.getOrNull(index + 1)
            ?: error("Valor ausente para ${args[index]}")
        when (args[index]) {
            "--broker" -> broker = BrokerAddressArgument.parse(value)
            "--porta" -> httpPort = value.toInt()
            "--equipe" -> teamId = value.toInt()
            else -> error("Opção desconhecida: ${args[index]}")
        }
        index += 2
    }

    require(teamId > 0) { "--equipe deve ser positivo." }
    require(httpPort in 1..65535) { "--porta inválida." }

    return Options(broker.host, broker.port, httpPort, teamId)
}

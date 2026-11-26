package br.edu.uff.rescuenet.simulator

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * API HTTP consumida pelo dashboard (via proxy /sim do Vite).
 *
 *   GET    /sim/health
 *   GET    /sim/nodes
 *   POST   /sim/nodes                  {"level": 60, "battery": 80, "team_id": 1}
 *   DELETE /sim/nodes                  remove todos
 *   PATCH  /sim/nodes/{id}             {"level": 90} e/ou {"battery": 10}
 *   DELETE /sim/nodes/{id}
 *   POST   /sim/nodes/{id}/crash       queda abrupta (LWT)
 *   POST   /sim/nodes/{id}/reconnect
 *
 * Os IDs são enviados como texto, no mesmo formato da API do backend.
 */
class SimulatorApi(
    private val registry: NodeRegistry,
    private val brokerLabel: String
) {
    private class ApiException(val status: Int, message: String) : RuntimeException(message)

    fun start(host: String, port: Int): HttpServer {
        val server = HttpServer.create(InetSocketAddress(host, port), 0)
        server.executor = Executors.newFixedThreadPool(4)
        server.createContext("/sim") { exchange -> handle(exchange) }
        server.start()
        return server
    }

    private fun handle(exchange: HttpExchange) {
        try {
            val path = exchange.requestURI.path.trimEnd('/')
            val parts = path.removePrefix("/sim").trim('/').split('/').filter { it.isNotEmpty() }
            val method = exchange.requestMethod

            when {
                parts == listOf("health") && method == "GET" ->
                    send(exchange, 200, JSONObject().put("status", "ok").put("broker", brokerLabel))

                parts == listOf("nodes") && method == "GET" ->
                    send(exchange, 200, JSONArray(registry.list().map(::toJson)))

                parts == listOf("nodes") && method == "POST" -> {
                    val body = readBody(exchange)
                    val profile = profileFrom(body, NodeProfile(level = 60.0, battery = 80.0))
                    val teamId = if (body.has("team_id")) body.getInt("team_id") else null
                    if (teamId != null && teamId <= 0) throw ApiException(400, "team_id deve ser positivo.")
                    send(exchange, 201, toJson(registry.create(profile, teamId)))
                }

                parts == listOf("nodes") && method == "DELETE" -> {
                    registry.removeAll()
                    sendEmpty(exchange)
                }

                parts.size == 2 && parts[0] == "nodes" && method == "PATCH" -> {
                    val node = registry.get(nodeIdFrom(parts[1]))
                    val current = node.snapshot().profile
                    node.updateProfile(profileFrom(readBody(exchange), current))
                    send(exchange, 200, toJson(node.snapshot()))
                }

                parts.size == 2 && parts[0] == "nodes" && method == "DELETE" -> {
                    registry.remove(nodeIdFrom(parts[1]))
                    sendEmpty(exchange)
                }

                parts.size == 3 && parts[0] == "nodes" && parts[2] == "crash" && method == "POST" -> {
                    val node = registry.get(nodeIdFrom(parts[1]))
                    node.crash()
                    send(exchange, 200, toJson(node.snapshot()))
                }

                parts.size == 3 && parts[0] == "nodes" && parts[2] == "reconnect" && method == "POST" -> {
                    val node = registry.get(nodeIdFrom(parts[1]))
                    node.reconnect()
                    send(exchange, 200, toJson(node.snapshot()))
                }

                else -> throw ApiException(404, "Rota não encontrada: $method $path")
            }
        } catch (error: ApiException) {
            sendError(exchange, error.status, error.message)
        } catch (error: NodeNotFoundException) {
            sendError(exchange, 404, error.message)
        } catch (error: NodeLimitException) {
            sendError(exchange, 409, error.message)
        } catch (error: IllegalArgumentException) {
            sendError(exchange, 400, error.message)
        } catch (error: JSONException) {
            sendError(exchange, 400, "JSON inválido: ${error.message}")
        } catch (error: Exception) {
            sendError(exchange, 500, "Erro interno: ${error.message}")
        } finally {
            exchange.close()
        }
    }

    private fun nodeIdFrom(text: String): Long =
        text.toLongOrNull()?.takeIf { it > 0 }
            ?: throw ApiException(400, "ID de nó inválido: $text")

    private fun readBody(exchange: HttpExchange): JSONObject {
        val text = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun profileFrom(body: JSONObject, fallback: NodeProfile): NodeProfile =
        NodeProfile(
            level = if (body.has("level")) body.getDouble("level") else fallback.level,
            battery = if (body.has("battery")) body.getDouble("battery") else fallback.battery
        )

    private fun toJson(snapshot: NodeSnapshot): JSONObject =
        JSONObject()
            .put("node_id", snapshot.nodeId.toString())
            .put("team_id", snapshot.teamId)
            .put("level", snapshot.profile.level)
            .put("battery", snapshot.profile.battery)
            .put("score", snapshot.score)
            .put("eligible", snapshot.eligible)
            .put("status", snapshot.status.label)
            .put("role", snapshot.role?.name ?: JSONObject.NULL)
            .put("coordinator_node_id", snapshot.coordinatorNodeId?.toString() ?: JSONObject.NULL)
            .put("epoch_counter", snapshot.epochCounter ?: JSONObject.NULL)
            .put("epoch_initiator_node_id", snapshot.epochInitiatorNodeId?.toString() ?: JSONObject.NULL)
            .put("known_candidates", snapshot.knownCandidates)
            .put("error", snapshot.error ?: JSONObject.NULL)

    private fun send(exchange: HttpExchange, status: Int, body: Any) {
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun sendEmpty(exchange: HttpExchange) {
        exchange.sendResponseHeaders(204, -1)
    }

    private fun sendError(exchange: HttpExchange, status: Int, message: String?) {
        try {
            send(exchange, status, JSONObject().put("error", message ?: "Erro desconhecido."))
        } catch (_: Exception) {
            // A resposta já havia começado.
        }
    }
}

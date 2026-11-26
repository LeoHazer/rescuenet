package br.edu.uff.rescuenet.data.location

/**
 * Payload publicado em rescuenet/teams/{team}/location.
 *
 * O node_id vai como número JSON (o backend Go o lê como int64), para que
 * o dashboard mostre um marcador por aparelho.
 */
object LocationPayload {

    fun encode(
        teamId: Int,
        nodeId: Long,
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        battery: Int,
        timestampSeconds: Long
    ): String = """
        {
            "team_id": $teamId,
            "node_id": $nodeId,
            "latitude": $latitude,
            "longitude": $longitude,
            "accuracy": $accuracy,
            "battery": $battery,
            "timestamp": $timestampSeconds
        }
    """.trimIndent()
}

/**
 * Limita o envio automático de localização: o GPS entrega posições a cada
 * ~5 s, mas só uma por intervalo é publicada.
 *
 * Os instantes são de relógio monotônico (SystemClock.elapsedRealtime),
 * em milissegundos.
 */
class AutoPublishThrottle(
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS
) {
    private var lastAttemptAtMillis: Long? = null

    /** Retorna true e registra a tentativa se o intervalo já passou. */
    fun tryAcquire(nowMillis: Long): Boolean {
        val last = lastAttemptAtMillis

        if (last != null && nowMillis - last < intervalMillis) {
            return false
        }

        lastAttemptAtMillis = nowMillis
        return true
    }

    /** Libera o próximo envio imediatamente (por exemplo, ao reconectar). */
    fun reset() {
        lastAttemptAtMillis = null
    }

    companion object {
        const val DEFAULT_INTERVAL_MILLIS = 10_000L
    }
}

package br.edu.uff.rescuenet.data.election

import kotlin.math.round

data class ElectionMetrics(
    val connectionStability: Double,
    val battery: Double,
    val availability: Double,
    val connectivity: Double,
    val computationalResources: Double,
    val dataEfficiency: Double
) {
    init {
        require(
            listOf(
                connectionStability,
                battery,
                availability,
                connectivity,
                computationalResources,
                dataEfficiency
            ).all { it in 0.0..100.0 }
        ) {
            "Todos os indicadores devem estar entre 0 e 100."
        }
    }
}

data class ElectionCandidate(
    val nodeId: Long,
    val metrics: ElectionMetrics,
    val mqttConnected: Boolean,
    val available: Boolean
) {
    val score: Double
        get() = AdaptiveBullyPolicy.calculateScore(metrics)

    val isEligible: Boolean
        get() = AdaptiveBullyPolicy.isEligible(this)
}

object AdaptiveBullyPolicy {

    const val WEIGHT_STABILITY = 0.30
    const val WEIGHT_BATTERY = 0.25
    const val WEIGHT_AVAILABILITY = 0.20
    const val WEIGHT_CONNECTIVITY = 0.15
    const val WEIGHT_RESOURCES = 0.05
    const val WEIGHT_DATA_EFFICIENCY = 0.05

    const val MINIMUM_BATTERY_PERCENT = 15.0
    const val HYSTERESIS_MARGIN = 10.0
    const val REQUIRED_CONSECUTIVE_ADVANTAGES = 2

    fun calculateScore(metrics: ElectionMetrics): Double {
        val weightedScore =
            metrics.connectionStability * WEIGHT_STABILITY +
            metrics.battery * WEIGHT_BATTERY +
            metrics.availability * WEIGHT_AVAILABILITY +
            metrics.connectivity * WEIGHT_CONNECTIVITY +
            metrics.computationalResources * WEIGHT_RESOURCES +
            metrics.dataEfficiency * WEIGHT_DATA_EFFICIENCY

        return round(weightedScore * 100.0) / 100.0
    }

    /** Conectado e disponível: requisito mínimo para participar da eleição. */
    fun canParticipate(candidate: ElectionCandidate): Boolean {
        return candidate.nodeId > 0L &&
            candidate.mqttConnected &&
            candidate.available
    }

    /**
     * Elegível para liderar com prioridade: além de participar, tem bateria
     * mínima. Um nó abaixo do mínimo só lidera se nenhum nó for elegível.
     */
    fun isEligible(candidate: ElectionCandidate): Boolean {
        return canParticipate(candidate) &&
            candidate.metrics.battery >= MINIMUM_BATTERY_PERCENT
    }

    /**
     * Mesma ordem usada pela eleição: elegíveis primeiro, depois maior
     * pontuação e, no empate, maior nodeId.
     */
    fun selectInitialCoordinator(
        candidates: Collection<ElectionCandidate>
    ): ElectionCandidate? {
        return candidates
            .filter { canParticipate(it) }
            .maxWithOrNull(
                compareBy<ElectionCandidate> { it.isEligible }
                    .thenBy { it.score }
                    .thenBy { it.nodeId }
            )
    }

    fun shouldReplaceCoordinator(
        current: ElectionCandidate,
        challenger: ElectionCandidate,
        consecutiveAdvantageCount: Int
    ): Boolean {
        if (!challenger.isEligible) return false
        if (challenger.nodeId == current.nodeId) return false

        if (!current.isEligible) return true

        val hasRequiredAdvantage =
            challenger.score >= current.score + HYSTERESIS_MARGIN

        val advantagePersisted =
            consecutiveAdvantageCount >= REQUIRED_CONSECUTIVE_ADVANTAGES

        return hasRequiredAdvantage && advantagePersisted
    }
}
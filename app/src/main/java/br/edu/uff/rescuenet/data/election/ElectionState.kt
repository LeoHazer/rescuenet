package br.edu.uff.rescuenet.data.election

data class ElectionEpoch(
    val counter: Long,
    val initiatorNodeId: Long
) : Comparable<ElectionEpoch> {

    init {
        require(counter > 0L) { "O contador da época deve ser positivo." }
        require(initiatorNodeId > 0L) { "O nodeId iniciador deve ser positivo." }
    }

    override fun compareTo(other: ElectionEpoch): Int {
        val counterComparison = counter.compareTo(other.counter)
        if (counterComparison != 0) return counterComparison

        return initiatorNodeId.compareTo(other.initiatorNodeId)
    }
}

class ElectionEpochTracker(
    private val localNodeId: Long,
    initialCounter: Long = 0L
) {
    var highestObservedCounter: Long = initialCounter
        private set

    var currentEpoch: ElectionEpoch? = null
        private set

    init {
        require(localNodeId > 0L) { "O nodeId local deve ser positivo." }
        require(initialCounter >= 0L) { "O contador inicial não pode ser negativo." }
    }

    fun startNewEpoch(): ElectionEpoch {
        val baseCounter = maxOf(
            highestObservedCounter,
            currentEpoch?.counter ?: 0L
        )

        check(baseCounter < Long.MAX_VALUE) {
            "Limite máximo do contador de épocas atingido."
        }

        val newEpoch = ElectionEpoch(
            counter = baseCounter + 1L,
            initiatorNodeId = localNodeId
        )

        highestObservedCounter = newEpoch.counter
        currentEpoch = newEpoch

        return newEpoch
    }

    fun synchronizeAndStartNewEpoch(observedEpoch: ElectionEpoch): ElectionEpoch {
        highestObservedCounter = maxOf(
            highestObservedCounter,
            observedEpoch.counter
        )

        val baseCounter = maxOf(
            highestObservedCounter,
            currentEpoch?.counter ?: 0L
        )

        check(baseCounter < Long.MAX_VALUE) {
            "Limite máximo do contador de épocas atingido."
        }

        val newEpoch = ElectionEpoch(
            counter = baseCounter + 1L,
            initiatorNodeId = localNodeId
        )

        highestObservedCounter = newEpoch.counter
        currentEpoch = newEpoch

        return newEpoch
    }

    /**
     * Registra um contador visto na rede (por exemplo, em um HELLO) sem
     * adotar a época. Assim, a próxima eleição local começa acima dela.
     */
    fun observeCounter(counter: Long) {
        if (counter > highestObservedCounter) {
            highestObservedCounter = counter
        }
    }

    fun observe(epoch: ElectionEpoch): Boolean {
        val previousEpoch = currentEpoch

        highestObservedCounter = maxOf(
            highestObservedCounter,
            epoch.counter
        )

        if (previousEpoch != null && epoch <= previousEpoch) {
            return false
        }

        currentEpoch = epoch
        return true
    }
}

/**
 * Conta, para cada desafiante, quantas avaliações seguidas ele teve a
 * vantagem exigida sobre o coordenador atual.
 *
 * O contador é por desafiante: com três ou mais nós, os HELLOs se
 * intercalam, e o HELLO de um nó mais fraco não pode zerar a contagem de
 * outro que de fato é melhor (antes, um contador único era zerado e a
 * troca nunca acontecia).
 */
class HysteresisTracker {

    private var currentNodeId: Long? = null
    private val consecutiveAdvantages = mutableMapOf<Long, Int>()

    fun reset() {
        currentNodeId = null
        consecutiveAdvantages.clear()
    }

    fun observe(
        current: ElectionCandidate,
        challenger: ElectionCandidate
    ): Boolean {
        if (challenger.nodeId == current.nodeId) {
            return false
        }

        if (!challenger.isEligible) {
            consecutiveAdvantages.remove(challenger.nodeId)
            return false
        }

        if (!current.isEligible) {
            reset()
            return true
        }

        if (currentNodeId != current.nodeId) {
            reset()
            currentNodeId = current.nodeId
        }

        val hasRequiredAdvantage =
            challenger.score >= current.score +
                AdaptiveBullyPolicy.HYSTERESIS_MARGIN

        if (!hasRequiredAdvantage) {
            consecutiveAdvantages.remove(challenger.nodeId)
            return false
        }

        val count = (consecutiveAdvantages[challenger.nodeId] ?: 0) + 1
        consecutiveAdvantages[challenger.nodeId] = count

        return count >= AdaptiveBullyPolicy.REQUIRED_CONSECUTIVE_ADVANTAGES
    }
}
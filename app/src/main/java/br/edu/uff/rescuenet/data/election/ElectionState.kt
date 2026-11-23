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

class HysteresisTracker {

    private var currentNodeId: Long? = null
    private var challengerNodeId: Long? = null
    private var consecutiveAdvantages: Int = 0

    fun reset() {
        currentNodeId = null
        challengerNodeId = null
        consecutiveAdvantages = 0
    }

    fun observe(
        current: ElectionCandidate,
        challenger: ElectionCandidate
    ): Boolean {
        if (!challenger.isEligible) {
            reset()
            return false
        }

        if (challenger.nodeId == current.nodeId) {
            reset()
            return false
        }

        if (!current.isEligible) {
            reset()
            return true
        }

        val hasRequiredAdvantage =
            challenger.score >= current.score +
                AdaptiveBullyPolicy.HYSTERESIS_MARGIN

        if (!hasRequiredAdvantage) {
            reset()
            return false
        }

        if (
            currentNodeId != current.nodeId ||
            challengerNodeId != challenger.nodeId
        ) {
            currentNodeId = current.nodeId
            challengerNodeId = challenger.nodeId
            consecutiveAdvantages = 1
        } else {
            consecutiveAdvantages++
        }

        return consecutiveAdvantages >=
            AdaptiveBullyPolicy.REQUIRED_CONSECUTIVE_ADVANTAGES
    }
}
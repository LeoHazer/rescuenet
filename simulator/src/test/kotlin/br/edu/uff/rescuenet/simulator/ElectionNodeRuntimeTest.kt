package br.edu.uff.rescuenet.simulator

import br.edu.uff.rescuenet.data.election.ElectionMessage
import br.edu.uff.rescuenet.data.election.ElectionMessageCodec
import br.edu.uff.rescuenet.data.election.ElectionRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vários nós trocando mensagens por um barramento em memória, com relógio
 * manual. Cada mensagem passa pelo codec JSON, como no broker.
 */
class ElectionNodeRuntimeTest {

    private class Network {
        var now = 0L
        private val nodes = linkedMapOf<Long, ElectionNodeRuntime>()
        private val alive = mutableSetOf<Long>()
        private val queue = ArrayDeque<String>()

        fun add(nodeId: Long, level: Double, battery: Double = 100.0): ElectionNodeRuntime {
            val runtime = ElectionNodeRuntime(
                nodeId = nodeId,
                teamId = 1,
                initialProfile = NodeProfile(level, battery),
                publish = { message: ElectionMessage ->
                    queue.addLast(ElectionMessageCodec.encode(message))
                }
            )
            nodes[nodeId] = runtime
            alive += nodeId
            runtime.start(now)
            deliver()
            return runtime
        }

        /** Queda abrupta: o nó para de enviar e de receber. */
        fun crash(nodeId: Long) {
            alive -= nodeId
            nodes.getValue(nodeId).stop()
        }

        fun node(nodeId: Long) = nodes.getValue(nodeId)

        fun coordinators() = alive.filter {
            nodes.getValue(it).role == ElectionRole.COORDINATOR
        }

        /** Avança o relógio em passos de 1 s, como o agendador do app. */
        fun advance(seconds: Int) {
            repeat(seconds) {
                now += 1_000L
                alive.toList().forEach { nodes.getValue(it).tick(now) }
                deliver()
            }
        }

        private fun deliver() {
            while (queue.isNotEmpty()) {
                val payload = queue.removeFirst()
                val message = ElectionMessageCodec.decode(payload)
                alive.toList()
                    .filter { it != message.nodeId }
                    .forEach { nodes.getValue(it).onMessage(message, now) }
            }
        }
    }

    @Test
    fun bestNodeIsElectedWhenNodesStartTogether() {
        val net = Network()
        net.add(1001, level = 30.0)
        net.add(1002, level = 60.0)
        net.add(1003, level = 95.0)

        net.advance(10)

        assertEquals(listOf(1003L), net.coordinators())
        listOf(1001L, 1002L).forEach {
            assertEquals(ElectionRole.FOLLOWER, net.node(it).role)
            assertEquals(1003L, net.node(it).coordinatorNodeId)
        }
    }

    @Test
    fun nextBestNodeTakesOverAfterCoordinatorCrash() {
        val net = Network()
        net.add(1001, level = 30.0)
        net.add(1002, level = 60.0)
        net.add(1003, level = 95.0)
        net.advance(10)

        net.crash(1003)
        // Os seguidores só desistem 15 s após o ÚLTIMO heartbeat recebido,
        // que pode ter chegado até 5 s antes da queda.
        net.advance(4)
        assertEquals(1003L, net.node(1002).coordinatorNodeId)

        net.advance(14)

        assertEquals(listOf(1002L), net.coordinators())
        assertEquals(1002L, net.node(1001).coordinatorNodeId)
    }

    @Test
    fun slightlyBetterNewcomerJoinsAsFollower() {
        val net = Network()
        net.add(1001, level = 60.0)
        net.advance(10)

        net.add(1002, level = 65.0)
        net.advance(30)

        assertEquals(listOf(1001L), net.coordinators())
        assertEquals(ElectionRole.FOLLOWER, net.node(1002).role)
    }

    @Test
    fun muchBetterNewcomerReceivesLeadershipThroughHysteresis() {
        val net = Network()
        net.add(1001, level = 40.0)
        net.advance(10)

        net.add(1002, level = 90.0)
        net.advance(12)

        assertEquals(listOf(1002L), net.coordinators())
        assertEquals(1002L, net.node(1001).coordinatorNodeId)
    }

    @Test
    fun raisingFollowerLevelHandsOverLeadership() {
        val net = Network()
        net.add(1001, level = 60.0)
        net.advance(10)
        net.add(1002, level = 62.0)
        net.advance(10)
        assertEquals(listOf(1001L), net.coordinators())

        net.node(1002).profile = NodeProfile(level = 90.0, battery = 100.0)
        net.advance(12)

        assertEquals(listOf(1002L), net.coordinators())
    }

    @Test
    fun hysteresisWorksWithSeveralFollowers() {
        val net = Network()
        net.add(1001, level = 60.0)
        net.advance(10)
        net.add(1002, level = 30.0)
        net.add(1003, level = 62.0)
        net.advance(10)
        assertEquals(listOf(1001L), net.coordinators())

        // Os HELLOs do nó fraco (1002) se intercalam com os do desafiante.
        net.node(1003).profile = NodeProfile(level = 90.0, battery = 100.0)
        net.advance(15)

        assertEquals(listOf(1003L), net.coordinators())
    }

    @Test
    fun coordinatorWithLowBatteryStepsDown() {
        val net = Network()
        net.add(1001, level = 90.0)
        net.add(1002, level = 50.0)
        net.advance(10)
        assertEquals(listOf(1001L), net.coordinators())

        net.node(1001).profile = NodeProfile(level = 90.0, battery = 10.0)
        net.advance(25)

        assertEquals(listOf(1002L), net.coordinators())
        assertTrue(net.node(1001).role != ElectionRole.COORDINATOR)
    }

    @Test
    fun lowBatteryCoordinatorHandsOverQuickly() {
        val net = Network()
        net.add(1001, level = 90.0)
        net.add(1002, level = 50.0)
        net.advance(10)

        net.node(1001).profile = NodeProfile(level = 90.0, battery = 10.0)
        // O próximo HELLO do seguidor elegível já dispara a troca (até 5 s
        // para o novo HELLO do líder + até 5 s para o HELLO do seguidor).
        net.advance(11)

        assertEquals(listOf(1002L), net.coordinators())
    }

    @Test
    fun singleLowBatteryNodeLeadsUntilEligibleNodeArrives() {
        val net = Network()
        net.add(1001, level = 90.0, battery = 10.0)
        net.advance(10)
        assertEquals(listOf(1001L), net.coordinators())

        net.add(1002, level = 40.0, battery = 80.0)
        net.advance(3)

        assertEquals(listOf(1002L), net.coordinators())
        assertEquals(1002L, net.node(1001).coordinatorNodeId)
    }

    @Test
    fun severalLowBatteryNodesElectOneLeaderWithoutElectionStorm() {
        val net = Network()
        net.add(1001, level = 40.0, battery = 10.0)
        net.add(1002, level = 90.0, battery = 10.0)
        net.add(1003, level = 60.0, battery = 5.0)
        net.advance(15)
        assertEquals(listOf(1002L), net.coordinators())

        val epoch = net.node(1002).currentEpoch
        net.advance(30)

        assertEquals(listOf(1002L), net.coordinators())
        assertEquals(epoch, net.node(1002).currentEpoch)
    }

    @Test
    fun crashedCoordinatorReturnsAsFollower() {
        val net = Network()
        net.add(1001, level = 95.0)
        net.add(1002, level = 60.0)
        net.advance(10)
        net.crash(1001)
        net.advance(20)
        assertEquals(listOf(1002L), net.coordinators())

        // Volta com o mesmo ID, como um celular reconectando.
        net.add(1001, level = 95.0)
        net.advance(3)
        assertEquals(ElectionRole.FOLLOWER, net.node(1001).role)

        // Depois, a histerese devolve a liderança ao nó melhor (95 vs 60).
        net.advance(12)
        assertEquals(listOf(1001L), net.coordinators())
    }
}

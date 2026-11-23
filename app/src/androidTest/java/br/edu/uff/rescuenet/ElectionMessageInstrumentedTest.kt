package br.edu.uff.rescuenet

import androidx.test.ext.junit.runners.AndroidJUnit4
import br.edu.uff.rescuenet.data.election.CoordinatorMessage
import br.edu.uff.rescuenet.data.election.ElectionEpoch
import br.edu.uff.rescuenet.data.election.ElectionMessage
import br.edu.uff.rescuenet.data.election.ElectionMessageCodec
import br.edu.uff.rescuenet.data.election.ElectionOkMessage
import br.edu.uff.rescuenet.data.election.ElectionMetrics
import br.edu.uff.rescuenet.data.election.ElectionRequestMessage
import br.edu.uff.rescuenet.data.election.HeartbeatMessage
import br.edu.uff.rescuenet.data.election.HelloMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElectionMessageInstrumentedTest {

    private val epoch = ElectionEpoch(
        counter = 7L,
        initiatorNodeId = 1001L
    )

    private val metrics = ElectionMetrics(
        connectionStability = 90.0,
        battery = 80.0,
        availability = 95.0,
        connectivity = 100.0,
        computationalResources = 70.0,
        dataEfficiency = 85.0
    )

    private fun assertRoundTrip(message: ElectionMessage) {
        val json = ElectionMessageCodec.encode(message)
        val decoded = ElectionMessageCodec.decode(json)
        assertEquals(message, decoded)
    }

    @Test
    fun helloMessageRoundTrip() {
        assertRoundTrip(
            HelloMessage(
                teamId = 1,
                nodeId = 1001L,
                epoch = epoch,
                timestamp = 1795030921L,
                metrics = metrics
            )
        )
    }

    @Test
    fun electionRequestRoundTrip() {
        assertRoundTrip(
            ElectionRequestMessage(
                teamId = 1,
                nodeId = 1001L,
                epoch = epoch,
                timestamp = 1795030921L
            )
        )
    }

    @Test
    fun okMessageRoundTrip() {
        assertRoundTrip(
            ElectionOkMessage(
                teamId = 1,
                nodeId = 1002L,
                epoch = epoch,
                timestamp = 1795030922L,
                targetNodeId = 1001L
            )
        )
    }

    @Test
    fun coordinatorMessageRoundTrip() {
        assertRoundTrip(
            CoordinatorMessage(
                teamId = 1,
                nodeId = 1002L,
                epoch = epoch,
                timestamp = 1795030923L,
                coordinatorNodeId = 1002L
            )
        )
    }

    @Test
    fun heartbeatMessageRoundTrip() {
        assertRoundTrip(
            HeartbeatMessage(
                teamId = 1,
                nodeId = 1002L,
                epoch = epoch,
                timestamp = 1795030924L,
                coordinatorNodeId = 1002L
            )
        )
    }

    @Test
    fun unknownMessageTypeIsRejected() {
        val payload = """
            {
                "type": "UNKNOWN",
                "team_id": 1,
                "node_id": 1001,
                "epoch_counter": 7,
                "epoch_initiator_node_id": 1001,
                "timestamp": 1795030921
            }
        """.trimIndent()

        var rejected = false

        try {
            ElectionMessageCodec.decode(payload)
        } catch (exception: IllegalArgumentException) {
            rejected = true
        }

        assertTrue("O tipo desconhecido deveria ser rejeitado.", rejected)
    }
}
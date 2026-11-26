package br.edu.uff.rescuenet.data.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrokerAddressTest {

    @Test
    fun hostWithoutPortUsesDefaultPort() {
        assertEquals(BrokerAddress("192.168.0.10", 1883), BrokerAddress.parse("192.168.0.10"))
    }

    @Test
    fun hostWithPortIsParsed() {
        assertEquals(BrokerAddress("192.168.0.10", 1884), BrokerAddress.parse("192.168.0.10:1884"))
    }

    @Test
    fun schemeSpacesAndTrailingSlashAreIgnored() {
        assertEquals(BrokerAddress("broker.local", 1883), BrokerAddress.parse("  tcp://broker.local/ "))
        assertEquals(BrokerAddress("10.0.0.2", 1883), BrokerAddress.parse("MQTT://10.0.0.2:1883"))
    }

    @Test
    fun invalidAddressesAreRejected() {
        listOf(
            "",
            "   ",
            "192.168.0.10:",
            "192.168.0.10:abc",
            "192.168.0.10:0",
            "192.168.0.10:70000",
            ":1883",
            "meu broker",
            "http://192.168.0.10"
        ).forEach { input ->
            assertNull("\"$input\" deveria ser rejeitado", BrokerAddress.parse(input))
        }
    }

    @Test
    fun defaultPortIsOmittedWhenDisplayed() {
        assertEquals("192.168.0.10", BrokerAddress("192.168.0.10", 1883).toString())
        assertEquals("192.168.0.10:1884", BrokerAddress("192.168.0.10", 1884).toString())
    }
}

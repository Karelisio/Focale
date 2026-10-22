package app.focale.cast.dlna

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Adresses du réseau local : les seules que la diffusion contacte ou laisse entrer. */
class LanAddressesTest {

    private fun address(text: String): InetAddress = requireNotNull(LanAddresses.literal(text))

    @Test
    fun `adresses privées et de lien local`() {
        for (lan in listOf("10.0.0.1", "10.255.255.254", "172.16.0.1", "172.31.255.1", "192.168.1.20", "169.254.10.3", "fe80::1", "fd12:3456::1")) {
            assertTrue(lan, LanAddresses.isLan(address(lan)))
            assertTrue(lan, LanAddresses.isLocalClient(address(lan)))
        }
    }

    @Test
    fun `adresses publiques refusées, boucle locale admise comme client seulement`() {
        for (public in listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.1.1", "100.64.0.1", "1.1.1.1", "2001:4860:4860::8888", "239.255.255.250")) {
            assertFalse(public, LanAddresses.isLan(address(public)))
            assertFalse(public, LanAddresses.isLocalClient(address(public)))
        }
        assertFalse(LanAddresses.isLan(address("127.0.0.1")))
        assertTrue(LanAddresses.isLocalClient(address("127.0.0.1")))
        assertTrue(LanAddresses.isLocalClient(address("::1")))
    }

    @Test
    fun `seules les adresses écrites en clair sont lues, aucun nom résolu`() {
        assertEquals(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20)), LanAddresses.literal("192.168.1.20"))
        assertEquals(address("fe80::1"), LanAddresses.literal("[fe80::1]"))
        assertNull(LanAddresses.literal("tv.local"))
        assertNull(LanAddresses.literal("localhost"))
        assertNull(LanAddresses.literal("192.168.1.300"))
        assertNull(LanAddresses.literal("192.168.1"))
        assertNull(LanAddresses.literal(""))
    }
}

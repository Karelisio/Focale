package app.focale.cast.dlna

import java.net.Inet6Address
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
        // Zone de l'appareil qui a écrit l'adresse : ignorée.
        assertEquals(address("fe80::1"), LanAddresses.literal("[fe80::1%wlan0]"))
        assertEquals(address("fe80::1"), LanAddresses.literal("[fe80::1%25eth0]"))
        assertNull(LanAddresses.literal("tv.local"))
        assertNull(LanAddresses.literal("localhost"))
        assertNull(LanAddresses.literal("192.168.1.300"))
        assertNull(LanAddresses.literal("192.168.1"))
        assertNull(LanAddresses.literal(""))
    }

    @Test
    fun `noms d'hôte à résoudre`() {
        for (name in listOf("tv-salon", "BRAVIA.local", "tv.home.arpa", "lg-webos-tv.lan.", "a1")) assertTrue(name, LanAddresses.isHostName(name))
        for (name in listOf("", "192.168.1.20", "192.168.1", "10.0.0.300", "fe80::1", "tv_salon", "-tv", "tv-", "a..b", "tv salon", "tv.local/x", "é.local")) {
            assertFalse(name, LanAddresses.isHostName(name))
        }
    }

    @Test
    fun `IPv4 d'abord, sinon IPv6 unique locale puis de lien local, avec l'interface du réseau`() {
        val both = requireNotNull(LinkAddresses.of(listOf(address("fe80::a"), address("192.168.1.10"), address("2a01:e0a::1")), scopeId = 3))
        assertEquals(address("192.168.1.10"), both.primary)
        assertFalse(both.ipv6Only)
        assertEquals(address("192.168.1.10"), both.searchAddress)
        assertEquals(address("239.255.255.250"), both.searchGroup)

        val ipv6 = requireNotNull(LinkAddresses.of(listOf(address("fe80::a"), address("2a01:e0a::1"), address("fd12::a")), scopeId = 3))
        assertTrue(ipv6.ipv6Only)
        // Adresse publique écartée, unique locale d'abord, lien local avec son interface.
        assertEquals(listOf(address("fd12::a"), address("fe80::a")), ipv6.ipv6.map { InetAddress.getByAddress(it.address) })
        assertEquals(3, ipv6.ipv6.last().scopeId)
        assertEquals(address("fe80::a"), InetAddress.getByAddress(ipv6.searchAddress.address))
        val group = ipv6.searchGroup as Inet6Address
        assertEquals(address("ff02::c"), InetAddress.getByAddress(group.address))
        assertEquals(3, group.scopeId)
        assertTrue("SSDP IPv6", "HOST: [FF02::C]:1900\r\n" in Ssdp.searchRequest(ipv6 = true).decodeToString())

        // Lien local sans interface connue : inutilisable ; rien de local : pas de réseau.
        assertNull(LinkAddresses.of(listOf(address("fe80::a"), address("2a01:e0a::1"), address("8.8.8.8")), scopeId = null))
        assertNull(LinkAddresses.of(emptyList(), scopeId = 3))
    }

    @Test
    fun `le serveur de médias écoute à l'adresse de la famille de la TV`() {
        val ipv6 = requireNotNull(LinkAddresses.of(listOf(address("fe80::a"), address("fd12::a")), scopeId = 5))
        fun server(links: LinkAddresses, host: String?) = links.serverFor(host).let { InetAddress.getByAddress(it.address) to (it as? Inet6Address)?.scopeId }
        assertEquals(address("fd12::a") to 0, server(ipv6, "[fd12::20]"))
        assertEquals(address("fe80::a") to 5, server(ipv6, "[fe80::20]"))
        assertEquals("nom : adresse principale", address("fd12::a") to 0, server(ipv6, "tv.local"))
        val linkOnly = requireNotNull(LinkAddresses.of(listOf(address("fe80::a")), scopeId = 5))
        assertEquals(address("fe80::a") to 5, server(linkOnly, "[fd12::20]"))

        val dual = requireNotNull(LinkAddresses.of(listOf(address("192.168.1.10"), address("fe80::a")), scopeId = 5))
        assertEquals(address("192.168.1.10") to null, server(dual, "192.168.1.20"))
        assertEquals(address("192.168.1.10") to null, server(dual, "tv.local"))
        assertEquals(address("fe80::a") to 5, server(dual, "[fe80::20]"))

        // Joignable d'ici : même famille, réseau local seulement.
        assertTrue(ipv6.reaches(address("fe80::20")))
        assertFalse(ipv6.reaches(address("192.168.1.20")))
        assertFalse(ipv6.reaches(address("2a01:e0a::1")))
        assertTrue(dual.reaches(address("192.168.1.20")))
        // Seul le lien local reçoit l'interface.
        assertEquals(5, (ipv6.scoped(address("fe80::20")) as Inet6Address).scopeId)
        assertEquals(0, (ipv6.scoped(address("fd12::20")) as Inet6Address).scopeId)
        assertEquals(address("192.168.1.20"), dual.scoped(address("192.168.1.20")))
    }
}

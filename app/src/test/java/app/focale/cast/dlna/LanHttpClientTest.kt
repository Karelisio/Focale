package app.focale.cast.dlna

import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.net.UnknownHostException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Noms et adresses IPv6 des TV ([LanHttpClient]) : un nom n'est contacté que s'il mène au réseau
 * local, à l'adresse résolue, sans seconde résolution ; une IPv6 de lien local reçoit l'interface
 * du réseau. Résolutions simulées : aucune requête DNS dans les tests.
 */
class LanHttpClientTest {

    private val tv = FakeTv()

    @After
    fun tearDown() = tv.close()

    private fun ip(text: String): InetAddress = requireNotNull(LanAddresses.literal(text))

    /** Adresse [text] sous le nom [name], comme la rend une résolution. */
    private fun named(name: String, text: String): InetAddress = InetAddress.getByAddress(name, ip(text).address)

    /** Socket qui note l'adresse visée et refuse la connexion : rien ne part. */
    private class Recorder {
        val targets: MutableList<SocketAddress> = Collections.synchronizedList(ArrayList())
        fun socket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                targets += endpoint
                throw IOException("connexion simulée")
            }
        }
    }

    private fun refused(block: () -> Unit) {
        try {
            block()
            fail("requête envoyée")
        } catch (refused: LanHttpException) {
            // Refusée avant toute connexion.
        }
    }

    @Test
    fun `un nom local est résolu une fois, contacté à son adresse, et garde son nom dans Host`() {
        val queries = AtomicInteger()
        val http = LanHttpClient(
            hostAllowed = { it.isLoopbackAddress },
            resolver = { name ->
                queries.incrementAndGet()
                assertEquals("salon.local", name)
                listOf(named(name, "127.0.0.1"))
            },
        )
        val port = tv.socket.localPort
        tv.respond("200 OK", "<root/>")
        tv.respond("200 OK", "<root/>")
        assertEquals(200, http.get("http://Salon.local:$port/dmr").status)
        assertEquals(200, http.get("http://salon.local.:$port/dmr").status)
        assertEquals("Salon.local:$port", tv.next().headers["host"])
        tv.next()
        assertEquals("une seule résolution par nom", 1, queries.get())
    }

    @Test
    fun `un nom public, ou qui ne se résout pas, est refusé sans connexion`() {
        val recorder = Recorder()
        val names = mapOf(
            "tv.example.com" to listOf("93.184.216.34"),
            "piege.local" to listOf("8.8.8.8", "2001:4860:4860::8888"),
            "boucle" to listOf("127.0.0.1"),
        )
        val http = LanHttpClient(
            socketFactory = recorder::socket,
            resolver = { name -> names[name]?.map { named(name, it) } ?: throw UnknownHostException(name) },
        )
        for (url in listOf("http://tv.example.com/dmr", "http://piege.local:8080/", "http://boucle/", "http://inconnue.local/")) {
            refused { http.get(url) }
        }
        // Noms mal formés : pas même résolus.
        for (url in listOf("http://tv_salon/", "http://-tv/", "http://192.168.1/", "http://a..b/")) {
            refused { http.get(url) }
        }
        assertTrue(recorder.targets.isEmpty())
    }

    @Test
    fun `parmi les adresses d'un nom, seule une locale est contactée, IPv4 d'abord`() {
        val recorder = Recorder()
        val http = LanHttpClient(
            socketFactory = recorder::socket,
            resolver = { name -> listOf(named(name, "8.8.8.8"), named(name, "fd00::20"), named(name, "192.168.1.20")) },
        )
        runCatching { http.get("http://bravia.home.arpa:52323/dmr.xml") }
        val target = recorder.targets.single() as InetSocketAddress
        assertEquals(ip("192.168.1.20"), target.address)
        assertEquals(52323, target.port)
    }

    @Test
    fun `une résolution trop longue est abandonnée`() {
        val never = CountDownLatch(1)
        val http = LanHttpClient(
            resolver = {
                never.await()
                emptyList()
            },
            resolveTimeoutMs = 50,
        )
        try {
            refused { http.get("http://lente.local/") }
        } finally {
            never.countDown()
        }
    }

    @Test
    fun `une IPv6 de lien local reçoit l'interface du réseau, sa zone d'origine est ignorée`() {
        val recorder = Recorder()
        val link = LinkAddresses.of(listOf(ip("fe80::a"), ip("fd00::a")), scopeId = 7)!!
        val http = LanHttpClient(socketFactory = recorder::socket, hostAllowed = link::reaches, scoped = link::scoped)
        runCatching { http.get("http://[fe80::1234]:8080/dmr") }
        runCatching { http.get("http://[fe80::1234%25wlan0]:8080/dmr") }
        runCatching { http.get("http://[fd00::20]:8080/dmr") }
        val (bare, zoned, unique) = recorder.targets.map { (it as InetSocketAddress).address as Inet6Address }
        for (linkLocal in listOf(bare, zoned)) {
            assertEquals(ip("fe80::1234"), InetAddress.getByAddress(linkLocal.address))
            assertEquals(7, linkLocal.scopeId)
        }
        assertEquals(0, unique.scopeId)
        // Une IPv4 ne se joint pas d'un réseau qui n'en a pas.
        refused { http.get("http://192.168.1.20:8080/dmr") }
        assertEquals(3, recorder.targets.size)
    }
}

package app.focale.cast.dlna

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Serveur de médias de la diffusion, sur la boucle locale : jeton du chemin, `Range`, méthodes,
 * refus des clients hors réseau local, remplacement du média et arrêt.
 */
class MediaServerTest {

    private val loopback: InetAddress = InetAddress.getByName("127.0.0.1")
    private val video = ByteArray(10_000) { (it % 251).toByte() }
    private val servers = mutableListOf<MediaServer>()

    @After
    fun tearDown() {
        servers.forEach(MediaServer::stop)
    }

    private fun server(clientAllowed: (InetAddress) -> Boolean = LanAddresses::isLocalClient): MediaServer =
        MediaServer(loopback, clientAllowed).also {
            it.start()
            servers += it
        }

    private class Reply(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    /** Requête brute, réponse lue jusqu'à la fermeture (le serveur ferme chaque connexion). */
    private fun request(url: String, method: String = "GET", headers: Map<String, String> = emptyMap()): Reply {
        val uri = URI(url)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(uri.host, uri.port), 2_000)
            socket.soTimeout = 5_000
            val head = buildString {
                append("$method ${uri.rawPath} HTTP/1.1\r\nHost: ${uri.host}:${uri.port}\r\n")
                headers.forEach { (name, value) -> append("$name: $value\r\n") }
                append("\r\n")
            }
            socket.getOutputStream().write(head.toByteArray())
            val input = socket.getInputStream().buffered()
            val status = HttpWire.readLine(input)!!.split(' ')[1].toInt()
            val replyHeaders = HttpWire.readHeaders(input)
            val body = ByteArrayOutputStream().also { input.copyTo(it) }.toByteArray()
            return Reply(status, replyHeaders, body)
        }
    }

    @Test
    fun `le média publié est servi entier, avec les en-têtes DLNA`() {
        val server = server()
        val url = server.publish(BytesMedia(video, "video/mp4", video = true))
        assertTrue(url.startsWith("http://127.0.0.1:${server.port}/"))
        assertTrue(url.endsWith("/media.mp4"))
        val reply = request(url)
        assertEquals(200, reply.status)
        assertArrayEquals(video, reply.body)
        assertEquals("video/mp4", reply.headers["content-type"])
        assertEquals("10000", reply.headers["content-length"])
        assertEquals("bytes", reply.headers["accept-ranges"])
        assertEquals("Streaming", reply.headers["transfermode.dlna.org"])
        assertTrue(reply.headers["contentfeatures.dlna.org"]!!.startsWith("DLNA.ORG_OP=01"))
        assertEquals("close", reply.headers["connection"])
    }

    @Test
    fun `jeton de 128 bits, neuf à chaque média, un autre chemin ne sert rien`() {
        val server = server()
        val first = server.publish(BytesMedia(video, "video/mp4", video = true))
        val token = URI(first).path.split('/')[1]
        assertTrue(Regex("[0-9a-f]{32}").matches(token))
        // Jeton faux, nom faux, racine, jeton seul.
        val base = "http://127.0.0.1:${server.port}"
        assertEquals(404, request("$base/${"0".repeat(32)}/media.mp4").status)
        assertEquals(404, request("$base/$token/autre.mp4").status)
        assertEquals(404, request("$base/").status)
        assertEquals(404, request("$base/$token").status)
        assertEquals(404, request("$base/../$token/media.mp4").status)
        // Nouveau média : l'ancienne URL ne sert plus.
        val photo = byteArrayOf(1, 2, 3)
        val second = server.publish(BytesMedia(photo, "image/jpeg"))
        assertNotEquals(first, second)
        assertEquals(404, request(first).status)
        val reply = request(second)
        assertEquals(200, reply.status)
        assertArrayEquals(photo, reply.body)
        assertEquals("Interactive", reply.headers["transfermode.dlna.org"])
        // Plus rien de publié.
        server.clear()
        assertEquals(404, request(second).status)
    }

    @Test
    fun `plages Range`() {
        val server = server()
        val url = server.publish(BytesMedia(video, "video/mp4", video = true))
        val part = request(url, headers = mapOf("Range" to "bytes=100-199"))
        assertEquals(206, part.status)
        assertEquals("bytes 100-199/10000", part.headers["content-range"])
        assertEquals("100", part.headers["content-length"])
        assertArrayEquals(video.copyOfRange(100, 200), part.body)

        val open = request(url, headers = mapOf("Range" to "bytes=9990-"))
        assertEquals(206, open.status)
        assertArrayEquals(video.copyOfRange(9_990, 10_000), open.body)

        val suffix = request(url, headers = mapOf("Range" to "bytes=-5"))
        assertEquals("bytes 9995-9999/10000", suffix.headers["content-range"])
        assertArrayEquals(video.copyOfRange(9_995, 10_000), suffix.body)

        // Fin au-delà du contenu : ramenée au dernier octet.
        assertEquals("bytes 9000-9999/10000", request(url, headers = mapOf("Range" to "bytes=9000-20000")).headers["content-range"])

        val outside = request(url, headers = mapOf("Range" to "bytes=10000-"))
        assertEquals(416, outside.status)
        assertEquals("bytes */10000", outside.headers["content-range"])
        assertEquals(0, outside.body.size)

        // Plusieurs plages : le contenu entier.
        assertEquals(200, request(url, headers = mapOf("Range" to "bytes=0-1,5-6")).status)
    }

    @Test
    fun `lecture de l'en-tête Range`() {
        assertEquals(ByteRange.Full, ByteRange.parse(null, 100))
        assertEquals(ByteRange.Part(0, 99), ByteRange.parse("bytes=0-", 100))
        assertEquals(ByteRange.Part(10, 20), ByteRange.parse("bytes=10-20", 100))
        assertEquals(ByteRange.Part(90, 99), ByteRange.parse("bytes=-10", 100))
        assertEquals(ByteRange.Part(0, 99), ByteRange.parse("bytes=-500", 100))
        assertEquals(ByteRange.Unsatisfiable, ByteRange.parse("bytes=20-10", 100))
        assertEquals(ByteRange.Unsatisfiable, ByteRange.parse("bytes=-0", 100))
        assertEquals(ByteRange.Full, ByteRange.parse("items=0-1", 100))
        assertEquals(ByteRange.Full, ByteRange.parse("bytes=abc", 100))
    }

    @Test
    fun `HEAD sans corps, autres méthodes refusées`() {
        val server = server()
        val url = server.publish(BytesMedia(video, "video/mp4", video = true))
        val head = request(url, method = "HEAD")
        assertEquals(200, head.status)
        assertEquals("10000", head.headers["content-length"])
        assertEquals(0, head.body.size)
        val post = request(url, method = "POST")
        assertEquals(405, post.status)
        assertEquals("GET, HEAD", post.headers["allow"])
        assertEquals(405, request(url, method = "DELETE").status)
    }

    @Test
    fun `client hors du réseau local refusé, sans rien lire du média`() {
        var asked: InetAddress? = null
        val server = server(clientAllowed = { address ->
            asked = address
            false
        })
        val opened = BooleanArray(1)
        val url = server.publish(
            object : ServedMedia {
                override val mimeType = "image/jpeg"
                override val length = 3L
                override val video = false
                override fun open(offset: Long) = byteArrayOf(1, 2, 3).inputStream().also { opened[0] = true }
            },
        )
        val reply = request(url)
        assertEquals(403, reply.status)
        assertEquals(0, reply.body.size)
        assertEquals(loopback, asked)
        assertFalse(opened[0])
    }

    @Test
    fun `arrêt, plus d'écoute, plus de publication`() {
        val server = server()
        val url = server.publish(BytesMedia(video, "video/mp4", video = true))
        assertEquals(200, request(url).status)
        server.stop()
        try {
            request(url)
            fail("le serveur arrêté répond encore")
        } catch (expected: IOException) {
            // Connexion refusée.
        }
        try {
            server.publish(BytesMedia(video, "video/mp4", video = true))
            fail("publication après l'arrêt")
        } catch (expected: IllegalStateException) {
            // Attendu.
        }
    }

    @Test
    fun `arrêt pendant un transfert, la connexion est coupée`() {
        val server = server()
        val big = ByteArray(64 * 1024 * 1024)
        val url = server.publish(BytesMedia(big, "video/mp4", video = true))
        val uri = URI(url)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(uri.host, uri.port), 2_000)
            socket.soTimeout = 5_000
            socket.getOutputStream().write("GET ${uri.rawPath} HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val input = socket.getInputStream()
            // Le transfert a commencé…
            assertTrue(input.read(ByteArray(1024)) > 0)
            server.stop()
            // … puis s'interrompt bien avant la fin.
            var received = 0L
            try {
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    received += read
                }
            } catch (cut: IOException) {
                // Connexion réinitialisée : attendu aussi.
            }
            assertTrue("reçu $received octets", received < big.size)
        }
    }
}

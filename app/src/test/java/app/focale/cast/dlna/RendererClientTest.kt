package app.focale.cast.dlna

import java.io.ByteArrayOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Commandes d'une TV factice (boucle locale) : requêtes SOAP envoyées (en-têtes, corps), réponses
 * lues (longueur fixe ou découpée), erreurs UPnP ; client HTTP qui refuse tout hors du réseau local.
 */
class RendererClientTest {

    private val tv = FakeTv()

    @After
    fun tearDown() {
        tv.socket.close()
    }

    private val renderer = tv.renderer()

    private val client = RendererClient(renderer, LanHttpClient(hostAllowed = { it.isLoopbackAddress }))

    private fun envelope(action: String, service: String = "AVTransport", values: String = "") = FakeTv.envelope(action, service, values)

    @Test
    fun `SetAVTransportURI puis Play, en-têtes et corps attendus par la TV`() {
        tv.respond("200 OK", envelope("SetAVTransportURI"))
        tv.respond("200 OK", envelope("Play"))
        client.setUri("http://192.168.1.10:4000/abc/media.jpg", Didl.metadata("IMG.jpg", "http://192.168.1.10:4000/abc/media.jpg", "image/jpeg", false))
        client.play()

        val set = tv.next()
        assertEquals("POST /upnp/control/AVTransport1 HTTP/1.1", set.line)
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"", set.headers["soapaction"])
        assertEquals("text/xml; charset=\"utf-8\"", set.headers["content-type"])
        assertEquals("127.0.0.1:${tv.socket.localPort}", set.headers["host"])
        assertEquals("close", set.headers["connection"])
        val arguments = XmlTree.parse(set.body)!!.find("SetAVTransportURI")!!
        assertEquals("0", arguments.childText("InstanceID"))
        assertEquals("http://192.168.1.10:4000/abc/media.jpg", arguments.childText("CurrentURI"))
        assertTrue(arguments.childText("CurrentURIMetaData")!!.startsWith("<DIDL-Lite"))

        val play = tv.next()
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#Play\"", play.headers["soapaction"])
        assertEquals("1", XmlTree.parse(play.body)!!.find("Play")!!.childText("Speed"))
    }

    @Test
    fun `Seek, état et position, réponses découpées comprises`() {
        tv.respond("200 OK", envelope("Seek"))
        tv.respond(
            "200 OK",
            envelope("GetTransportInfo", values = "<CurrentTransportState>PLAYING</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>"),
            chunked = true,
        )
        tv.respond("200 OK", envelope("GetPositionInfo", values = "<Track>1</Track><TrackDuration>0:02:00</TrackDuration><RelTime>0:00:42.250</RelTime>"))
        tv.respond("200 OK", envelope("GetTransportInfo", values = "<CurrentTransportState>STOPPED</CurrentTransportState><CurrentTransportStatus>ERROR_OCCURRED</CurrentTransportStatus>"))

        client.seek(83_900)
        val seek = XmlTree.parse(tv.next().body)!!.find("Seek")!!
        assertEquals("REL_TIME", seek.childText("Unit"))
        assertEquals("00:01:23", seek.childText("Target"))

        assertEquals(TransportInfo(TransportState.PLAYING, ok = true), client.transportInfo())
        assertEquals(PositionInfo(positionMs = 42_250, durationMs = 120_000), client.position())
        assertEquals(TransportInfo(TransportState.STOPPED, ok = false), client.transportInfo())
    }

    @Test
    fun `volume par RenderingControl`() {
        tv.respond("200 OK", envelope("GetVolume", "RenderingControl", "<CurrentVolume>23</CurrentVolume>"))
        tv.respond("200 OK", envelope("SetVolume", "RenderingControl"))
        assertEquals(23, client.volume())
        assertTrue(tv.next().line.startsWith("POST /upnp/control/RenderingControl1 "))
        client.setVolume(140)
        val set = tv.next()
        assertEquals("\"urn:schemas-upnp-org:service:RenderingControl:1#SetVolume\"", set.headers["soapaction"])
        assertEquals("100", XmlTree.parse(set.body)!!.find("SetVolume")!!.childText("DesiredVolume"))
        assertEquals("Master", XmlTree.parse(set.body)!!.find("SetVolume")!!.childText("Channel"))

        // Sans RenderingControl, rien n'est envoyé.
        val mute = RendererClient(renderer.copy(renderingControl = null), LanHttpClient(hostAllowed = { true }))
        assertNull(mute.volume())
        mute.setVolume(10)
        assertTrue(tv.requests.isEmpty())
    }

    @Test
    fun `erreur UPnP de la TV`() {
        tv.respond(
            "500 Internal Server Error",
            "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault>" +
                "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">" +
                "<errorCode>714</errorCode><errorDescription>Illegal MIME-type</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>",
        )
        tv.respond("404 Not Found", "")
        try {
            client.setUri("http://192.168.1.10:4000/abc/media.mkv", "")
            fail("format accepté")
        } catch (refused: UpnpException) {
            assertEquals(714, refused.code)
            assertTrue(refused.refusesMedia)
        }
        try {
            client.stop()
            fail("erreur HTTP ignorée")
        } catch (failed: UpnpException) {
            assertNull(failed.code)
            assertFalse(failed.refusesMedia)
        }
    }

    @Test
    fun `le client ne contacte que le réseau local, sans DNS`() {
        val http = LanHttpClient()
        for (url in listOf(
            "http://8.8.8.8/dmr",
            "http://tv.example.com/dmr",
            "https://192.168.1.20/dmr",
            "http://127.0.0.1:${tv.socket.localPort}/dmr",
            "pas une URL",
        )) {
            try {
                http.get(url)
                fail("requête envoyée à $url")
            } catch (refused: LanHttpException) {
                // Refusée avant toute connexion.
            }
        }
        assertTrue(tv.requests.isEmpty())
    }

    @Test
    fun `lecture d'un corps découpé`() {
        val body = "4\r\nWiki\r\n6;ext=1\r\npedia \r\n0\r\nX-Trailer: 1\r\n\r\n"
        val read = HttpWire.readBody(body.byteInputStream(), mapOf("transfer-encoding" to "chunked"), 1_024)
        assertEquals("Wikipedia ", read.decodeToString())
        try {
            HttpWire.readBody("ffff\r\n".byteInputStream(), mapOf("transfer-encoding" to "chunked"), 16)
            fail("bloc trop long accepté")
        } catch (tooLong: LanHttpException) {
            // Attendu.
        }
        val fixed = ByteArrayOutputStream().apply { write("abc".toByteArray()) }.toByteArray()
        assertEquals("abc", HttpWire.readBody(fixed.inputStream(), mapOf("content-length" to "3"), 16).decodeToString())
    }
}

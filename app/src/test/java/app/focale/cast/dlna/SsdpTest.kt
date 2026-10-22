package app.focale.cast.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Requête M-SEARCH et réponses SSDP de vraies TV (Samsung, LG, Sony, Kodi), avec leurs écarts de forme. */
class SsdpTest {

    @Test
    fun `la recherche vise les lecteurs de médias sans rien dire de l'appareil`() {
        val request = Ssdp.searchRequest().decodeToString()
        assertEquals(
            "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n",
            request,
        )
        assertFalse("USER-AGENT" in request.uppercase())
        // MX borné à ce que permet UPnP 1.1.
        assertTrue("MX: 5\r\n" in Ssdp.searchRequest(mx = 30).decodeToString())
    }

    @Test
    fun `réponse d'une TV Samsung`() {
        val message = Ssdp.parse(
            "HTTP/1.1 200 OK\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "DATE: Thu, 22 Oct 2026 08:00:00 GMT\r\n" +
                "EXT:\r\n" +
                "LOCATION: http://192.168.1.20:9197/dmr\r\n" +
                "SERVER: SHP, UPnP/1.0, Samsung UPnP SDK/1.0\r\n" +
                "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                "USN: uuid:0f8e6a2c-00b4-1000-a1c2-f877b8d4e3a1::urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                "Content-Length: 0\r\n" +
                "BOOTID.UPNP.ORG: 23\r\n\r\n",
        )!!
        assertEquals(SsdpMessage.Kind.RESPONSE, message.kind)
        assertEquals("http://192.168.1.20:9197/dmr", message.location)
        assertEquals("uuid:0f8e6a2c-00b4-1000-a1c2-f877b8d4e3a1", message.udn)
        assertEquals(1800, message.maxAgeSeconds)
        assertEquals("SHP, UPnP/1.0, Samsung UPnP SDK/1.0", message.server)
        assertTrue(message.isRenderer)
    }

    @Test
    fun `réponse d'une TV LG, en-têtes en casse mixte et fins de ligne LF`() {
        val message = Ssdp.parse(
            "HTTP/1.1 200 OK\n" +
                "Cache-Control: max-age = 1800\n" +
                "Ext: \n" +
                "Location: http://192.168.1.30:1105/\n" +
                "Server: WebOS/4.1.0 UPnP/1.0\n" +
                "st: urn:schemas-upnp-org:device:MediaRenderer:1\n" +
                "Usn: uuid:7a1d4a2e-8c3b-4f6e-9a77-acf108d2c0b1::urn:schemas-upnp-org:device:MediaRenderer:1\n\n",
        )!!
        assertEquals("http://192.168.1.30:1105/", message.location)
        assertEquals("uuid:7a1d4a2e-8c3b-4f6e-9a77-acf108d2c0b1", message.udn)
        assertEquals(1800, message.maxAgeSeconds)
        assertTrue(message.isRenderer)
    }

    @Test
    fun `réponse d'une TV Sony qui annonce son service AVTransport`() {
        val message = Ssdp.parse(
            "HTTP/1.1 200 OK\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "EXT:\r\n" +
                "LOCATION: http://192.168.1.40:52323/dmr.xml\r\n" +
                "SERVER: Linux/4.9 UPnP/1.0 SonyTV/2.0\r\n" +
                "ST: urn:schemas-upnp-org:service:AVTransport:1\r\n" +
                "USN: uuid:4c534a7e-1c6b-11eb-8e55-104fa8a1b2c3::urn:schemas-upnp-org:service:AVTransport:1\r\n\r\n",
        )!!
        assertEquals("http://192.168.1.40:52323/dmr.xml", message.location)
        assertEquals("uuid:4c534a7e-1c6b-11eb-8e55-104fa8a1b2c3", message.udn)
        assertTrue(message.isRenderer)
    }

    @Test
    fun `annonces de présence et de départ de Kodi`() {
        val alive = Ssdp.parse(
            "NOTIFY * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "LOCATION: http://192.168.1.50:1704/\r\n" +
                "NT: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                "NTS: ssdp:alive\r\n" +
                "SERVER: UPnP/1.0 DLNADOC/1.50 Kodi\r\n" +
                "USN: uuid:a9a3d6a8-63ad-4b7f-b1bb-2f6c2f2b9d41::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n",
        )!!
        assertEquals(SsdpMessage.Kind.ALIVE, alive.kind)
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", alive.type)
        assertEquals("http://192.168.1.50:1704/", alive.location)

        val byebye = Ssdp.parse(
            "NOTIFY * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "NT: urn:schemas-upnp-org:device:MediaRenderer:1\r\n" +
                "NTS: ssdp:byebye\r\n" +
                "USN: uuid:a9a3d6a8-63ad-4b7f-b1bb-2f6c2f2b9d41::urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n",
        )!!
        assertEquals(SsdpMessage.Kind.BYEBYE, byebye.kind)
        assertNull(byebye.location)
        assertEquals("uuid:a9a3d6a8-63ad-4b7f-b1bb-2f6c2f2b9d41", byebye.udn)
    }

    @Test
    fun `ce qui n'est pas une annonce de lecteur est écarté ou signalé`() {
        // M-SEARCH d'un autre appareil, réponse d'erreur, présence sans LOCATION.
        assertNull(Ssdp.parse(Ssdp.searchRequest().decodeToString()))
        assertNull(Ssdp.parse("HTTP/1.1 404 Not Found\r\nLOCATION: http://192.168.1.2/\r\n\r\n"))
        assertNull(Ssdp.parse("HTTP/1.1 200 OK\r\nST: upnp:rootdevice\r\n\r\n"))
        assertNull(Ssdp.parse(""))
        assertNull(Ssdp.parse("NOTIFY * HTTP/1.1\r\nNTS: ssdp:update\r\nLOCATION: http://192.168.1.2/\r\n\r\n"))
        // Routeur qui répond pour tout autre type : lu, mais pas un lecteur.
        val router = Ssdp.parse(
            "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.1:5000/rootDesc.xml\r\n" +
                "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\nUSN: uuid:1234::upnp:rootdevice\r\n\r\n",
        )!!
        assertFalse(router.isRenderer)
        // USN sans uuid : pas d'identifiant.
        assertNull(Ssdp.parse("HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.3/\r\nUSN: tv-salon\r\n\r\n")!!.udn)
    }
}

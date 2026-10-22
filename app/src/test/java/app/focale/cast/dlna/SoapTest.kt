package app.focale.cast.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Construction et lecture des messages SOAP d'AVTransport et de RenderingControl, métadonnées DIDL-Lite, durées UPnP. */
class SoapTest {

    private val avTransport = "urn:schemas-upnp-org:service:AVTransport:1"

    @Test
    fun `enveloppe d'une action, arguments échappés dans l'ordre`() {
        val metadata = Didl.metadata(title = "Plage & dunes <été>", url = "http://192.168.1.10:41234/abc/media.jpg", mimeType = "image/jpeg", video = false)
        val envelope = Soap.envelope(
            avTransport,
            "SetAVTransportURI",
            listOf("InstanceID" to "0", "CurrentURI" to "http://192.168.1.10:41234/abc/media.jpg", "CurrentURIMetaData" to metadata),
        )
        assertTrue(envelope.startsWith("<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope"))
        assertTrue("<u:SetAVTransportURI xmlns:u=\"$avTransport\"><InstanceID>0</InstanceID><CurrentURI>" in envelope)
        // Les métadonnées, un document XML, passent échappées dans leur argument.
        assertTrue("<CurrentURIMetaData>&lt;DIDL-Lite" in envelope)
        assertFalse("<DIDL-Lite" in envelope)
        assertEquals("\"$avTransport#Play\"", Soap.actionHeader(avTransport, "Play"))

        // Relue comme le ferait la TV : arguments retrouvés tels quels.
        val body = XmlTree.parse(envelope)!!.find("SetAVTransportURI")!!
        assertEquals(listOf("InstanceID", "CurrentURI", "CurrentURIMetaData"), body.children.map { it.name })
        assertEquals(metadata, body.childText("CurrentURIMetaData"))
        val didl = XmlTree.parse(metadata)!!
        assertEquals("Plage & dunes <été>", didl.find("title")!!.text)
        assertEquals("object.item.imageItem.photo", didl.find("class")!!.text)
    }

    @Test
    fun `réponse GetPositionInfo d'une TV Samsung`() {
        val response = """
            <?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:GetPositionInfoResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"><Track>1</Track><TrackDuration>00:01:30</TrackDuration><TrackMetaData>&lt;DIDL-Lite&gt;&lt;/DIDL-Lite&gt;</TrackMetaData><TrackURI>http://192.168.1.10:41234/abc/media.mp4</TrackURI><RelTime>00:00:42</RelTime><AbsTime>00:00:42</AbsTime><RelCount>2147483647</RelCount><AbsCount>2147483647</AbsCount></u:GetPositionInfoResponse></s:Body></s:Envelope>
        """.trimIndent()
        val result = Soap.parseResponse(response.toByteArray(), "GetPositionInfo") as SoapResult.Success
        assertEquals("00:00:42", result.values["RelTime"])
        assertEquals("00:01:30", result.values["TrackDuration"])
        assertEquals("<DIDL-Lite></DIDL-Lite>", result.values["TrackMetaData"])
    }

    @Test
    fun `réponse de Kodi, préfixe SOAP-ENV`() {
        val response = """
            <?xml version="1.0" encoding="UTF-8"?>
            <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/" SOAP-ENV:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
              <SOAP-ENV:Body>
                <m:GetTransportInfoResponse xmlns:m="urn:schemas-upnp-org:service:AVTransport:1">
                  <CurrentTransportState>PAUSED_PLAYBACK</CurrentTransportState>
                  <CurrentTransportStatus>OK</CurrentTransportStatus>
                  <CurrentSpeed>1</CurrentSpeed>
                </m:GetTransportInfoResponse>
              </SOAP-ENV:Body>
            </SOAP-ENV:Envelope>
        """.trimIndent()
        val result = Soap.parseResponse(response.toByteArray(), "GetTransportInfo") as SoapResult.Success
        assertEquals(TransportState.PAUSED, TransportState.of(result.values["CurrentTransportState"]))
        assertEquals("OK", result.values["CurrentTransportStatus"])
    }

    @Test
    fun `erreur UPnP d'une TV LG qui refuse un format`() {
        val fault = """
            <?xml version="1.0" encoding="utf-8"?>
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
            <s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring>
            <detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>714</errorCode><errorDescription>Illegal MIME-type</errorDescription></UPnPError></detail>
            </s:Fault></s:Body></s:Envelope>
        """.trimIndent()
        val result = Soap.parseResponse(fault.toByteArray(), "SetAVTransportURI") as SoapResult.Fault
        assertEquals(714, result.code)
        assertEquals("Illegal MIME-type", result.description)
        assertTrue(UpnpException(result.code, result.description).refusesMedia)
        assertFalse(UpnpException(701, "Transition not available").refusesMedia)
        // Ni réponse ni erreur : illisible.
        assertNull(Soap.parseResponse("<html/>".toByteArray(), "Play"))
        assertNull(Soap.parseResponse("pas du XML".toByteArray(), "Play"))
    }

    @Test
    fun `métadonnées DIDL-Lite d'une vidéo`() {
        val didl = Didl.metadata(
            title = "VID_20260101.mp4",
            url = "http://192.168.1.10:41234/abc/media.mp4",
            mimeType = "video/mp4",
            video = true,
            sizeBytes = 12_345_678,
            durationMs = 3_723_000,
            width = 1920,
            height = 1080,
        )
        val res = XmlTree.parse(didl)!!.find("res")!!
        assertEquals("http://192.168.1.10:41234/abc/media.mp4", res.text)
        assertTrue("protocolInfo=\"http-get:*:video/mp4:DLNA.ORG_OP=01;DLNA.ORG_CI=0;" in didl)
        assertTrue("size=\"12345678\"" in didl)
        assertTrue("duration=\"01:02:03.000\"" in didl)
        assertTrue("resolution=\"1920x1080\"" in didl)
        assertTrue("<upnp:class>object.item.videoItem</upnp:class>" in didl)
        assertTrue(Didl.contentFeatures(video = false).startsWith("DLNA.ORG_PN=JPEG_LRG;"))
    }

    @Test
    fun `durées UPnP`() {
        assertEquals("00:00:00", UpnpTime.format(-5))
        assertEquals("00:01:05", UpnpTime.format(65_400))
        assertEquals("02:00:00", UpnpTime.format(7_200_000))
        assertEquals(62_000L, UpnpTime.parse("0:01:02"))
        assertEquals(62_500L, UpnpTime.parse("00:01:02.500"))
        assertEquals(62_500L, UpnpTime.parse("00:01:02.1/2"))
        assertEquals(36_000_000L, UpnpTime.parse("10:00:00"))
        assertNull(UpnpTime.parse("NOT_IMPLEMENTED"))
        assertNull(UpnpTime.parse("00:61:00"))
        assertNull(UpnpTime.parse(null))
        assertNull(UpnpTime.parse("12"))
    }

    @Test
    fun `états de lecture`() {
        assertEquals(TransportState.PLAYING, TransportState.of("PLAYING"))
        assertEquals(TransportState.PAUSED, TransportState.of("paused_playback"))
        assertEquals(TransportState.NO_MEDIA, TransportState.of("NO_MEDIA_PRESENT"))
        assertEquals(TransportState.TRANSITIONING, TransportState.of(" TRANSITIONING "))
        assertEquals(TransportState.UNKNOWN, TransportState.of(null))
    }
}

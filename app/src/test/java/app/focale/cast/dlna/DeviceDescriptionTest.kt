package app.focale.cast.dlna

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Descriptions d'appareils de vraies TV (Samsung Tizen, LG webOS, Sony Bravia, Kodi) : nom, services
 * et URL de commande résolues (chemin absolu, relatif au dossier, `URLBase`), appareil imbriqué,
 * documents refusés.
 */
class DeviceDescriptionTest {

    private fun fixture(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/cast/$name")).use { it.readBytes() }

    @Test
    fun `TV Samsung`() {
        val tv = DeviceDescription.parse(fixture("samsung_dmr.xml"), "http://192.168.1.20:9197/dmr")!!
        assertEquals("[TV] Samsung Q60 Series (55)", tv.name)
        assertEquals("Samsung Electronics", tv.manufacturer)
        assertEquals("QE55Q60TAU", tv.model)
        assertEquals("uuid:0f8e6a2c-00b4-1000-a1c2-f877b8d4e3a1", tv.udn)
        assertEquals(DlnaService("urn:schemas-upnp-org:service:AVTransport:1", "http://192.168.1.20:9197/upnp/control/AVTransport1"), tv.avTransport)
        assertEquals("http://192.168.1.20:9197/upnp/control/RenderingControl1", tv.renderingControl?.controlUrl)
    }

    @Test
    fun `TV LG, URL relatives au dossier de la description`() {
        val tv = DeviceDescription.parse(fixture("lg_webos.xml"), "http://192.168.1.30:1105/")!!
        assertEquals("[LG] webOS TV OLED55C1PUB", tv.name)
        assertEquals("LG Electronics", tv.manufacturer)
        assertEquals(
            "http://192.168.1.30:1105/AVTransport/7a1d4a2e-8c3b-4f6e-9a77-acf108d2c0b1/control.xml",
            tv.avTransport.controlUrl,
        )
        assertEquals(
            "http://192.168.1.30:1105/RenderingControl/7a1d4a2e-8c3b-4f6e-9a77-acf108d2c0b1/control.xml",
            tv.renderingControl?.controlUrl,
        )
    }

    @Test
    fun `TV Sony, éléments propres au fabricant ignorés`() {
        val tv = DeviceDescription.parse(fixture("sony_bravia.xml"), "http://192.168.1.40:52323/dmr.xml")!!
        assertEquals("KD-55XH9505", tv.name)
        assertEquals("Sony Corporation", tv.manufacturer)
        assertEquals("http://192.168.1.40:52323/upnp/control/AVTransport", tv.avTransport.controlUrl)
        assertEquals("http://192.168.1.40:52323/upnp/control/RenderingControl", tv.renderingControl?.controlUrl)
    }

    @Test
    fun `Kodi, URLBase sans chemin plutôt que l'adresse de la description`() {
        val tv = DeviceDescription.parse(fixture("kodi.xml"), "http://192.168.1.50:1704/description.xml")!!
        assertEquals("Kodi (salon)", tv.name)
        assertEquals("XBMC Foundation", tv.manufacturer)
        assertEquals("uuid:a9a3d6a8-63ad-4b7f-b1bb-2f6c2f2b9d41", tv.udn)
        assertEquals(
            "http://192.168.1.50:1704/AVTransport/a9a3d6a8-63ad-4b7f-b1bb-2f6c2f2b9d41/control.xml",
            tv.avTransport.controlUrl,
        )
    }

    @Test
    fun `lecteur imbriqué dans un appareil racine, AVTransport d'une version ultérieure`() {
        val xml = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <device>
                <deviceType>urn:schemas-denon-com:device:AiosDevice:1</deviceType>
                <friendlyName>Ampli du salon</friendlyName>
                <UDN>uuid:root</UDN>
                <deviceList>
                  <device>
                    <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
                    <friendlyName>Serveur</friendlyName>
                    <UDN>uuid:server</UDN>
                  </device>
                  <device>
                    <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                    <friendlyName>  Ampli
                      du salon  </friendlyName>
                    <manufacturer>Denon</manufacturer>
                    <UDN>uuid:renderer</UDN>
                    <serviceList>
                      <service>
                        <serviceType>urn:schemas-upnp-org:service:AVTransport:3</serviceType>
                        <controlURL>http://192.168.1.60:60006/upnp/control/renderer_dvc/AVTransport</controlURL>
                      </service>
                    </serviceList>
                  </device>
                </deviceList>
              </device>
            </root>
        """.trimIndent()
        val tv = DeviceDescription.parse(xml.toByteArray(), "http://192.168.1.60:60006/upnp/desc/aios_device/aios_device.xml")!!
        assertEquals("uuid:renderer", tv.udn)
        assertEquals("Ampli du salon", tv.name)
        assertEquals("urn:schemas-upnp-org:service:AVTransport:3", tv.avTransport.type)
        assertEquals("http://192.168.1.60:60006/upnp/control/renderer_dvc/AVTransport", tv.avTransport.controlUrl)
        // Pas de RenderingControl : pas de volume.
        assertNull(tv.renderingControl)
    }

    @Test
    fun `appareil sans AVTransport, document mal formé ou piégé`() {
        val server = """
            <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
              <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType><friendlyName>NAS</friendlyName>
              <serviceList><service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType>
              <controlURL>/cd</controlURL></service></serviceList>
            </device></root>
        """.trimIndent()
        assertNull(DeviceDescription.parse(server.toByteArray(), "http://192.168.1.5/"))
        assertNull(DeviceDescription.parse("<root><device>".toByteArray(), "http://192.168.1.5/"))
        assertNull(DeviceDescription.parse("<html><body>Not a device</body></html>".toByteArray(), "http://192.168.1.5/"))
        // Entité externe : jamais chargée (ni fichier lu, ni requête).
        val hostile = """
            <?xml version="1.0"?>
            <!DOCTYPE root [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
            <root><device><friendlyName>&secret;</friendlyName>
            <serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
            <controlURL>/av</controlURL></service></serviceList></device></root>
        """.trimIndent()
        assertNull(DeviceDescription.parse(hostile.toByteArray(), "http://192.168.1.5/"))
    }

    @Test
    fun `résolution des URL de commande`() {
        assertEquals("http://192.168.1.2:80/a/b", DeviceDescription.resolve("http://192.168.1.2:80", "a/b"))
        assertEquals("http://192.168.1.2/x/a", DeviceDescription.resolve("http://192.168.1.2/x/desc.xml", "a"))
        assertEquals("http://192.168.1.2/a", DeviceDescription.resolve("http://192.168.1.2/x/desc.xml", "/a"))
        assertEquals("http://192.168.1.3:5000/c", DeviceDescription.resolve("http://192.168.1.2/", " http://192.168.1.3:5000/c\n"))
        assertNull(DeviceDescription.resolve("http://192.168.1.2/", "ftp://192.168.1.2/c"))
        assertNotNull(DeviceDescription.resolve("http://192.168.1.2/", "c?x=1"))
    }
}

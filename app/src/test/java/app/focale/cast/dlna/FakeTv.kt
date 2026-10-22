package app.focale.cast.dlna

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Requête reçue par une [FakeTv]. */
internal class TvRequest(val line: String, val headers: Map<String, String>, val body: String) {
    /** Action SOAP (« Play »), d'après l'en-tête SOAPACTION. */
    val action: String? get() = headers["soapaction"]?.trim('"')?.substringAfter('#', "")?.takeIf { it.isNotEmpty() }

    /** Argument [name] de l'action. */
    fun argument(name: String): String? = XmlTree.parse(body)?.find(action ?: return null)?.childText(name)
}

/**
 * TV factice sur la boucle locale : une réponse par connexion, calculée par [answer] (null : la
 * connexion se ferme sans réponse) ou, à défaut, préparée par [respond] et attendue dans l'ordre ;
 * requêtes reçues dans [requests]. [unplug] la débranche : toute connexion est refusée, comme
 * avec une TV éteinte.
 */
internal class FakeTv(private val answer: ((TvRequest) -> String?)? = null) : Closeable {
    val socket = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0)) }
    val requests = LinkedBlockingQueue<TvRequest>()
    private val responses = LinkedBlockingQueue<String>()
    val base = "http://127.0.0.1:${socket.localPort}"

    init {
        Thread {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use {
                    val input = it.getInputStream().buffered()
                    val line = HttpWire.readLine(input).orEmpty()
                    val headers = HttpWire.readHeaders(input)
                    val body = HttpWire.readBody(input, headers, 1 shl 20).decodeToString()
                    val request = TvRequest(line, headers, body)
                    requests += request
                    val response = if (answer != null) answer(request) else responses.poll(5, TimeUnit.SECONDS)!!
                    if (response != null) it.getOutputStream().write(response.toByteArray())
                }
            }
        }.apply { isDaemon = true }.start()
    }

    fun respond(status: String, body: String, chunked: Boolean = false) {
        responses += response(status, body, chunked)
    }

    fun next(): TvRequest = requests.poll(5, TimeUnit.SECONDS)!!

    /** Débranchée : la socket d'écoute est fermée, toute connexion est refusée. */
    fun unplug() = socket.close()

    override fun close() = socket.close()

    /** Lecteur de médias décrit par cette TV : AVTransport et RenderingControl. */
    fun renderer(name: String = "Salon"): DlnaRenderer = DlnaRenderer(
        udn = "uuid:tv",
        name = name,
        manufacturer = null,
        model = null,
        location = "$base/dmr",
        avTransport = DlnaService("urn:schemas-upnp-org:service:AVTransport:1", "$base/upnp/control/AVTransport1"),
        renderingControl = DlnaService("urn:schemas-upnp-org:service:RenderingControl:1", "$base/upnp/control/RenderingControl1"),
    )

    companion object {
        fun response(status: String, body: String, chunked: Boolean = false): String = if (chunked) {
            val half = body.length / 2
            val chunks = listOf(body.substring(0, half), body.substring(half))
            "HTTP/1.1 $status\r\nContent-Type: text/xml\r\nTransfer-Encoding: chunked\r\n\r\n" +
                chunks.joinToString("") { "${it.toByteArray().size.toString(16)}\r\n$it\r\n" } + "0\r\n\r\n"
        } else {
            "HTTP/1.1 $status\r\nContent-Type: text/xml; charset=\"utf-8\"\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body"
        }

        fun envelope(action: String, service: String = "AVTransport", values: String = "") =
            "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>" +
                "<u:${action}Response xmlns:u=\"urn:schemas-upnp-org:service:$service:1\">$values</u:${action}Response></s:Body></s:Envelope>"

        /** Erreur UPnP [code] (714 : type MIME illégal). */
        fun fault(code: Int, description: String) =
            "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault>" +
                "<faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">" +
                "<errorCode>$code</errorCode><errorDescription>$description</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"
    }
}

/**
 * Lecteur de médias simulé derrière une [FakeTv] : il garde le média reçu et son état de lecture,
 * et sa position avance d'une seconde à chaque interrogation pendant la lecture. [refuses] : média
 * refusé dès SetAVTransportURI (erreur 714) ; [neverPlays] : média accepté, que la TV ne lit jamais
 * (lecture demandée, elle reste arrêtée) ; [offline] : la TV ne répond plus (connexion coupée sans
 * réponse), le temps d'un passage à vide.
 */
internal class FakeRenderer : Closeable {
    @Volatile var refuses: (url: String) -> Boolean = { false }
    @Volatile var neverPlays: (url: String) -> Boolean = { false }
    @Volatile var offline = false

    @Volatile var uri: String? = null
        private set

    @Volatile var state = "NO_MEDIA_PRESENT"
        private set

    @Volatile var positionMs = 0L
        private set

    /** Actions reçues, dans l'ordre (« SetAVTransportURI », « Play »…). */
    val actions: MutableList<String> = Collections.synchronizedList(ArrayList())

    val tv = FakeTv(::answer)
    val renderer = tv.renderer()

    /** Actions reçues depuis [from] (indice dans [actions]). */
    fun actionsSince(from: Int): List<String> = synchronized(actions) { actions.drop(from) }

    private fun answer(request: TvRequest): String? {
        if (offline) return null
        val action = request.action.orEmpty()
        actions += action
        val ok = { values: String -> FakeTv.response("200 OK", FakeTv.envelope(action, if (action.endsWith("Volume")) "RenderingControl" else "AVTransport", values)) }
        return when (action) {
            "SetAVTransportURI" -> {
                val url = request.argument("CurrentURI").orEmpty()
                if (refuses(url)) {
                    FakeTv.response("500 Internal Server Error", FakeTv.fault(714, "Illegal MIME-type"))
                } else {
                    uri = url
                    state = "STOPPED"
                    positionMs = 0
                    ok("")
                }
            }
            "Play" -> {
                if (!neverPlays(uri.orEmpty())) state = "PLAYING"
                ok("")
            }
            "Pause" -> {
                state = "PAUSED_PLAYBACK"
                ok("")
            }
            "Stop" -> {
                state = "STOPPED"
                ok("")
            }
            "Seek" -> {
                positionMs = UpnpTime.parse(request.argument("Target")) ?: positionMs
                ok("")
            }
            "GetTransportInfo" -> ok("<CurrentTransportState>$state</CurrentTransportState><CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>")
            "GetPositionInfo" -> {
                if (state == "PLAYING") positionMs += 1_000
                ok("<Track>1</Track><TrackDuration>0:02:00</TrackDuration><RelTime>${UpnpTime.format(positionMs)}</RelTime>")
            }
            "GetVolume" -> ok("<CurrentVolume>20</CurrentVolume>")
            "SetVolume" -> ok("")
            else -> FakeTv.response("500 Internal Server Error", FakeTv.fault(401, "Invalid Action"))
        }
    }

    override fun close() = tv.close()
}

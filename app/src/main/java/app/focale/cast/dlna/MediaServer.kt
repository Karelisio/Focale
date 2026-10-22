package app.focale.cast.dlna

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.Semaphore

/** Média servi à la TV : type, taille exacte, et lecture à partir d'une position. */
internal interface ServedMedia {
    val mimeType: String
    val length: Long
    val video: Boolean

    /** Flux du contenu à partir de l'octet [offset]. */
    fun open(offset: Long): InputStream
}

/** Média déjà en mémoire (photo convertie en JPEG). */
internal class BytesMedia(private val bytes: ByteArray, override val mimeType: String, override val video: Boolean = false) : ServedMedia {
    override val length: Long get() = bytes.size.toLong()
    override fun open(offset: Long): InputStream = bytes.inputStream(offset.toInt(), bytes.size - offset.toInt())
}

/** Plage demandée par un en-tête `Range`. */
internal sealed interface ByteRange {
    /** Tout le contenu (pas de Range, ou une forme ignorée : unité inconnue, plusieurs plages). */
    data object Full : ByteRange

    /** Octets [start] à [end] inclus. */
    data class Part(val start: Long, val end: Long) : ByteRange {
        val length: Long get() = end - start + 1
    }

    /** Plage hors du contenu : réponse 416. */
    data object Unsatisfiable : ByteRange

    companion object {
        /**
         * Lecture de `bytes=début-fin`, `bytes=début-` et `bytes=-suffixe` (RFC 9110, § 14.1.2) pour
         * un contenu de [length] octets ; la fin est ramenée au dernier octet.
         */
        fun parse(header: String?, length: Long): ByteRange {
            val value = header?.trim() ?: return Full
            if (!value.startsWith("bytes=", ignoreCase = true)) return Full
            val spec = value.substring(6).trim()
            if (',' in spec) return Full
            val dash = spec.indexOf('-')
            if (dash < 0) return Full
            val first = spec.substring(0, dash).trim()
            val last = spec.substring(dash + 1).trim()
            if (first.isEmpty()) {
                val suffix = last.toLongOrNull() ?: return Full
                if (suffix <= 0 || length == 0L) return Unsatisfiable
                return Part((length - suffix).coerceAtLeast(0), length - 1)
            }
            val start = first.toLongOrNull() ?: return Full
            val end = if (last.isEmpty()) length - 1 else (last.toLongOrNull() ?: return Full)
            if (start >= length || end < start) return Unsatisfiable
            return Part(start, minOf(end, length - 1))
        }
    }
}

/**
 * Petit serveur HTTP, démarré le temps d'une diffusion vers une TV, qui ne sert que le média
 * diffusé :
 * - lié à l'adresse Wi-Fi de l'appareil ([bindAddress]), sur un port tiré par le système ;
 * - chaque média publié reçoit un chemin neuf, `/<jeton>/media.<ext>`, jeton de 128 bits tiré
 *   par [SecureRandom] et comparé en temps constant : l'URL précédente ne sert plus rien ;
 * - clients hors du réseau local refusés (403, [clientAllowed]) ; GET et HEAD seulement ;
 * - `Range` (une plage) pour que la TV se déplace dans une vidéo ; en-têtes DLNA ;
 * - [stop] ferme la socket d'écoute et coupe les transferts en cours.
 */
internal class MediaServer(
    private val bindAddress: InetAddress,
    private val clientAllowed: (InetAddress) -> Boolean = LanAddresses::isLocalClient,
    private val random: SecureRandom = SecureRandom(),
) {
    private class Published(val token: String, val name: String, val media: ServedMedia)

    @Volatile private var published: Published? = null
    @Volatile private var stopped = false
    private var socket: ServerSocket? = null
    private val clients = Collections.synchronizedSet(HashSet<Socket>())
    private val slots = Semaphore(MAX_CLIENTS)

    /** Port d'écoute, une fois [start] appelé. */
    val port: Int get() = socket?.localPort ?: -1

    @Synchronized
    fun start() {
        check(!stopped && socket == null) { "serveur déjà démarré ou arrêté" }
        val server = ServerSocket()
        server.bind(InetSocketAddress(bindAddress, 0), BACKLOG)
        socket = server
        Thread({ acceptLoop(server) }, "focale-cast-http").apply { isDaemon = true }.start()
    }

    /** Publie [media] et renvoie son URL ; le média précédent n'est plus servi. */
    fun publish(media: ServedMedia): String {
        check(!stopped && socket != null) { "serveur arrêté" }
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        val name = "media." + extension(media.mimeType)
        published = Published(token, name, media)
        val host = bindAddress.hostAddress?.let { if (':' in it) "[${it.substringBefore('%')}]" else it }
        return "http://$host:$port/$token/$name"
    }

    /** Plus aucun média servi (la TV a été arrêtée). */
    fun clear() {
        published = null
    }

    @Synchronized
    fun stop() {
        stopped = true
        published = null
        runCatching { socket?.close() }
        socket = null
        synchronized(clients) { clients.toList() }.forEach { runCatching { it.close() } }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!stopped) {
            val client = try {
                server.accept()
            } catch (closed: SocketException) {
                break
            } catch (error: IOException) {
                if (stopped) break else continue
            }
            if (!slots.tryAcquire()) {
                runCatching { client.close() }
                continue
            }
            clients += client
            Thread({
                try {
                    client.use(::serve)
                } catch (error: IOException) {
                    // TV partie en cours de transfert, ou serveur arrêté.
                } catch (error: RuntimeException) {
                    Log.w(TAG, "Requête de la TV en échec", error)
                } finally {
                    clients -= client
                    slots.release()
                }
            }, "focale-cast-client").apply { isDaemon = true }.start()
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = READ_TIMEOUT_MS
        val output = client.getOutputStream().buffered(BUFFER)
        val input = client.getInputStream().buffered()
        // Requête lue (bornée) avant toute réponse : fermer sur des octets non lus couperait la
        // connexion avant que le refus n'arrive.
        val requestLine = HttpWire.readLine(input) ?: return
        val headers = HttpWire.readHeaders(input)
        val remote = client.inetAddress
        if (remote == null || !clientAllowed(remote)) {
            respond(output, 403, "Forbidden")
            return
        }
        val parts = requestLine.split(' ')
        if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) {
            respond(output, 400, "Bad Request")
            return
        }
        val (method, target) = parts
        val current = published
        if (current == null || !matches(target, current)) {
            respond(output, 404, "Not Found")
            return
        }
        if (method != "GET" && method != "HEAD") {
            respond(output, 405, "Method Not Allowed", extra = mapOf("Allow" to "GET, HEAD"))
            return
        }
        val media = current.media
        val length = media.length
        val range = ByteRange.parse(headers["range"], length)
        val common = linkedMapOf(
            "Content-Type" to media.mimeType,
            "Accept-Ranges" to "bytes",
            "Cache-Control" to "no-store",
            "transferMode.dlna.org" to if (media.video) "Streaming" else "Interactive",
            "contentFeatures.dlna.org" to Didl.contentFeatures(media.video),
        )
        when (range) {
            ByteRange.Unsatisfiable -> respond(output, 416, "Range Not Satisfiable", extra = mapOf("Content-Range" to "bytes */$length"))
            ByteRange.Full -> send(output, method, 200, "OK", common, media, 0, length)
            is ByteRange.Part -> send(
                output, method, 206, "Partial Content",
                common + ("Content-Range" to "bytes ${range.start}-${range.end}/$length"),
                media, range.start, range.length,
            )
        }
    }

    /** Chemin « /<jeton>/<nom> » du média publié ; jeton comparé en temps constant. */
    private fun matches(target: String, current: Published): Boolean {
        val path = target.substringBefore('?').removePrefix("/")
        val token = path.substringBefore('/')
        val name = path.substringAfter('/', "")
        return MessageDigest.isEqual(token.toByteArray(), current.token.toByteArray()) && name == current.name
    }

    private fun send(
        output: OutputStream,
        method: String,
        status: Int,
        reason: String,
        headers: Map<String, String>,
        media: ServedMedia,
        offset: Long,
        count: Long,
    ) {
        writeHead(output, status, reason, headers + ("Content-Length" to count.toString()))
        if (method == "HEAD" || count == 0L) {
            output.flush()
            return
        }
        media.open(offset).use { input ->
            val buffer = ByteArray(BUFFER)
            var left = count
            while (left > 0 && !stopped) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read < 0) break
                output.write(buffer, 0, read)
                left -= read
            }
        }
        output.flush()
    }

    private fun respond(output: OutputStream, status: Int, reason: String, extra: Map<String, String> = emptyMap()) {
        writeHead(output, status, reason, extra + ("Content-Length" to "0"))
        output.flush()
    }

    private fun writeHead(output: OutputStream, status: Int, reason: String, headers: Map<String, String>) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
    }

    private fun extension(mimeType: String): String = when (mimeType.lowercase()) {
        "image/jpeg" -> "jpg"
        "video/mp4" -> "mp4"
        "video/quicktime" -> "mov"
        "video/x-matroska" -> "mkv"
        "video/webm" -> "webm"
        "video/3gpp" -> "3gp"
        "video/x-msvideo", "video/avi" -> "avi"
        else -> if (mimeType.startsWith("video/")) "mp4" else "bin"
    }

    companion object {
        private const val TAG = "MediaServer"
        private const val TOKEN_BYTES = 16
        private const val BACKLOG = 8
        /** Transferts simultanés : une TV en ouvre deux ou trois quand on se déplace dans une vidéo. */
        private const val MAX_CLIENTS = 8
        private const val READ_TIMEOUT_MS = 15_000
        private const val BUFFER = 64 * 1024
    }
}

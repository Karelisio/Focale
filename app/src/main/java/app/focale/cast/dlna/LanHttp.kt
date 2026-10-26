package app.focale.cast.dlna

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URISyntaxException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Réponse HTTP lue en entier ; en-têtes en minuscules. */
internal class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    val text: String get() = body.decodeToString()
}

/** Échec d'un échange avec un appareil du réseau local (adresse refusée, réponse illisible…). */
internal class LanHttpException(message: String) : IOException(message)

/**
 * Lecture du format HTTP/1.1 sur un flux brut, commune au client et au serveur de médias : lignes
 * bornées, en-têtes, corps à longueur fixe ou découpé (`chunked`).
 */
internal object HttpWire {
    const val MAX_LINE = 8 * 1024
    const val MAX_HEADERS = 64

    /** Ligne terminée par CRLF (ou LF seul), sans son terminateur ; null en fin de flux. */
    fun readLine(input: InputStream, limit: Int = MAX_LINE): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (line.size() == 0) null else line.toString(Charsets.ISO_8859_1.name())
            if (byte == '\n'.code) break
            line.write(byte)
            if (line.size() > limit) throw LanHttpException("ligne trop longue")
        }
        val bytes = line.toByteArray()
        val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
        return String(bytes, 0, end, Charsets.ISO_8859_1)
    }

    /** En-têtes jusqu'à la ligne vide, noms en minuscules (le dernier l'emporte). */
    fun readHeaders(input: InputStream): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        repeat(MAX_HEADERS + 1) {
            val line = readLine(input) ?: throw LanHttpException("en-têtes interrompus")
            if (line.isEmpty()) return headers
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        throw LanHttpException("trop d'en-têtes")
    }

    /** Corps d'une réponse, au plus [limit] octets. */
    fun readBody(input: InputStream, headers: Map<String, String>, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            while (true) {
                val sizeLine = readLine(input) ?: throw LanHttpException("bloc interrompu")
                val size = sizeLine.substringBefore(';').trim().toIntOrNull(16)
                    ?: throw LanHttpException("taille de bloc illisible")
                if (size == 0) {
                    // En-têtes de fin éventuels, puis la ligne vide.
                    var trailer = readLine(input)
                    while (!trailer.isNullOrEmpty()) trailer = readLine(input)
                    break
                }
                if (out.size() + size > limit) throw LanHttpException("réponse trop longue")
                copyExactly(input, out, size.toLong())
                readLine(input)
            }
            return out.toByteArray()
        }
        val length = headers["content-length"]?.trim()?.toLongOrNull()
        if (length != null) {
            if (length > limit) throw LanHttpException("réponse trop longue")
            copyExactly(input, out, length)
            return out.toByteArray()
        }
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > limit) throw LanHttpException("réponse trop longue")
        }
        return out.toByteArray()
    }

    private fun copyExactly(input: InputStream, out: ByteArrayOutputStream, count: Long) {
        val buffer = ByteArray(8 * 1024)
        var left = count
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) throw LanHttpException("réponse tronquée")
            out.write(buffer, 0, read)
            left -= read
        }
    }
}

/**
 * Client HTTP minimal pour les appareils du réseau local : description des TV et commandes SOAP.
 *
 * Il passe par une socket brute plutôt que par `HttpURLConnection`, que la politique de sécurité
 * réseau d'Android limite à HTTPS : autoriser le trafic en clair (`cleartextTrafficPermitted`)
 * l'ouvrirait pour toute l'application, alors que ce client, lui, n'accepte que des adresses
 * locales ([hostAllowed], par défaut [LanAddresses.isLan]) : aucune requête hors du réseau local.
 * Une TV qui se désigne par son nom (« bravia.local », « tv-salon ») est résolue par le système
 * ([resolver] : DNS du réseau local, mDNS des noms en « .local » si le système le fait), une fois
 * par nom et par minute, et seule une adresse locale obtenue est contactée ; un nom qui ne mène
 * qu'ailleurs est refusé comme une adresse publique. La connexion se fait à l'adresse retenue,
 * sans seconde résolution. Une connexion par requête (`Connection: close`).
 *
 * @param socketFactory socket non connectée (celle du réseau Wi-Fi sur l'appareil).
 * @param resolver adresses d'un nom ; jamais appelé pour une adresse écrite en clair.
 * @param scoped adresse prête à joindre ([LinkAddresses.scoped] : interface d'une IPv6 de lien local).
 */
internal class LanHttpClient(
    private val socketFactory: () -> Socket = ::Socket,
    private val hostAllowed: (InetAddress) -> Boolean = LanAddresses::isLan,
    private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    private val scoped: (InetAddress) -> InetAddress = { it },
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 6_000,
    private val resolveTimeoutMs: Long = 3_000,
    private val clock: () -> Long = System::nanoTime,
) {
    /** Noms déjà résolus : adresse retenue et instant de la résolution ([clock]). */
    private val names = ConcurrentHashMap<String, Pair<InetAddress, Long>>()

    fun get(url: String): HttpResponse = execute("GET", url, emptyMap(), null)

    fun post(url: String, headers: Map<String, String>, body: ByteArray): HttpResponse = execute("POST", url, headers, body)

    private fun execute(method: String, url: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        val uri = try {
            URI(url.trim())
        } catch (invalid: URISyntaxException) {
            throw LanHttpException("URL illisible")
        }
        if (!uri.scheme.equals("http", ignoreCase = true)) throw LanHttpException("schéma refusé")
        val host = uri.host ?: throw LanHttpException("hôte absent")
        val address = scoped(address(host))
        val port = if (uri.port in 1..65_535) uri.port else 80
        val target = buildString {
            append(uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/")
            uri.rawQuery?.let { append('?').append(it) }
        }
        socketFactory().use { socket ->
            socket.connect(InetSocketAddress(address, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            val request = buildString {
                append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
                // Sans la zone d'une IPv6 de lien local, propre à l'appareil qui l'écrit (RFC 9110, § 7.2).
                val named = if (host.startsWith("[") && '%' in host) host.substringBefore('%') + "]" else host
                append("Host: ").append(named).append(':').append(port).append("\r\n")
                append("Connection: close\r\n")
                headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                if (body != null) append("Content-Length: ").append(body.size).append("\r\n")
                append("\r\n")
            }
            val output = socket.getOutputStream()
            output.write(request.toByteArray(Charsets.ISO_8859_1))
            if (body != null) output.write(body)
            output.flush()
            val input = socket.getInputStream().buffered()
            while (true) {
                val statusLine = HttpWire.readLine(input) ?: throw LanHttpException("réponse vide")
                val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull()
                if (!statusLine.startsWith("HTTP/") || status == null) throw LanHttpException("réponse illisible")
                val responseHeaders = HttpWire.readHeaders(input)
                // « 100 Continue » : la vraie réponse suit.
                if (status in 100..199) continue
                val responseBody = if (method == "HEAD" || status == 204 || status == 304) {
                    ByteArray(0)
                } else {
                    HttpWire.readBody(input, responseHeaders, MAX_BODY)
                }
                return HttpResponse(status, responseHeaders, responseBody)
            }
        }
    }

    /** Adresse à contacter pour [host] : écrite en clair, ou la première locale (IPv4 d'abord) de son nom. */
    private fun address(host: String): InetAddress {
        LanAddresses.literal(host)?.let { literal ->
            if (!hostAllowed(literal)) throw LanHttpException("adresse hors du réseau local refusée")
            return literal
        }
        if (!LanAddresses.isHostName(host)) throw LanHttpException("hôte illisible")
        val name = host.lowercase().removeSuffix(".")
        names[name]?.let { (address, at) -> if (clock() - at < NAME_TTL_NS) return address }
        val local = resolve(name).filter(hostAllowed)
        val address = local.firstOrNull { it is Inet4Address } ?: local.firstOrNull()
            ?: throw LanHttpException("nom hors du réseau local refusé")
        names[name] = address to clock()
        return address
    }

    /** Résolution de [name] par [resolver], abandonnée au bout de [resolveTimeoutMs] : le système peut attendre bien plus. */
    private fun resolve(name: String): List<InetAddress> {
        val task = FutureTask { resolver(name) }
        Thread(task, "focale-cast-dns").apply { isDaemon = true }.start()
        return try {
            task.get(resolveTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            task.cancel(true)
            throw LanHttpException("résolution trop longue")
        } catch (failed: ExecutionException) {
            throw LanHttpException("nom inconnu")
        } catch (interrupted: InterruptedException) {
            task.cancel(true)
            Thread.currentThread().interrupt()
            throw LanHttpException("résolution interrompue")
        }
    }

    companion object {
        /** Une description d'appareil ou une réponse SOAP tient en quelques kilo-octets. */
        const val MAX_BODY = 1 shl 20

        /** Durée de vie d'un nom résolu : une session interroge la TV chaque seconde. */
        private const val NAME_TTL_NS = 60_000_000_000L
    }
}

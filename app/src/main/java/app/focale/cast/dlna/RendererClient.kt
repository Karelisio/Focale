package app.focale.cast.dlna

/** État de lecture d'AVTransport (`CurrentTransportState`). */
internal enum class TransportState { PLAYING, PAUSED, STOPPED, TRANSITIONING, NO_MEDIA, UNKNOWN;

    companion object {
        fun of(value: String?): TransportState = when (value?.trim()?.uppercase()) {
            "PLAYING" -> PLAYING
            "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> PAUSED
            "STOPPED" -> STOPPED
            "TRANSITIONING" -> TRANSITIONING
            "NO_MEDIA_PRESENT" -> NO_MEDIA
            else -> UNKNOWN
        }
    }
}

/** État de lecture (`GetTransportInfo`) ; [ok] faux quand la TV signale une erreur (`ERROR_OCCURRED`). */
internal data class TransportInfo(val state: TransportState, val ok: Boolean)

/** Position de lecture (`GetPositionInfo`) ; null quand la TV ne la donne pas. */
internal data class PositionInfo(val positionMs: Long?, val durationMs: Long?)

/**
 * Commandes d'une TV : AVTransport (média, lecture, pause, position, arrêt) et, s'il existe,
 * RenderingControl (volume). Appels bloquants, à faire hors du fil principal ; une erreur UPnP
 * lève [UpnpException], une TV injoignable une `IOException`.
 */
internal class RendererClient(private val renderer: DlnaRenderer, private val http: LanHttpClient) {

    fun setUri(url: String, metadata: String) {
        transport("SetAVTransportURI", "CurrentURI" to url, "CurrentURIMetaData" to metadata)
    }

    fun play() {
        transport("Play", "Speed" to "1")
    }

    fun pause() {
        transport("Pause")
    }

    fun stop() {
        transport("Stop")
    }

    fun seek(positionMs: Long) {
        transport("Seek", "Unit" to "REL_TIME", "Target" to UpnpTime.format(positionMs))
    }

    fun transportInfo(): TransportInfo {
        val values = transport("GetTransportInfo")
        return TransportInfo(
            state = TransportState.of(values["CurrentTransportState"]),
            ok = !values["CurrentTransportStatus"].equals("ERROR_OCCURRED", ignoreCase = true),
        )
    }

    fun position(): PositionInfo {
        val values = transport("GetPositionInfo")
        return PositionInfo(UpnpTime.parse(values["RelTime"]), UpnpTime.parse(values["TrackDuration"])?.takeIf { it > 0 })
    }

    /** Volume de 0 à 100, null sans RenderingControl. */
    fun volume(): Int? {
        val service = renderer.renderingControl ?: return null
        return call(service, "GetVolume", listOf("InstanceID" to "0", "Channel" to "Master"))["CurrentVolume"]
            ?.toIntOrNull()?.coerceIn(0, 100)
    }

    fun setVolume(volume: Int) {
        val service = renderer.renderingControl ?: return
        call(service, "SetVolume", listOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to volume.coerceIn(0, 100).toString()))
    }

    private fun transport(action: String, vararg arguments: Pair<String, String>): Map<String, String> =
        call(renderer.avTransport, action, listOf("InstanceID" to "0") + arguments)

    private fun call(service: DlnaService, action: String, arguments: List<Pair<String, String>>): Map<String, String> {
        val body = Soap.envelope(service.type, action, arguments).toByteArray()
        val response = http.post(
            service.controlUrl,
            mapOf(
                "Content-Type" to "text/xml; charset=\"utf-8\"",
                "SOAPACTION" to Soap.actionHeader(service.type, action),
            ),
            body,
        )
        return when (val result = Soap.parseResponse(response.body, action)) {
            is SoapResult.Success -> if (response.status in 200..299) result.values else throw UpnpException(null, "HTTP ${response.status}")
            is SoapResult.Fault -> throw UpnpException(result.code, result.description)
            null -> if (response.status in 200..299) emptyMap() else throw UpnpException(null, "HTTP ${response.status}")
        }
    }
}

package app.focale.backup

import java.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonPrimitive

// Enregistrements d'une sauvegarde des données (DataArchive), un JSON chacun. Les médias sont
// numérotés dans l'ordre de leurs enregistrements ([MediaRecord] : 0, 1…) et désignés par ce numéro ;
// albums, personnes et visages, par leur identifiant sur le téléphone sauvegardé. Champs facultatifs
// omis à leur valeur par défaut ; champs inconnus ignorés (version ultérieure).

/** Enregistrement d'une sauvegarde des données ; son type est écrit devant lui (voir [DataArchive]). */
sealed interface DataRecord

/** Premier enregistrement : création de la sauvegarde et versions dont viennent ses données. */
@Serializable
data class HeaderRecord(
    /** Création de la sauvegarde (ms depuis l'époque). */
    val createdAt: Long,
    /** Version de Focale qui l'a écrite (« 1.9.0 »). */
    val appVersion: String,
    /** Version de l'étape « visages » de l'analyse ([app.focale.people.FaceAnalysisStep.VERSION]). */
    val faceVersion: Int,
) : DataRecord

/**
 * Réglages de l'utilisateur, liste blanche ([app.focale.core.settings.SettingsRepository]) :
 * [values] ceux qu'il a réglés, [keys] tous ceux que connaissait la version qui a sauvegardé
 * (absents de [values] : à leur valeur par défaut).
 */
@Serializable
data class SettingsRecord(
    val values: Map<String, JsonPrimitive>,
    val keys: List<String>,
) : DataRecord

/** Dossier masqué de Photos : son `BUCKET_ID`, et son chemin pour le retrouver ailleurs. */
@Serializable
data class HiddenFolderRecord(
    val bucketId: Long,
    /** `RELATIVE_PATH` du dossier, null s'il n'avait plus de média. */
    val path: String? = null,
    val name: String? = null,
) : DataRecord

/** Média désigné par les autres enregistrements, par sa clé portable (voir [MediaMatcher]). */
@Serializable
data class MediaRecord(
    /** `RELATIVE_PATH` (« DCIM/Camera/ »). */
    val path: String,
    /** `DISPLAY_NAME`. */
    val name: String,
    /** Taille du fichier (octets). */
    val size: Long,
    /** Date de tri de l'index (ms) : `DATE_TAKEN`, sinon date d'origine, sinon `DATE_MODIFIED`. */
    val taken: Long,
) : DataRecord

/** Date d'origine d'un média restauré ou copié que MediaStore n'avait pas trouvée (`media_date`). */
@Serializable
data class MediaDateRecord(val media: Int, val taken: Long) : DataRecord

@Serializable
data class FavoriteRecord(val media: Int, val addedAt: Long) : DataRecord

/** Album virtuel ; [cover] : sa couverture choisie (numéro de média). */
@Serializable
data class AlbumRecord(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val cover: Int? = null,
) : DataRecord

@Serializable
data class AlbumMemberRecord(val album: Long, val media: Int, val addedAt: Long) : DataRecord

/** Personne ; [cover] : son visage choisi comme vignette, [photos] : ses photos sauvegardées (résumé). */
@Serializable
data class PersonRecord(
    val id: Long,
    val name: String? = null,
    val hidden: Boolean = false,
    val pinned: Boolean = false,
    val createdAt: Long,
    val cover: Long? = null,
    val photos: Int = 0,
) : DataRecord

/** « Pas la même » : deux personnes ([low] < [high]) dont la fusion n'est plus suggérée. */
@Serializable
data class RefusalRecord(val low: Long, val high: Long) : DataRecord

/**
 * Passage de la détection des visages sur une photo, même sans visage trouvé ; [current] faux :
 * la photo a changé depuis, elle est à relire.
 */
@Serializable
data class FaceScanRecord(
    val media: Int,
    val version: Int,
    val faces: Int,
    val current: Boolean = true,
) : DataRecord

/** Visage : cadre, empreinte, rattachement à une personne et décision de l'utilisateur ([manual]). */
@Serializable
data class FaceRecord(
    val id: Long,
    val media: Int,
    val person: Long? = null,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    /** Empreinte SFace : flottants 32 bits petit-boutistes, en Base64. */
    @Serializable(with = Base64Serializer::class) val embedding: ByteArray,
    val quality: Float,
    val manual: Boolean = false,
) : DataRecord

/** Recherche de l'historique : texte, ou recherche à pastilles en JSON (`SearchFilters.historyKey`). */
@Serializable
data class SearchRecord(val query: String, val searchedAt: Long) : DataRecord

/** Octets en Base64 (alphabet standard, sans retour à la ligne). */
internal object Base64Serializer : KSerializer<ByteArray> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("app.focale.backup.Base64", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ByteArray) = encoder.encodeString(Base64.getEncoder().encodeToString(value))

    /** @throws IllegalArgumentException texte qui n'est pas du Base64. */
    override fun deserialize(decoder: Decoder): ByteArray = Base64.getDecoder().decode(decoder.decodeString())
}

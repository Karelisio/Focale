package app.focale.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Résultats des étapes d'analyse complémentaires (voir app.focale.ml.AnalysisStep).
// Comme media_analysis, ces tables ne référencent pas `media` par clé étrangère : l'index
// MediaStore est reconstruisible, les lignes orphelines sont purgées après synchronisation.

/** Texte reconnu dans une photo (OCR sur l'appareil) ; vide si elle n'en contient pas. */
@Entity(tableName = "media_text")
data class MediaTextEntity(
    @PrimaryKey @ColumnInfo(name = "media_id") val mediaId: Long,
    /** `date_modified` du média au moment de l'analyse : une édition relance la lecture. */
    @ColumnInfo(name = "analyzed_modified") val analyzedModified: Long,
    val version: Int,
    /** Texte brut, une ligne par ligne reconnue. */
    val text: String,
    /** [text] normalisé (minuscules, sans accents, espaces simples) pour la recherche. */
    @ColumnInfo(name = "text_key") val textKey: String,
)

/** Passage de la détection de visages sur une photo, même sans visage trouvé. */
@Entity(tableName = "face_scan")
data class FaceScanEntity(
    @PrimaryKey @ColumnInfo(name = "media_id") val mediaId: Long,
    @ColumnInfo(name = "analyzed_modified") val analyzedModified: Long,
    val version: Int,
    @ColumnInfo(name = "face_count") val faceCount: Int,
)

/** Visage détecté dans une photo ; [personId] le rattache à une personne. */
@Entity(tableName = "face", indices = [Index("media_id"), Index("person_id")])
data class FaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "media_id") val mediaId: Long,
    @ColumnInfo(name = "person_id") val personId: Long? = null,
    /** Cadre du visage en fraction de l'image (orientation EXIF appliquée), de 0 à 1. */
    @ColumnInfo(name = "box_left") val boxLeft: Float,
    @ColumnInfo(name = "box_top") val boxTop: Float,
    @ColumnInfo(name = "box_right") val boxRight: Float,
    @ColumnInfo(name = "box_bottom") val boxBottom: Float,
    /** Empreinte du visage : flottants 32 bits petit-boutistes, de norme 1. */
    val embedding: ByteArray,
    /** Qualité de la détection (0 à 1) : sert à choisir les vignettes et les représentants. */
    val quality: Float,
    /** Rattachement choisi par l'utilisateur : le regroupement automatique n'y touche plus. */
    val manual: Boolean = false,
)

/** Personne : groupe de visages, nommé ou non par l'utilisateur. */
@Entity(tableName = "person")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String? = null,
    /** Visage servant de vignette (choisi par l'utilisateur ou le meilleur du groupe). */
    @ColumnInfo(name = "cover_face_id") val coverFaceId: Long? = null,
    /** Masquée de la liste des personnes. */
    val hidden: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

/** Empreinte sémantique d'une photo, pour la recherche en langage naturel. */
@Entity(tableName = "media_embedding")
data class MediaEmbeddingEntity(
    @PrimaryKey @ColumnInfo(name = "media_id") val mediaId: Long,
    @ColumnInfo(name = "analyzed_modified") val analyzedModified: Long,
    val version: Int,
    /** Flottants 32 bits petit-boutistes, de norme 1 ; null si le calcul a échoué. */
    val vector: ByteArray?,
)

/** Avancement d'une étape d'analyse : photos concernées et photos à jour. */
data class StepCounts(
    val total: Int,
    val done: Int,
)

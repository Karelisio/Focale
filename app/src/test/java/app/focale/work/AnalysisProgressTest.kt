package app.focale.work

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.focale.core.database.FaceScanEntity
import app.focale.core.database.FocaleDatabase
import app.focale.core.database.MediaEmbeddingEntity
import app.focale.core.database.MediaEntity
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.database.MediaTextEntity
import app.focale.core.database.StepCounts
import app.focale.ml.AnalysisKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compteurs d'avancement des étapes : mêmes photos que leur file d'attente, sur une vraie base Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnalysisProgressTest {

    private lateinit var database: FocaleDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, FocaleDatabase::class.java).allowMainThreadQueries().build()
        runBlocking {
            database.mediaDao().upsert(
                listOf(
                    media(1),
                    media(2),
                    media(3, modified = 2_000L),
                    media(4, screenshot = true),
                    media(5, gif = true),
                    media(6, video = true),
                    media(7, trashed = true),
                ),
            )
        }
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `le texte compte toutes les photos et seules les lectures à jour`() = runBlocking {
        database.textDao().upsert(
            listOf(
                MediaTextEntity(1, analyzedModified = 1_000L, version = 1, text = "", textKey = ""),
                // Photo modifiée depuis la lecture, puis lecture d'une ancienne version : à refaire.
                MediaTextEntity(3, analyzedModified = 1_000L, version = 1, text = "", textKey = ""),
                MediaTextEntity(2, analyzedModified = 1_000L, version = 0, text = "", textKey = ""),
                MediaTextEntity(4, analyzedModified = 1_000L, version = 1, text = "", textKey = ""),
            ),
        )
        // Photos 1 à 5 (captures et GIF compris) ; vidéo et corbeille exclues.
        assertEquals(StepCounts(total = 5, done = 2), database.textDao().progress(version = 1))
        assertEquals(3, database.textDao().pending(version = 1, limit = 100).size)
    }

    @Test
    fun `les visages écartent captures d'écran et GIF, comme leur file`() = runBlocking {
        database.faceDao().upsertScans(listOf(FaceScanEntity(1, analyzedModified = 1_000L, version = 1, faceCount = 2)))
        assertEquals(StepCounts(total = 3, done = 1), database.faceDao().progress(version = 1))
        assertEquals(2, database.faceDao().pending(version = 1, limit = 100).size)
    }

    @Test
    fun `les empreintes suivent leur version`() = runBlocking {
        database.embeddingDao().upsert(
            listOf(
                MediaEmbeddingEntity(1, analyzedModified = 1_000L, version = 2, vector = null),
                MediaEmbeddingEntity(2, analyzedModified = 1_000L, version = 1, vector = null),
            ),
        )
        assertEquals(StepCounts(total = 5, done = 1), database.embeddingDao().progress(version = 2))
        assertEquals(4, database.embeddingDao().pending(version = 2, limit = 100).size)
    }

    @Test
    fun `l'état n'est complet que lorsque chaque étape est à jour`() {
        val main = AnalysisProgress(analyzed = 10, total = 10)
        val partial = AnalysisStatus(
            main = main,
            steps = listOf(StepProgress(AnalysisKind.TEXT, 10, 10), StepProgress(AnalysisKind.FACES, 3, 8)),
            phase = AnalysisPhase.IDLE,
            onlyWhileCharging = true,
        )
        assertFalse(partial.complete)
        assertTrue(partial.copy(steps = listOf(StepProgress(AnalysisKind.FACES, 8, 8))).complete)
        // Bibliothèque vide : rien à faire.
        assertTrue(AnalysisStatus(AnalysisProgress(0, 0), emptyList(), AnalysisPhase.IDLE, onlyWhileCharging = false).complete)
    }

    @Test
    fun `seuls les échecs des analyses inachevées sont affichés`() {
        val status = AnalysisStatus(
            main = AnalysisProgress(analyzed = 10, total = 10),
            steps = listOf(StepProgress(AnalysisKind.TEXT, 0, 8), StepProgress(AnalysisKind.FACES, 8, 8)),
            phase = AnalysisPhase.IDLE,
            onlyWhileCharging = false,
            issues = mapOf(
                AnalysisKind.LABELS to "ancien échec",
                AnalysisKind.TEXT to "MlKitException: indisponible",
                AnalysisKind.FACES to "ancien échec",
            ),
        )
        assertEquals(mapOf(AnalysisKind.TEXT to "MlKitException: indisponible"), status.failures)
        assertEquals(
            setOf(AnalysisKind.LABELS, AnalysisKind.TEXT),
            status.copy(main = AnalysisProgress(analyzed = 4, total = 10)).failures.keys,
        )
    }

    @Test
    fun `un échec est décrit par sa cause première`() {
        val error = IllegalStateException(
            "Reconnaissance de texte indisponible",
            IllegalArgumentException("Composant non enregistré\ndétails techniques"),
        )
        assertEquals("IllegalArgumentException: Composant non enregistré", AnalysisIssues.describe(error))
        assertEquals("NullPointerException", AnalysisIssues.describe(NullPointerException()))
        assertEquals(200, AnalysisIssues.describe(IllegalStateException("x".repeat(500))).length)
    }

    private fun media(
        id: Long,
        modified: Long = 1_000L,
        screenshot: Boolean = false,
        gif: Boolean = false,
        video: Boolean = false,
        trashed: Boolean = false,
    ) = MediaEntity(
        id = id,
        mediaType = if (video) MediaQueryBuilder.MEDIA_TYPE_VIDEO else MediaQueryBuilder.MEDIA_TYPE_IMAGE,
        mimeType = if (video) "video/mp4" else "image/jpeg",
        displayName = "IMG_$id.jpg", relativePath = "DCIM/Camera/", bucketId = 1, bucketName = "Camera",
        dateTaken = id * 1_000, dateAdded = id * 1_000, dateModified = modified, generation = 0, size = 1,
        width = 4000, height = 3000, orientation = 0, duration = 0, volumeName = "external_primary",
        isScreenshot = screenshot, isGif = gif, isTrashed = trashed, dateExpires = 0,
    )
}

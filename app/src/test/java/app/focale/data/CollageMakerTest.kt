package app.focale.data

import android.Manifest
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.exifinterface.media.ExifInterface
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.focale.collage.CollageAspect
import app.focale.collage.CollageRenderer
import app.focale.collage.CollageState
import app.focale.core.database.FocaleDatabase
import app.focale.core.mediastore.ConsentRequester
import app.focale.core.mediastore.MediaStoreSource
import app.focale.core.mediastore.MediaSyncManager
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaType
import app.focale.core.permissions.MediaAccessManager
import app.focale.vault.VaultRestoreDateTest
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Enregistrement d'un collage par le chemin d'écriture commun : nouveau JPEG de 4 096 px dans
 * `Pictures/Focale`, publié dans MediaStore (simulé comme le vrai, voir
 * [VaultRestoreDateTest.FakeMediaStore]) puis indexé pour la visionneuse. Les photos sont de
 * vrais fichiers JPEG et PNG, lus par le décodeur de zones d'Android ; l'une est stockée de côté
 * avec une orientation EXIF, et s'affiche droite dans le collage.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CollageMakerTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var database: FocaleDatabase
    private lateinit var store: VaultRestoreDateTest.FakeMediaStore
    private lateinit var maker: CollageMaker
    private lateinit var media: MediaRepository

    @Before
    fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        database = Room.inMemoryDatabaseBuilder(context, FocaleDatabase::class.java).allowMainThreadQueries().build()
        store = Robolectric.buildContentProvider(VaultRestoreDateTest.FakeMediaStore::class.java).create(MediaStore.AUTHORITY).get()
        store.directory = folder.newFolder("storage")
        val sync = MediaSyncManager(
            context = context,
            database = database,
            dao = database.mediaDao(),
            source = MediaStoreSource(context.contentResolver),
            accessManager = MediaAccessManager(context),
            stateStore = PreferenceDataStoreFactory.create { File(folder.root, "state.preferences_pb") },
            scope = scope,
            io = Dispatchers.IO,
        )
        val operations = MediaOperations(
            context, context.contentResolver, ConsentRequester(), sync, database.trashDao(), database.favoriteDao(),
            database.mediaDao(), Dispatchers.IO,
        )
        media = MediaRepository(database.mediaDao(), Dispatchers.IO)
        maker = CollageMaker(context, context.contentResolver, operations, sync, media, Dispatchers.IO)
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
    }

    @Test
    fun `le collage est un nouveau JPEG de 4 096 px dans Pictures-Focale, publié, indexé, photos droites`() = runTest {
        // Photo stockée en paysage, orientation 6 : affichée en portrait, tournée d'un quart de tour.
        val sideways = photo("IMG_0001.jpg", quarters(1200, 800), Bitmap.CompressFormat.JPEG, orientation = 6)
        val square = photo("Capture.png", solid(1000, 1000, Color.BLUE), Bitmap.CompressFormat.PNG)
        val state = CollageState(layoutId = "2-side", photos = listOf(1L, 2L), spacing = 0f)

        val result = maker.save(state, mapOf(1L to sideways, 2L to square), now = JULY_14)

        assertTrue("$result", result is CollageResult.Saved)
        assertEquals(1, store.count { true })
        val row = store.row(collageName())
        assertEquals(CollageMaker.RELATIVE_PATH, row.getAsString(MediaStore.MediaColumns.RELATIVE_PATH))
        assertEquals("image/jpeg", row.getAsString(MediaStore.MediaColumns.MIME_TYPE))
        assertEquals(0, row.getAsInteger(MediaStore.MediaColumns.IS_PENDING))
        // Indexé : la visionneuse peut l'ouvrir.
        val item = (result as CollageResult.Saved).item
        assertNotNull(item)
        assertEquals(collageName(), item!!.displayName)
        assertEquals(row.getAsLong(MediaStore.MediaColumns._ID), item.id)

        val bytes = store.content(collageName())
        val collage = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(CollageRenderer.LONG_SIDE, collage.width)
        assertEquals(CollageRenderer.LONG_SIDE, collage.height)
        // Case gauche (2 048 × 4 096) : photo droite, vert en haut à gauche, rouge en haut à droite.
        assertColor(GREEN, collage.getPixel(512, 1024))
        assertColor(Color.RED, collage.getPixel(1536, 1024))
        assertColor(Color.YELLOW, collage.getPixel(512, 3072))
        assertColor(Color.BLUE, collage.getPixel(1536, 3072))
        assertColor(Color.BLUE, collage.getPixel(3072, 2048))

        val exif = ExifInterface(bytes.inputStream())
        assertEquals("Focale", exif.getAttribute(ExifInterface.TAG_SOFTWARE))
        assertEquals(ExifInterface.ORIENTATION_NORMAL, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertTrue(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)!!.startsWith("2019:07:14"))
    }

    @Test
    fun `proportion 16-9, 4 096 px de large, fond et espacement`() = runTest {
        val first = photo("a.jpg", solid(800, 600, Color.RED), Bitmap.CompressFormat.JPEG)
        val second = photo("b.jpg", solid(800, 600, Color.BLUE), Bitmap.CompressFormat.JPEG)
        val state = CollageState(
            layoutId = "2-stack",
            photos = listOf(1L, 2L),
            aspect = CollageAspect.LANDSCAPE,
            spacing = CollageState.MAX_SPACING,
            backgroundColor = Color.BLACK,
        )

        maker.save(state, mapOf(1L to first, 2L to second), now = JULY_14)

        val bytes = store.content(collageName())
        val collage = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertEquals(4096, collage.width)
        assertEquals(2304, collage.height)
        // Espacement : 5 % de 2 304 px = 115 px, au bord et entre les deux photos.
        assertColor(Color.BLACK, collage.getPixel(50, 1152))
        assertColor(Color.BLACK, collage.getPixel(2048, 1152))
        assertColor(Color.RED, collage.getPixel(2048, 600))
        assertColor(Color.BLUE, collage.getPixel(2048, 1700))
    }

    @Test
    fun `aperçu, orientation appliquée comme à l'export, grand côté borné`() = runTest {
        val sideways = photo("IMG_0002.jpg", quarters(1200, 800), Bitmap.CompressFormat.JPEG, orientation = 6)
        val preview = maker.loadPreview(sideways, 960)
        assertEquals(640, preview.width)
        assertEquals(960, preview.height)
        assertColor(GREEN, preview.getPixel(160, 240))
        assertColor(Color.RED, preview.getPixel(480, 240))
    }

    @Test
    fun `une photo illisible fait échouer l'enregistrement sans rien laisser dans MediaStore`() = runTest {
        val good = photo("a.jpg", solid(400, 300, Color.RED), Bitmap.CompressFormat.JPEG)
        val missing = good.copy(id = 2, uri = Uri.fromFile(File(folder.root, "absente.jpg")))
        val state = CollageState(layoutId = "2-side", photos = listOf(1L, 2L))

        val result = maker.save(state, mapOf(1L to good, 2L to missing), now = JULY_14)

        assertTrue("$result", result is CollageResult.Failed)
        assertEquals(0, store.count { true })
    }

    /** Nom du collage daté de [JULY_14], à l'heure locale. */
    private fun collageName(): String = "Collage_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(JULY_14)) + ".jpg"

    /** Photo de test écrite dans un fichier, avec son orientation EXIF. */
    private fun photo(name: String, bitmap: Bitmap, format: Bitmap.CompressFormat, orientation: Int? = null): MediaItem {
        val file = File(folder.root, name)
        file.outputStream().use { bitmap.compress(format, 95, it) }
        if (orientation != null) {
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
        val mime = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
        return MediaItem(
            id = if (name.startsWith("Capture") || name == "b.jpg") 2 else 1,
            uri = Uri.fromFile(file),
            type = MediaType.IMAGE,
            mimeType = mime,
            displayName = name,
            relativePath = "DCIM/Camera/",
            bucketId = 1,
            bucketName = "Camera",
            dateTaken = JULY_14,
            dateModified = JULY_14,
            size = file.length(),
            width = bitmap.width,
            height = bitmap.height,
            orientation = if (orientation == 6) 90 else 0,
            durationMs = 0,
            isGif = false,
            isScreenshot = false,
        )
    }

    /** Couleur proche de [expected], à la compression JPEG près. */
    private fun assertColor(expected: Int, actual: Int) {
        val close = listOf(Color::red, Color::green, Color::blue).all { channel -> Math.abs(channel(expected) - channel(actual)) <= 24 }
        assertTrue("attendu ${Integer.toHexString(expected)}, obtenu ${Integer.toHexString(actual)}", close)
    }

    private companion object {
        /** 14 juillet 2019, 10:15:00.250 UTC. */
        const val JULY_14 = 1_563_099_300_250L
        val GREEN = Color.rgb(0, 160, 0)

        fun solid(width: Int, height: Int, color: Int): Bitmap =
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

        /** Quatre quarts : rouge, bleu en haut ; vert, jaune en bas. */
        fun quarters(width: Int, height: Int): Bitmap = solid(width, height, Color.RED).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint()
            paint.color = Color.BLUE
            canvas.drawRect(width / 2f, 0f, width.toFloat(), height / 2f, paint)
            paint.color = GREEN
            canvas.drawRect(0f, height / 2f, width / 2f, height.toFloat(), paint)
            paint.color = Color.YELLOW
            canvas.drawRect(width / 2f, height / 2f, width.toFloat(), height.toFloat(), paint)
        }
    }
}

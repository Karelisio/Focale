package app.focale.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.focale.core.image.ThumbnailSizes
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaQuery
import app.focale.core.model.MediaType
import app.focale.core.settings.AppSettings
import app.focale.core.settings.ThemeMode
import app.focale.data.Memory
import app.focale.data.MemoryKind
import app.focale.ui.theme.FocaleTheme
import app.focale.ui.timeline.MEMORY_CARD_THUMBNAIL
import app.focale.ui.timeline.MemoriesCarousel
import coil3.SingletonImageLoader
import coil3.asImage
import coil3.memory.MemoryCache
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Captures de référence du carrousel des souvenirs (voyage, souvenir avec une personne,
 * souvenir général), en français, thèmes clair et sombre, sur une largeur où tiennent deux
 * grandes cartes. Les couvertures sont des aplats placés dans le cache mémoire de Coil.
 * Régénérer avec `./gradlew recordRoborazziStandardDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "fr-rFR-w480dp-h800dp-xhdpi")
class MemoriesScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    /** Construits au moment de la capture, une fois l'application Robolectric prête (cache de Coil). */
    private fun memories() = listOf(
        memory(
            id = 1,
            kind = MemoryKind.Trip(1, "Lisbonne", "PT", LocalDate.of(2025, 10, 17), LocalDate.of(2025, 10, 24)),
            count = 214,
            colors = 0xFF2E7DB8.toInt() to 0xFFF0C27A.toInt(),
        ),
        memory(
            id = 2,
            kind = MemoryKind.WithPerson(1, personId = 4, name = "Alice", face = null),
            count = 38,
            colors = 0xFFD9775B.toInt() to 0xFF5B3A6E.toInt(),
        ),
        memory(id = 3, kind = MemoryKind.YearsAgo(2), count = 57, colors = 0xFF6E9B5A.toInt() to 0xFF24413A.toInt()),
    )

    @Test
    fun souvenirsClair() = capture(dark = false)

    @Test
    fun souvenirsSombre() = capture(dark = true)

    /** Largeur d'un téléphone : un nom long fait passer le titre sur deux lignes équilibrées. */
    @Test
    @Config(qualifiers = "fr-rFR-w400dp-h800dp-xhdpi")
    fun souvenirsNomLong() = capture(dark = false) {
        val long = memory(
            id = 4,
            kind = MemoryKind.WithPerson(2, personId = 5, name = "Marie-Christine", face = null),
            count = 12,
            colors = 0xFF8C6BB1.toInt() to 0xFF3B2F4F.toInt(),
        )
        listOf(long) + memories()
    }

    private fun capture(dark: Boolean, sample: () -> List<Memory> = ::memories) {
        val memories = sample()
        compose.setContent {
            FocaleTheme(AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false)) {
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    MemoriesCarousel(memories = memories, onOpen = {}, modifier = Modifier.padding(top = 16.dp))
                }
            }
        }
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    /** Souvenir dont la couverture est déjà dans le cache mémoire, sous la clé de la carte. */
    private fun memory(id: Long, kind: MemoryKind, count: Int, colors: Pair<Int, Int>): Memory {
        val date = LocalDate.of(2025, 10, 20).minusYears(kind.yearsAgo - 1L).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        val cover = MediaItem(
            id = id, uri = Uri.parse("content://media/external/images/media/$id"), type = MediaType.IMAGE,
            mimeType = "image/jpeg", displayName = "IMG_$id.jpg", relativePath = "DCIM/Camera/", bucketId = 1,
            bucketName = "Camera", dateTaken = date, dateModified = date, size = 1, width = 4000, height = 3000,
            orientation = 0, durationMs = 0, isGif = false, isScreenshot = false,
        )
        // Ciel et sol en aplats : une image lisible qui reste légère une fois compressée.
        val side = 64
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(colors.first)
            drawRect(0f, side * 0.62f, side.toFloat(), side.toFloat(), Paint().apply { color = colors.second })
        }
        SingletonImageLoader.get(ApplicationProvider.getApplicationContext()).memoryCache
            ?.set(MemoryCache.Key(ThumbnailSizes.memoryKey(id, date, MEMORY_CARD_THUMBNAIL)), MemoryCache.Value(bitmap.asImage()))
        return Memory(kind, MediaQuery(), count, cover)
    }
}

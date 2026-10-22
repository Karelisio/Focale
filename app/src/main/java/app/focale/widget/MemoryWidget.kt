package app.focale.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.focale.MainActivity
import app.focale.R
import app.focale.core.locale.AppLocales
import app.focale.ui.theme.FocaleSeed
import app.focale.ui.theme.seededColorScheme
import com.materialkolor.PaletteStyle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Widget « Souvenir » : une photo de la bibliothèque (souvenir du jour en priorité : années
 * passées, personnes, voyages), renouvelée toutes les quelques heures, aux couleurs
 * Material You. Un appui l'ouvre dans la visionneuse de Focale.
 */
class MemoryWidget : GlanceAppWidget() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Dependencies {
        fun photoPicker(): WidgetPhotoPicker
        fun appLocales(): AppLocales
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val dependencies = EntryPointAccessors.fromApplication(context.applicationContext, Dependencies::class.java)
        val photo = runCatching { dependencies.photoPicker().pick() }.getOrNull()
        val bitmap = photo?.let { runCatching { loadBitmap(context, it) }.getOrNull() }
        // Mis à jour par une tâche de fond, avec le contexte de l'application : textes dans la langue de Focale.
        val text = dependencies.appLocales().localized(context)
        provideContent {
            GlanceTheme(colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) GlanceTheme.colors else fallbackColors) {
                if (photo != null && bitmap != null) {
                    PhotoContent(context, photo, bitmap)
                } else {
                    EmptyContent(context, text)
                }
            }
        }
    }

    /** Miniature MediaStore, convertie en image logicielle pour être transmise au lanceur. */
    private fun loadBitmap(context: Context, photo: WidgetPhoto): Bitmap {
        val thumbnail = context.contentResolver.loadThumbnail(photo.item.uri, Size(BITMAP_SIZE, BITMAP_SIZE), null)
        return if (thumbnail.config == Bitmap.Config.HARDWARE) thumbnail.copy(Bitmap.Config.ARGB_8888, false) else thumbnail
    }

    companion object {
        private const val BITMAP_SIZE = 640

        /** Couleurs de Focale avant Android 12 (pas de couleurs dynamiques). */
        private val fallbackColors by lazy {
            ColorProviders(
                light = seededColorScheme(FocaleSeed, dark = false, style = PaletteStyle.TonalSpot),
                dark = seededColorScheme(FocaleSeed, dark = true, style = PaletteStyle.TonalSpot),
            )
        }

        /** Intention explicite vers la visionneuse ; l'URI rend chaque intention distincte. */
        fun openMediaIntent(context: Context, mediaId: Long): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_MEDIA)
                .setData("focale://media/$mediaId".toUri())
                .putExtra(MainActivity.EXTRA_MEDIA_ID, mediaId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

@Composable
private fun PhotoContent(context: Context, photo: WidgetPhoto, bitmap: Bitmap) {
    Box(
        contentAlignment = Alignment.BottomStart,
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(24.dp)
            .background(GlanceTheme.colors.surfaceVariant)
            .clickable(actionStartActivity(MemoryWidget.openMediaIntent(context, photo.item.id))),
    ) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = photo.title,
            contentScale = ContentScale.Crop,
            modifier = GlanceModifier.fillMaxSize(),
        )
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(ImageProvider(R.drawable.widget_scrim))
                .padding(start = 14.dp, end = 14.dp, top = 24.dp, bottom = 12.dp),
        ) {
            Text(
                text = photo.title,
                maxLines = 1,
                style = TextStyle(color = ColorProvider(Color.White), fontSize = 15.sp, fontWeight = FontWeight.Bold),
            )
            Text(
                text = photo.subtitle,
                maxLines = 1,
                style = TextStyle(color = ColorProvider(Color.White.copy(alpha = 0.85f)), fontSize = 12.sp),
            )
        }
    }
}

@Composable
private fun EmptyContent(context: Context, text: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        ?: Intent(context, MainActivity::class.java)
    Column(
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(24.dp)
            .background(GlanceTheme.colors.secondaryContainer)
            .padding(16.dp)
            .clickable(actionStartActivity(launch)),
    ) {
        Image(
            provider = ImageProvider(R.drawable.ic_launcher_monochrome),
            contentDescription = null,
            colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer),
            modifier = GlanceModifier.size(56.dp),
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(
            text = text.getString(R.string.widget_empty),
            style = TextStyle(
                color = GlanceTheme.colors.onSecondaryContainer,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            ),
        )
    }
}

/** Récepteur du widget : planifie son renouvellement tant qu'une instance existe. */
class MemoryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MemoryWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefresh.schedule(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetRefresh.cancel(context)
    }
}

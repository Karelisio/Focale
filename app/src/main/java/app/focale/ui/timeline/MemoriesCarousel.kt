package app.focale.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.CarouselItemScope
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.core.image.ThumbnailSizes
import app.focale.core.image.thumbnail
import app.focale.core.util.Formatters
import app.focale.data.Memory
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/**
 * Souvenirs « il y a X ans » en tête de la timeline : carrousel Material 3 à
 * navigation multiple, chaque carte ouvrant les médias de la semaine concernée.
 */
@Composable
fun MemoriesCarousel(memories: List<Memory>, onOpen: (Memory) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(
            text = stringResource(R.string.memories_title),
            style = MaterialTheme.typography.titleMediumEmphasized,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
        )
        HorizontalMultiBrowseCarousel(
            state = rememberCarouselState { memories.size },
            preferredItemWidth = 196.dp,
            itemSpacing = 8.dp,
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(236.dp),
        ) { index ->
            val memory = memories[index]
            MemoryCard(memory = memory, onClick = { onOpen(memory) })
        }
    }
}

@Composable
private fun CarouselItemScope.MemoryCard(memory: Memory, onClick: () -> Unit) {
    val context = LocalContext.current
    val cover = memory.cover
    val info = carouselItemDrawInfo
    Box(
        Modifier
            .fillMaxSize()
            .maskClip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick),
    ) {
        AsyncImage(
            model = remember(cover.id, cover.dateModified) {
                ImageRequest.Builder(context)
                    .data(cover.thumbnail())
                    .size(CARD_THUMBNAIL)
                    .memoryCacheKey(ThumbnailSizes.memoryKey(cover.id, cover.dateModified, CARD_THUMBNAIL))
                    .build()
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // Légende alignée sur la partie visible de la carte, estompée quand elle rétrécit.
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .graphicsLayer {
                    val range = info.maxSize - info.minSize
                    alpha = if (range > 0f) ((info.size - info.minSize) / range).coerceIn(0f, 1f) else 1f
                    translationX = info.maskRect.left
                }
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                .padding(start = 14.dp, end = 14.dp, top = 28.dp, bottom = 12.dp),
        ) {
            Text(
                text = pluralStringResource(R.plurals.memories_years_ago, memory.yearsAgo, memory.yearsAgo),
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = Color.White,
                maxLines = 1,
            )
            Text(
                text = Formatters.dayMonthYear(context, cover.dateTaken) + " · " +
                    pluralStringResource(R.plurals.media_count, memory.count, memory.count),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
            )
        }
    }
}

private const val CARD_THUMBNAIL = 512

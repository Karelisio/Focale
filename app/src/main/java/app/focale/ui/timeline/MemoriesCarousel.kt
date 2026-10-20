package app.focale.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Luggage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.carousel.CarouselItemScope
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.core.image.ThumbnailSizes
import app.focale.core.image.thumbnail
import app.focale.core.util.Formatters
import app.focale.data.Memory
import app.focale.data.MemoryKind
import app.focale.data.MemoryTitles
import app.focale.ui.people.FaceAvatar
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.time.temporal.ChronoUnit

/**
 * Souvenirs en tête de la timeline (« il y a X ans », avec une personne, voyages) :
 * carrousel Material 3 à navigation multiple, chaque carte ouvrant les médias du souvenir.
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
    // Badge et légende alignés sur la partie visible de la carte, estompés quand elle rétrécit.
    val visiblePart = Modifier.graphicsLayer {
        val range = info.maxSize - info.minSize
        alpha = if (range > 0f) ((info.size - info.minSize) / range).coerceIn(0f, 1f) else 1f
        translationX = info.maskRect.left
    }
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
                    .size(MEMORY_CARD_THUMBNAIL)
                    .memoryCacheKey(ThumbnailSizes.memoryKey(cover.id, cover.dateModified, MEMORY_CARD_THUMBNAIL))
                    .build()
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        MemoryBadge(
            kind = memory.kind,
            modifier = Modifier
                .align(Alignment.TopStart)
                .then(visiblePart)
                .padding(12.dp),
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .then(visiblePart)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))))
                .padding(start = 14.dp, end = 14.dp, top = 28.dp, bottom = 12.dp),
        ) {
            Text(
                text = MemoryTitles.cardTitle(LocalResources.current, memory.kind),
                // Deux lignes équilibrées : « Il y a 1 an / avec Alice » plutôt qu'un nom coupé.
                style = MaterialTheme.typography.titleMediumEmphasized.copy(lineBreak = LineBreak.Heading),
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // Un voyage a déjà ses mois dans le titre : sa durée plutôt que la date de la couverture.
            val kind = memory.kind
            val detail = if (kind is MemoryKind.Trip) {
                val days = ChronoUnit.DAYS.between(kind.firstDay, kind.lastDay).toInt() + 1
                pluralStringResource(R.plurals.memories_trip_days, days, days)
            } else {
                Formatters.dayMonthYear(context, cover.dateTaken)
            }
            Text(
                text = detail + " · " + pluralStringResource(R.plurals.media_count, memory.count, memory.count),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.85f),
                maxLines = 1,
            )
        }
    }
}

/** Ce qui distingue une carte du souvenir général : le visage de la personne, la valise d'un voyage. */
@Composable
private fun MemoryBadge(kind: MemoryKind, modifier: Modifier) {
    val ring = modifier
        .size(BADGE_SIZE)
        .border(2.dp, Color.White.copy(alpha = 0.9f), CircleShape)
    when (kind) {
        is MemoryKind.WithPerson -> FaceAvatar(face = kind.face, modifier = ring)
        is MemoryKind.Trip -> Box(
            contentAlignment = Alignment.Center,
            modifier = ring
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Icon(
                Icons.Rounded.Luggage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )
        }
        is MemoryKind.YearsAgo -> Unit
    }
}

/** Taille demandée pour la couverture d'une carte (clé du cache mémoire). */
internal const val MEMORY_CARD_THUMBNAIL = 512
private val BADGE_SIZE = 36.dp

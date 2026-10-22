package app.focale.ui.collage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.RoundedCorner
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.collage.CollageAspect
import app.focale.collage.CollageBackground
import app.focale.collage.CollageGeometry
import app.focale.collage.CollageLayout
import app.focale.collage.CollageState
import app.focale.ui.components.Choice
import app.focale.ui.components.ConnectedChoiceGroup
import app.focale.ui.editor.InkSwatch

/** Réglages de l'écran de collage, un panneau chacun. */
enum class CollageTool { LAYOUT, FORMAT, BORDERS, BACKGROUND }

/** Quatre panneaux : seul celui ouvert affiche son libellé. */
@Composable
internal fun CollageToolChoices(tool: CollageTool, onSelect: (CollageTool) -> Unit) {
    ConnectedChoiceGroup(
        choices = listOf(
            Choice(CollageTool.LAYOUT, stringResource(R.string.collage_tool_layout), Icons.Outlined.Dashboard),
            Choice(CollageTool.FORMAT, stringResource(R.string.collage_tool_format), Icons.Outlined.AspectRatio),
            Choice(CollageTool.BORDERS, stringResource(R.string.collage_tool_borders), Icons.Outlined.RoundedCorner),
            Choice(CollageTool.BACKGROUND, stringResource(R.string.collage_tool_background), Icons.Outlined.FormatColorFill),
        ),
        selected = tool,
        onSelect = onSelect,
        compact = true,
        modifier = Modifier
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/**
 * Dispositions proposées pour ce nombre de photos, en vignettes à la proportion du collage ; la
 * choisie est en couleur d'accent. Chaque vignette est nommée pour TalkBack (« Grille 2 × 2 »).
 */
@Composable
internal fun LayoutPanel(layouts: List<CollageLayout>, selected: String, aspect: CollageAspect, onSelect: (String) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = layouts.indexOfFirst { it.id == selected }.coerceAtLeast(0))
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        items(layouts, key = { it.id }) { layout ->
            LayoutThumbnail(
                layout = layout,
                aspect = aspect,
                selected = layout.id == selected,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(layout.id)
                },
            )
        }
    }
}

@Composable
private fun LayoutThumbnail(layout: CollageLayout, aspect: CollageAspect, selected: Boolean, onClick: () -> Unit) {
    val name = layout.name.asString()
    val shape = RoundedCornerShape(if (selected) 18.dp else 12.dp)
    val colors = MaterialTheme.colorScheme
    val cellColor = if (selected) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.45f)
    // Hauteur fixe, largeur à la proportion du collage (bornée pour les formats extrêmes).
    val width = (THUMBNAIL_HEIGHT * aspect.ratio).coerceIn(THUMBNAIL_HEIGHT * 0.6f, THUMBNAIL_HEIGHT * 1.6f)
    Box(
        Modifier
            .size(width, THUMBNAIL_HEIGHT)
            .clip(shape)
            .background(if (selected) colors.primaryContainer else colors.surfaceContainerHighest)
            .border(if (selected) 2.dp else 0.dp, if (selected) colors.primary else Color.Transparent, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name }
            .padding(5.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val gap = 3.dp.toPx()
            val corner = CornerRadius(2.5.dp.toPx())
            CollageGeometry.cells(layout, size.width, size.height, gap).forEach { cell ->
                drawRoundRect(cellColor, Offset(cell.left, cell.top), Size(cell.width, cell.height), corner)
            }
        }
    }
}

private val THUMBNAIL_HEIGHT: Dp = 64.dp

/** Proportions du collage en pastilles : « 1:1 » affiché, nom complet lu par TalkBack (« Carré (1:1) »). */
@Composable
internal fun FormatPanel(selected: CollageAspect, onSelect: (CollageAspect) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        items(CollageAspect.entries) { aspect ->
            val name = aspectName(aspect)
            FilterChip(
                selected = aspect == selected,
                onClick = { onSelect(aspect) },
                label = { Text("${aspect.width}:${aspect.height}") },
                leadingIcon = { AspectGlyph(aspect) },
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.semantics { contentDescription = name },
            )
        }
    }
}

/** Petit cadre à la proportion [aspect], dans un carré de 18 dp. */
@Composable
private fun AspectGlyph(aspect: CollageAspect) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(18.dp)) {
        val stroke = 1.5.dp.toPx()
        val side = size.minDimension - stroke
        val width = if (aspect.ratio >= 1f) side else side * aspect.ratio
        val height = if (aspect.ratio >= 1f) side / aspect.ratio else side
        drawRoundRect(
            color = color,
            topLeft = Offset((size.width - width) / 2f, (size.height - height) / 2f),
            size = Size(width, height),
            cornerRadius = CornerRadius(2.dp.toPx()),
            style = Stroke(stroke),
        )
    }
}

@Composable
internal fun aspectName(aspect: CollageAspect): String = stringResource(
    when (aspect) {
        CollageAspect.SQUARE -> R.string.collage_aspect_square
        CollageAspect.PORTRAIT -> R.string.collage_aspect_portrait
        CollageAspect.PHOTO -> R.string.collage_aspect_photo
        CollageAspect.STORY -> R.string.collage_aspect_story
        CollageAspect.LANDSCAPE -> R.string.collage_aspect_landscape
    },
)

/**
 * Espacement entre les cases (et autour) et arrondi de leurs coins. Comme dans l'éditeur, un
 * glissement du curseur forme une seule étape d'historique.
 */
@Composable
internal fun BordersPanel(
    spacing: Float,
    corners: Float,
    onSpacingStart: () -> Unit,
    onSpacing: (Float) -> Unit,
    onCornersStart: () -> Unit,
    onCorners: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        SettingSlider(stringResource(R.string.collage_spacing), spacing, CollageState.MAX_SPACING, onSpacingStart, onSpacing)
        SettingSlider(stringResource(R.string.collage_corners), corners, CollageState.MAX_CORNERS, onCornersStart, onCorners)
    }
}

@Composable
private fun SettingSlider(label: String, value: Float, max: Float, onStart: () -> Unit, onValue: (Float) -> Unit) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
        )
        val sliderState = rememberSliderState(value = value, trackRange = 0f..max)
        // Synchronise le curseur avec l'état (annuler, rétablir).
        LaunchedEffect(value) { if (sliderState.value != value) sliderState.value = value }
        var dragging by remember { mutableStateOf(false) }
        Slider(
            state = sliderState,
            onValueChange = {
                if (!dragging) {
                    dragging = true
                    onStart()
                }
                sliderState.value = it
                onValue(it)
            },
            onValueChangeFinished = { dragging = false },
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label },
        )
    }
}

/**
 * Fond du collage : blanc, noir et six couleurs du thème Material You (principale, secondaire,
 * tertiaire et leurs teintes atténuées), en pastilles nommées. La couleur est résolue au choix :
 * c'est elle que l'aperçu montre et que l'export peint.
 */
@Composable
internal fun BackgroundPanel(selected: CollageBackground, onSelect: (CollageBackground, Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        CollageBackground.entries.forEach { background ->
            val color = background.color(scheme)
            InkSwatch(
                color = color,
                label = backgroundName(background),
                selected = background == selected,
                enabled = true,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(background, color.toArgb())
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Couleur du fond dans le schéma [scheme]. */
fun CollageBackground.color(scheme: ColorScheme): Color = when (this) {
    CollageBackground.WHITE -> Color.White
    CollageBackground.BLACK -> Color.Black
    CollageBackground.PRIMARY -> scheme.primary
    CollageBackground.PRIMARY_SOFT -> scheme.primaryContainer
    CollageBackground.SECONDARY -> scheme.secondary
    CollageBackground.SECONDARY_SOFT -> scheme.secondaryContainer
    CollageBackground.TERTIARY -> scheme.tertiary
    CollageBackground.TERTIARY_SOFT -> scheme.tertiaryContainer
}

@Composable
private fun backgroundName(background: CollageBackground): String = stringResource(
    when (background) {
        CollageBackground.WHITE -> R.string.collage_background_white
        CollageBackground.BLACK -> R.string.collage_background_black
        CollageBackground.PRIMARY -> R.string.collage_background_primary
        CollageBackground.PRIMARY_SOFT -> R.string.collage_background_primary_soft
        CollageBackground.SECONDARY -> R.string.collage_background_secondary
        CollageBackground.SECONDARY_SOFT -> R.string.collage_background_secondary_soft
        CollageBackground.TERTIARY -> R.string.collage_background_tertiary
        CollageBackground.TERTIARY_SOFT -> R.string.collage_background_tertiary_soft
    },
)

package app.focale.ui.editor

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material.icons.outlined.HorizontalRule
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.Rectangle
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberSliderState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import app.focale.R
import app.focale.edit.DrawTool
import app.focale.edit.DrawingRenderer
import app.focale.ui.components.MorphShape

/**
 * Panneau de l'outil Dessin : outils en boutons connectés, palette de pastilles et
 * épaisseur, avec son aperçu à la taille réelle du trait à l'écran.
 *
 * @param ink couleur résolue de [DrawingOptions.ink] dans le thème de l'éditeur.
 */
@Composable
fun DrawingPanel(
    options: DrawingOptions,
    ink: Color,
    onTool: (DrawTool) -> Unit,
    onInk: (InkColor) -> Unit,
    onSize: (Float) -> Unit,
) {
    // Couleur et épaisseur sont sans objet pour la gomme : estompées plutôt que masquées,
    // pour que l'aperçu de la photo garde sa taille.
    val inking = options.tool != DrawTool.ERASER
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        DrawToolGroup(selected = options.tool, onSelect = onTool)
        InkPalette(selected = options.ink, enabled = inking, onSelect = onInk, modifier = Modifier.padding(top = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SizePreview(options, ink, Modifier.size(PREVIEW_SIZE))
            val label = stringResource(R.string.draw_thickness)
            val sliderState = rememberSliderState(value = options.size, trackRange = DrawingOptions.MIN_SIZE..DrawingOptions.MAX_SIZE)
            Slider(
                state = sliderState,
                onValueChange = {
                    sliderState.value = it
                    onSize(it)
                },
                enabled = inking,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
                    .semantics { contentDescription = label },
            )
        }
    }
}

/** Couleur de la pastille dans le thème courant (Material You pour les trois premières). */
@Composable
@ReadOnlyComposable
fun InkColor.color(): Color = when (this) {
    InkColor.PRIMARY -> MaterialTheme.colorScheme.primary
    InkColor.SECONDARY -> MaterialTheme.colorScheme.secondary
    InkColor.TERTIARY -> MaterialTheme.colorScheme.tertiary
    InkColor.BLACK -> Color.Black
    InkColor.WHITE -> Color.White
    InkColor.RED -> Color(0xFFE53935)
    InkColor.YELLOW -> Color(0xFFFDD835)
    InkColor.GREEN -> Color(0xFF43A047)
    InkColor.BLUE -> Color(0xFF1E88E5)
}

/** Outils en groupe connecté d'icônes ; un appui long en donne le nom. */
@Composable
private fun DrawToolGroup(selected: DrawTool, onSelect: (DrawTool) -> Unit, modifier: Modifier = Modifier) {
    IconToggleGroup(
        items = DrawTool.entries,
        selected = selected,
        label = { drawToolLabel(it) },
        onSelect = onSelect,
        modifier = modifier,
    ) { tool, label ->
        Icon(drawToolIcon(tool), contentDescription = label, modifier = Modifier.size(ToggleButtonDefaults.IconSize))
    }
}

/**
 * Choix exclusif en groupe connecté de boutons à icône, de même largeur ; un appui long en
 * donne le nom ([label], que [icon] reçoit pour le décrire à TalkBack).
 */
@Composable
internal fun <T> IconToggleGroup(
    items: List<T>,
    selected: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (item: T, label: String) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
    ) {
        items.forEachIndexed { index, item ->
            val text = label(item)
            // Le poids doit porter sur l'enfant direct de la rangée, pas sur l'infobulle.
            Box(Modifier.weight(1f)) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                    tooltip = { PlainTooltip { Text(text) } },
                    state = rememberTooltipState(),
                ) {
                    ToggleButton(
                        checked = item == selected,
                        onCheckedChange = {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(item)
                        },
                        shapes = when (index) {
                            0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            items.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { role = Role.RadioButton },
                    ) {
                        icon(item, text)
                    }
                }
            }
        }
    }
}

@Composable
internal fun InkPalette(selected: InkColor?, enabled: Boolean, onSelect: (InkColor) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier
            .fillMaxWidth()
            .selectableGroup()
            .alpha(if (enabled) 1f else DISABLED_ALPHA),
    ) {
        InkColor.entries.forEach { ink ->
            InkSwatch(
                color = ink.color(),
                label = inkLabel(ink),
                selected = ink == selected,
                enabled = enabled,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onSelect(ink)
                },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Pastille ronde qui se transforme en « cookie » coché une fois choisie, comme la sélection de la grille. */
@Composable
private fun InkSwatch(
    color: Color,
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "swatchMorph",
    )
    val morph = remember { Morph(MaterialShapes.Circle, MaterialShapes.Cookie9Sided) }
    val shape = remember(progress) { MorphShape(morph, progress) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(44.dp)
            .selectable(
                selected = selected,
                interactionSource = null,
                indication = ripple(bounded = false, radius = 20.dp),
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics { contentDescription = label },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(30.dp)
                .graphicsLayer {
                    rotationZ = progress * 40f
                    scaleX = 1f + 0.1f * progress
                    scaleY = 1f + 0.1f * progress
                }
                .clip(shape)
                .background(color)
                // Liseré visible même pour le noir sur le fond sombre de l'éditeur.
                .border(1.dp, MaterialTheme.colorScheme.outline, shape),
        ) {
            if (progress > 0.05f) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                    modifier = Modifier
                        .size(18.dp)
                        .graphicsLayer {
                            rotationZ = -progress * 40f
                            scaleX = progress
                            scaleY = progress
                        },
                )
            }
        }
    }
}

/** Aperçu de l'épaisseur à la taille réelle du trait ; pour la gomme, son rayon d'action. */
@Composable
private fun SizePreview(options: DrawingOptions, ink: Color, modifier: Modifier = Modifier) {
    val container = MaterialTheme.colorScheme.surfaceContainerHighest
    val outline = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        drawRoundRect(container, cornerRadius = CornerRadius(16.dp.toPx()))
        val diameter = options.strokeSize.dp.toPx()
        when (options.tool) {
            DrawTool.HIGHLIGHTER -> drawRect(
                color = ink.copy(alpha = DrawingRenderer.HIGHLIGHTER_ALPHA),
                topLeft = center - Offset(diameter / 2f, diameter / 2f),
                size = Size(diameter, diameter),
            )
            DrawTool.ERASER -> drawCircle(outline, radius = ERASER_RADIUS.toPx(), style = Stroke(1.5.dp.toPx()))
            else -> drawCircle(ink, radius = diameter / 2f)
        }
    }
}

@Composable
private fun drawToolLabel(tool: DrawTool): String = stringResource(
    when (tool) {
        DrawTool.PEN -> R.string.draw_pen
        DrawTool.HIGHLIGHTER -> R.string.draw_highlighter
        DrawTool.LINE -> R.string.draw_line
        DrawTool.ARROW -> R.string.draw_arrow
        DrawTool.RECTANGLE -> R.string.draw_rectangle
        DrawTool.ELLIPSE -> R.string.draw_ellipse
        DrawTool.ERASER -> R.string.draw_eraser
    },
)

private fun drawToolIcon(tool: DrawTool): ImageVector = when (tool) {
    DrawTool.PEN -> Icons.Outlined.Gesture
    DrawTool.HIGHLIGHTER -> Icons.Outlined.BorderColor
    DrawTool.LINE -> Icons.Outlined.HorizontalRule
    DrawTool.ARROW -> Icons.Outlined.NorthEast
    DrawTool.RECTANGLE -> Icons.Outlined.Rectangle
    DrawTool.ELLIPSE -> Icons.Outlined.Circle
    DrawTool.ERASER -> EraserIcon
}

@Composable
private fun inkLabel(ink: InkColor): String = stringResource(
    when (ink) {
        InkColor.PRIMARY -> R.string.draw_color_primary
        InkColor.SECONDARY -> R.string.draw_color_secondary
        InkColor.TERTIARY -> R.string.draw_color_tertiary
        InkColor.BLACK -> R.string.draw_color_black
        InkColor.WHITE -> R.string.draw_color_white
        InkColor.RED -> R.string.draw_color_red
        InkColor.YELLOW -> R.string.draw_color_yellow
        InkColor.GREEN -> R.string.draw_color_green
        InkColor.BLUE -> R.string.draw_color_blue
    },
)

/** Gomme : glyphe « ink_eraser » des Material Symbols, absent des icônes Material étendues. */
private val EraserIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Outlined.InkEraser",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 960f,
        viewportHeight = 960f,
    )
        // Tracé exprimé de y = -960 à 0, comme dans les Material Symbols.
        .addGroup(translationY = 960f)
        .addPath(
            pathData = addPathNodes(
                "M690-240h190v80H610l80-80Zm-500 80-85-85q-23-23-23.5-57t22.5-58l440-456q23-24 56.5-24t56.5 " +
                    "23l199 199q23 23 23 57t-23 57L520-160H190Zm296-80 314-322-198-198-442 456 64 64h262Zm-6-240Z",
            ),
            fill = SolidColor(Color.Black),
        )
        .clearGroup()
        .build()
}

internal val PREVIEW_SIZE = 52.dp
internal const val DISABLED_ALPHA = 0.38f

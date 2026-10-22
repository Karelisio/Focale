package app.focale.ui.viewer

import androidx.collection.intListOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorPosition
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import app.focale.R
import java.text.NumberFormat
import java.util.Locale

/** Vitesses de lecture des vidéos (logique pure) : celles que proposent les commandes, et leur écriture. */
internal object VideoSpeeds {
    /** Vitesse normale, celle de chaque ouverture d'une visionneuse. */
    const val NORMAL = 1f

    /** Vitesses proposées, de 0,25× à 2× par quarts, comme dans la plupart des lecteurs (Media3 compris). */
    val All: List<Float> = listOf(0.25f, 0.5f, 0.75f, NORMAL, 1.25f, 1.5f, 1.75f, 2f)

    /**
     * Multiplicateur [speed] aux conventions de [locale], sans zéro inutile : « 1,5 », « 0,25 » ou
     * « 2 » en français, « 1.5 » en anglais. Le signe « × » vient des chaînes (`video_speed_value`).
     */
    fun format(speed: Float, locale: Locale): String =
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 2 }.format(speed.toDouble())
}

/**
 * Vitesse de lecture des vidéos d'une visionneuse, à donner à chacune de ses pages vidéo
 * ([VideoPlayerPage]) : choisie dans les commandes, elle vaut pour la vidéo en cours et les
 * suivantes, et survit à une rotation ou à une recréation de l'activité, comme l'affichage des
 * commandes ; quitter la visionneuse l'oublie, la prochaine ouverture repart de 1×.
 */
@Composable
fun rememberVideoSpeed(): MutableFloatState = rememberSaveable { mutableFloatStateOf(VideoSpeeds.NORMAL) }

/**
 * Bouton de vitesse des commandes vidéo, à l'autre bout du curseur que le bouton son : la vitesse
 * en cours (« 1× », « 1,5× »…), à la couleur d'accent hors de 1×, comme le son coupé. Un appui
 * ouvre le menu des vitesses ([VideoSpeedMenu]) ; le choix s'applique aussitôt et referme le menu.
 * TalkBack le nomme « Vitesse de lecture : 1,5× » et relit ce nom à chaque nouvelle vitesse.
 *
 * @param menuMaxHeight hauteur dont dispose le menu ; au-delà (paysage), il défile.
 */
@Composable
internal fun VideoSpeedButton(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    menuMaxHeight: Dp,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val label = speedLabel(speed)
    val description = stringResource(R.string.video_speed_button, label)
    Box(modifier) {
        TextButton(
            onClick = { menuOpen = true },
            shapes = ButtonDefaults.shapes(),
            enabled = enabled,
            colors = ButtonDefaults.textButtonColors(
                contentColor = if (speed == VideoSpeeds.NORMAL) LocalContentColor.current else MaterialTheme.colorScheme.primary,
            ),
            contentPadding = SpeedButtonPadding,
            modifier = Modifier
                // Pas plus large que le bouton son tant que le libellé y tient : le curseur garde sa place.
                .widthIn(min = SpeedButtonMinWidth)
                .semantics {
                    contentDescription = description
                    liveRegion = LiveRegionMode.Polite
                },
        ) {
            Text(
                text = label,
                // Chiffres à chasse fixe, comme les temps de la progression.
                style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum"),
                maxLines = 1,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
        VideoSpeedMenu(
            expanded = menuOpen,
            speed = speed,
            maxHeight = menuMaxHeight,
            onSelect = {
                menuOpen = false
                onSpeedChange(it)
            },
            onDismiss = { menuOpen = false },
        )
    }
}

/**
 * Menu des vitesses (Material 3 Expressive), au-dessus du bouton ([speedMenuPosition]) : un groupe
 * d'options, la vitesse en cours cochée (TalkBack : cases d'option, dont celle-ci cochée, sous le
 * titre « Vitesse de lecture »). S'il ne tient pas dans [maxHeight], il défile, la vitesse en
 * cours en vue.
 */
@Composable
private fun VideoSpeedMenu(
    expanded: Boolean,
    speed: Float,
    maxHeight: Dp,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val title = stringResource(R.string.video_speed_title)
    val gap = with(LocalDensity.current) { SpeedMenuGap.roundToPx() }
    val position = remember(gap) { speedMenuPosition(gap) }
    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismiss,
        popupPositionProvider = MenuDefaults.rememberDropdownMenuPopupPositionProvider(position),
    ) {
        val current = remember { BringIntoViewRequester() }
        LaunchedEffect(Unit) {
            // Menu mesuré à l'image suivante : la vitesse en cours défile alors en vue.
            withFrameNanos {}
            current.bringIntoView()
        }
        DropdownMenuGroup(
            shapes = MenuDefaults.groupShape(0, 1),
            modifier = Modifier
                // Deux options au moins, même dans une fenêtre très basse (écran partagé).
                .heightIn(max = maxHeight.coerceAtLeast(SpeedMenuMinHeight))
                .verticalScroll(rememberScrollState())
                .semantics { paneTitle = title },
        ) {
            VideoSpeeds.All.forEachIndexed { index, option ->
                val selected = option == speed
                SelectableDropdownMenuItem(
                    selected = selected,
                    onClick = { onSelect(option) },
                    text = { Text(speedLabel(option), style = LocalTextStyle.current.copy(fontFeatureSettings = "tnum")) },
                    shapes = MenuDefaults.itemShape(index, VideoSpeeds.All.size),
                    selectedLeadingIcon = {
                        Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(MenuDefaults.LeadingIconSize))
                    },
                    modifier = if (selected) Modifier.bringIntoViewRequester(current) else Modifier,
                )
            }
        }
    }
}

/**
 * Place du menu des vitesses : au-dessus du bouton, à [gap] pixels de lui, ce qui dégage les temps
 * de la progression ; s'il n'y tient pas (paysage), bas du menu au bas du bouton, qu'il recouvre
 * sans descendre sur la barre d'outils. Aligné sur le début du bouton, sinon sur sa fin.
 */
private fun speedMenuPosition(gap: Int): MenuAnchorPosition = MenuAnchorPosition.Custom(
    xCandidates = {
        val fromStart = anchorBounds.left
        val fromEnd = anchorBounds.right - menuSize.width
        if (layoutDirection == LayoutDirection.Ltr) intListOf(fromStart, fromEnd) else intListOf(fromEnd, fromStart)
    },
    yCandidates = { intListOf(anchorBounds.top - gap - menuSize.height, anchorBounds.bottom - menuSize.height) },
)

/** Libellé d'une vitesse dans la langue de l'application : « 1,5× » en français, « 1.5× » en anglais. */
@Composable
private fun speedLabel(speed: Float): String =
    stringResource(R.string.video_speed_value, VideoSpeeds.format(speed, LocalConfiguration.current.locales[0]))

/**
 * Marge que laissent les menus Material 3 en haut et en bas de l'écran : au-delà de la hauteur qui
 * va d'elle au bas du bouton, le menu des vitesses défile.
 */
internal val SpeedMenuMargin = 48.dp

/** Écart entre le menu des vitesses et son bouton : les temps, au-dessus du curseur, restent visibles. */
private val SpeedMenuGap = 8.dp

/** Hauteur minimale du menu des vitesses : deux options de 48 dp. */
private val SpeedMenuMinHeight = 96.dp

/** Largeur minimale du bouton de vitesse : celle du bouton son, pour un curseur centré entre les deux. */
private val SpeedButtonMinWidth = 48.dp
private val SpeedButtonPadding = PaddingValues(horizontal = 4.dp)

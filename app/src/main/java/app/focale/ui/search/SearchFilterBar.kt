package app.focale.ui.search

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.core.model.MediaFilter
import app.focale.data.SearchChip
import app.focale.people.Person
import app.focale.ui.components.EmptyState
import app.focale.ui.components.labelRes
import app.focale.ui.people.FaceAvatar

/** Nature d'une pastille, et le bouton qui en ajoute une. */
enum class SearchFilterKind(@StringRes val label: Int, @StringRes val addDescription: Int, val icon: ImageVector) {
    PERSON(R.string.search_filter_person, R.string.search_filter_add_person, Icons.Outlined.Face),
    PLACE(R.string.search_filter_place, R.string.search_filter_add_place, Icons.Outlined.LocationOn),
    PERIOD(R.string.search_filter_period, R.string.search_filter_add_period, Icons.Outlined.CalendarMonth),
    TYPE(R.string.search_filter_type, R.string.search_filter_add_type, Icons.Outlined.PermMedia),
}

/** Types proposés par la pastille « Type ». */
val SearchTypes: List<MediaFilter> = listOf(MediaFilter.PHOTOS, MediaFilter.VIDEOS, MediaFilter.SCREENSHOTS, MediaFilter.GIFS)

/** Icône d'une pastille (hors personne, qui montre son visage). */
fun SearchChip.icon(): ImageVector = when (this) {
    is SearchChip.Person -> Icons.Outlined.Face
    is SearchChip.Place -> Icons.Outlined.LocationOn
    is SearchChip.Period -> Icons.Outlined.CalendarMonth
    is SearchChip.Type -> filter.icon()
}

fun MediaFilter.icon(): ImageVector = when (this) {
    MediaFilter.ALL -> Icons.Outlined.PermMedia
    MediaFilter.PHOTOS -> Icons.Outlined.Image
    MediaFilter.VIDEOS -> Icons.Outlined.Videocam
    MediaFilter.SCREENSHOTS -> Icons.Outlined.Screenshot
    MediaFilter.GIFS -> Icons.Outlined.Gif
}

/**
 * Barre des filtres de la recherche, sur une ligne qui défile : pastilles actives (un appui en
 * retire une, icône ×), puis de quoi en ajouter. Une personne de plus est toujours possible ; lieu,
 * période et type n'ont qu'une pastille chacun.
 * @param people personnes connues : nom actuel et visage des pastilles de personne.
 * @param kinds natures proposées à l'ajout (pas de personne tant qu'aucune n'est reconnue…).
 */
@Composable
fun SearchFilterBar(
    chips: List<SearchChip>,
    people: Map<Long, Person>,
    onRemove: (SearchChip) -> Unit,
    onAdd: (SearchFilterKind) -> Unit,
    onPickType: (MediaFilter) -> Unit,
    modifier: Modifier = Modifier,
    kinds: Set<SearchFilterKind> = SearchFilterKind.entries.toSet(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding),
    ) {
        chips.forEach { chip -> key(chip) { ActiveFilterChip(chip, people, onRemove) } }
        SearchFilterKind.entries.filter { it in kinds }.forEach { kind ->
            val taken = when (kind) {
                SearchFilterKind.PERSON -> false
                SearchFilterKind.PLACE -> chips.any { it is SearchChip.Place }
                SearchFilterKind.PERIOD -> chips.any { it is SearchChip.Period }
                SearchFilterKind.TYPE -> chips.any { it is SearchChip.Type }
            }
            when {
                taken -> Unit
                kind == SearchFilterKind.TYPE -> TypeFilterChip(onPickType)
                else -> AddFilterChip(kind) { onAdd(kind) }
            }
        }
    }
}

/** Pastille active : un appui la retire ; lue « Filtre de période : 2023, bouton ». */
@Composable
private fun ActiveFilterChip(chip: SearchChip, people: Map<Long, Person>, onRemove: (SearchChip) -> Unit) {
    val resources = LocalResources.current
    val haptics = LocalHapticFeedback.current
    val label = SearchFilterLabels.chip(resources, chip, people)
    val description = SearchFilterLabels.description(resources, chip, label)
    val removeLabel = stringResource(R.string.search_filter_remove)
    val remove = {
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        onRemove(chip)
    }
    // Visage de la personne s'il est connu, sinon l'icône de la nature du filtre.
    val face = (chip as? SearchChip.Person)?.let { people[it.id]?.cover }
    InputChip(
        selected = true,
        onClick = remove,
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) },
        shapes = InputChipDefaults.shapes(),
        avatar = face?.let { { FaceAvatar(it, Modifier.size(InputChipDefaults.AvatarSize)) } },
        leadingIcon = if (face == null) {
            { Icon(chip.icon(), contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize)) }
        } else {
            null
        },
        trailingIcon = { Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(InputChipDefaults.IconSize)) },
        // Un bouton (et non une case cochée) : l'appui retire le filtre.
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = description
            role = Role.Button
            onClick(label = removeLabel) {
                remove()
                true
            }
        },
    )
}

/** Pastille d'ajout (« Lieu ▾ ») : ouvre le choix correspondant. */
@Composable
private fun AddFilterChip(kind: SearchFilterKind, onClick: () -> Unit) {
    val description = stringResource(kind.addDescription)
    FilterChip(
        selected = false,
        onClick = onClick,
        label = { Text(stringResource(kind.label)) },
        shapes = FilterChipDefaults.shapes(),
        leadingIcon = { Icon(kind.icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
        trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = description
            role = Role.Button
            onClick {
                onClick()
                true
            }
        },
    )
}

/** Pastille « Type » et son menu (photos, vidéos, captures, GIF). */
@Composable
private fun TypeFilterChip(onPick: (MediaFilter) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        AddFilterChip(SearchFilterKind.TYPE) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SearchTypes.forEach { filter ->
                DropdownMenuItem(
                    text = { Text(stringResource(filter.labelRes())) },
                    leadingIcon = { Icon(filter.icon(), contentDescription = null) },
                    onClick = {
                        open = false
                        onPick(filter)
                    },
                )
            }
        }
    }
}

/**
 * Ligne sous les pastilles : nombre de résultats (annoncé par TalkBack quand il change) ou
 * recherche en cours, diaporama et « Tout effacer ».
 */
@Composable
fun SearchResultsHeader(
    count: Int,
    searching: Boolean,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    onSlideshow: (() -> Unit)? = null,
) {
    val searchingLabel = stringResource(R.string.search_filter_searching)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 4.dp),
    ) {
        Box(Modifier.weight(1f)) {
            when {
                searching -> LinearWavyProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(end = 16.dp)
                        .semantics { contentDescription = searchingLabel },
                )
                count >= 0 -> Text(
                    text = pluralStringResource(R.plurals.search_filter_results, count, count),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        if (onSlideshow != null) {
            IconButton(onClick = onSlideshow, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Outlined.Slideshow, contentDescription = stringResource(R.string.action_slideshow))
            }
        }
        TextButton(onClick = onClear) { Text(stringResource(R.string.search_filter_clear_all)) }
    }
}

/** Ce que donnerait la recherche sans une pastille ([chip]), ou sans son texte (null). */
data class Relaxation(val chip: SearchChip?, val count: Int)

/**
 * Aucun résultat : message clair et, quand c'est possible, les pastilles dont le retrait donnerait
 * des résultats (« Sans « 2023 » : 42 résultats »), un appui les retire.
 */
@Composable
fun SearchNoResults(
    relaxations: List<Relaxation>,
    label: (Relaxation) -> String,
    onRelax: (Relaxation) -> Unit,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        icon = Icons.Outlined.SearchOff,
        title = stringResource(R.string.search_filter_empty_title),
        message = stringResource(R.string.search_filter_empty_message),
        modifier = modifier,
        action = if (relaxations.isEmpty()) {
            null
        } else {
            {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    relaxations.forEach { relaxation ->
                        AssistChip(
                            onClick = { onRelax(relaxation) },
                            label = {
                                Text(pluralStringResource(R.plurals.search_filter_without, relaxation.count, label(relaxation), relaxation.count))
                            },
                            leadingIcon = {
                                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
                            },
                            shape = MaterialTheme.shapes.large,
                        )
                    }
                }
            }
        },
    )
}

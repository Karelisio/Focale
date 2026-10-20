package app.focale.ui.people

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.people.Person
import app.focale.people.PersonTogether

/**
 * « Avec… » : personnes à retrouver sur les photos de [person], parmi celles qui y figurent
 * ([candidates], nommées d'abord). Le bouton annonce le nombre de photos où toutes
 * figurent ensemble ([photoCount], -1 tant qu'il est inconnu).
 */
@Composable
fun TogetherSheet(
    person: Person,
    candidates: List<PersonTogether>?,
    selection: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    photoCount: Int,
    onShow: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Ouverte en entier : le bouton reste visible sous la liste.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            text = stringResource(R.string.people_together_sheet_title),
            style = MaterialTheme.typography.titleLargeEmphasized,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Text(
            text = stringResource(R.string.people_together_sheet_message, personLabel(person)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
        )
        when {
            candidates == null -> Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
                LoadingIndicator()
            }
            candidates.isEmpty() -> Text(
                text = stringResource(R.string.people_together_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(24.dp),
            )
            else -> {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    itemsIndexed(candidates, key = { _, candidate -> candidate.person.id }) { index, candidate ->
                        val id = candidate.person.id
                        val checked = id in selection
                        SegmentedListItem(
                            checked = checked,
                            onCheckedChange = { onSelectionChange(if (it) selection + id else selection - id) },
                            shapes = ListItemDefaults.segmentedShapes(index, candidates.size),
                            leadingContent = { FaceAvatar(candidate.person.cover, Modifier.size(48.dp)) },
                            trailingContent = { Checkbox(checked = checked, onCheckedChange = null) },
                            supportingContent = {
                                Text(pluralStringResource(R.plurals.people_together_count, candidate.photoCount, candidate.photoCount))
                            },
                        ) {
                            Text(personLabel(candidate.person), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Button(
                    onClick = onShow,
                    enabled = selection.isNotEmpty() && photoCount != 0,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) {
                    Text(
                        when {
                            selection.isEmpty() || photoCount < 0 -> stringResource(R.string.people_together_show)
                            photoCount == 0 -> stringResource(R.string.people_together_none)
                            else -> pluralStringResource(R.plurals.people_together_show_count, photoCount, photoCount)
                        },
                    )
                }
            }
        }
    }
}

/** Titre des photos où [people] figurent ensemble : « Alice et Bob », « Alice, Bob et Chloé ». */
fun togetherTitle(resources: Resources, people: List<Person>): String {
    val labels = people.map { it.name ?: resources.getString(R.string.people_unnamed) }
    if (labels.size < 2) return labels.firstOrNull().orEmpty()
    return resources.getString(R.string.people_together_title, labels.dropLast(1).joinToString(", "), labels.last())
}

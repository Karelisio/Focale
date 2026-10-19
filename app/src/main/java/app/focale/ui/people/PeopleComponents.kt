package app.focale.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.ml.LabelDictionary
import app.focale.people.FaceThumbnail
import app.focale.people.Person
import coil3.compose.AsyncImage

/** Vignette ronde recadrée sur un visage ; silhouette tant qu'elle se charge. */
@Composable
fun FaceAvatar(face: FaceThumbnail?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Face,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.fillMaxSize(0.5f),
        )
        if (face != null) {
            AsyncImage(
                model = face,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Nom de la personne, ou « Ajouter un nom » (mis en avant) si elle n'en a pas. */
@Composable
fun PersonName(person: Person, modifier: Modifier = Modifier, centered: Boolean = true) {
    Text(
        text = person.name ?: stringResource(R.string.people_add_name),
        style = MaterialTheme.typography.labelLarge,
        color = if (person.name == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** Libellé lisible d'une personne (dialogues, descriptions). */
@Composable
fun personLabel(person: Person): String = person.name ?: stringResource(R.string.people_unnamed)

/**
 * Personne dans une grille : vignette pleine largeur, nom et nombre de photos. Une
 * personne masquée (affichée sur demande) est atténuée.
 */
@Composable
fun PersonGridTile(person: Person, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick)
            .alpha(if (person.hidden) HIDDEN_ALPHA else 1f)
            .padding(6.dp),
    ) {
        FaceAvatar(person.cover, Modifier.fillMaxWidth().aspectRatio(1f))
        PersonName(person, Modifier.fillMaxWidth().padding(top = 8.dp))
        Text(
            text = if (person.hidden) {
                stringResource(R.string.people_hidden_badge)
            } else {
                pluralStringResource(R.plurals.people_photo_count, person.photoCount, person.photoCount)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Personnes reconnues sur une photo (feuille d'informations de la visionneuse). */
@Composable
fun PeopleChips(people: List<Person>, modifier: Modifier = Modifier) {
    if (people.isEmpty()) return
    Column(modifier) {
        Text(
            text = stringResource(R.string.people_title),
            style = MaterialTheme.typography.titleSmallEmphasized,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            people.forEach { person ->
                SuggestionChip(
                    onClick = {},
                    label = { Text(personLabel(person)) },
                    icon = { FaceAvatar(person.cover, Modifier.size(SuggestionChipDefaults.IconSize)) },
                    shape = MaterialTheme.shapes.large,
                )
            }
        }
    }
}

/** Rangée horizontale de personnes (onglets Albums et Recherche). */
@Composable
fun PeopleRow(
    people: List<Person>,
    onOpen: (Person) -> Unit,
    modifier: Modifier = Modifier,
    avatarSize: Dp = 76.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = contentPadding,
        modifier = modifier.fillMaxWidth(),
    ) {
        items(people, key = { it.id }) { person ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(avatarSize + 16.dp)
                    .clip(MaterialTheme.shapes.large)
                    .clickable { onOpen(person) }
                    .padding(vertical = 6.dp),
            ) {
                FaceAvatar(person.cover, Modifier.size(avatarSize))
                PersonName(person, Modifier.fillMaxWidth().padding(top = 8.dp, start = 2.dp, end = 2.dp))
            }
        }
    }
}

/**
 * Personnes nommées dont le nom, ou l'un de ses mots, commence par [key] (saisie
 * normalisée) : suggestions de la recherche.
 */
fun matchingPeople(people: List<Person>, key: String, limit: Int = 4): List<Person> {
    if (key.length < 2) return emptyList()
    return people.filter { person ->
        val name = person.name?.let(LabelDictionary::normalize) ?: return@filter false
        name.startsWith(key) || name.split(' ').any { it.startsWith(key) }
    }.take(limit)
}

/** Suggestions de personnes dans la liste de la recherche. */
fun LazyListScope.peopleSuggestions(people: List<Person>, onPick: (Person) -> Unit) {
    items(people, key = { "person-${it.id}" }) { person ->
        ListItem(
            onClick = { onPick(person) },
            leadingContent = { FaceAvatar(person.cover, Modifier.size(48.dp)) },
            supportingContent = { Text(pluralStringResource(R.plurals.people_photo_count, person.photoCount, person.photoCount)) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        ) { Text(person.name.orEmpty()) }
    }
}

/**
 * Section « Personnes » d'une grille pleine largeur : titre, « Tout afficher » et rangée
 * de vignettes. Rien n'est ajouté tant qu'aucune personne n'est reconnue.
 */
fun LazyGridScope.peopleSection(
    people: List<Person>,
    onOpen: (Person) -> Unit,
    onSeeAll: () -> Unit,
) {
    if (people.isEmpty()) return
    item(key = "people", span = { GridItemSpan(maxLineSpan) }) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp, start = 4.dp),
            ) {
                Text(
                    text = stringResource(R.string.people_title),
                    style = MaterialTheme.typography.headlineSmallEmphasized,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSeeAll) { Text(stringResource(R.string.people_see_all)) }
            }
            PeopleRow(people, onOpen, Modifier.padding(top = 4.dp))
        }
    }
}

private const val HIDDEN_ALPHA = 0.55f

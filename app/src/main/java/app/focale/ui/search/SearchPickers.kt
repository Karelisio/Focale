package app.focale.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditCalendar
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.data.PeriodChoices
import app.focale.data.PlaceSuggestion
import app.focale.data.SearchChip
import app.focale.ml.CountryResolver
import app.focale.people.Person
import app.focale.ui.people.FaceAvatar
import app.focale.ui.people.personLabel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** Choix d'une personne à ajouter aux filtres (celles déjà choisies n'y sont plus). */
@Composable
fun PersonPickerSheet(people: List<Person>, onPick: (Person) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetTitle(stringResource(R.string.search_filter_pick_person))
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.navigationBarsPadding()) {
            items(people, key = { it.id }) { person ->
                ListItem(
                    onClick = { onPick(person) },
                    leadingContent = { FaceAvatar(person.cover, Modifier.size(48.dp)) },
                    supportingContent = { Text(pluralStringResource(R.plurals.people_photo_count, person.photoCount, person.photoCount)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                ) { Text(personLabel(person)) }
            }
        }
    }
}

/** Choix d'un lieu de la bibliothèque, les plus photographiés d'abord. */
@Composable
fun PlacePickerSheet(places: List<PlaceSuggestion>, locale: Locale, onPick: (PlaceSuggestion) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetTitle(stringResource(R.string.search_filter_pick_place))
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.navigationBarsPadding()) {
            items(places, key = { "${it.name}-${it.countryCode}" }) { place ->
                ListItem(
                    onClick = { onPick(place) },
                    leadingContent = { SuggestionThumb(place.cover) },
                    supportingContent = {
                        Text(
                            CountryResolver.displayName(place.countryCode, locale) + " · " +
                                pluralStringResource(R.plurals.media_count, place.count, place.count),
                        )
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                ) { Text(place.names.display(locale)) }
            }
        }
    }
}

/**
 * Choix d'une période : années et mois récents où la recherche a des médias, ou des dates
 * quelconques ([onPickDates] : sélecteur de plage de dates).
 */
@Composable
fun PeriodPickerSheet(
    choices: PeriodChoices,
    onPick: (SearchChip.Period) -> Unit,
    onPickDates: () -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = LocalResources.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        SheetTitle(stringResource(R.string.search_filter_pick_period))
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
        ) {
            if (choices.years.isNotEmpty()) {
                SectionLabel(stringResource(R.string.search_filter_years))
                ChoiceRow(choices.years.map { SearchChip.Period.year(it) }, { it.firstDay?.year.toString() }, onPick)
            }
            if (choices.months.isNotEmpty()) {
                SectionLabel(stringResource(R.string.search_filter_recent_months))
                ChoiceRow(choices.months.map { SearchChip.Period.month(it) }, { SearchFilterLabels.period(resources, it) }, onPick)
            }
            FilledTonalButton(
                onClick = onPickDates,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.padding(top = 20.dp),
            ) {
                Icon(Icons.Outlined.EditCalendar, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text(stringResource(R.string.search_filter_pick_dates), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
        }
    }
}

/** Plage de dates choisie dans le calendrier M3 ; un seul jour si la fin manque. */
@Composable
fun DateRangeDialog(onConfirm: (SearchChip.Period) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDateRangePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            val start = state.selectedStartDateMillis
            TextButton(
                enabled = start != null,
                onClick = {
                    if (start != null) onConfirm(SearchChip.Period.days(utcDay(start), utcDay(state.selectedEndDateMillis ?: start)))
                },
            ) { Text(stringResource(R.string.search_filter_dates_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    ) {
        DateRangePicker(
            state = state,
            title = {
                Text(
                    stringResource(R.string.search_filter_dates_title),
                    modifier = Modifier.padding(start = 64.dp, end = 12.dp, top = 16.dp),
                )
            },
        )
    }
}

/** Le sélecteur de dates renvoie minuit UTC du jour choisi. */
private fun utcDay(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate()

@Composable
private fun ChoiceRow(periods: List<SearchChip.Period>, label: (SearchChip.Period) -> String, onPick: (SearchChip.Period) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        periods.forEach { period ->
            SuggestionChip(onClick = { onPick(period) }, label = { Text(label(period)) }, shape = MaterialTheme.shapes.large)
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLargeEmphasized,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

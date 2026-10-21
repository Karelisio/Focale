package app.focale.ui.search

import android.content.res.Resources
import android.text.format.DateFormat
import app.focale.R
import app.focale.data.SearchChip
import app.focale.data.SearchFilters
import app.focale.ml.CountryResolver
import app.focale.people.Person
import app.focale.ui.components.labelRes
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Libellés des pastilles et des recherches mémorisées, dans la langue de l'application. */
object SearchFilterLabels {

    /**
     * Libellé d'une pastille : nom de la personne (actuel si elle figure dans [people]), lieu dans
     * la langue de l'application, période ou type.
     */
    fun chip(resources: Resources, chip: SearchChip, people: Map<Long, Person>): String = when (chip) {
        is SearchChip.Person -> (if (chip.id in people) people.getValue(chip.id).name else chip.name)
            ?: resources.getString(R.string.people_unnamed)
        is SearchChip.Place -> chip.names.display(resources.locale)
        is SearchChip.Period -> period(resources, chip)
        is SearchChip.Type -> resources.getString(chip.filter.labelRes())
    }

    /** Description pour l'accessibilité : nature du filtre et valeur (« Filtre de lieu : Lyon, France »). */
    fun description(resources: Resources, chip: SearchChip, label: String): String = when (chip) {
        is SearchChip.Person -> resources.getString(R.string.search_filter_person_description, label)
        is SearchChip.Place -> resources.getString(
            R.string.search_filter_place_description,
            chip.country?.let { "$label, ${CountryResolver.displayName(it, resources.locale)}" } ?: label,
        )
        is SearchChip.Period -> resources.getString(R.string.search_filter_period_description, label)
        is SearchChip.Type -> resources.getString(R.string.search_filter_type_description, label)
    }

    /**
     * « 2023 », « Juillet 2022 », « 14 juil. 2021 », « 3 août – 12 août 2024 »,
     * « Juillet, toutes les années ».
     */
    fun period(resources: Resources, period: SearchChip.Period): String {
        val locale = resources.locale
        val first = period.firstDay
        val last = period.lastDay
        val days = if (first != null && last != null) {
            when {
                first == last -> format(first, "dMMMyyyy", locale)
                first.dayOfYear == 1 && last == first.withDayOfYear(first.lengthOfYear()) -> first.year.toString()
                first.dayOfMonth == 1 && last == YearMonth.from(first).atEndOfMonth() -> format(first, "MMMMyyyy", locale).capitalized(locale)
                first.year == last.year -> format(first, "dMMM", locale) + RANGE + format(last, "dMMMyyyy", locale)
                else -> format(first, "dMMMyyyy", locale) + RANGE + format(last, "dMMMyyyy", locale)
            }
        } else {
            null
        }
        val month = period.month?.takeIf { it in 1..12 }
            ?.let { Month.of(it).getDisplayName(TextStyle.FULL_STANDALONE, locale).capitalized(locale) }
        return when {
            days != null && month != null -> days + SEPARATOR + month
            days != null -> days
            month != null -> resources.getString(R.string.search_filter_every_year, month)
            else -> ""
        }
    }

    /** Recherche mémorisée, lisible : « plage · Alice · 2023 ». */
    fun entry(resources: Resources, filters: SearchFilters, people: Map<Long, Person>): String =
        (listOfNotNull(filters.text.trim().takeIf { it.isNotEmpty() }) + filters.chips.map { chip(resources, it, people) })
            .joinToString(SEPARATOR)

    /** Date d'un jour selon un squelette ICU (« dMMMyyyy »), sans fuseau horaire. */
    private fun format(day: LocalDate, skeleton: String, locale: Locale): String =
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(TimeUnit.DAYS.toMillis(day.toEpochDay())))

    private fun String.capitalized(locale: Locale): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

    private val Resources.locale: Locale get() = configuration.locales[0]

    private const val RANGE = " – "
    private const val SEPARATOR = " · "
}

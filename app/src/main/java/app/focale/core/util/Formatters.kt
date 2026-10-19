package app.focale.core.util

import android.content.Context
import android.text.format.DateFormat
import android.text.format.Formatter
import app.focale.R
import app.focale.core.model.GroupLevel
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Mise en forme localisée (FR/EN) des dates, tailles et durées. */
object Formatters {

    /** Libellé d'en-tête : « Aujourd'hui », « Lundi 14 juillet », « Juillet 2023 », « 2023 »… */
    fun periodLabel(
        context: Context,
        periodStart: Long,
        level: GroupLevel,
        today: LocalDate = LocalDate.now(),
        locale: Locale = currentLocale(context),
    ): String {
        val zone = ZoneId.systemDefault()
        val date = Instant.ofEpochMilli(periodStart).atZone(zone).toLocalDate()
        return when (level) {
            GroupLevel.DAY -> when (date) {
                today -> context.getString(R.string.date_today)
                today.minusDays(1) -> context.getString(R.string.date_yesterday)
                else -> {
                    val skeleton = if (date.year == today.year) "EEEEdMMMM" else "EEEdMMMMyyyy"
                    format(periodStart, skeleton, locale)
                }
            }
            GroupLevel.MONTH -> format(periodStart, "MMMMyyyy", locale)
            GroupLevel.YEAR -> date.year.toString()
        }.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
    }

    /** Date et heure complètes, ex. « 14 juil. 2023, 18:42 ». */
    fun dateTime(context: Context, epochMs: Long, locale: Locale = currentLocale(context)): String {
        val pattern = DateFormat.getBestDateTimePattern(
            locale,
            if (DateFormat.is24HourFormat(context)) "dMMMyyyyHHmm" else "dMMMyyyyhhmma",
        )
        return SimpleDateFormat(pattern, locale).format(Date(epochMs))
    }

    /** Date courte pour la bulle de défilement rapide, ex. « juil. 2023 ». */
    fun monthYear(context: Context, epochMs: Long, locale: Locale = currentLocale(context)): String =
        format(epochMs, "MMMyyyy", locale)

    fun fileSize(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

    /** Durée de vidéo : « 0:42 », « 12:05 », « 1:02:33 ». */
    fun duration(durationMs: Long): String {
        val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(durationMs.coerceAtLeast(0))
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    /** Résolution et nombre de mégapixels, ex. « 4000 × 3000 · 12 Mpx ». */
    fun resolution(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return "—"
        val megapixels = width.toLong() * height / 1_000_000.0
        return if (megapixels >= 0.95) {
            String.format(Locale.getDefault(), "%d × %d · %.1f Mpx", width, height, megapixels)
        } else {
            "$width × $height"
        }
    }

    private fun format(epochMs: Long, skeleton: String, locale: Locale): String =
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(epochMs))

    fun currentLocale(context: Context): Locale = context.resources.configuration.locales[0]
}

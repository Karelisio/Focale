package app.focale.core.util

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalContext
import android.content.Context

/** Texte à afficher, résolu dans la langue courante au moment de l'affichage. */
@Immutable
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText
    data class Raw(val value: String) : UiText

    /** [parts], séparés par [separator], insérés dans [id] (« Restaurés : 12 favoris · 3 albums »). */
    data class Joined(@StringRes val id: Int, val parts: List<UiText>, val separator: String = " · ") : UiText

    fun resolve(context: Context): String = when (this) {
        is Res -> context.getString(id, *args.toTypedArray())
        is Plural -> context.resources.getQuantityString(id, count, *args.toTypedArray())
        is Raw -> value
        is Joined -> context.getString(id, parts.joinToString(separator) { it.resolve(context) })
    }

    @Composable
    fun asString(): String = resolve(LocalContext.current)
}

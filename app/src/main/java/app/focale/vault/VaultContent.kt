package app.focale.vault

import app.focale.core.locale.AppLocales
import app.focale.core.settings.SettingsRepository
import app.focale.core.settings.VaultAlbumOrder
import app.focale.core.settings.VaultAlbumSort
import app.focale.core.settings.VaultItemOrder
import app.focale.core.settings.VaultSort
import java.text.Collator
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Contenu du coffre dans l'ordre choisi par l'utilisateur, mémorisé dans les réglages : le même
 * pour la grille de tout le coffre, celles des albums et la visionneuse, qui parcourt ainsi les
 * éléments dans l'ordre de la grille d'où l'on vient.
 */
@Singleton
class VaultContent @Inject constructor(
    private val repository: VaultRepository,
    private val settings: SettingsRepository,
    private val locales: AppLocales,
) {
    val itemSort: Flow<VaultSort> = settings.settings.map { it.vaultSort }.distinctUntilChanged()

    val albumSort: Flow<VaultAlbumSort> = settings.settings.map { it.vaultAlbumSort }.distinctUntilChanged()

    /** Tout le coffre, ou l'album [albumId]. */
    fun items(albumId: Long? = null): Flow<List<VaultItem>> =
        combine(repository.observeItems(albumId), itemSort) { items, sort -> items.sortedWith(sort.comparator(locales.locale)) }

    fun albums(): Flow<List<VaultAlbum>> =
        combine(repository.observeAlbums(), albumSort) { albums, sort -> albums.sortedWith(sort.comparator(locales.locale)) }

    suspend fun setItemSort(sort: VaultSort) = settings.setVaultSort(sort)

    suspend fun setAlbumSort(sort: VaultAlbumSort) = settings.setVaultAlbumSort(sort)
}

/**
 * Ordre des éléments ; à égalité, les plus récents d'abord. Par nom : ni casse ni accents,
 * dans l'ordre alphabétique de [locale] (langue de l'application).
 */
fun VaultSort.comparator(locale: Locale): Comparator<VaultItem> {
    val field: Comparator<VaultItem> = when (order) {
        VaultItemOrder.DATE_TAKEN -> compareBy { it.dateTaken }
        VaultItemOrder.DATE_ADDED -> compareBy { it.addedAt }
        VaultItemOrder.NAME -> compareBy(nameCollator(locale)) { it.displayName }
        VaultItemOrder.SIZE -> compareBy { it.size }
    }
    return (if (ascending) field else field.reversed())
        .thenByDescending { it.dateTaken }
        .thenByDescending { it.id }
}

/** Ordre des albums ; à égalité, par nom (dans l'ordre alphabétique de [locale]). */
fun VaultAlbumSort.comparator(locale: Locale): Comparator<VaultAlbum> {
    val collator = nameCollator(locale)
    val field: Comparator<VaultAlbum> = when (order) {
        VaultAlbumOrder.NAME -> compareBy(collator) { it.name }
        VaultAlbumOrder.DATE -> compareBy { it.createdAt }
        VaultAlbumOrder.COUNT -> compareBy { it.count }
    }
    return (if (ascending) field else field.reversed())
        .thenBy(collator) { it.name }
        .thenBy { it.id }
}

private fun nameCollator(locale: Locale): Collator = Collator.getInstance(locale).apply { strength = Collator.PRIMARY }

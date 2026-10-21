package app.focale.ui.search

import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import app.focale.R
import app.focale.core.model.MediaFilter
import app.focale.core.settings.AppSettings
import app.focale.data.SearchChip
import app.focale.people.Person
import app.focale.ui.theme.FocaleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Barre des pastilles : retrait d'un appui, rôle de bouton et descriptions, ajout par nature. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class SearchFilterBarTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val lea = Person(id = 1, name = "Léa", hidden = false, photoCount = 40, cover = null)
    private val removed = ArrayList<SearchChip>()
    private val added = ArrayList<SearchFilterKind>()
    private val types = ArrayList<MediaFilter>()
    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)

    private fun show(chips: List<SearchChip>) {
        compose.setContent {
            FocaleTheme(AppSettings(dynamicColor = false)) {
                SearchFilterBar(
                    chips = chips,
                    people = mapOf(lea.id to lea),
                    onRemove = { removed += it },
                    onAdd = { added += it },
                    onPickType = { types += it },
                )
            }
        }
    }

    @Test
    fun `une pastille est un bouton décrit, qu'un appui retire`() {
        val person = SearchChip.Person(1, "Lea (ancien nom)")
        val year = SearchChip.Period.year(2023)
        show(listOf(person, year))
        // Nom actuel de la personne, et non celui mémorisé.
        compose.onNodeWithContentDescription(context.getString(R.string.search_filter_person_description, "Léa"))
            .assert(isButton)
            .performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.search_filter_period_description, "2023"))
            .assert(isButton)
            .performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(person, year), removed)
    }

    @Test
    fun `une pastille de lieu porte aussi le pays dans sa description`() {
        show(listOf(SearchChip.Place("Lyon", "FR")))
        compose.onNodeWithContentDescription(context.getString(R.string.search_filter_place_description, "Lyon, France"))
            .assert(isButton)
    }

    @Test
    fun `les pastilles d'ajout ouvrent le choix et le type se choisit dans un menu`() {
        show(listOf(SearchChip.Place("Paris", "FR")))
        // Un lieu est déjà choisi : plus de pastille « Lieu », mais toujours une personne de plus.
        compose.onNodeWithContentDescription(context.getString(R.string.search_filter_add_place)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.search_filter_add_person))
            .assert(isButton)
            .performScrollTo()
            .performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.search_filter_add_period))
            .performScrollTo()
            .performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.search_filter_add_type))
            .performScrollTo()
            .performClick()
        compose.onNodeWithText(context.getString(R.string.filter_videos)).performClick()
        assertEquals(listOf(SearchFilterKind.PERSON, SearchFilterKind.PERIOD), added)
        assertEquals(listOf(MediaFilter.VIDEOS), types)
    }

    @Test
    fun `sans personne reconnue, pas de pastille pour en ajouter`() {
        compose.setContent {
            FocaleTheme(AppSettings(dynamicColor = false)) {
                SearchFilterBar(
                    chips = emptyList(),
                    people = emptyMap(),
                    onRemove = {},
                    onAdd = {},
                    onPickType = {},
                    kinds = setOf(SearchFilterKind.PERIOD, SearchFilterKind.TYPE),
                )
            }
        }
        compose.onNodeWithContentDescription(context.getString(R.string.search_filter_add_person)).assertDoesNotExist()
        compose.onNodeWithContentDescription(context.getString(R.string.search_filter_add_period)).assert(isButton)
    }
}

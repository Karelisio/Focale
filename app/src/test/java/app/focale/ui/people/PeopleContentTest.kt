package app.focale.ui.people

import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import app.focale.R
import app.focale.core.settings.AppSettings
import app.focale.core.settings.PeopleSort
import app.focale.people.Person
import app.focale.ui.theme.FocaleTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Écran Personnes : appui long (épingler), menu de tri et carte de suggestion. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class PeopleContentTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val lea = Person(id = 1, name = "Léa", hidden = false, photoCount = 40, cover = null, pinned = true)
    private val tom = Person(id = 2, name = "Tom", hidden = false, photoCount = 12, cover = null)
    private val unnamed = Person(id = 3, name = null, hidden = false, photoCount = 5, cover = null)

    private val pinned = ArrayList<Pair<Long, Boolean>>()
    private val sorts = ArrayList<PeopleSort>()
    private val merged = ArrayList<MergeProposal>()
    private val refused = ArrayList<MergeProposal>()

    private fun show(suggestion: MergeProposal? = null) {
        compose.setContent {
            FocaleTheme(AppSettings(dynamicColor = false)) {
                PeopleContent(
                    people = listOf(lea, tom, unnamed),
                    suggestion = suggestion,
                    sort = PeopleSort.PHOTOS,
                    showHidden = false,
                    analysisEnabled = true,
                    onBack = {},
                    onOpen = {},
                    onSortChange = { sorts += it },
                    onShowHiddenChange = {},
                    onPinnedChange = { person, value -> pinned += person.id to value },
                    onMerge = { merged += it },
                    onRefuse = { refused += it },
                    onEnableAnalysis = {},
                )
            }
        }
    }

    @Test
    fun `un appui long épingle ou désépingle la personne`() {
        show()
        compose.onNodeWithText("Tom").performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.people_pin)).performClick()
        compose.onNodeWithText("Léa").performTouchInput { longClick() }
        compose.onNodeWithText(context.getString(R.string.people_unpin)).performClick()
        assertEquals(listOf(2L to true, 1L to false), pinned)
    }

    @Test
    fun `le menu de tri transmet l'ordre choisi`() {
        show()
        compose.onNodeWithContentDescription(context.getString(R.string.people_sort)).performClick()
        compose.onNodeWithText(context.getString(R.string.people_sort_name)).performClick()
        assertEquals(listOf(PeopleSort.NAME), sorts)
    }

    @Test
    fun `la carte de suggestion fusionne ou distingue les deux personnes`() {
        val proposal = MergeProposal(target = lea, source = unnamed)
        show(proposal)
        compose.onNodeWithText(context.getString(R.string.people_suggestion_title)).assertExists()
        compose.onNodeWithText(context.getString(R.string.people_suggestion_different)).performClick()
        compose.onNodeWithText(context.getString(R.string.people_merge_action)).performClick()
        assertEquals(listOf(proposal), refused)
        assertEquals(listOf(proposal), merged)
    }
}

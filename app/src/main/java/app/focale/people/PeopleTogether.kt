package app.focale.people

import androidx.compose.runtime.Immutable
import app.focale.core.model.MediaQuery
import app.focale.core.model.SearchTerm

/** Personne vue sur des photos avec une autre, et le nombre de photos où elles figurent ensemble. */
@Immutable
data class PersonTogether(val person: Person, val photoCount: Int)

/**
 * Photos où figurent toutes les personnes [personIds] : un terme de recherche par personne,
 * combinés par ET, comme la recherche « alice bob » quand ces personnes sont nommées.
 */
fun photosTogether(personIds: Collection<Long>): MediaQuery =
    MediaQuery(terms = personIds.distinct().map { SearchTerm(personIds = listOf(it)) })

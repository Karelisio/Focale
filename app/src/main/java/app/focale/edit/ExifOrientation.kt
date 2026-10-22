package app.focale.edit

/**
 * Orientation EXIF (valeurs 1 à 8) vue comme la transformation qui affiche l'image stockée :
 * k quarts de tour horaires, puis miroir horizontal éventuel.
 *
 * | valeur | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 |
 * |---|---|---|---|---|---|---|---|---|
 * | quarts de tour | 0 | 0 | 2 | 2 | 1 | 1 | 3 | 3 |
 * | miroir | non | oui | non | oui | oui | non | oui | non |
 */
object ExifOrientation {
    const val NORMAL = 1

    private val TURNS = intArrayOf(0, 0, 2, 2, 1, 1, 3, 3)
    private val MIRRORED = booleanArrayOf(false, true, false, true, true, false, true, false)

    /** Quarts de tour horaires qui affichent l'image stockée (valeur invalide : aucun). */
    fun quarterTurns(orientation: Int): Int = if (orientation in 1..8) TURNS[orientation - 1] else 0

    /** Miroir horizontal appliqué après les quarts de tour (valeur invalide : aucun). */
    fun mirrored(orientation: Int): Boolean = orientation in 1..8 && MIRRORED[orientation - 1]

    /**
     * Orientation qui affiche l'image tournée de [quarterTurns] quarts de tour horaires
     * (négatif : antihoraires) par rapport à [orientation]. Avec miroir, tourner l'affichage
     * dans un sens revient à tourner l'image stockée dans l'autre. Une valeur absente ou
     * invalide compte comme « normale », comme pour les décodeurs.
     */
    fun rotate(orientation: Int, quarterTurns: Int): Int {
        val index = if (orientation in 1..8) orientation - 1 else NORMAL - 1
        val mirrored = MIRRORED[index]
        val turns = Math.floorMod(TURNS[index] + if (mirrored) -quarterTurns else quarterTurns, 4)
        return (1..8).first { TURNS[it - 1] == turns && MIRRORED[it - 1] == mirrored }
    }
}

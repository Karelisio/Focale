package app.focale.collage

import app.focale.R
import app.focale.core.util.UiText
import app.focale.edit.NormRect

/**
 * Disposition d'un collage : [cells] en fractions du collage (0 à 1), qui pavent exactement le
 * carré unité, sans chevauchement ni vide ; l'espacement est retiré ensuite
 * ([CollageGeometry.cells]). [name] la décrit (TalkBack, choix de la disposition).
 */
data class CollageLayout(val id: String, val name: UiText, val cells: List<NormRect>)

/**
 * Dispositions proposées pour 2 à 9 photos : grilles, rangées et colonnes, une grande photo et
 * des petites, mosaïques décalées, moulinet. La première de chaque liste est celle d'un nouveau
 * collage. Les cases sont dans l'ordre où les photos les remplissent : la première sélectionnée
 * prend la plus grande case.
 */
object CollageLayouts {
    const val MIN_PHOTOS = 2
    const val MAX_PHOTOS = 9

    private val all: Map<Int, List<CollageLayout>> = buildMap {
        put(
            2,
            listOf(
                layout("2-side", UiText.Res(R.string.collage_layout_side_by_side), row(cell(), cell())),
                layout("2-stack", UiText.Res(R.string.collage_layout_stacked), column(cell(), cell())),
                layout("2-large-left", UiText.Res(R.string.collage_layout_large_left), row(cell(2f), cell())),
                layout("2-large-top", UiText.Res(R.string.collage_layout_large_top), column(cell(2f), cell())),
            ),
        )
        put(
            3,
            listOf(
                layout("3-large-left", largeLeft(2), row(cell(), column(cell(), cell()))),
                layout("3-large-top", largeTop(2), column(cell(), row(cell(), cell()))),
                layout("3-columns", columns(3), row(cell(), cell(), cell())),
                layout("3-rows", rows(3), column(cell(), cell(), cell())),
                layout("3-large-right", largeRight(2), row(column(cell(), cell()), cell())),
                layout("3-large-bottom", largeBottom(2), column(row(cell(), cell()), cell())),
            ),
        )
        put(
            4,
            listOf(
                layout("4-grid", grid(2, 2), column(row(cell(), cell()), row(cell(), cell()))),
                layout("4-large-top", largeTop(3), column(cell(2f), row(cell(), cell(), cell()))),
                layout("4-large-left", largeLeft(3), row(cell(2f), column(cell(), cell(), cell()))),
                layout("4-staggered", staggered(), row(column(cell(3f), cell(2f)), column(cell(2f), cell(3f)))),
                layout("4-columns", columns(4), row(cell(), cell(), cell(), cell())),
            ),
        )
        put(
            5,
            listOf(
                layout("5-2-3", topBottom(2, 3), column(row(cell(), cell()), row(cell(), cell(), cell()))),
                layout("5-pinwheel", UiText.Res(R.string.collage_layout_pinwheel), pinwheel()),
                layout("5-large-left", largeLeft(4), row(cell(), column(row(cell(), cell()), row(cell(), cell())))),
                layout("5-large-top", largeTop(4), column(cell(1.5f), row(cell(), cell(), cell(), cell()))),
                layout("5-3-2", topBottom(3, 2), column(row(cell(), cell(), cell()), row(cell(), cell()))),
            ),
        )
        put(
            6,
            listOf(
                layout("6-grid-2x3", grid(2, 3), column(row(cell(), cell()), row(cell(), cell()), row(cell(), cell()))),
                layout("6-grid-3x2", grid(3, 2), column(row(cell(), cell(), cell()), row(cell(), cell(), cell()))),
                layout(
                    "6-large",
                    UiText.Plural(R.plurals.collage_layout_large_and_small, 5),
                    column(row(cell(2f), column(cell(), cell()), weight = 2f), row(cell(), cell(), cell())),
                ),
                layout(
                    "6-staggered",
                    staggered(),
                    row(column(cell(3f), cell(2f)), column(cell(2f), cell(3f)), column(cell(3f), cell(2f))),
                ),
            ),
        )
        put(
            7,
            listOf(
                layout("7-2-3-2", threeRows(2, 3, 2), column(row(cell(), cell()), row(cell(), cell(), cell()), row(cell(), cell()))),
                layout("7-large-top", largeTop(6), column(cell(2f), row(cell(), cell(), cell()), row(cell(), cell(), cell()))),
                layout("7-3-1-3", threeRows(3, 1, 3), column(row(cell(), cell(), cell()), cell(1.4f), row(cell(), cell(), cell()))),
                layout(
                    "7-large-left",
                    largeLeft(6),
                    row(cell(), column(row(cell(), cell()), row(cell(), cell()), row(cell(), cell()))),
                ),
                layout(
                    "7-staggered",
                    staggered(),
                    row(column(cell(3f), cell(2f)), column(cell(), cell(), cell()), column(cell(2f), cell(3f))),
                ),
            ),
        )
        put(
            8,
            listOf(
                layout("8-3-2-3", threeRows(3, 2, 3), column(row(cell(), cell(), cell()), row(cell(), cell()), row(cell(), cell(), cell()))),
                layout(
                    "8-grid-2x4",
                    grid(2, 4),
                    column(row(cell(), cell()), row(cell(), cell()), row(cell(), cell()), row(cell(), cell())),
                ),
                layout(
                    "8-grid-4x2",
                    grid(4, 2),
                    column(row(cell(), cell(), cell(), cell()), row(cell(), cell(), cell(), cell())),
                ),
                layout(
                    "8-columns-3-2-3",
                    threeColumns(3, 2, 3),
                    row(column(cell(), cell(), cell()), column(cell(), cell()), column(cell(), cell(), cell())),
                ),
                layout(
                    "8-2-3-3",
                    threeRows(2, 3, 3),
                    column(row(cell(), cell(), weight = 1.5f), row(cell(), cell(), cell()), row(cell(), cell(), cell())),
                ),
            ),
        )
        put(
            9,
            listOf(
                layout(
                    "9-grid",
                    grid(3, 3),
                    column(row(cell(), cell(), cell()), row(cell(), cell(), cell()), row(cell(), cell(), cell())),
                ),
                layout(
                    "9-large-center",
                    UiText.Res(R.string.collage_layout_large_center),
                    column(row(cell(), cell(), cell()), row(cell(), cell(2f), cell(), weight = 2f), row(cell(), cell(), cell())),
                ),
                layout(
                    "9-large-top",
                    largeTop(8),
                    column(cell(2f), row(cell(), cell(), cell(), cell()), row(cell(), cell(), cell(), cell())),
                ),
                layout(
                    "9-2-3-4",
                    threeRows(2, 3, 4),
                    column(row(cell(), cell()), row(cell(), cell(), cell()), row(cell(), cell(), cell(), cell())),
                ),
                layout(
                    "9-staggered",
                    staggered(),
                    row(
                        column(cell(3f), cell(2f), cell(2f)),
                        column(cell(2f), cell(3f), cell(2f)),
                        column(cell(2f), cell(2f), cell(3f)),
                    ),
                ),
            ),
        )
    }

    private val byId: Map<String, CollageLayout> = all.values.flatten().associateBy { it.id }

    /** Dispositions pour [count] photos (2 à 9). */
    fun forCount(count: Int): List<CollageLayout> = all[count] ?: error("Collage de $count photos")

    fun byId(id: String): CollageLayout? = byId[id]

    // ── Noms ──────────────────────────────────────────────────────────────────────

    private fun grid(columns: Int, rows: Int) = UiText.Res(R.string.collage_layout_grid, listOf(columns, rows))
    private fun columns(count: Int) = UiText.Plural(R.plurals.collage_layout_columns, count)
    private fun rows(count: Int) = UiText.Plural(R.plurals.collage_layout_rows, count)
    private fun largeLeft(others: Int) = UiText.Plural(R.plurals.collage_layout_large_left_and, others)
    private fun largeRight(others: Int) = UiText.Plural(R.plurals.collage_layout_large_right_and, others)
    private fun largeTop(others: Int) = UiText.Plural(R.plurals.collage_layout_large_top_and, others)
    private fun largeBottom(others: Int) = UiText.Plural(R.plurals.collage_layout_large_bottom_and, others)
    private fun topBottom(top: Int, bottom: Int) = UiText.Res(R.string.collage_layout_top_bottom, listOf(top, bottom))
    private fun threeRows(a: Int, b: Int, c: Int) = UiText.Res(R.string.collage_layout_three_rows, listOf(a, b, c))
    private fun threeColumns(a: Int, b: Int, c: Int) = UiText.Res(R.string.collage_layout_three_columns, listOf(a, b, c))
    private fun staggered() = UiText.Res(R.string.collage_layout_staggered)

    // ── Construction ──────────────────────────────────────────────────────────────

    /** Case, ou découpe du rectangle parent en morceaux proportionnels à leur poids. */
    private sealed interface Node {
        val weight: Float
    }

    private class Leaf(override val weight: Float) : Node

    private class Split(val across: Boolean, val children: List<Node>, override val weight: Float) : Node

    private fun cell(weight: Float = 1f): Node = Leaf(weight)

    /** Côte à côte, de gauche à droite. */
    private fun row(vararg children: Node, weight: Float = 1f): Node = Split(across = true, children.toList(), weight)

    /** Les unes sous les autres, de haut en bas. */
    private fun column(vararg children: Node, weight: Float = 1f): Node = Split(across = false, children.toList(), weight)

    private fun layout(id: String, name: UiText, root: Node): CollageLayout =
        CollageLayout(id, name, ArrayList<NormRect>().also { place(root, NormRect.Full, it) })

    private fun layout(id: String, name: UiText, cells: List<NormRect>): CollageLayout = CollageLayout(id, name, cells)

    /**
     * Cases de [node] dans [rect], dans l'ordre de lecture de l'arbre. Deux morceaux voisins
     * partagent la même valeur de bord (fin de l'un, début de l'autre) et le dernier finit
     * exactement sur le bord du parent : aucune erreur d'arrondi ne laisse de vide entre eux.
     */
    private fun place(node: Node, rect: NormRect, out: MutableList<NormRect>) {
        when (node) {
            is Leaf -> out += rect
            is Split -> {
                val total = node.children.sumOf { it.weight.toDouble() }
                var cumulative = 0.0
                val start = if (node.across) rect.left else rect.top
                val length = if (node.across) rect.width else rect.height
                var from = start
                node.children.forEachIndexed { index, child ->
                    cumulative += child.weight
                    val to = if (index == node.children.lastIndex) {
                        if (node.across) rect.right else rect.bottom
                    } else {
                        (start + length * (cumulative / total)).toFloat()
                    }
                    val part = if (node.across) rect.copy(left = from, right = to) else rect.copy(top = from, bottom = to)
                    place(child, part, out)
                    from = to
                }
            }
        }
    }

    /**
     * Moulinet : une case carrée au centre, entourée de quatre cases en long qui tournent autour
     * d'elle (aucune découpe droite d'un bord à l'autre ne le produit). Le centre d'abord, puis
     * dans le sens des aiguilles d'une montre depuis le haut.
     */
    private fun pinwheel(): List<NormRect> {
        val near = PINWHEEL_BAND
        val far = 1f - PINWHEEL_BAND
        return listOf(
            NormRect(near, near, far, far),
            NormRect(0f, 0f, far, near),
            NormRect(far, 0f, 1f, far),
            NormRect(near, far, 1f, 1f),
            NormRect(0f, near, near, 1f),
        )
    }

    /** Épaisseur des cases du pourtour du moulinet. */
    private const val PINWHEEL_BAND = 0.3f
}

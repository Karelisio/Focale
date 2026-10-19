package app.focale.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.icu.text.CompactDecimalFormat
import android.util.LruCache
import android.util.TypedValue
import android.view.MotionEvent
import app.focale.core.database.GeoPhoto
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.image.MediaThumbnail
import app.focale.core.mediastore.MediaStoreSource
import app.focale.map.Basemap
import app.focale.map.BasemapKind
import app.focale.map.CountryLabel
import app.focale.map.PhotoCluster
import app.focale.map.PhotoClustering
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.toBitmap
import kotlin.math.floor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.TileSystem
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.PolyOverlayWithIW
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline

/**
 * Fond de carte hors ligne : terres, lacs et frontières, en version simplifiée aux
 * petits niveaux de zoom et détaillée au-delà de [DETAIL_ZOOM]. Les couleurs suivent
 * le thème (Material You).
 */
class BasemapOverlay(basemap: Basemap, density: Float) : Overlay() {

    private val simple = ArrayList<PolyOverlayWithIW>()
    private val detailed = ArrayList<PolyOverlayWithIW>()
    private val lands = ArrayList<Polygon>()
    private val lakes = ArrayList<Polygon>()
    private val borders = ArrayList<Polyline>()

    init {
        for (layer in basemap.layers) {
            val target = if (layer.detailed) detailed else simple
            for (ring in layer.rings) {
                val points = List(ring.size / 2) { GeoPoint(ring[2 * it], ring[2 * it + 1]) }
                target += when (layer.kind) {
                    BasemapKind.LAND -> Polygon().also { it.setPoints(points); lands += it }
                    BasemapKind.LAKE -> Polygon().also { it.setPoints(points); lakes += it }
                    BasemapKind.BORDER -> Polyline().also {
                        it.setPoints(points)
                        it.outlinePaint.strokeWidth = BORDER_WIDTH_DP * density
                        it.outlinePaint.strokeCap = Paint.Cap.ROUND
                        borders += it
                    }
                }
            }
        }
    }

    fun setColors(land: Int, water: Int, border: Int) {
        // Le contour prend la couleur du remplissage : pas de liseré entre polygones voisins.
        lands.forEach { it.fill(land) }
        lakes.forEach { it.fill(water) }
        borders.forEach { it.outlinePaint.color = border }
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        val layers = if (projection.zoomLevel < DETAIL_ZOOM) simple else detailed
        for (overlay in layers) overlay.draw(canvas, projection)
    }

    private fun Polygon.fill(color: Int) {
        fillPaint.color = color
        outlinePaint.color = color
        outlinePaint.strokeWidth = 1f
    }

    private companion object {
        const val DETAIL_ZOOM = 3.0
        const val BORDER_WIDTH_DP = 0.8f
    }
}

/** Noms des pays, sans chevauchement : les plus importants sont placés en premier. */
class CountryLabelOverlay(labels: List<CountryLabel>, french: Boolean, context: Context) : Overlay() {

    private class Entry(val point: GeoPoint, val minZoom: Int, val name: String)

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = context.sp(TEXT_SP)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        letterSpacing = 0.04f
    }
    private val halo = Paint(text).apply {
        style = Paint.Style.STROKE
        strokeWidth = HALO_DP * context.resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
    }
    private val entries = labels
        .sortedBy { it.minZoom }
        .map { Entry(GeoPoint(it.latitude, it.longitude), it.minZoom, if (french) it.nameFr else it.nameEn) }
    private val widths = FloatArray(entries.size) { text.measureText(entries[it].name) }
    private val placed = ArrayList<RectF>()
    private val position = android.graphics.Point()

    fun setColors(label: Int, background: Int) {
        text.color = label
        halo.color = background
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        val zoom = projection.zoomLevel
        val height = text.textSize
        placed.clear()
        for ((index, entry) in entries.withIndex()) {
            if (entry.minZoom > zoom + 0.5) break
            projection.toPixels(entry.point, position)
            val x = position.x.toFloat()
            val y = position.y.toFloat()
            if (x < 0 || y < 0 || x > canvas.width || y > canvas.height) continue
            val half = widths[index] / 2 + height / 2
            val box = RectF(x - half, y - height, x + half, y + height / 2)
            if (placed.any { RectF.intersects(it, box) }) continue
            placed += box
            canvas.drawText(entry.name, x, y, halo)
            canvas.drawText(entry.name, x, y, text)
        }
    }

    private companion object {
        const val TEXT_SP = 11f
        const val HALO_DP = 3f
    }
}

/**
 * Photos regroupées par proximité, dessinées en vignettes rondes avec leur nombre.
 * Le regroupement est recalculé hors du thread principal à chaque niveau de zoom
 * entier ; en attendant, le niveau précédent reste affiché.
 */
class PhotoClusterOverlay(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onClusterTap: (PhotoCluster) -> Unit,
) : Overlay() {

    private class Thumb(bitmap: Bitmap) {
        val width = bitmap.width.toFloat()
        val height = bitmap.height.toFloat()
        val shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    private class Hit(val cluster: PhotoCluster, val x: Float, val y: Float, val radius: Float)

    private val density = context.resources.displayMetrics.density
    private val radius = RADIUS_DP * density
    private val ringWidth = RING_DP * density
    private var mapView: MapView? = null

    var photos: List<GeoPhoto> = emptyList()
        set(value) {
            field = value
            levels.clear()
            requestedLevel = -1
            mapView?.invalidate()
        }

    private val levels = HashMap<Int, List<PhotoCluster>>()
    private var shownLevel = -1
    private var requestedLevel = -1
    private val thumbnails = LruCache<Long, Thumb>(MAX_THUMBNAILS)
    private val loading = HashSet<Long>()
    private val hits = ArrayList<Hit>()
    private val geo = GeoPoint(0.0, 0.0)
    private val position = android.graphics.Point()
    private val matrix = Matrix()

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(SHADOW_DP * density, 0f, density, 0x40000000)
    }
    private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG)
    private val photo = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val badge = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = context.sp(BADGE_TEXT_SP)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun attach(view: MapView) {
        mapView = view
    }

    fun setColors(ringColor: Int, placeholderColor: Int, badgeColor: Int, badgeTextColor: Int) {
        ring.color = ringColor
        placeholder.color = placeholderColor
        badge.color = badgeColor
        badgeText.color = badgeTextColor
    }

    override fun draw(canvas: Canvas, projection: Projection) {
        hits.clear()
        val level = floor(projection.zoomLevel).toInt()
        val clusters = levels[level] ?: run {
            request(level)
            levels[shownLevel] ?: return
        }
        if (levels.containsKey(level)) shownLevel = level
        for (cluster in clusters) {
            geo.setCoords(cluster.latitude, cluster.longitude)
            projection.toPixels(geo, position)
            val x = position.x.toFloat()
            val y = position.y.toFloat()
            val r = if (cluster.count > 1) radius * GROUP_SCALE else radius
            if (x < -r || y < -r || x > canvas.width + r || y > canvas.height + r) continue
            drawCluster(canvas, cluster, x, y, r)
            hits += Hit(cluster, x, y, r + ringWidth)
        }
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val hit = hits.lastOrNull { hit ->
            val dx = hit.x - e.x
            val dy = hit.y - e.y
            dx * dx + dy * dy <= hit.radius * hit.radius
        } ?: return false
        onClusterTap(hit.cluster)
        return true
    }

    override fun onDetach(mapView: MapView) {
        this.mapView = null
        thumbnails.evictAll()
    }

    private fun request(level: Int) {
        if (requestedLevel == level) return
        requestedLevel = level
        val snapshot = photos
        val worldSize = TileSystem.MapSize(level.toDouble())
        val cell = CELL_DP * density.toDouble()
        scope.launch {
            val result = withContext(Dispatchers.Default) { PhotoClustering.cluster(snapshot, worldSize, cell) }
            if (snapshot === photos) {
                levels[level] = result
                mapView?.invalidate()
            }
        }
    }

    private fun drawCluster(canvas: Canvas, cluster: PhotoCluster, x: Float, y: Float, r: Float) {
        canvas.drawCircle(x, y, r + ringWidth, ring)
        val thumb = thumbnails.get(cluster.cover.mediaId)
        if (thumb == null) {
            canvas.drawCircle(x, y, r, placeholder)
            load(cluster.cover)
        } else {
            // Recadrage centré de la vignette dans le disque.
            val scale = 2 * r / minOf(thumb.width, thumb.height)
            matrix.setScale(scale, scale)
            matrix.postTranslate(x - thumb.width * scale / 2, y - thumb.height * scale / 2)
            thumb.shader.setLocalMatrix(matrix)
            photo.shader = thumb.shader
            canvas.drawCircle(x, y, r, photo)
        }
        if (cluster.count > 1) {
            val label = formatCount(cluster.count)
            val height = badgeText.textSize * 1.6f
            val width = maxOf(height, badgeText.measureText(label) + height * 0.7f)
            val left = x + r * 0.35f
            val top = y - r - ringWidth
            val box = RectF(left, top, left + width, top + height)
            canvas.drawRoundRect(box, height / 2, height / 2, badge)
            canvas.drawText(label, box.centerX(), box.centerY() - (badgeText.descent() + badgeText.ascent()) / 2, badgeText)
        }
    }

    private fun load(cover: GeoPhoto) {
        val id = cover.mediaId
        if (!loading.add(id)) return
        val isVideo = cover.mediaType == MediaQueryBuilder.MEDIA_TYPE_VIDEO
        val request = ImageRequest.Builder(context)
            .data(MediaThumbnail(id, MediaStoreSource.contentUri(id, isVideo), cover.dateModified))
            .size(THUMBNAIL_PX)
            .target(
                onSuccess = { image ->
                    loading.remove(id)
                    // Le shader exige une image logicielle (les miniatures peuvent être matérielles).
                    val bitmap = image.toBitmap().let { if (it.config == Bitmap.Config.HARDWARE) it.copy(Bitmap.Config.ARGB_8888, false) else it }
                    thumbnails.put(id, Thumb(bitmap))
                    mapView?.invalidate()
                },
                onError = { loading.remove(id) },
            )
            .build()
        SingletonImageLoader.get(context).enqueue(request)
    }

    /** « 1,2 k » / « 1.2K » selon la langue. */
    private val compact = CompactDecimalFormat.getInstance(
        context.resources.configuration.locales[0],
        CompactDecimalFormat.CompactStyle.SHORT,
    )

    private fun formatCount(count: Int): String = compact.format(count)

    private companion object {
        const val RADIUS_DP = 24f
        const val RING_DP = 3f
        const val SHADOW_DP = 3f
        const val GROUP_SCALE = 1.15f
        const val BADGE_TEXT_SP = 11f
        const val CELL_DP = 72f
        const val THUMBNAIL_PX = 192
        const val MAX_THUMBNAILS = 300
    }
}

/** Taille de texte en sp convertie en pixels (mise à l'échelle non linéaire comprise). */
private fun Context.sp(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

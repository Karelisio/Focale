package app.focale.print

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.util.Log
import app.focale.core.model.MediaItem
import java.io.FileOutputStream
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Document d'impression des [photos], une par page ([PrintLayout]) : PDF écrit par le
 * framework d'impression d'Android, qui le transmet au service choisi (imprimante, PDF…).
 *
 * Chaque photo est décodée à la résolution utile de l'imprimante, orientation EXIF appliquée
 * ([ImageDecoder]), puis libérée avant la suivante ; en noir et blanc, elle est désaturée. Le
 * travail se fait hors du fil principal et s'arrête dès que l'impression est annulée.
 */
internal class PhotoPrintAdapter(
    private val resolver: ContentResolver,
    private val photos: List<MediaItem>,
    private val documentName: String,
) : PrintDocumentAdapter() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var attributes: PrintAttributes? = null
    private var writing: Job? = null

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        attributes = newAttributes
        callback.onLayoutFinished(documentInfo(documentName, photos.size), newAttributes != oldAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        val current = attributes ?: run {
            callback.onWriteFailed(null)
            return
        }
        val requested = PrintPages.indices(pages.map { it.start..it.end }, photos.size)
        writing = scope.launch {
            try {
                withContext(Dispatchers.IO) { write(requested, current, destination) }
                callback.onWriteFinished(PrintPages.ranges(requested).map { PageRange(it.first, it.last) }.toTypedArray())
            } catch (cancelled: CancellationException) {
                callback.onWriteCancelled()
            } catch (error: Exception) {
                Log.w(TAG, "Impression impossible", error)
                callback.onWriteFailed(error.message)
            } catch (error: OutOfMemoryError) {
                Log.w(TAG, "Mémoire insuffisante pour l'impression", error)
                callback.onWriteFailed(error.message)
            }
        }
        cancellationSignal?.setOnCancelListener { writing?.cancel() }
    }

    override fun onFinish() {
        scope.cancel()
    }

    private suspend fun write(indices: List<Int>, attributes: PrintAttributes, destination: ParcelFileDescriptor) {
        val media = attributes.mediaSize ?: PrintAttributes.MediaSize.ISO_A4
        val minMargins = attributes.minMargins
        val margin = if (minMargins == null) 0 else maxOf(minMargins.leftMils, minMargins.topMils, minMargins.rightMils, minMargins.bottomMils)
        val dpi = attributes.resolution?.let { min(it.horizontalDpi, it.verticalDpi) } ?: 0
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
            if (attributes.colorMode == PrintAttributes.COLOR_MODE_MONOCHROME) {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }
        }
        val document = PdfDocument()
        try {
            for (index in indices) {
                currentCoroutineContext().ensureActive()
                val photo = photos[index]
                var layout = PrintLayout.page(photo.displayWidth, photo.displayHeight, media.widthMils, media.heightMils, margin, dpi)
                val bitmap = decode(photo) { width, height ->
                    // Dimensions lues dans le fichier : elles font foi si l'index les ignorait.
                    PrintLayout.page(width, height, media.widthMils, media.heightMils, margin, dpi).also { layout = it }
                }
                val page = document.startPage(PdfDocument.PageInfo.Builder(layout.width, layout.height, index).create())
                bitmap?.let {
                    val frame = layout.photo
                    page.canvas.drawBitmap(it, null, RectF(frame.left, frame.top, frame.right, frame.bottom), paint)
                    it.recycle()
                }
                document.finishPage(page)
            }
            currentCoroutineContext().ensureActive()
            FileOutputStream(destination.fileDescriptor).use(document::writeTo)
        } finally {
            document.close()
        }
    }

    /** Photo décodée à la taille que donne [layout] pour ses dimensions affichées ; null si elle est illisible (page vide). */
    private fun decode(photo: MediaItem, layout: (width: Int, height: Int) -> PrintPage): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, photo.uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val page = layout(info.size.width, info.size.height)
            if (page.decodeWidth < info.size.width || page.decodeHeight < info.size.height) {
                decoder.setTargetSize(page.decodeWidth, page.decodeHeight)
            }
        }
    } catch (error: Exception) {
        Log.w(TAG, "Photo illisible : ${photo.displayName}", error)
        null
    }

    companion object {
        private const val TAG = "PhotoPrintAdapter"

        /** Description du document : photos, une page chacune. */
        fun documentInfo(name: String, pages: Int): PrintDocumentInfo =
            PrintDocumentInfo.Builder(name)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_PHOTO)
                .setPageCount(pages)
                .build()
    }
}

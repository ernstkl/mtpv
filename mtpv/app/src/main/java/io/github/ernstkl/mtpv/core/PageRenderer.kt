package io.github.ernstkl.mtpv.core

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File
import kotlin.math.min

/** Renders single pages of one PDF. PdfRenderer allows only one open page, hence the locking. */
class PageRenderer(file: File) : AutoCloseable {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private var closed = false

    val pageCount: Int = renderer.pageCount

    /**
     * Renders 1-based [page] to fit inside [maxW] x [maxH], or returns null for a blank screen.
     * [dark] swaps light and dark ([DarkPages]).
     */
    @Synchronized
    fun render(page: Int, maxW: Int, maxH: Int, dark: Boolean = false): Bitmap? {
        if (closed || page !in 1..pageCount || maxW <= 0 || maxH <= 0) return null
        renderer.openPage(page - 1).use { p ->
            val scale = min(maxW / p.width.toFloat(), maxH / p.height.toFloat())
            val w = (p.width * scale).toInt().coerceAtLeast(1)
            val h = (p.height * scale).toInt().coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            if (dark) {
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                DarkPages.invert(px)
                bmp.setPixels(px, 0, w, 0, 0, w, h)
            }
            return bmp
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        renderer.close()
        pfd.close()
    }
}

/** Keeps the renderer for the most recently used file open. */
class RendererHolder(private val cache: PdfCache) {
    private var id: String? = null
    private var renderer: PageRenderer? = null

    @Synchronized
    fun get(fileId: String): PageRenderer? {
        // A file missing a moment ago may have arrived since (sent by the controller), so only a renderer is kept.
        if (fileId == id && renderer != null) return renderer
        renderer?.close()
        id = fileId
        renderer = if (cache.has(fileId)) runCatching { PageRenderer(cache.file(fileId)) }.getOrNull() else null
        return renderer
    }

    /** Synchronized so that a [get] for another file cannot close the renderer between lookup and render. */
    @Synchronized
    fun render(fileId: String?, page: Int, maxW: Int, maxH: Int, dark: Boolean): Bitmap? =
        fileId?.let { get(it) }?.render(page, maxW, maxH, dark)
}

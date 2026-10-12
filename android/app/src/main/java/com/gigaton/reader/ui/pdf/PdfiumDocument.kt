package com.gigaton.reader.ui.pdf

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import com.shockwave.pdfium.PdfiumCore
import com.shockwave.pdfium.PdfDocument as PdfiumLibraryDocument
import java.io.File

/**
 * Bundled PDFium backend.
 *
 * Renders through the app's own copy of the PDFium engine (Apache-2.0,
 * `com.github.barteksc:pdfium-android` 1.9.0), completely bypassing the
 * platform's `android.graphics.pdf.PdfRenderer`, which some devices ship in a
 * state that fails to render real-world PDFs. Offline reading still works
 * because a downloaded paper renders locally from its file.
 */
class PdfiumDocument private constructor(
    private val pdfium: PdfiumCore,
    private val document: PdfiumLibraryDocument,
    private val fd: ParcelFileDescriptor,
) : ReaderDocument {

    private val lock = Any()
    @Volatile
    private var closed = false

    override val pageCount: Int
        get() = pdfium.getPageCount(document)

    companion object {
        /** Heavy, can throw — call from a background dispatcher. */
        fun open(context: Context, file: File): PdfiumDocument {
            val pdfium = PdfiumCore(context)
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val document = try {
                pdfium.newDocument(fd, null)
            } catch (t: Throwable) {
                runCatching { fd.close() }
                throw t
            }
            return PdfiumDocument(pdfium, document, fd)
        }
    }

    override fun ratio(index: Int): Float = synchronized(lock) {
        if (closed) return@synchronized 0.75f
        runCatching {
            val w = pdfium.getPageWidth(document, index)
            val h = pdfium.getPageHeight(document, index)
            if (h == 0) 0.75f else w.toFloat() / h.toFloat()
        }.getOrDefault(0.75f)
    }

    override fun render(index: Int, widthPx: Int, backgroundArgb: Int): RenderResult =
        synchronized(lock) {
            if (closed) {
                return@synchronized RenderResult(null, IllegalStateException("Renderer is closed"))
            }
            if (widthPx <= 0) {
                return@synchronized RenderResult(null, IllegalStateException("Invalid render width"))
            }

            val widths = listOf(widthPx, (widthPx / 2).coerceAtLeast(1), (widthPx / 4).coerceAtLeast(1))
            val configs = listOf(Bitmap.Config.RGB_565, Bitmap.Config.ARGB_8888)

            var lastError: Throwable? = null
            for (w in widths) {
                for (config in configs) {
                    try {
                        val pageW = pdfium.getPageWidth(document, index)
                        val pageH = pdfium.getPageHeight(document, index)
                        if (pageW == 0) continue
                        val height = (w.toLong() * pageH / pageW).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(w, height, config)
                        bitmap.eraseColor(backgroundArgb)
                        pdfium.renderPageBitmap(
                            document, bitmap, index, 0, 0, w, height, false,
                        )
                        return@synchronized RenderResult(bitmap, null)
                    } catch (t: Throwable) {
                        lastError = t
                    }
                }
            }
            RenderResult(null, lastError ?: IllegalStateException("PDF page could not be rendered"))
        }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { pdfium.closeDocument(document) }
            runCatching { fd.close() }
        }
    }
}
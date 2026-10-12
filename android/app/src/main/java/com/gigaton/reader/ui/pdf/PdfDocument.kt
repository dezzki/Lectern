package com.gigaton.reader.ui.pdf

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Platform renderer backend using android.graphics.pdf.PdfRenderer.
 * Rendering is crash-proof and reports failures via RenderResult.error.
 */
class PdfDocument private constructor(
    private val renderer: PdfRenderer,
    private val fd: ParcelFileDescriptor,
) : ReaderDocument {

    override val pageCount: Int = renderer.pageCount

    @Volatile
    private var closed = false

    private val renderLock = Any()

    companion object {
        /** Heavy, can throw — call from a background dispatcher. */
        fun open(file: File): PdfDocument {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            return try {
                PdfDocument(PdfRenderer(fd), fd)
            } catch (t: Throwable) {
                runCatching { fd.close() }
                throw t
            }
        }
    }

    override fun ratio(index: Int): Float = synchronized(renderLock) {
        if (closed) return@synchronized 0.75f
        runCatching {
            renderer.openPage(index).use { page ->
                if (page.height == 0) 0.75f else page.width.toFloat() / page.height.toFloat()
            }
        }.getOrDefault(0.75f)
    }

    override fun render(index: Int, widthPx: Int, backgroundArgb: Int): RenderResult =
        synchronized(renderLock) {
            if (closed) {
                return@synchronized RenderResult(null, IllegalStateException("Renderer is closed"))
            }
            if (widthPx <= 0) {
                return@synchronized RenderResult(null, IllegalStateException("Invalid render width"))
            }

            val widths = listOf(widthPx, (widthPx / 2).coerceAtLeast(1), (widthPx / 4).coerceAtLeast(1))
            val modes = listOf(
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                PdfRenderer.Page.RENDER_MODE_FOR_PRINT,
            )
            val configs = listOf(Bitmap.Config.RGB_565, Bitmap.Config.ARGB_8888)

            var lastError: Throwable? = null
            for (mode in modes) {
                for (w in widths) {
                    for (config in configs) {
                        try {
                            val bitmap = doRender(index, w, backgroundArgb, mode, config)
                            if (bitmap != null) {
                                return@synchronized RenderResult(bitmap, null)
                            }
                        } catch (t: Throwable) {
                            lastError = t
                        }
                    }
                }
            }
            RenderResult(null, lastError ?: IllegalStateException("PDF page could not be rendered"))
        }

    private fun doRender(
        index: Int,
        widthPx: Int,
        backgroundArgb: Int,
        mode: Int,
        config: Bitmap.Config,
    ): Bitmap? {
        renderer.openPage(index).use { page ->
            if (page.width == 0) return null
            val height = (widthPx.toLong() * page.height / page.width)
                .toInt()
                .coerceAtLeast(1)
            if (height <= 0) return null
            val bitmap = Bitmap.createBitmap(widthPx, height, config)
            bitmap.eraseColor(backgroundArgb)
            page.render(bitmap, null, null, mode)
            return bitmap
        }
    }

    override fun close() {
        synchronized(renderLock) {
            if (closed) return
            closed = true
            runCatching { renderer.close() }
            runCatching { fd.close() }
        }
    }
}
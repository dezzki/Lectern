package com.gigaton.reader.ui.pdf

import android.graphics.Bitmap

/** Upper bound (pixels) for a single rendered page; high zoom scales visually. */
const val MAX_RENDER_WIDTH_PX = 1600

/** Result of a render attempt — bitmap may be null and carry an error. */
class RenderResult(
    val bitmap: Bitmap?,
    val error: Throwable?,
)

/**
 * Common lazy page-rendering contract, implemented by the platform
 * [PdfDocument] (android.graphics.pdf.PdfRenderer) and the bundled PDFium
 * engine [PdfiumDocument]. Rendering must never throw: failures come back via
 * [RenderResult.error] so the UI can surface the real reason.
 */
interface ReaderDocument : AutoCloseable {
    val pageCount: Int
    fun ratio(index: Int): Float
    fun render(index: Int, widthPx: Int, backgroundArgb: Int): RenderResult
}
package com.dietnotify.app.parser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Reads every page of a PDF with on-device OCR (ML Kit), so both text PDFs and
 * scanned / image-only diet charts are supported. Works fully offline.
 */
class PdfTextExtractor(private val context: Context) {

    class NotAPdfException : Exception()

    suspend fun extract(uri: Uri, onProgress: (page: Int, total: Int) -> Unit): List<OcrPage> {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val descriptor = withContext(Dispatchers.IO) {
                context.contentResolver.openFileDescriptor(uri, "r")
            } ?: throw NotAPdfException()
            val renderer = try {
                PdfRenderer(descriptor)
            } catch (e: Exception) {
                descriptor.close()
                throw NotAPdfException()
            }
            renderer.use {
                val pages = mutableListOf<OcrPage>()
                val total = renderer.pageCount.coerceAtMost(MAX_PAGES)
                for (i in 0 until total) {
                    onProgress(i + 1, total)
                    val bitmap = withContext(Dispatchers.Default) { renderPage(renderer, i) }
                    val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
                    val lines = result.textBlocks.flatMap { block ->
                        block.lines.mapNotNull { line ->
                            val box = line.boundingBox ?: return@mapNotNull null
                            OcrLine(line.text, box.left, box.top, box.right, box.bottom)
                        }
                    }
                    pages += OcrPage(bitmap.width, bitmap.height, lines)
                    bitmap.recycle()
                }
                return pages
            }
        } finally {
            recognizer.close()
        }
    }

    private fun renderPage(renderer: PdfRenderer, index: Int): Bitmap {
        renderer.openPage(index).use { page ->
            val scale = TARGET_WIDTH.toFloat() / page.width
            val bitmap = Bitmap.createBitmap(
                TARGET_WIDTH,
                (page.height * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888,
            )
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }

    companion object {
        private const val TARGET_WIDTH = 1600
        private const val MAX_PAGES = 40
    }
}

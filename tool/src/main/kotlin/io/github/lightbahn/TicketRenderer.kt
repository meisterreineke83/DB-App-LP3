package io.github.lightbahn

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.ResultPoint
import com.google.zxing.common.HybridBinarizer
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Ergebnis: der ausgeschnittene Barcode (falls gefunden) und die ganze erste Seite als Rückfall. */
internal class RenderedTicket(
    val code: Bitmap?,
    val page: Bitmap,
)

/**
 * Rendert das Ticket-PDF und schneidet den Barcode (Aztec bei DB-Tickets) aus.
 *
 * Wichtig: Der Code wird nur gefunden und ausgeschnitten, nicht neu erzeugt.
 * So bleiben die Original-Pixel aus dem PDF exakt erhalten und das Kontrollgerät
 * liest genau das, was die Bahn ausgestellt hat.
 */
internal object TicketRenderer {

    private const val RENDER_WIDTH_PX = 1700

    fun render(pdf: File): RenderedTicket {
        val cache = File(pdf.path + ".code.png")
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                var firstPage: Bitmap? = null
                for (i in 0 until min(renderer.pageCount, 3)) {
                    val bitmap = renderer.openPage(i).use { page ->
                        val scale = RENDER_WIDTH_PX.toFloat() / page.width
                        val bmp = Bitmap.createBitmap(
                            RENDER_WIDTH_PX,
                            (page.height * scale).roundToInt(),
                            Bitmap.Config.ARGB_8888,
                        )
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                    if (firstPage == null) firstPage = bitmap
                    // Beim ersten Öffnen wird der Code gesucht, danach kommt er sofort aus dem Cache.
                    if (i == 0 && cache.exists()) {
                        BitmapFactory.decodeFile(cache.path)?.let { return RenderedTicket(it, bitmap) }
                    }
                    val code = findCode(bitmap)
                    if (code != null) {
                        runCatching { cache.outputStream().use { code.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                        return RenderedTicket(code, bitmap)
                    }
                }
                return RenderedTicket(null, firstPage ?: error("PDF hat keine Seiten"))
            }
        }
    }

    private fun findCode(page: Bitmap): Bitmap? {
        val w = page.width
        val h = page.height
        val pixels = IntArray(w * h)
        page.getPixels(pixels, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(w, h, pixels)
        val (result, points) = locate(source, w, h) ?: return null
        if (points.size < 2) return null

        val minX = points.minOf { it.x }
        val maxX = points.maxOf { it.x }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        // Bei QR-Codes liegen die Punkte in den Suchmustern, nicht an der Kante → großzügiger Rand.
        val marginFactor = if (result.barcodeFormat == BarcodeFormat.AZTEC) 0.12f else 0.25f
        val size = max(maxX - minX, maxY - minY)
        val margin = size * marginFactor + 8f

        val left = (minX - margin).toInt().coerceIn(0, w - 1)
        val top = (minY - margin).toInt().coerceIn(0, h - 1)
        val right = (maxX + margin).toInt().coerceIn(left + 1, w)
        val bottom = (maxY + margin).toInt().coerceIn(top + 1, h)
        return Bitmap.createBitmap(page, left, top, right - left, bottom - top)
    }

    private val hints = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(
            BarcodeFormat.AZTEC,
            BarcodeFormat.QR_CODE,
            BarcodeFormat.DATA_MATRIX,
            BarcodeFormat.PDF_417,
        ),
        DecodeHintType.TRY_HARDER to true,
    )

    private fun tryDecode(source: LuminanceSource): Result? = try {
        MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints)
    } catch (e: Exception) {
        null
    }

    /**
     * Der Aztec-Detektor von ZXing sucht den Code nur um die Bildmitte herum.
     * Auf einer ganzen A4-Seite liegt der Code aber meist oben rechts – deshalb
     * schieben wir ein Suchfenster über die Seite (oben rechts zuerst), bis ein
     * Fenster-Mittelpunkt im Code liegt. Gibt die Eckpunkte in Seitenkoordinaten zurück.
     */
    private fun locate(source: LuminanceSource, w: Int, h: Int): Pair<Result, List<ResultPoint>>? {
        tryDecode(source)?.let { r -> return r to r.resultPoints.orEmpty().filterNotNull() }
        val win = (min(w, h) * 0.45f).toInt().coerceAtLeast(200)
        val step = (win / 4).coerceAtLeast(40)
        var cy = step / 2
        while (cy < h) {
            var cx = w - step / 2
            while (cx > 0) {
                val half = minOf(win / 2, cx, w - cx, cy, h - cy)
                if (half >= 60) {
                    val left = cx - half
                    val top = cy - half
                    val r = tryDecode(source.crop(left, top, half * 2, half * 2))
                    if (r != null) {
                        val points = r.resultPoints.orEmpty().filterNotNull()
                            .map { ResultPoint(it.x + left, it.y + top) }
                        return r to points
                    }
                }
                cx -= step
            }
            cy += step
        }
        return null
    }
}

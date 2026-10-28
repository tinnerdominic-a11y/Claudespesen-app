package ch.expenses.app.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import ch.expenses.app.data.Receipt
import ch.expenses.app.ocr.ReceiptParser
import java.io.File
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One PDF per month: a summary table of all receipts with totals per currency,
 * followed by one page per receipt with its details and the photo.
 */
object PdfExporter {

    private const val W = 595 // A4 in points
    private const val H = 842
    private const val M = 40f

    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; typeface = Typeface.DEFAULT_BOLD }
    private val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14f; typeface = Typeface.DEFAULT_BOLD }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; typeface = Typeface.DEFAULT_BOLD }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 8f; color = Color.DKGRAY }
    private val warn = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.rgb(183, 28, 28) }
    private val line = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f }

    fun export(month: YearMonth, receipts: List<Receipt>, outDir: File): File {
        val doc = PdfDocument()
        var pageNo = 0
        val monthLabel = month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))

        // --- Summary pages ---
        val cols = floatArrayOf(M, M + 30, M + 100, M + 290, M + 400) // #, date, merchant, category, amount
        val rowH = 18f
        var page: PdfDocument.Page? = null
        var y = 0f
        fun newSummaryPage() {
            page?.let(doc::finishPage)
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++pageNo).create())
            val c = page!!.canvas
            y = M + 20
            c.drawText("Expenses $monthLabel", M, y, title)
            y += 30
            listOf("#", "Date", "Merchant", "Category", "Amount").forEachIndexed { i, h -> c.drawText(h, cols[i], y, bold) }
            y += 6
            c.drawLine(M, y, W - M, y, line)
            y += rowH - 4
        }
        newSummaryPage()
        receipts.forEachIndexed { i, r ->
            if (y > H - M - 60) newSummaryPage()
            val c = page!!.canvas
            c.drawText("${i + 1}", cols[0], y, text)
            c.drawText(r.date?.format(ReceiptParser.displayDate) ?: "–", cols[1], y, text)
            c.drawText(ellipsize(r.merchant.ifBlank { "(unknown)" }, text, cols[3] - cols[2] - 8), cols[2], y, text)
            c.drawText(ellipsize(r.category, text, cols[4] - cols[3] - 8), cols[3], y, text)
            drawRight(c, amountLabel(r), W - M, y, if (r.amountCents == null) warn else text)
            y += 4
            c.drawLine(M, y, W - M, y, line)
            y += rowH - 4
        }
        if (y > H - M - 60) newSummaryPage()
        y += 8
        val totals = receipts.filter { it.amountCents != null }.groupBy { it.currency }
            .mapValues { (_, list) -> list.sumOf { it.amountCents!! } }
        page!!.canvas.drawText("${receipts.size} receipts", M, y, bold)
        totals.toSortedMap().forEach { (cur, sum) ->
            drawRight(page!!.canvas, "Total $cur ${ReceiptParser.formatAmount(sum)}", W - M, y, bold)
            y += rowH
        }
        val missing = receipts.count { it.amountCents == null }
        if (missing > 0) page!!.canvas.drawText("$missing receipt(s) without an amount", M, y + 4, warn)
        doc.finishPage(page!!)

        // --- One page per receipt ---
        receipts.forEachIndexed { i, r ->
            val p = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++pageNo).create())
            val c = p.canvas
            var ty = M + 14
            c.drawText("#${i + 1}  ${r.merchant.ifBlank { "(unknown merchant)" }}", M, ty, heading)
            ty += 18
            val details = buildList {
                add("Date: " + (r.date?.format(ReceiptParser.displayDate) ?: "–"))
                add("Amount: " + amountLabel(r))
                if (r.category.isNotBlank()) add("Category: ${r.category}")
            }.joinToString("     ")
            c.drawText(details, M, ty, text)
            if (r.note.isNotBlank()) {
                ty += 14
                c.drawText(ellipsize("Note: ${r.note}", text, W - 2 * M), M, ty, text)
            }
            ty += 12
            val area = RectF(M, ty, W - M, H - M)
            val bmp = loadUprightBitmap(r.imagePath, 1400)
            if (bmp != null) {
                val scale = minOf(area.width() / bmp.width, area.height() / bmp.height)
                val w = bmp.width * scale
                val h = bmp.height * scale
                val left = area.left + (area.width() - w) / 2
                c.drawBitmap(bmp, null, RectF(left, area.top, left + w, area.top + h), Paint(Paint.FILTER_BITMAP_FLAG))
                bmp.recycle()
            } else {
                c.drawText("Photo not available", M, area.top + 20, warn)
            }
            c.drawText("Page $pageNo", W - M - 30, H - 16f, small)
            doc.finishPage(p)
        }

        outDir.mkdirs()
        val file = File(outDir, "Expenses_${month}.pdf")
        file.outputStream().use(doc::writeTo)
        doc.close()
        return file
    }

    private fun amountLabel(r: Receipt) =
        r.amountCents?.let { "${r.currency} ${ReceiptParser.formatAmount(it)}" } ?: "missing"

    private fun drawRight(c: Canvas, s: String, right: Float, y: Float, p: Paint) =
        c.drawText(s, right - p.measureText(s), y, p)

    private fun ellipsize(s: String, p: Paint, maxWidth: Float): String {
        if (p.measureText(s) <= maxWidth) return s
        var end = s.length
        while (end > 0 && p.measureText(s, 0, end) + p.measureText("…") > maxWidth) end--
        return s.substring(0, end) + "…"
    }
}

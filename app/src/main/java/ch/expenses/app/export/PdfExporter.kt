package ch.expenses.app.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import ch.expenses.app.data.Receipt
import ch.expenses.app.ocr.ReceiptParser
import ch.expenses.app.ocr.Vat
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
    private const val SW = 842 // summary pages are landscape
    private const val SH = 595
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

        // --- Summary pages (landscape, one column per VAT rate) ---
        val vatCols = receipts.flatMap { r -> r.vatLines.map { r.abroad to it.rateBp } }.distinct()
            .sortedWith(compareBy<Pair<Boolean, Int?>> { it.first }.thenByDescending { it.second ?: -1 })
        val right = SW - M
        val amountRight = right
        val vatW = 64f
        val vatRight = { k: Int -> amountRight - 80f - (vatCols.size - 1 - k) * vatW }
        val curX = amountRight - 80f - vatCols.size * vatW - 30f
        val xNo = M
        val xDate = M + 22
        val xMerchant = M + 82
        val xCategory = maxOf(xMerchant + 120, curX - 95)
        val rowH = 18f
        var page: PdfDocument.Page? = null
        var y = 0f
        fun vatHeader(k: Int) = vatCols[k].let { (abroad, rate) ->
            (if (abroad) "Foreign " else "VAT ") + (rate?.let { Vat.formatRate(it) } ?: "")
        }
        fun newSummaryPage() {
            page?.let(doc::finishPage)
            page = doc.startPage(PdfDocument.PageInfo.Builder(SW, SH, ++pageNo).create())
            val c = page!!.canvas
            y = M + 10
            c.drawText("Expenses $monthLabel", M, y, title)
            y += 28
            c.drawText("#", xNo, y, bold)
            c.drawText("Date", xDate, y, bold)
            c.drawText("Merchant", xMerchant, y, bold)
            c.drawText("Category", xCategory, y, bold)
            c.drawText("Cur.", curX, y, bold)
            vatCols.indices.forEach { k -> drawRight(c, vatHeader(k), vatRight(k), y, bold) }
            drawRight(c, "Amount", amountRight, y, bold)
            y += 6
            c.drawLine(M, y, right, y, line)
            y += rowH - 4
        }
        newSummaryPage()
        receipts.forEachIndexed { i, r ->
            if (y > SH - M - 30) newSummaryPage()
            val c = page!!.canvas
            c.drawText("${i + 1}", xNo, y, text)
            c.drawText(r.date?.format(ReceiptParser.displayDate) ?: "–", xDate, y, text)
            val merchant = r.merchant.ifBlank { "(unknown)" } + if (r.abroad) " (abroad)" else ""
            c.drawText(ellipsize(merchant, text, xCategory - xMerchant - 8), xMerchant, y, text)
            c.drawText(ellipsize(r.category, text, curX - xCategory - 8), xCategory, y, text)
            c.drawText(r.currency, curX, y, text)
            vatCols.forEachIndexed { k, (abroad, rate) ->
                val matching = r.vatLines.filter { it.rateBp == rate }
                if (r.abroad == abroad && matching.isNotEmpty()) {
                    drawRight(c, ReceiptParser.formatAmount(matching.sumOf { it.amountCents }), vatRight(k), y, text)
                }
            }
            drawRight(c, r.amountCents?.let(ReceiptParser::formatAmount) ?: "missing", amountRight, y,
                if (r.amountCents == null) warn else text)
            y += 4
            c.drawLine(M, y, right, y, line)
            y += rowH - 4
        }
        // Totals per currency, with each VAT column summed (Swiss and foreign VAT kept apart).
        receipts.groupBy { it.currency }.toSortedMap().forEach { (cur, list) ->
            if (y > SH - M - 30) newSummaryPage()
            val c = page!!.canvas
            y += 4
            c.drawText("Total $cur (${list.size} receipts)", xMerchant, y, bold)
            c.drawText(cur, curX, y, bold)
            vatCols.forEachIndexed { k, (abroad, rate) ->
                val sum = list.filter { it.abroad == abroad }.flatMap { it.vatLines }.filter { it.rateBp == rate }
                drawRight(c, ReceiptParser.formatAmount(sum.sumOf { it.amountCents }), vatRight(k), y, bold)
            }
            drawRight(c, ReceiptParser.formatAmount(list.sumOf { it.amountCents ?: 0L }), amountRight, y, bold)
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
                if (r.abroad) add("From abroad")
            }.joinToString("     ")
            c.drawText(details, M, ty, text)
            if (r.vatLines.isNotEmpty()) {
                ty += 14
                val vat = r.vatLines.joinToString("   ") { v ->
                    (v.rateBp?.let { Vat.formatRate(it) + " " } ?: "") + "${r.currency} ${ReceiptParser.formatAmount(v.amountCents)}"
                }
                c.drawText((if (r.abroad) "Foreign VAT: " else "VAT: ") + vat, M, ty, text)
            }
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

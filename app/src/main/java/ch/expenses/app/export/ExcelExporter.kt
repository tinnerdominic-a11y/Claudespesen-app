package ch.expenses.app.export

import android.graphics.Bitmap
import ch.expenses.app.data.Receipt
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Monthly Excel workbook: one row per receipt with its photo embedded as a thumbnail. */
object ExcelExporter {

    fun export(month: YearMonth, receipts: List<Receipt>, outDir: File): File {
        val rows = receipts.map { r ->
            val bmp = loadUprightBitmap(r.imagePath, 900)
            val jpeg = bmp?.let {
                ByteArrayOutputStream().also { out -> it.compress(Bitmap.CompressFormat.JPEG, 80, out) }.toByteArray()
            }
            XlsxRow(
                date = r.date,
                merchant = r.merchant,
                category = r.category,
                amountCents = r.amountCents,
                currency = r.currency,
                note = r.note,
                image = jpeg,
                imageWidth = bmp?.width ?: 0,
                imageHeight = bmp?.height ?: 0,
            ).also { bmp?.recycle() }
        }
        val title = "Expenses " + month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
        outDir.mkdirs()
        val file = File(outDir, "Expenses_${month}.xlsx")
        file.outputStream().buffered().use { XlsxWriter.write(it, title, rows) }
        return file
    }
}

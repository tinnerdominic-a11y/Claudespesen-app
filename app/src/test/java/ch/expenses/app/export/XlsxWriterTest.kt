package ch.expenses.app.export

import ch.expenses.app.ocr.VatLine
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream

class XlsxWriterTest {

    @Test
    fun writesSheetDrawingAndImages() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val rows = listOf(
            XlsxRow(LocalDate.of(2026, 10, 14), "Migros & Co <Zürich>", "Food & drinks", 800, "CHF", "",
                listOf(VatLine(260, 20), VatLine(810, 5)), image = jpeg, imageWidth = 300, imageHeight = 400),
            XlsxRow(null, "", "", null, "EUR", "unreadable"),
        )
        val out = ByteArrayOutputStream()
        XlsxWriter.write(out, "Expenses October 2026", rows)

        val entries = mutableMapOf<String, String>()
        ZipInputStream(out.toByteArray().inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entries[it.name] = zip.readBytes().toString(Charsets.UTF_8) }
        }
        assertTrue(entries.containsKey("xl/media/image1.jpeg"))
        assertTrue(entries.containsKey("xl/drawings/drawing1.xml"))
        val sheet = entries.getValue("xl/worksheets/sheet1.xml")
        assertTrue(sheet.contains("Migros &amp; Co &lt;Zürich&gt;"))
        assertTrue(sheet.contains("<v>${LocalDate.of(2026, 10, 14).toEpochDay() + 25569}</v>"))
        assertTrue(sheet.contains("SUMIF(E3:E4,&quot;CHF&quot;,D3:D4)"))
        // VAT columns, highest rate first, with their own totals; the photo moves to column I.
        assertTrue(sheet.contains(">VAT 8.1%<") && sheet.contains(">VAT 2.6%<"))
        assertTrue(sheet.contains("""<c r="H3" s="4"><v>0.2</v></c>"""))
        assertTrue(sheet.contains("SUMIF(E3:E4,&quot;CHF&quot;,G3:G4)"))
        // Totals carry their computed values for viewers that don't recalculate.
        assertTrue(sheet.contains("SUMIF(E3:E4,&quot;CHF&quot;,D3:D4)</f><v>8.0</v>"))
        assertTrue(sheet.contains("SUMIF(E3:E4,&quot;CHF&quot;,H3:H4)</f><v>0.2</v>"))
        assertTrue(entries.getValue("xl/drawings/drawing1.xml").contains("<xdr:col>9</xdr:col>"))
    }
}

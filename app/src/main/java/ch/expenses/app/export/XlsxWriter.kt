package ch.expenses.app.export

import java.io.OutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One expense line in the spreadsheet. [image] is a JPEG thumbnail, [imageWidth]/[imageHeight] its pixel size. */
class XlsxRow(
    val date: LocalDate?,
    val merchant: String,
    val category: String,
    val amountCents: Long?,
    val currency: String,
    val note: String,
    val image: ByteArray? = null,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
)

/**
 * Minimal .xlsx writer (no Apache POI, which is huge on Android): a single sheet with a title,
 * header, one row per receipt with the photo embedded next to it, and SUMIF totals per currency.
 */
object XlsxWriter {

    private const val PHOTO_COL = 6 // column G, 0-based
    private const val PHOTO_ROW_PT = 160.0
    private const val PHOTO_MAX_H_PT = 150.0
    private const val PHOTO_MAX_W_PT = 230.0
    private const val EMU_PER_PT = 12700L

    fun write(out: OutputStream, title: String, rows: List<XlsxRow>) {
        val images = rows.withIndex().filter { it.value.image != null }
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) = put(zip, name, content.toByteArray(Charsets.UTF_8))
            put("[Content_Types].xml", contentTypes(images.isNotEmpty()))
            put("_rels/.rels", ROOT_RELS)
            put("xl/workbook.xml", WORKBOOK)
            put("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
            put("xl/styles.xml", STYLES)
            put("xl/worksheets/sheet1.xml", sheet(title, rows, images.isNotEmpty()))
            if (images.isNotEmpty()) {
                put("xl/worksheets/_rels/sheet1.xml.rels", SHEET_RELS)
                put("xl/drawings/drawing1.xml", drawing(images))
                put("xl/drawings/_rels/drawing1.xml.rels", drawingRels(images.size))
                images.forEachIndexed { n, (_, row) -> put(zip, "xl/media/image${n + 1}.jpeg", row.image!!) }
            }
        }
    }

    private fun put(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    // Sheet rows (1-based): 1 title, 2 header, 3.. data.
    private const val FIRST_DATA_ROW = 3

    private fun sheet(title: String, rows: List<XlsxRow>, hasImages: Boolean): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        append("""<sheetPr><pageSetUpPr fitToPage="1"/></sheetPr>""")
        append("""<sheetViews><sheetView workbookViewId="0"><pane ySplit="2" topLeftCell="A3" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>""")
        append("<cols>")
        listOf(12, 30, 18, 12, 9, 30, 34).forEachIndexed { i, w ->
            append("""<col min="${i + 1}" max="${i + 1}" width="$w" customWidth="1"/>""")
        }
        append("</cols><sheetData>")
        append("""<row r="1">${str("A1", title, STYLE_TITLE)}</row>""")
        append("""<row r="2">""")
        listOf("Date", "Merchant", "Category", "Amount", "Currency", "Note", "Photo").forEachIndexed { i, h ->
            append(str("${col(i)}2", h, STYLE_HEADER))
        }
        append("</row>")
        rows.forEachIndexed { i, row ->
            val r = FIRST_DATA_ROW + i
            val ht = if (row.image != null) """ ht="$PHOTO_ROW_PT" customHeight="1"""" else ""
            append("""<row r="$r"$ht>""")
            row.date?.let { append("""<c r="A$r" s="$STYLE_DATE"><v>${it.toEpochDay() + 25569}</v></c>""") }
            append(str("B$r", row.merchant, STYLE_TEXT))
            append(str("C$r", row.category, STYLE_TEXT))
            row.amountCents?.let { append("""<c r="D$r" s="$STYLE_MONEY"><v>${it / 100.0}</v></c>""") }
            append(str("E$r", row.currency, STYLE_TEXT))
            append(str("F$r", row.note, STYLE_TEXT))
            append("</row>")
        }
        val last = FIRST_DATA_ROW + rows.size - 1
        var r = last + 2
        rows.map { it.currency }.distinct().sorted().forEach { cur ->
            append("""<row r="$r">""")
            append(str("C$r", "Total", STYLE_HEADER))
            val formula = if (rows.isEmpty()) "0" else """SUMIF(E$FIRST_DATA_ROW:E$last,"$cur",D$FIRST_DATA_ROW:D$last)"""
            append("""<c r="D$r" s="$STYLE_MONEY_BOLD"><f>${esc(formula)}</f></c>""")
            append(str("E$r", cur, STYLE_HEADER))
            append("</row>")
            r++
        }
        append("</sheetData>")
        append("""<pageSetup orientation="landscape" fitToWidth="1" fitToHeight="0"/>""")
        if (hasImages) append("""<drawing r:id="rId1"/>""")
        append("</worksheet>")
    }

    private fun drawing(images: List<IndexedValue<XlsxRow>>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
        append("""<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""")
        images.forEachIndexed { n, (rowIndex, row) ->
            val aspect = if (row.imageHeight > 0) row.imageWidth.toDouble() / row.imageHeight else 0.75
            var h = PHOTO_MAX_H_PT
            var w = h * aspect
            if (w > PHOTO_MAX_W_PT) { w = PHOTO_MAX_W_PT; h = w / aspect }
            val cx = (w * EMU_PER_PT).toLong()
            val cy = (h * EMU_PER_PT).toLong()
            val sheetRow0 = FIRST_DATA_ROW - 1 + rowIndex // 0-based for drawings
            val id = n + 1
            append("<xdr:oneCellAnchor>")
            append("""<xdr:from><xdr:col>$PHOTO_COL</xdr:col><xdr:colOff>38100</xdr:colOff><xdr:row>$sheetRow0</xdr:row><xdr:rowOff>38100</xdr:rowOff></xdr:from>""")
            append("""<xdr:ext cx="$cx" cy="$cy"/>""")
            append("""<xdr:pic><xdr:nvPicPr><xdr:cNvPr id="${id + 1}" name="Receipt $id"/><xdr:cNvPicPr><a:picLocks noChangeAspect="1"/></xdr:cNvPicPr></xdr:nvPicPr>""")
            append("""<xdr:blipFill><a:blip r:embed="rId$id"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>""")
            append("""<xdr:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic>""")
            append("<xdr:clientData/></xdr:oneCellAnchor>")
        }
        append("</xdr:wsDr>")
    }

    private fun drawingRels(count: Int): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
        for (i in 1..count) {
            append("""<Relationship Id="rId$i" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="../media/image$i.jpeg"/>""")
        }
        append("</Relationships>")
    }

    private fun contentTypes(hasImages: Boolean) = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
        append("""<Default Extension="xml" ContentType="application/xml"/>""")
        if (hasImages) append("""<Default Extension="jpeg" ContentType="image/jpeg"/>""")
        append("""<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""")
        append("""<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""")
        append("""<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""")
        if (hasImages) append("""<Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>""")
        append("</Types>")
    }

    private fun str(ref: String, value: String, style: Int) =
        if (value.isEmpty()) "" else """<c r="$ref" s="$style" t="inlineStr"><is><t xml:space="preserve">${esc(value)}</t></is></c>"""

    private fun col(i: Int) = ('A' + i).toString()

    private fun esc(s: String) = buildString {
        for (ch in s) when {
            ch == '&' -> append("&amp;")
            ch == '<' -> append("&lt;")
            ch == '>' -> append("&gt;")
            ch == '"' -> append("&quot;")
            ch < ' ' && ch != '\n' && ch != '\t' -> {} // control characters are invalid in XML
            else -> append(ch)
        }
    }

    private const val STYLE_TITLE = 1
    private const val STYLE_HEADER = 2
    private const val STYLE_DATE = 3
    private const val STYLE_MONEY = 4
    private const val STYLE_MONEY_BOLD = 5
    private const val STYLE_TEXT = 6

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<numFmts count="2"><numFmt numFmtId="164" formatCode="dd.mm.yyyy"/><numFmt numFmtId="165" formatCode="#,##0.00"/></numFmts>
<fonts count="3"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="14"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="7">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
<xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1"/>
<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment vertical="top"/></xf>
<xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment vertical="top"/></xf>
<xf numFmtId="165" fontId="2" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/>
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="top" wrapText="1"/></xf>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

    private const val WORKBOOK = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Expenses" sheetId="1" r:id="rId1"/></sheets></workbook>"""

    private const val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""

    private const val SHEET_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing1.xml"/></Relationships>"""
}

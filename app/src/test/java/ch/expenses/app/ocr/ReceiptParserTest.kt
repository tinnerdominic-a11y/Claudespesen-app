package ch.expenses.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class ReceiptParserTest {

    private val today = LocalDate.of(2026, 10, 28)

    @Test
    fun swissSupermarketReceipt() {
        val text = """
            MIGROS
            Genossenschaft Migros Zürich
            Bahnhofstrasse 12, 8001 Zürich
            Tel. 044 123 45 67
            Bananen            2.95
            Vollmilch 1L       1.65
            Brot               3.40
            TOTAL CHF          8.00
            Bar               10.00
            Rückgeld           2.00
            MWST 2.6%          0.20
            14.10.2026 12:31
        """.trimIndent()
        val r = ReceiptParser.parse(text, today)
        assertEquals("MIGROS", r.merchant)
        assertEquals(LocalDate.of(2026, 10, 14), r.date)
        assertEquals(800L, r.totalCents)
        assertEquals("CHF", r.currency)
    }

    @Test
    fun restaurantWithAmountOnNextLine() {
        val text = """
            ** Restaurant Zum Löwen **
            Datum: 03/09/26
            2 Kaffee        9.00
            1 Gipfeli       3.50
            Total zu bezahlen
            CHF 12.50
        """.trimIndent()
        val r = ReceiptParser.parse(text, today)
        assertEquals("Restaurant Zum Löwen", r.merchant)
        assertEquals(LocalDate.of(2026, 9, 3), r.date)
        assertEquals(1250L, r.totalCents)
    }

    @Test
    fun euroReceiptWithCommaDecimals() {
        val text = """
            Tankstelle Shell
            2026-08-01
            Super 95   1.234,50 €
            Summe      1.234,50 €
        """.trimIndent()
        val r = ReceiptParser.parse(text, today)
        assertEquals(123450L, r.totalCents)
        assertEquals("EUR", r.currency)
        assertEquals(LocalDate.of(2026, 8, 1), r.date)
    }

    @Test
    fun fallsBackToLargestAmount() {
        val text = "Kiosk\nArtikel 4.50\nArtikel 12.-\n"
        assertEquals(1200L, ReceiptParser.parse(text, today).totalCents)
    }

    @Test
    fun apostropheThousands() {
        assertEquals(listOf(123450L), ReceiptParser.amountsIn("Total 1'234.50"))
    }

    @Test
    fun ignoresDatesAndTimesAsAmounts() {
        assertEquals(emptyList<Long>(), ReceiptParser.amountsIn("12.05.2026 14:30"))
    }

    @Test
    fun skipsFutureDates() {
        val text = "Shop\nHaltbar bis 01.01.2027\nDatum 20.10.2026"
        assertEquals(LocalDate.of(2026, 10, 20), ReceiptParser.parse(text, today).date)
    }

    @Test
    fun nothingReadable() {
        val r = ReceiptParser.parse("", today)
        assertNull(r.merchant); assertNull(r.date); assertNull(r.totalCents); assertNull(r.currency)
    }

    @Test
    fun userAmounts() {
        assertEquals(1250L, ReceiptParser.parseUserAmount("12.50"))
        assertEquals(1250L, ReceiptParser.parseUserAmount("12,5"))
        assertEquals(123450L, ReceiptParser.parseUserAmount("1'234.50"))
        assertEquals(123450L, ReceiptParser.parseUserAmount("1.234,50"))
        assertNull(ReceiptParser.parseUserAmount("abc"))
        assertEquals("1'234.50", ReceiptParser.formatAmount(123450))
        assertEquals("0.05", ReceiptParser.formatAmount(5))
    }
}

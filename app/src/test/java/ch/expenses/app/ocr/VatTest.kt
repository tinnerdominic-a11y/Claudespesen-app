package ch.expenses.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class VatTest {

    private fun parse(text: String) = ReceiptParser.parse(text.trimIndent(), LocalDate.of(2026, 10, 28))

    @Test
    fun singleSwissVatLine() {
        val r = parse(
            """
            Coop
            Sandwich        6.50
            Wasser          1.50
            Total CHF       8.00
            MWST 2.6%       0.20
            """
        )
        assertEquals(800L, r.totalCents)
        assertEquals(listOf(VatLine(260, 20)), r.vat)
    }

    @Test
    fun swissRateTableWithTwoRates() {
        val r = parse(
            """
            Migros
            Total CHF        25.40
            MWST-Satz   Brutto   MWST
            A 2.6%      15.40    0.39
            B 8.1%      10.00    0.75
            """
        )
        assertEquals(listOf(VatLine(260, 39), VatLine(810, 75)), r.vat)
    }

    @Test
    fun tableWithNetGrossAndVat() {
        // Net, VAT, gross: the VAT is the amount that matches 8.1 % of the net.
        val r = parse(
            """
            Restaurant Krone
            Total            54.05
            MWST  8.1%   50.00   4.05   54.05
            """
        )
        assertEquals(listOf(VatLine(810, 405)), r.vat)
    }

    @Test
    fun rateOnlyIsComputedFromTotal() {
        val r = parse(
            """
            Bäckerei Hug
            Total CHF 10.80
            Preise inkl. 8.1% MWST
            """
        )
        assertEquals(listOf(VatLine(810, 81)), r.vat)
    }

    @Test
    fun totalIncludingVatIsStillTheTotal() {
        val r = parse(
            """
            Hotel Bern
            Total inkl. MWST CHF 220.00
            MWST 3.8% 8.05
            """
        )
        assertEquals(22000L, r.totalCents)
        assertEquals(listOf(VatLine(380, 805)), r.vat)
    }

    @Test
    fun germanReceiptWithUst() {
        val r = parse(
            """
            Edeka
            Summe EUR 11,90
            19% USt 1,90
            """
        )
        assertEquals(1190L, r.totalCents)
        assertEquals(listOf(VatLine(1900, 190)), r.vat)
    }

    @Test
    fun oldRateWithTwoDecimalsIsNotAnAmount() {
        val r = parse(
            """
            Shop
            Total 107.70
            MWST 7.70% 7.70
            """
        )
        assertEquals(10770L, r.totalCents)
        assertEquals(listOf(VatLine(770, 770)), r.vat)
    }

    @Test
    fun vatAmountWithoutRate() {
        val r = parse("Kiosk\nTotal 5.00\nMWST 0.37")
        assertEquals(listOf(VatLine(null, 37)), r.vat)
    }

    @Test
    fun discountPercentIsNotVat() {
        val r = parse("Shop\nRabatt 10% -1.00\nTotal 9.00\nMWST 8.1% 0.67")
        assertEquals(listOf(VatLine(810, 67)), r.vat)
    }

    @Test
    fun abroadDetection() {
        assertEquals(false, parse("Migros\nTotal CHF 8.00\nMWST 2.6% 0.20").abroad)
        assertEquals(true, parse("Edeka\nSumme EUR 11,90\n19% USt 1,90").abroad)
        // Foreign rate even when paid in CHF.
        assertEquals(true, parse("Shop\nTotal CHF 12.00\nVAT 20% 2.00").abroad)
        assertEquals(true, parse("Autogrill Italia\nTotale 5.00\nP.IVA 01234567890").abroad)
        assertEquals(false, parse("Shop AG\nCHE-123.456.789 MWST\nTotal 5.00").abroad)
    }

    @Test
    fun noVat() {
        assertEquals(emptyList<VatLine>(), parse("Kiosk\nTotal 5.00").vat)
    }

    @Test
    fun encodingAndFormatting() {
        val lines = listOf(VatLine(810, 92), VatLine(null, 5))
        assertEquals(lines, Vat.decode(Vat.encode(lines)))
        assertEquals(emptyList<VatLine>(), Vat.decode(""))
        assertEquals("8.1%", Vat.formatRate(810))
        assertEquals("19%", Vat.formatRate(1900))
        assertEquals("5.55%", Vat.formatRate(555))
        assertEquals(810, Vat.parseRate("8,1 %"))
        assertEquals(81L, Vat.fromGross(1080, 810))
    }
}
